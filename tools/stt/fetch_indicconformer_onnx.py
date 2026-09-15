"""Fetch ready-made sherpa-onnx int8 IndicConformer models (no NeMo needed).

Source: https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx
(Apache-2.0 packaging of AI4Bharat's MIT models; English is NVIDIA's
fast-conformer CTC). All 10 Indic languages share one tokens.txt; English has
its own.

    python tools/stt/fetch_indicconformer_onnx.py --out tools/work/stt [--langs hi en]

Use tools/stt/export_indicconformer.py only if you need to re-export from .nemo.
"""
import argparse
import os
import shutil

from huggingface_hub import hf_hub_download

REPO = "parismitaglobalsolutions/indicconformer-sherpa-onnx"
LANGS = ["hi", "en", "bn", "mr", "gu", "ta", "te", "kn", "ml", "or"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--langs", nargs="*", default=LANGS)
    args = ap.parse_args()
    shared = hf_hub_download(REPO, "tokens.txt")
    for lang in args.langs:
        d = os.path.join(args.out, lang)
        os.makedirs(d, exist_ok=True)
        shutil.copy(hf_hub_download(REPO, f"{lang}/model.int8.onnx"), os.path.join(d, "model.int8.onnx"))
        shutil.copy(hf_hub_download(REPO, "en/tokens.txt") if lang == "en" else shared, os.path.join(d, "tokens.txt"))
        print(lang, os.path.getsize(os.path.join(d, "model.int8.onnx")) // 2**20, "MB")


if __name__ == "__main__":
    main()
