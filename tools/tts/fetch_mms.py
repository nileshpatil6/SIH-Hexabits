"""Download sherpa-onnx ready MMS-TTS (VITS) voices for the languages that the
rasa13 model does not cover: hi, gu, or, en.

    python tools/tts/fetch_mms.py --out tools/work/tts

Source: https://huggingface.co/sriram09764/itantra-tts-onnx (converted from
facebook/mms-tts-*, CC-BY-NC-4.0).
"""
import argparse
import os

from huggingface_hub import snapshot_download

REPO = "sriram09764/itantra-tts-onnx"
DEFAULT_LANGS = ["hi", "gu", "or", "en"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--langs", nargs="*", default=DEFAULT_LANGS)
    args = ap.parse_args()

    local = snapshot_download(REPO, local_dir=os.path.join(args.out, "_mms_repo"))
    folders = {d.lower(): d for d in os.listdir(local) if os.path.isdir(os.path.join(local, d))}
    for lang in args.langs:
        # Repo folders may be named by ISO code or by language name.
        match = next((folders[k] for k in folders if k == lang or k.startswith(lang) or lang in k), None)
        if match is None:
            print(f"!! no folder for {lang}; repo has: {sorted(folders)}")
            continue
        src = os.path.join(local, match)
        files = os.listdir(src)
        assert "model.onnx" in files and "tokens.txt" in files, f"{src} missing model.onnx/tokens.txt"
        print(f"{lang}: {src} ({os.path.getsize(os.path.join(src, 'model.onnx')) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
