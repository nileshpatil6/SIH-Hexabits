"""Export ai4bharat/vits_rasa_13 (one 40M VITS for 13 Indic languages) to a
sherpa-onnx VITS model.

The HF repo is gated: accept the terms on huggingface.co and `huggingface-cli login` first.

    python tools/tts/export_rasa13.py --out tools/work/tts/rasa13

Emotion is fixed to neutral; speaker is selected per language via `sid`.
If sherpa-onnx refuses the export, fall back to MMS voices for these languages
(tools/tts/fetch_mms.py --langs bn mr ta te kn ml). The app does not change.
"""
import argparse
import json
import os

import onnx
import torch
from transformers import AutoModel, AutoTokenizer

REPO = "ai4bharat/vits_rasa_13"
# Languages we need from this model, and the speaker name prefix to look for.
WANTED = {"bn": "bengali", "mr": "marathi", "ta": "tamil", "te": "telugu", "kn": "kannada", "ml": "malayalam"}


class Wrapper(torch.nn.Module):
    """Adapts HF VitsModel to sherpa's VITS input contract:
    x[int64 1xT], x_length[int64 1], noise_scale[f], length_scale[f], noise_scale_w[f], sid[int64 1] -> y."""

    def __init__(self, model, emotion_id: int):
        super().__init__()
        self.model = model
        self.emotion_id = emotion_id

    def forward(self, x, x_length, noise_scale, length_scale, noise_scale_w, sid):
        self.model.noise_scale = float(noise_scale)
        self.model.speaking_rate = 1.0 / float(length_scale)
        self.model.noise_scale_duration = float(noise_scale_w)
        mask = torch.ones_like(x)
        out = self.model(input_ids=x, attention_mask=mask, speaker_id=sid, emotion_id=torch.tensor([self.emotion_id]))
        return out.waveform


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    model = AutoModel.from_pretrained(REPO, trust_remote_code=True).eval()
    tok = AutoTokenizer.from_pretrained(REPO, trust_remote_code=True)
    cfg = model.config

    speakers = getattr(cfg, "speaker_names", None) or getattr(cfg, "speakers", None) or {}
    emotions = getattr(cfg, "emotion_names", None) or getattr(cfg, "emotions", None) or {}
    print("speakers:", speakers)
    print("emotions:", emotions)

    def index_of(table, needle):
        items = table.items() if isinstance(table, dict) else enumerate(table)
        for k, v in items:
            name, idx = (k, v) if isinstance(v, int) else (v, k)
            if needle in str(name).lower():
                return int(idx)
        return 0

    neutral = index_of(emotions, "neutral") if emotions else 0
    speaker_map = {lang: index_of(speakers, name) for lang, name in WANTED.items()}

    # tokens.txt: character -> id, as sherpa's "characters" frontend expects.
    vocab = tok.get_vocab()
    with open(os.path.join(args.out, "tokens.txt"), "w", encoding="utf-8") as f:
        for ch, i in sorted(vocab.items(), key=lambda kv: kv[1]):
            f.write(f"{' ' if ch == ' ' else ch} {i}\n")

    path = os.path.join(args.out, "model.onnx")
    x = torch.tensor([tok("नमस्ते").input_ids], dtype=torch.int64)
    torch.onnx.export(
        Wrapper(model, neutral),
        (x, torch.tensor([x.shape[1]]), torch.tensor(0.667), torch.tensor(1.0), torch.tensor(0.8), torch.tensor([0])),
        path,
        input_names=["x", "x_length", "noise_scale", "length_scale", "noise_scale_w", "sid"],
        output_names=["y"],
        dynamic_axes={"x": {1: "T"}, "y": {1: "L"}},
        opset_version=15,
    )

    m = onnx.load(path)
    for k, v in {
        "model_type": "vits",
        "comment": "mms",  # character frontend, add_blank handled by metadata below
        "language": ",".join(WANTED),
        "add_blank": int(getattr(tok, "add_blank", True)),
        "n_speakers": len(speakers) if speakers else 1,
        "sample_rate": cfg.sampling_rate,
        "punctuation": " ".join(list(".,!?;:।")),
        "frontend": "characters",
    }.items():
        p = m.metadata_props.add()
        p.key, p.value = k, str(v)
    onnx.save(m, path)

    with open(os.path.join(args.out, "speakers.json"), "w") as f:
        json.dump(speaker_map, f, indent=2)
    print(f"wrote {path} ({os.path.getsize(path) / 1e6:.1f} MB), speakers {speaker_map}")


if __name__ == "__main__":
    main()
