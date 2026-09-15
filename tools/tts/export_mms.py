"""Export Meta MMS-TTS (VITS) voices to sherpa-onnx format using sherpa's official
recipe (https://k2-fsa.github.io/sherpa/onnx/tts/mms.html). Linux/Colab only
(builds the monotonic_align Cython extension). English uses sherpa's prebuilt.

    python tools/tts/export_mms.py --out tools/work/tts/mms [--langs hi gu or]

Output per language: <out>/<lang>/model.onnx + tokens.txt (16 kHz, 1 speaker).
License of the weights: CC-BY-NC-4.0.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tarfile
import textwrap
import urllib.request

MMS_CODE = {"hi": "hin", "gu": "guj", "mr": "mar", "kn": "kan", "ml": "mal", "ta": "tam", "te": "tel", "bn": "ben", "or": "ory"}
ENG_PREBUILT = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mms-eng.tar.bz2"

EXPORT = textwrap.dedent(r'''
import collections, os
import onnx, torch
from vits import utils
from vits.models import SynthesizerTrn

class OnnxModel(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.model = m
    def forward(self, x, x_lengths, noise_scale=0.667, length_scale=1.0, noise_scale_w=0.8):
        return self.model.infer(x=x, x_lengths=x_lengths, noise_scale=noise_scale, length_scale=length_scale, noise_scale_w=noise_scale_w)[0]

hps = utils.get_hparams_from_file("config.json")
assert hps.data.training_files.split(".")[-1] != "uroman", "uroman models unsupported"
symbols = [x.replace("\n", "") for x in open("vocab.txt", encoding="utf-8").readlines()]
dup = set(k for k, c in collections.Counter(s.upper() for s in symbols).items() if c > 1)
with open("tokens.txt", "w", encoding="utf-8") as f:
    for idx, tok in enumerate(symbols):
        f.write(f"{tok} {idx}\n")
        if tok.lower() != tok.upper() and len(tok.upper()) == 1 and tok.upper() not in dup:
            f.write(f"{tok.upper()} {idx}\n")
net_g = SynthesizerTrn(len(symbols), hps.data.filter_length // 2 + 1, hps.train.segment_size // hps.data.hop_length, **hps.model)
net_g.cpu().eval()
utils.load_checkpoint("G_100000.pth", net_g, None)
x = torch.randint(1, 10, (1, 50), dtype=torch.int64)
args = (x, torch.tensor([50]), torch.tensor([1.0]), torch.tensor([1.0]), torch.tensor([1.0]))
with torch.no_grad():
    torch.onnx.export(OnnxModel(net_g), args, "model.onnx", opset_version=13, dynamo=False,
        input_names=["x", "x_length", "noise_scale", "length_scale", "noise_scale_w"], output_names=["y"],
        dynamic_axes={"x": {0: "N", 1: "L"}, "x_length": {0: "N"}, "y": {0: "N", 2: "L"}})
m = onnx.load("model.onnx")
for k, v in {"model_type": "vits", "comment": "mms", "url": "https://huggingface.co/facebook/mms-tts/tree/main",
             "add_blank": int(hps.data.add_blank), "language": os.environ.get("language", "unknown"),
             "frontend": "characters", "n_speakers": int(hps.data.n_speakers), "sample_rate": hps.data.sampling_rate}.items():
    p = m.metadata_props.add(); p.key, p.value = k, str(v)
onnx.save(m, "model.onnx")
print("exported", os.path.getsize("model.onnx") // 2**20, "MB, tokens", len(symbols))
''')


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--langs", nargs="*", default=list(MMS_CODE) + ["en"])
    ap.add_argument("--work", default=None, help="scratch dir for the MMS repo clone")
    args = ap.parse_args()
    out = os.path.abspath(args.out)
    work = os.path.abspath(args.work or os.path.join(out, "_work"))
    os.makedirs(work, exist_ok=True)

    if any(l != "en" for l in args.langs):
        subprocess.run([sys.executable, "-m", "pip", "-q", "install", "onnx", "scipy", "Cython", "torch"], check=True)
        if not os.path.isdir(os.path.join(work, "MMS")):
            subprocess.run(["git", "clone", "-q", "https://huggingface.co/spaces/mms-meta/MMS"], cwd=work, check=True)
            subprocess.run("python3 setup.py build && cp build/lib*/vits/monotonic_align/core*.so . && sed -i.bak s/.monotonic_align.core/.core/g ./__init__.py",
                           shell=True, cwd=os.path.join(work, "MMS", "vits", "monotonic_align"), check=True)
        open(os.path.join(work, "vits-mms.py"), "w").write(EXPORT)

    env = dict(os.environ, PYTHONPATH=f"{work}/MMS:{work}/MMS/vits")
    for lang in args.langs:
        d = os.path.join(out, lang)
        os.makedirs(d, exist_ok=True)
        if os.path.exists(os.path.join(d, "model.onnx")):
            print(lang, "exists, skipping")
            continue
        if lang == "en":
            tb = os.path.join(work, "vits-mms-eng.tar.bz2")
            urllib.request.urlretrieve(ENG_PREBUILT, tb)
            with tarfile.open(tb) as t:
                t.extractall(work)
            for f in ("model.onnx", "tokens.txt"):
                shutil.copy(os.path.join(work, "vits-mms-eng", f), os.path.join(d, f))
            print("en: prebuilt")
            continue
        code = MMS_CODE[lang]
        for f in ("G_100000.pth", "config.json", "vocab.txt"):
            if not os.path.exists(os.path.join(d, f)):
                urllib.request.urlretrieve(f"https://huggingface.co/facebook/mms-tts/resolve/main/models/{code}/{f}", os.path.join(d, f))
        r = subprocess.run([sys.executable, os.path.join(work, "vits-mms.py")], cwd=d, env=dict(env, language=lang), capture_output=True, text=True)
        print(f"{lang} ({code}): rc={r.returncode} {r.stdout.strip()[-200:]}")
        if r.returncode != 0:
            print(r.stderr[-2000:])
        else:
            os.remove(os.path.join(d, "G_100000.pth"))


if __name__ == "__main__":
    main()
