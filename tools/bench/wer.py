"""WER and RTF benchmark for an STT pack directory.

manifest.tsv: one line per clip, "<wav path>\t<reference transcript>".

    python tools/bench/wer.py --dir tools/work/stt/hi --manifest data/hi/test.tsv
"""
import argparse
import os
import time

import jiwer
import numpy as np
import sherpa_onnx
import soundfile as sf


def load(path: str) -> np.ndarray:
    a, sr = sf.read(path, dtype="float32", always_2d=True)
    a = a[:, 0]
    if sr != 16000:
        n = int(len(a) * 16000 / sr)
        a = np.interp(np.linspace(0, len(a), n, endpoint=False), np.arange(len(a)), a).astype(np.float32)
    return a


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--fp32", action="store_true")
    ap.add_argument("--threads", type=int, default=2)
    args = ap.parse_args()

    model = os.path.join(args.dir, "model.onnx" if args.fp32 else "model.int8.onnx")
    rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=model, tokens=os.path.join(args.dir, "tokens.txt"), num_threads=args.threads)

    refs, hyps, audio_s, decode_s = [], [], 0.0, 0.0
    base = os.path.dirname(os.path.abspath(args.manifest))
    for line in open(args.manifest, encoding="utf-8"):
        if not line.strip():
            continue
        wav, ref = line.rstrip("\n").split("\t", 1)
        a = load(wav if os.path.isabs(wav) else os.path.join(base, wav))
        t0 = time.time()
        s = rec.create_stream()
        s.accept_waveform(16000, a)
        rec.decode_stream(s)
        decode_s += time.time() - t0
        audio_s += len(a) / 16000
        refs.append(ref.strip())
        hyps.append(s.result.text.strip())

    print(f"clips {len(refs)}  audio {audio_s:.1f}s")
    print(f"WER {jiwer.wer(refs, hyps) * 100:.2f}%  CER {jiwer.cer(refs, hyps) * 100:.2f}%")
    print(f"RTF {decode_s / max(audio_s, 1e-6):.3f} ({args.threads} threads, {'fp32' if args.fp32 else 'int8'})")


if __name__ == "__main__":
    main()
