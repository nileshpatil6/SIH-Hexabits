"""Sanity-check an exported STT model with the same runtime the app uses.

    python tools/stt/verify_stt.py --dir tools/work/stt/hi --wav sample_hi.wav
"""
import argparse
import os
import time

import numpy as np
import sherpa_onnx
import soundfile as sf


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--wav", required=True)
    ap.add_argument("--fp32", action="store_true")
    args = ap.parse_args()

    model = os.path.join(args.dir, "model.onnx" if args.fp32 else "model.int8.onnx")
    rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=model, tokens=os.path.join(args.dir, "tokens.txt"), num_threads=2, decoding_method="greedy_search"
    )
    audio, sr = sf.read(args.wav, dtype="float32", always_2d=True)
    audio = audio[:, 0]
    if sr != 16000:
        # crude linear resample; fine for a smoke test
        n = int(len(audio) * 16000 / sr)
        audio = np.interp(np.linspace(0, len(audio), n, endpoint=False), np.arange(len(audio)), audio).astype(np.float32)
    t0 = time.time()
    s = rec.create_stream()
    s.accept_waveform(16000, audio)
    rec.decode_stream(s)
    dt = time.time() - t0
    dur = len(audio) / 16000
    print(s.result.text)
    print(f"audio {dur:.2f}s decode {dt:.2f}s RTF {dt / dur:.3f}")


if __name__ == "__main__":
    main()
