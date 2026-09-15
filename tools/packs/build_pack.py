"""Build an .itpack (zip + manifest.json) that the app imports from Settings > Model packs.

STT (one language):
    python tools/packs/build_pack.py stt --lang hi --dir tools/work/stt/hi --out dist/
TTS, single-language MMS voice:
    python tools/packs/build_pack.py tts --lang hi --dir tools/work/tts/_mms_repo/hin --out dist/
TTS, multi-language rasa13 (speakers.json maps lang -> sid):
    python tools/packs/build_pack.py tts --lang bn mr ta te kn ml --dir tools/work/tts/rasa13 --name rasa13-tts --out dist/

Add --assets to also copy the unpacked pack into app/assets/models/<name>/ for
the demo build (the app installs bundled packs on first run).
"""
import argparse
import hashlib
import json
import os
import shutil
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["stt", "tts"])
    ap.add_argument("--lang", nargs="+", required=True)
    ap.add_argument("--dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--name")
    ap.add_argument("--version", default="1")
    ap.add_argument("--fp32", action="store_true", help="STT: ship model.onnx instead of model.int8.onnx")
    ap.add_argument("--assets", action="store_true")
    args = ap.parse_args()

    name = args.name or f"{'-'.join(args.lang)}-{args.kind}"
    files = {"tokens": "tokens.txt"}
    speakers = {}

    if args.kind == "stt":
        model = "model.onnx" if args.fp32 or not os.path.exists(os.path.join(args.dir, "model.int8.onnx")) else "model.int8.onnx"
        files["model"] = model
        engine, sample_rate = "nemo_ctc", 16000
    else:
        files["model"] = "model.onnx"
        engine = "vits"
        sample_rate = 22050
        cfg = os.path.join(args.dir, "config.json")
        if os.path.exists(cfg):
            sample_rate = json.load(open(cfg)).get("sampling_rate", sample_rate)
        sp = os.path.join(args.dir, "speakers.json")
        if os.path.exists(sp):
            speakers = json.load(open(sp))
        if os.path.exists(os.path.join(args.dir, "lexicon.txt")):
            files["lexicon"] = "lexicon.txt"

    for key, fn in files.items():
        assert os.path.exists(os.path.join(args.dir, fn)), f"missing {fn} in {args.dir}"

    manifest = {
        "name": name,
        "kind": args.kind,
        "engine": engine,
        "langs": args.lang,
        "version": args.version,
        "sampleRate": sample_rate,
        "files": files,
        "speakers": speakers,
        "sha256": {fn: sha256(os.path.join(args.dir, fn)) for fn in files.values()},
    }

    os.makedirs(args.out, exist_ok=True)
    pack = os.path.join(args.out, f"{name}.itpack")
    # Models are already dense; STORED avoids wasting phone CPU on inflate.
    with zipfile.ZipFile(pack, "w", compression=zipfile.ZIP_STORED) as z:
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for fn in files.values():
            z.write(os.path.join(args.dir, fn), fn)
    print(f"{pack}: {os.path.getsize(pack) / 1e6:.1f} MB")

    if args.assets:
        dest = os.path.join(ROOT, "app", "assets", "models", name)
        os.makedirs(dest, exist_ok=True)
        with open(os.path.join(dest, "manifest.json"), "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        for fn in files.values():
            shutil.copy2(os.path.join(args.dir, fn), os.path.join(dest, fn))
        print(f"bundled into {dest} (add 'assets/models/{name}/' to pubspec.yaml assets)")


if __name__ == "__main__":
    main()
