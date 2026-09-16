# iTantra

Full project handoff (status, bugs, what is left): see [HANDOFF.md](HANDOFF.md).

SIH 26173: Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links.

An Android walkie-talkie that works with no internet and no cellular network. Speech is recognised on the phone, sent as a few hundred bytes of text over Bluetooth LE mesh or Wi-Fi Direct, and spoken aloud on the receiving phone.

```
Voice -> Silero VAD -> IndicConformer STT -> text -> BLE mesh / Wi-Fi Direct -> text -> VITS TTS -> Voice
```

Languages: Hindi, English, Bengali, Marathi, Gujarati, Tamil, Telugu, Kannada, Malayalam, Odia.

## Layout

| Path | What |
|---|---|
| `app/` | Flutter UI (Riverpod, go_router) |
| `app/pigeons/itantra.dart` | Typed Flutter/Kotlin bridge definition (`dart run pigeon --input pigeons/itantra.dart`) |
| `app/android/app/src/main/kotlin/itantra/mesh` | Packet codec, fragmentation, dedup, signing/encryption, router, BLE and Wi-Fi Direct transports |
| `app/android/app/src/main/kotlin/itantra/speech` | sherpa-onnx STT/TTS engines, model pack manager |
| `app/android/app/src/main/kotlin/itantra/audio` | Mic capture, VAD sentence segmentation, playback and alert mode |
| `app/android/app/src/main/kotlin/itantra/core` | `ItantraCore` singleton wiring everything together |
| `tools/` | Laptop-only Python: model export, quantization, pack building, WER/RTF benchmarks |
| `docs/protocol.md` | Wire protocol |

There is no server.

## Models

| Role | Model | License |
|---|---|---|
| STT (9 Indic) | AI4Bharat IndicConformer per-language, CTC head, int8 (~190 MB each) | MIT |
| STT (English) | NVIDIA fast-conformer CTC (sherpa export, ~167 MB) | CC-BY-4.0 |
| TTS (all 10) | Meta MMS-TTS VITS, sherpa export (~108 MB each) | CC-BY-NC-4.0 |
| TTS (planned) | AI4Bharat vits_rasa_13 for bn mr ta te kn ml, see tools/tts/export_rasa13.py | CC-BY-4.0 |
| VAD | Silero VAD (bundled) | MIT |
| Runtime | sherpa-onnx 1.13.8 | Apache-2.0 |

## Setup

1. Flutter 3.44+, JDK 17, Android SDK.
2. Download the sherpa-onnx AAR (not committed, 50 MB):
   ```
   curl -L -o app/android/app/libs/sherpa-onnx-1.13.8.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
   ```
3. `cd app && flutter pub get && flutter run`

## Building model packs

See `tools/` and `HANDOFF.md`. Typical flow for Hindi (Linux or Colab for the MMS export):

```
pip install -r tools/requirements.txt
python tools/stt/fetch_indicconformer_onnx.py --out tools/work/stt --langs hi
python tools/tts/export_mms.py --out tools/work/tts/mms --langs hi   # Linux/Colab
python tools/packs/build_pack.py stt --lang hi --dir tools/work/stt/hi --out dist/
python tools/packs/build_pack.py tts --lang hi --dir tools/work/tts/mms/hi --out dist/
```

Copy the `.itpack` files to the phone and import them from Settings > Model packs.

## Tests

```
cd app && flutter test
cd app/android && ./gradlew :app:testDebugUnitTest
```


## Prebuilt packs

All 20 packs (10 STT + 10 TTS, verified end to end) are published at https://huggingface.co/datasets/Mr66/itantra-packs

