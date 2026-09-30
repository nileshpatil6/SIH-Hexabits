---
pretty_name: iTantra Offline Indic Speech Packs
license: other
license_name: mixed
license_link: https://github.com/nileshpatil6/SIH-Hexabits#-licenses
language:
  - hi
  - bn
  - mr
  - gu
  - ta
  - te
  - kn
  - ml
  - or
  - en
tags:
  - speech
  - asr
  - tts
  - indic
  - sherpa-onnx
  - onnx
  - int8
  - offline
  - on-device
  - android
  - mesh
  - disaster-response
  - indicconformer
  - mms-tts
task_categories:
  - automatic-speech-recognition
  - text-to-speech
size_categories:
  - n<1K
---

<div align="center">

<img src="https://huggingface.co/datasets/Mr66/itantra-packs/resolve/main/assets/banner.jpg" alt="iTantra" width="100%">

# iTantra Offline Indic Speech Packs

**Speech recognition + speech synthesis for 10 Indian languages, running fully offline on a 3 GB Android phone.**

Ready-to-run [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) packs that power **[iTantra](https://github.com/nileshpatil6/SIH-Hexabits)**, a walkie-talkie that turns your voice into a 234-byte packet, hops it phone to phone over Bluetooth mesh, and speaks it aloud on the other side. No internet, no SIM, no server.

[![GitHub](https://img.shields.io/badge/GitHub-SIH--Hexabits-181717?logo=github)](https://github.com/nileshpatil6/SIH-Hexabits)
[![Languages](https://img.shields.io/badge/languages-10-0284C7)](#languages)
[![Offline](https://img.shields.io/badge/100%25-offline-10B981)](#)
[![sherpa-onnx](https://img.shields.io/badge/runtime-sherpa--onnx-F97316)](https://github.com/k2-fsa/sherpa-onnx)
[![SIH 2026](https://img.shields.io/badge/Smart%20India%20Hackathon-2026-E8711A)](https://github.com/nileshpatil6/SIH-Hexabits#-smart-india-hackathon-2026)

</div>

## What is inside

20 packs: one **speech-to-text** and one **text-to-speech** pack per language.

| Pack | Model | Size | License |
|---|---|---:|---|
| `<lang>-stt.itpack` (9 Indic) | AI4Bharat **IndicConformer**, per-language, CTC head, **int8** | ~198 MB | MIT |
| `en-stt.itpack` | NVIDIA fast-conformer CTC | ~175 MB | CC-BY-4.0 |
| `<lang>-tts.itpack` | Meta **MMS-TTS** VITS, exported with sherpa-onnx's official recipe | ~114 MB | CC-BY-NC-4.0 |

Each `.itpack` is a plain **zip**: a `manifest.json` (language, kind, engine, version, sha256, sample rate) plus the ONNX model and `tokens.txt`. Unzip it and you can use it from any sherpa-onnx binding (Python, Kotlin, Swift, C++, JS).

## Languages

Round-trip test: the TTS pack speaks a flood warning, the STT pack transcribes it back, character error rate measured (lower is better).

| Language | Code | STT | TTS | Character error |
|---|:---:|:---:|:---:|---:|
| Kannada | `kn` | Yes | Yes | 1.0 % |
| Marathi | `mr` | Yes | Yes | 1.2 % |
| Tamil | `ta` | Yes | Yes | 2.0 % |
| Bengali | `bn` | Yes | Yes | 3.2 % |
| Gujarati | `gu` | Yes | Yes | 3.3 % |
| Telugu | `te` | Yes | Yes | 3.3 % |
| Odia | `or` | Yes | Yes | 6.0 % |
| Hindi | `hi` | Yes | Yes | 8.5 % |
| English | `en` | Yes | Yes | 10.2 % |
| Malayalam | `ml` | Yes | Yes | 13.6 % |

On a OnePlus CPH2717 (CPU only): **236 ms** to decode a Hindi sentence, 644 ms to load an STT pack, 654 ms to load a voice. STT runs about 5 to 20 times faster than real time.

> The error rates come from synthetic speech on a desktop CPU, not real field recordings.

## Use it

### In the iTantra app (easiest)

Install the APK from [GitHub releases](https://github.com/nileshpatil6/SIH-Hexabits/releases/latest), tap **Download speech models**, pick your language. Done, offline from then on.

### In Python

```python
import json, zipfile
import soundfile as sf
import sherpa_onnx
from huggingface_hub import hf_hub_download

lang = "hi"
for kind in ("stt", "tts"):
    path = hf_hub_download("Mr66/itantra-packs", f"{lang}-{kind}.itpack", repo_type="dataset")
    zipfile.ZipFile(path).extractall(f"packs/{lang}-{kind}")

print(json.load(open(f"packs/{lang}-stt/manifest.json")))  # file names, engine, sample rate
```

Then point sherpa-onnx at the extracted files (`OfflineRecognizer.from_nemo_ctc(...)` for STT, `OfflineTts` with a VITS config for TTS). The exact file names for each pack are listed in its `manifest.json`.

### Download everything

```bash
huggingface-cli download Mr66/itantra-packs --repo-type dataset --local-dir itantra-packs
```

## Why these models

- **Per-language STT instead of one big model.** The multilingual IndicConformer needs about 2.5 GB of RAM; per-language int8 models load one at a time and fit on 3 GB phones.
- **CTC head only.** Fast, simple decoding with no language model, which is ideal on phone CPUs.
- **One VITS voice per language.** No single small multilingual TTS model covered all 10 languages at the time of building.

## Licenses

The packs keep the licenses of the models they wrap:

- IndicConformer STT: **MIT** (AI4Bharat)
- English STT: **CC-BY-4.0** (NVIDIA)
- MMS-TTS voices: **CC-BY-NC-4.0 (non-commercial only)** (Meta)

Please respect the non-commercial terms of the TTS voices.

## Credits

[AI4Bharat IndicConformer](https://github.com/AI4Bharat/IndicConformerASR) · [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) · [Meta MMS](https://huggingface.co/facebook/mms-tts) · [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)

Built by **Team Hexabits** for **Smart India Hackathon 2026, PS 26173 (ISRO)**. If this helps you, give the dataset a like and the [GitHub repo](https://github.com/nileshpatil6/SIH-Hexabits) a star.
