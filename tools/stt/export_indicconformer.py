"""Export a per-language AI4Bharat IndicConformer hybrid CTC/RNNT model to a
sherpa-onnx NeMo-CTC model (encoder + CTC head in one ONNX), int8 quantized.

Needs the AI4Bharat NeMo fork (nemo-v2 branch); run on Linux or Colab.

    python tools/stt/export_indicconformer.py --lang hi --out tools/work/stt/hi

Produces model.onnx, model.int8.onnx, tokens.txt. Then build a pack with
tools/packs/build_pack.py.
"""
import argparse
import os
import urllib.request

import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

# Per-language "large" hybrid models published by AI4Bharat.
BASE = "https://objectstore.e2enetworks.net/indicconformer/models"
LANG_MODEL = {
    code: f"indicconformer_stt_{code}_hybrid_rnnt_large"
    for code in ["hi", "bn", "mr", "gu", "ta", "te", "kn", "ml", "or"]
}
# IndicConformer has no English model; use NVIDIA's small English CTC conformer.
ENGLISH_NGC = "nvidia/stt_en_conformer_ctc_small"


def add_meta(path: str, meta: dict) -> None:
    m = onnx.load(path)
    while len(m.metadata_props):
        m.metadata_props.pop()
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key, p.value = k, str(v)
    onnx.save(m, path)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--nemo", help="local .nemo file (skips download)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    import nemo.collections.asr as nemo_asr  # imported late: heavy

    if args.lang == "en":
        model = nemo_asr.models.ASRModel.from_pretrained(ENGLISH_NGC)
    else:
        nemo_path = args.nemo
        if not nemo_path:
            name = LANG_MODEL[args.lang]
            nemo_path = os.path.join(args.out, f"{name}.nemo")
            if not os.path.exists(nemo_path):
                print(f"downloading {name} ...")
                urllib.request.urlretrieve(f"{BASE}/{name}.nemo", nemo_path)
        model = nemo_asr.models.ASRModel.restore_from(nemo_path)
        # Hybrid model: switch the export/decoding head to CTC.
        if hasattr(model, "cur_decoder"):
            model.cur_decoder = "ctc"
        if hasattr(model, "set_export_config"):
            model.set_export_config({"decoder_type": "ctc"})

    model.eval()
    model.preprocessor.featurizer.dither = 0.0
    model.preprocessor.featurizer.pad_to = 0

    # tokens.txt in sherpa format: "<token> <id>", blank last.
    # Hybrid models keep the CTC vocab on ctc_decoder (same as joint's); plain CTC models on decoder.
    for owner in ("ctc_decoder", "joint", "decoder"):
        vocab = getattr(getattr(model, owner, None), "vocabulary", None)
        if vocab:
            break
    else:
        raise RuntimeError("could not find model vocabulary")
    with open(os.path.join(args.out, "tokens.txt"), "w", encoding="utf-8") as f:
        for i, tok in enumerate(vocab):
            f.write(f"{tok} {i}\n")
        f.write(f"<blk> {len(vocab)}\n")

    fp32 = os.path.join(args.out, "model.onnx")
    model.export(fp32)

    cfg = model.cfg.preprocessor
    add_meta(fp32, {
        "vocab_size": len(vocab) + 1,
        "normalize_type": cfg.get("normalize", "per_feature"),
        "subsampling_factor": model.cfg.encoder.get("subsampling_factor", 4),
        "model_type": "EncDecCTCModelBPE",
        "version": "1",
        "model_author": "AI4Bharat" if args.lang != "en" else "NVIDIA",
        "language": args.lang,
        "comment": "iTantra export, CTC head of hybrid model",
    })

    int8 = os.path.join(args.out, "model.int8.onnx")
    quantize_dynamic(fp32, int8, weight_type=QuantType.QUInt8)
    for p in (fp32, int8):
        print(f"{p}: {os.path.getsize(p) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
