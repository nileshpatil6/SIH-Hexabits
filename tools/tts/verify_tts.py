"""Synthesize a sentence with an exported/downloaded VITS model using sherpa-onnx.

    python tools/tts/verify_tts.py --dir tools/work/tts/rasa13 --text "வணக்கம்" --sid 4 --wav out.wav
"""
import argparse
import os
import time

import sherpa_onnx
import soundfile as sf


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--text", required=True)
    ap.add_argument("--sid", type=int, default=0)
    ap.add_argument("--wav", default="tts_out.wav")
    args = ap.parse_args()

    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=os.path.join(args.dir, "model.onnx"), tokens=os.path.join(args.dir, "tokens.txt")
            ),
            num_threads=2,
        )
    )
    tts = sherpa_onnx.OfflineTts(cfg)
    t0 = time.time()
    audio = tts.generate(args.text, sid=args.sid, speed=1.0)
    dt = time.time() - t0
    dur = len(audio.samples) / audio.sample_rate
    sf.write(args.wav, audio.samples, audio.sample_rate)
    print(f"{args.wav}: {dur:.2f}s audio in {dt:.2f}s (RTF {dt / max(dur, 1e-6):.3f}), speakers={tts.num_speakers}")


if __name__ == "__main__":
    main()
