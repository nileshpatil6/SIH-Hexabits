# iTantra

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
| STT (9 Indic) | AI4Bharat IndicConformer per-language large, CTC head, int8 (~130 MB each) | MIT |
| STT (English) | NVIDIA stt_en_conformer_ctc_small | CC-BY-4.0 |
| TTS (bn mr ta te kn ml) | AI4Bharat vits_rasa_13, one 40M model | CC-BY-4.0 |
| TTS (hi gu or en) | Meta MMS-TTS VITS | CC-BY-NC-4.0 |
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

See `tools/`. Typical flow for Hindi (Linux or Colab for the NeMo step):

```
pip install -r tools/requirements.txt
python tools/stt/export_indicconformer.py --lang hi --out tools/work/stt/hi
python tools/stt/verify_stt.py --dir tools/work/stt/hi --wav sample.wav
python tools/packs/build_pack.py stt --lang hi --dir tools/work/stt/hi --out dist/

python tools/tts/fetch_mms.py --out tools/work/tts
python tools/packs/build_pack.py tts --lang hi --dir tools/work/tts/_mms_repo/<hindi folder> --out dist/
```

Copy the `.itpack` files to the phone and import them from Settings > Model packs.

## Tests

```
cd app && flutter test
cd app/android && ./gradlew :app:testDebugUnitTest
```
