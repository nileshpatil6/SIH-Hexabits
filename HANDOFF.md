# iTantra Handoff (SIH 26173)

Everything a new developer needs to pick this project up: what the product is, what is built, how to build and test it, where the models come from, what is verified, what is known to be broken, and what is left.

- Repo: https://github.com/nileshpatil6/SIH-Hexabits (branch `master`)
- Model packs: https://huggingface.co/datasets/Mr66/itantra-packs (public)

---

## 1. The problem and the idea

**Problem statement:** SIH 2026, PS 26173, "iTantra: Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links".

In disasters, voice beats text (not everyone can read), but raw audio needs far more bandwidth than weak links (Bluetooth, offline mesh, bad signal) can carry.

**Idea:** never send audio. Send the *meaning* as text and rebuild the voice on the receiving phone.

```
Phone A: mic -> VAD (detect pause) -> STT (on device) -> text
         -> BLE mesh / Wi-Fi Direct (a few hundred bytes)
Phone B: text -> TTS (on device) -> speaker
```

**Hard constraints**
- Fully offline. No internet, no cloud APIs, **no server at all** (confirmed with the team).
- 10 languages: Hindi, English, Bengali, Marathi, Gujarati, Tamil, Telugu, Kannada, Malayalam, Odia.
- Must run on cheap phones. Target floor: **3 GB RAM**.
- Push-to-talk (walkie-talkie) and a continuous "call" mode.
- Emergency alerts that play at maximum volume and cannot be casually muted.

**Size win (example):** a 3.5 s Hindi warning sentence is about 7,000 B as 16 kbps Opus audio vs about 234 B as one iTantra packet (including a 64 B signature). Roughly 30x smaller.

---

## 2. Decisions already made (and why)

| Topic | Decision | Reason |
|---|---|---|
| App stack | Flutter UI + Kotlin native layer | Team preference. All heavy work (audio, ML, radios) is Kotlin. |
| Flutter/Kotlin bridge | Pigeon (typed, generated) | No hand-written method channel strings. |
| ML runtime | sherpa-onnx 1.13.8 (Apache-2.0) | One library does STT + TTS + VAD on Android, CPU only, offline. |
| STT model | AI4Bharat IndicConformer, **one model per language**, CTC head, int8 | The 600M multilingual model needs ~2.5 GB RAM, too big for 3 GB phones. Per-language models load one at a time. |
| TTS model | Meta MMS-TTS (VITS), one voice per language | No small single multilingual model covers all 10 languages. `ai4bharat/vits_rasa_13` (one 40M model) misses hi, gu, or, en. Team chose "use existing voices now, train one model later". |
| VAD | Silero VAD (bundled in APK, 630 KB) | Detects end of sentence so each sentence is sent as soon as the speaker pauses. |
| Mesh | Clean-room Kotlin implementation of the ideas in the **BitChat whitepaper** | bitchat-android code is GPL-3.0; copying it would force the whole app to GPL. The whitepaper is public domain, so we wrote our own. |
| Transports | BLE mesh (always) + Wi-Fi Direct (when available) | BLE for multi-hop reach, Wi-Fi Direct for speed and range. |
| Server | None | App is fully offline. Python is only used on a laptop or Colab for model tooling. |

---

## 3. Repository map

```
SIH/
  README.md                 short overview + setup
  HANDOFF.md                this file
  docs/protocol.md          wire protocol spec (packet format, types, routing)
  .gitignore                ignores .env, dist/, *.aar, *.onnx (except silero), build dirs

  app/                      Flutter project (package name: itantra)
    pubspec.yaml            riverpod, go_router, permission_handler, file_picker, pigeon (dev)
    pigeons/itantra.dart    SOURCE of the bridge API. Regenerate after edits:
                              cd app && dart run pigeon --input pigeons/itantra.dart
    assets/models/silero_vad.onnx
    lib/
      main.dart                          router + bottom nav shell (Talk, Mesh, Settings)
      core/bridge/itantra_api.g.dart     GENERATED, do not edit
      core/state/app_state.dart          AppController (Riverpod StateNotifier), all app state;
                                         _NativeEvents receives Kotlin -> Dart callbacks
      core/languages.dart                the 10 languages + sample sentences
      core/permissions.dart              runtime permission requests
      features/onboarding/               name, language, permissions
      features/talk/talk_screen.dart     PTT button, call mode, feed bubbles, typed send, SOS
      features/alerts/alert_overlay.dart full-screen flashing alert with ACKNOWLEDGE
      features/peers/peers_screen.dart   mesh on/off, nearby phones, hops, RSSI
      features/settings/                 settings, model pack import, diagnostics page
    test/widget_test.dart                2 Dart tests (language list)

    android/app/
      build.gradle.kts      minSdk 26, targetSdk 34, arm64 + armv7 only, sherpa AAR from libs/,
                            coroutines, bouncycastle (crypto)
      libs/sherpa-onnx-1.13.8.aar   NOT in git (50 MB). Download it, see section 5.
      src/main/AndroidManifest.xml  BLE, Wi-Fi Direct, mic, foreground service permissions
      src/main/kotlin/itantra/
        app/MainActivity.kt           wires Pigeon APIs to ItantraCore
        bridge/ItantraApi.g.kt        GENERATED
        bridge/BridgeImpl.kt          SpeechApi/MeshApi/ModelApi implementations, event forwarding
        core/ItantraCore.kt           process singleton: owns router, STT, TTS, capture, playback
        audio/AudioCapture.kt         16 kHz mono mic, AEC/NS, 512-sample frames
        audio/SentenceSegmenter.kt    Silero VAD, 0.45 s pause ends a sentence, 12 s hard cut
        audio/Playback.kt             serial speech queue; alert mode (ALARM stream, max volume,
                                      siren, vibrate, repeat x3, only ACK stops it)
        speech/SttEngine.kt           sherpa OfflineRecognizer (nemo_ctc), one model resident
        speech/TtsEngine.kt           sherpa OfflineTts (vits), up to 2 voices resident (LRU)
        speech/ModelPackManager.kt    .itpack import (zip + manifest + sha256), storage, lookup
        mesh/Packet.kt                packet struct + binary codec
        mesh/Payloads.kt              ANNOUNCE / TEXT / ACK payload encodings
        mesh/Fragmenter.kt            split/reassemble packets bigger than the BLE budget
        mesh/DedupCache.kt            LRU seen-set + TTL relay policy + jitter
        mesh/Crypto.kt                Ed25519 identity, X25519 + HKDF session key, ChaCha20-Poly1305
        mesh/Transport.kt             interface shared by both radios
        mesh/BleMeshTransport.kt      GATT server + client at the same time, framing, write queue
        mesh/WifiDirectTransport.kt   WifiP2pManager group + TCP port 47474
        mesh/MeshRouter.kt            flood routing, dedup, signature checks, outbox, DMs, ACKs
        service/MeshForegroundService.kt  keeps radios + mic alive in background
      src/test/kotlin/itantra/mesh/ProtocolTest.kt  10 Kotlin unit tests

  tools/                    Python, laptop/Colab only, never shipped
    requirements.txt
    stt/fetch_indicconformer_onnx.py   download ready sherpa int8 STT models (USE THIS)
    stt/export_indicconformer.py       re-export from .nemo (needs AI4Bharat NeMo fork, see notes)
    stt/verify_stt.py                  decode a wav with the same runtime as the app
    tts/export_mms.py                  export MMS voices with sherpa's official recipe (USE THIS)
    tts/export_rasa13.py               UNTESTED exporter for vits_rasa_13
    tts/verify_tts.py                  synthesize a sentence to wav
    packs/build_pack.py                build .itpack files (+ optional bundle into app assets)
    bench/wer.py                       WER/CER/RTF on a tsv of (wav, transcript)
    colab/itantra_model_packs.ipynb    notebook that builds all packs on Colab
```

---

## 4. How the app works end to end

**Sending (Phone A)**
1. User holds the mic button (PTT) or taps it (call mode).
2. `AudioCapture` streams 512-sample frames to `SentenceSegmenter`.
3. When Silero VAD sees about 0.45 s of silence, the utterance is emitted.
4. `SttEngine.transcribe` decodes it (int8 CTC, greedy). `TextCleaner` trims it.
5. The transcript shows in the feed and `MeshRouter.sendText` builds a signed TEXT packet (TTL 7).
6. The packet goes out on every transport that has neighbours. If it is bigger than the BLE budget, `Fragmenter` splits it.

**Receiving (Phone B)**
1. A transport delivers bytes to `MeshRouter.handleBytes`.
2. Fragments are relayed and reassembled. Duplicates are dropped by `DedupCache`.
3. The signature is verified against the sender's key learned from its ANNOUNCE.
4. `ItantraCore.onText` posts the message to Flutter and calls `speak`.
5. `TtsEngine` synthesizes in the *sender's* language (falls back to the receiver's language if that voice is missing), and `Playback` plays it.
6. The packet is relayed onward with TTL-1 after 10 to 220 ms of jitter.

**Alerts:** type ALERT, never density-capped, ACKed, played on the ALARM stream at max volume with a siren prefix, repeated 3 times, full-screen red overlay until ACKNOWLEDGE.

**Call mode half-duplex guard:** while TTS is playing, mic frames are dropped so the phone does not transcribe its own speaker.

**Model packs (`.itpack`)** are zips (stored, not compressed) with a `manifest.json`:

```json
{"name":"hi-stt","kind":"stt","engine":"nemo_ctc","langs":["hi"],"version":"1",
 "sampleRate":16000,"files":{"model":"model.int8.onnx","tokens":"tokens.txt"},
 "speakers":{},"sha256":{"model.int8.onnx":"...","tokens.txt":"..."}}
```

They are imported from Settings > Model packs and stored under the app's `files/models/<name>/`. Packs placed in `app/assets/models/<name>/` are auto-installed on first launch (for demo builds).

Full protocol details: `docs/protocol.md`.

---

## 5. Setup and build

**Requirements:** Flutter 3.44+, JDK 17, Android SDK (minSdk 26), Python 3.11 for tools.

```bash
git clone https://github.com/nileshpatil6/SIH-Hexabits.git
cd SIH-Hexabits

# 1. sherpa-onnx AAR (not committed)
curl -L -o app/android/app/libs/sherpa-onnx-1.13.8.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar

# 2. Flutter deps
cd app
flutter pub get

# 3. Run on a connected phone
flutter run
# or build an APK
flutter build apk --debug      # output: app/build/app/outputs/flutter-apk/app-debug.apk
```

**Tests**

```bash
cd app && flutter analyze && flutter test                 # analyzer clean, 2 tests pass
cd app/android && ./gradlew :app:testDebugUnitTest        # 10 Kotlin tests pass
```

**If you edit `pigeons/itantra.dart`:** run `dart run pigeon --input pigeons/itantra.dart` from `app/`. It regenerates both `lib/core/bridge/itantra_api.g.dart` and `android/app/src/main/kotlin/itantra/bridge/ItantraApi.g.kt`.

**Windows gotcha:** if the build fails with "Timeout waiting to lock build logic queue", old Gradle daemons are holding a lock. Run `cd app/android && ./gradlew --stop`, kill leftover `java.exe` processes, delete `app/android/.gradle`, and rebuild.

---

## 6. Getting the models onto a phone

**Easiest:** download packs from https://huggingface.co/datasets/Mr66/itantra-packs

| File | Size | Content |
|---|---|---|
| `<lang>-stt.itpack` | ~190 MB (en ~167 MB) | IndicConformer int8 CTC + tokens |
| `<lang>-tts.itpack` | ~109 MB | MMS VITS voice, 16 kHz, 1 speaker |

Languages: `hi en bn mr gu ta te kn ml or`. For a first test only Hindi (2 files, ~300 MB) is needed.

Then:

```bash
adb push hi-stt.itpack /sdcard/Download/
adb push hi-tts.itpack /sdcard/Download/
```

In the app: Settings > Model packs > Import .itpack, pick each file.

**Rebuilding packs yourself (Colab, no GPU needed)**
1. Zip the `tools/` folder as `tools.zip` with forward-slash paths. Windows PowerShell 5 `Compress-Archive` writes backslashes that break on Linux, so use Python `zipfile`.
2. Open `tools/colab/itantra_model_packs.ipynb` in Colab, upload `tools.zip`, and run all cells.
3. Or by hand:

```bash
pip install sherpa-onnx onnx soundfile huggingface_hub scipy Cython torch
python tools/stt/fetch_indicconformer_onnx.py --out work/stt
python tools/tts/export_mms.py --out work/tts/mms          # Linux only (builds a Cython extension)
python tools/packs/build_pack.py stt --lang hi --dir work/stt/hi --out dist/
python tools/packs/build_pack.py tts --lang hi --dir work/tts/mms/hi --out dist/
```

**Model sources (important, learned the hard way)**
- STT: `parismitaglobalsolutions/indicconformer-sherpa-onnx` on Hugging Face. All 10 Indic models share one `tokens.txt`; English has its own `en/tokens.txt`. Works directly with sherpa `from_nemo_ctc`.
- TTS: Meta MMS checkpoints from `facebook/mms-tts` (`models/<iso3>/G_100000.pth`), exported with sherpa's recipe (`tools/tts/export_mms.py`). English uses sherpa's prebuilt `vits-mms-eng`.
- **Do NOT use** `sriram09764/itantra-tts-onnx`. Its README claims sherpa compatibility, but the ONNX has a single `x` input, so sherpa fails with "input name cannot be empty".
- **Do NOT try the AI4Bharat NeMo fork on Colab.** Colab runs Python 3.13 and the fork's install is impractical there. The ready ONNX repo above avoids NeMo entirely.
- AI4Bharat direct `.nemo` URLs use the name `indicconformer_stt_<lang>_hybrid_rnnt_large.nemo` (not `..._ctc_rnnt_...`). The per-language Hugging Face repos are gated; the direct links are not.

---

## 7. What is verified

| Check | Result |
|---|---|
| `flutter analyze` | No issues |
| Dart tests | 2/2 pass |
| Kotlin unit tests (codec, fragments, dedup, TTL, signatures, encryption) | 10/10 pass |
| Debug APK build | Built successfully once (117 MB) |
| STT + TTS models load and run in sherpa-onnx | Yes, all 10 languages (Colab CPU, 2 threads) |
| Round trip: TTS sentence fed back into STT | See table below |

Round-trip test ("flood water is rising near the school, everyone move to higher ground" in each language):

| Lang | STT speed (RTF) | TTS speed (RTF) | Char error |
|---|---|---|---|
| hi | 0.10 | 0.58 | 8.5% |
| en | 0.05 | 0.79 | 10.2% |
| bn | 0.10 | 0.65 | 3.2% |
| mr | 0.19 | 0.58 | 1.2% |
| gu | 0.11 | 0.54 | 3.3% |
| ta | 0.10 | 0.61 | 2.0% |
| te | 0.10 | 0.84 | 3.3% |
| kn | 0.11 | 0.57 | 1.0% |
| ml | 0.10 | 0.89 | 13.6% |
| or | 0.10 | 0.58 | 6.0% |

RTF 0.10 means a 4 s sentence decodes in 0.4 s. Most of the "errors" are punctuation and spacing. These are desktop CPU numbers from synthetic speech, **not phone numbers and not real human speech**.

---

## 8. What is NOT verified yet

- **The app has never run on a real phone.** None of this has been tried on hardware:
  - pack import on device, STT/TTS load time and RAM on a 3 GB phone
  - BLE mesh between two or three phones, relays and hop counts
  - Wi-Fi Direct group formation
  - alerts over Do Not Disturb and on the lock screen
  - foreground service surviving in the background, battery drain
- The last on-device attempt (OnePlus CPH2717, Android 16, 8 GB RAM) stopped at the build step because of the Gradle lock issue in section 5. The phone was detected by `adb` fine.
- `tools/tts/export_rasa13.py` has never been run. Access to the gated rasa13 repo works (terms accepted on account `Mr66`), but the ONNX wrapper is a best guess at sherpa's VITS input contract.
- `tools/stt/export_indicconformer.py` (NeMo route) has never been run. It is only needed to re-export from `.nemo`.

---

## 9. Known bugs and weak spots (found by reading the code; fix these first)

1. **Wi-Fi Direct tiebreak is broken.** In `WifiDirectTransport.maybeConnect`, `localAddress` is never set, so `dev.deviceAddress < ""` is always false and every phone asks for `groupOwnerIntent = 12`. Two phones may both refuse to be the client. Fix: read the own device address from `WIFI_P2P_THIS_DEVICE_CHANGED_ACTION`, or just use `groupOwnerIntent = 7` and let the framework decide.
2. **Large packets get relayed twice.** In `MeshRouter.handleBytes`, each FRAGMENT is relayed, and then the reassembled packet also hits `relay()` in the TEXT/ALERT branch. Fix: skip `relay(p, ...)` when the packet came from reassembly.
3. **Duplicate BLE links.** Every phone is both GATT server and client, so A connects to B *and* B connects to A. Dedup hides the duplicate packets but airtime doubles. Fix: only connect as central when own peerId < remote peerId (needs the peerId in advertising data or a first handshake).
4. **Latency numbers use the sender's clock.** `receivedAt - sentAt` is wrong if phone clocks differ. Treat it as a rough diagnostic, or add a round-trip ACK timer.
5. **Pack import progress** always reports `PackKind.STT` and an empty language (`ModelApiImpl.importPack`). Cosmetic.
6. **Identity keys are stored in SharedPreferences**, not Android Keystore. Fine for a hackathon, not for production.
7. **Messages from unknown senders** are delivered unverified (by design, so a first alert is never dropped). The UI does not mark them as unverified yet.
8. **The relay counter** in diagnostics counts per transport broadcast, not per packet.

---

## 10. What is left (priority order)

**P0: make the demo work on phones**
1. Fix the Gradle lock and install the APK on one phone.
2. Import `hi-stt` + `hi-tts`. Test Settings > Test my voice (TTS), then hold the mic and speak (STT).
3. Measure on the phone: STT load time, decode time for a 4 s sentence, RAM. The Diagnostics page shows native heap and RTF.
4. Two phones: BLE discovery, send a sentence, hear it on the other phone. Fix bugs 1 to 3 above as they show up.
5. Test an ALERT with the receiving phone silenced, on Do Not Disturb, and locked.
6. Run everything in airplane mode (with Bluetooth and Wi-Fi switched back on) to prove it is offline.

**P1: demo polish**
- Three-phone relay test (A and C out of range of each other, B in the middle), with hop counts shown in the UI.
- A `demo` build flavor with hi + en packs bundled (`build_pack.py --assets`) so judges do not need to import files. The APK grows to ~700 MB; the alternative is phone-to-phone pack sharing (P2).
- Mark unverified senders in the feed.
- App icon, splash screen, Hindi UI strings.

**P2: planned features not built yet**
- Model pack sharing phone to phone over Wi-Fi Direct (`PACK_OFFER` / `PACK_CHUNK` types are reserved in the protocol but not implemented).
- Direct messages to one person: `MeshRouter.sendDm` exists and is encrypted, but there is no UI for it.
- Store-and-forward is only a simple outbox (100 packets, 24 h). No courier or gossip sync.
- Better battery-aware scanning than the current "20 s low latency, then balanced" switch.
- Automatic language detection (currently the speaker picks their language in Settings).

**P3: model quality**
- Try `vits_rasa_13` (one 40M model for bn mr ta te kn ml, CC-BY-4.0, a better license than MMS). Run `tools/tts/export_rasa13.py` on Colab, check with `verify_tts.py`, then build a multi-language pack: `build_pack.py tts --lang bn mr ta te kn ml --name rasa13-tts`. The app already supports multi-language packs with per-language speaker ids.
- Train one small multilingual VITS or Matcha model on the IIT Madras IndicTTS dataset to get a single all-in-one voice (the team's long-term goal).
- Run `tools/bench/wer.py` on real recorded speech (not synthetic TTS audio) for honest WER numbers in the report.
- A smaller STT for 2 GB phones: further quantization, or the Hinglish "swift" models in the same Hugging Face repo.

**P4: SIH report material**
- Architecture diagram, bandwidth comparison, the verification tables above, the license table (section 11), and photos or video of the multi-phone test.

---

## 11. Licenses

| Component | License | Note |
|---|---|---|
| IndicConformer (AI4Bharat) | MIT | STT for 9 Indic languages |
| English STT (NVIDIA fast-conformer CTC, via sherpa) | CC-BY-4.0 | |
| ONNX packaging repo (parismitaglobalsolutions) | Apache-2.0 | |
| MMS-TTS (Meta) | **CC-BY-NC-4.0** | Non-commercial. OK for SIH; must be replaced for any commercial use. |
| vits_rasa_13 (AI4Bharat) | CC-BY-4.0 | Not used yet |
| Silero VAD | MIT | |
| sherpa-onnx | Apache-2.0 | |
| BitChat | Only protocol ideas from the public-domain whitepaper. No GPL code copied. | |

---

## 12. Accounts, secrets and tooling notes

- **Never commit tokens.** `.env` is gitignored. The original developer's Hugging Face tokens were pasted into a chat during development, so they should be **revoked and rotated** at https://huggingface.co/settings/tokens. Create your own token if you need to upload packs.
- The packs dataset `Mr66/itantra-packs` belongs to the original developer's Hugging Face account. Downloading is public; uploading needs their token, or copy the packs to your own account.
- The **Google Colab CLI** (`colab`, v0.6) was used to run pack builds from the terminal. Quirks on Windows:
  - set `PYTHONUTF8=1`, otherwise scripts containing Indian scripts fail to load (cp1252)
  - always use `colab exec -s <name> -f script.py`; never pipe code in (PowerShell adds a BOM)
  - `colab exec` gives up after about 2 minutes with no output and interrupts the kernel. For long jobs, start a detached process on the VM (`subprocess.Popen(..., start_new_session=True)`) that writes to a log file, then poll with `colab download`
  - free VMs get reclaimed, so upload results (for example to Hugging Face) as each step finishes
  - use PowerShell, not Git Bash, for `colab download /content/...` (Git Bash rewrites `/content` paths)
  - always run `colab stop -s <name>` when done
- The git history has no AI attribution trailers; keep it that way.

---

## 13. Quick start checklist for the next developer

- [ ] Clone the repo, download the sherpa AAR, run `flutter pub get`
- [ ] `flutter analyze`, `flutter test` and `./gradlew :app:testDebugUnitTest` all pass
- [ ] Download `hi-stt.itpack` and `hi-tts.itpack` from the Hugging Face dataset
- [ ] `flutter run` on a phone, finish onboarding, import both packs
- [ ] Settings > Test my voice plays Hindi (TTS works)
- [ ] Hold the mic and speak Hindi; text appears (STT works)
- [ ] Second phone: same steps; the Mesh tab shows the first phone
- [ ] Speak on phone A, hear it on phone B
- [ ] Fix known bugs 1 to 3 (section 9)
- [ ] Alert test on a silenced phone
