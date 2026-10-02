# iTantra (SIH 26173/26174): Engineering Research Report for Team Hexabits
## Offline Indic STT/TTS, prosody-preserving "semantic radio", and a phone-only disaster mesh

The best submission is not a new ASR or TTS model. It is the system you already have, extended into a **"text + prosody" semantic radio**. Keep per-language AI4Bharat IndicConformer on sherpa-onnx for STT. Add a roughly 20–90-byte prosody/paralinguistic side-channel (pauses, fillers, urgency, pitch/rate, voice class). Render it on the receiver with your existing VITS voices plus a deterministic "prosody renderer". Then harden the BLE mesh with extended-advertising alert flooding, Coded PHY where the phone supports it, priority queues, and store-carry-forward. Every piece of this runs offline on a 3 GB Android phone today. Very few competing teams will have the combination.

## TL;DR

- **STT: keep per-language IndicConformer (MIT, ~120M params, int8 ~190 MB). Nothing open and phone-sized beats it for all 10 languages. Upgrade the front-end, not the model.** Add software AGC, low-energy-tuned VAD, adaptive endpointing and push-to-talk (PTT) release as end-of-utterance. Expect Malayalam to stay the weakest language: the IndicVoices paper reports 40.5% WER for its IndicASR model, versus 15.0% for Hindi. Whisper, MMS-1B, IndicWhisper, Seamless and the 600M multilingual model are all too heavy, carry the wrong license, or are much worse on Indic speech.
- **TTS / voice preservation: keep MMS-TTS for the demo, but add a prosody-renderer layer.** It covers measured pauses, filler clips, per-phrase speed/pitch/energy, an urgency preset, and nearest-voice selection from a small voice bank. That delivers "sounds like how the person talked" within weeks. Plan to replace MMS (CC-BY-NC-4.0, non-commercial) with one small multilingual VITS/Piper/Matcha model trained on CC-BY-4.0 SYSPIN plus IndicTTS/IndicVoices-R. Use Apache-2.0 Indic Parler-TTS offline as a "teacher" to synthesize emotional and urgent training data. True zero-shot voice cloning (IndicF5, kNN-VC, OpenVoice-class) is research-only on low-end phones.
- **Mesh: there is no phone-only way to get kilometres of range from one hop.** Area coverage comes from (a) density and hops, (b) Coded PHY on the subset of phones that support it (in Nordic Semiconductor's nRF52840 dev-kit outdoor test at 0 dBm, 1,300 m on Coded PHY versus 681.9 m on 1M PHY), (c) connectionless extended-advertising flooding for alerts, and (d) delay-tolerant store-carry-forward with "data mule" and "gateway" roles. Real-world precedents are FireChat (Hong Kong 2014) and BitChat (Nepal/Indonesia/Madagascar 2025). They are protest shutdowns, not floods. On July 23, 2026 India's I4C (MHA) directed Google to delist BitChat, Briar and Bridgefy within three hours; officials orally withdrew the demand on July 24 (MediaNama). That means you should pitch iTantra explicitly as an authenticated disaster-alert system, not an anonymous chat app.

---

## Key Findings (what to put on the slides)

| # | Stand-out point | Status | Why it matters for scoring |
|---|---|---|---|
| 1 | **"Semantic radio": text + ~24–90 B prosody frame instead of audio.** One sentence fits one BLE write (and, compressed, one extended advertisement) | Demo-ready (2–4 weeks) | Directly addresses the "low bitrate links" title; ~30x smaller than 16 kbps Opus (your own measurement) |
| 2 | **Prosody renderer**: pauses with measured durations, filler tokens ("अं…", "umm") rendered as matched clips, urgency style, speaking-rate and pitch-class transfer | Demo-ready | Accuracy criterion (40%) includes "human legibility and flow" of TTS; this is the flow |
| 3 | **PTT release = end-of-utterance** plus adaptive endpointing (Smart Turn v3, 8 MB) in hands-free mode | Demo-ready | Cuts the fixed 450 ms silence wait from the latency metric |
| 4 | **Script-offset text compression** (1 byte per Indic character instead of 3 in UTF-8) | Demo-ready | Pushes a full sentence plus signature under the 254 B extended-advertising PDU |
| 5 | **Distress auto-tagging**: urgency score from energy/pitch/rate plus sound-event tags (scream/crying/siren) plus keyword spotting, sent as 1–2 bytes and turned into ALERT priority | Demo-ready (heuristics); stretch (trained SER) | Unique, practical, and fits the ISRO "alert and distress" framing |
| 6 | **Alert-first mesh**: connectionless extended-advertising flood, Coded PHY where available, priority queues, geo/TTL scoping, "heard-by-N" receipts, data-mule and gateway (SMS) roles | Partly demo-ready; DTN/gateway is 4–8 weeks | Answers "maximum area coverage with phones only" |
| 7 | **Hybrid voice mode** (optional): ~1.2–1.6 kbps neural/parametric codec over Wi-Fi Direct/Aware when the link allows; text+prosody over BLE when it doesn't | Stretch | Shows engineering depth; honest comparison slide |
| 8 | **Coverage/latency simulation** (Python + The ONE) plus a real 6–10 phone field test | 1–2 weeks | Judges want numbers, not claims |

---

# Part 1 — STT that captures soft voices, pauses and disfluencies

## 1.1 Offline STT model landscape for the 10 languages

| Model | Params / on-disk | RAM on phone | Languages (of your 10) | Reported accuracy | License | Android / sherpa-onnx readiness | Verdict |
|---|---|---|---|---|---|---|---|
| **AI4Bharat IndicConformer per-language "large" (hybrid CTC-RNNT)** | 120M; 17 conformer blocks, d=512;\[1\] int8 CTC export ~188–190 MB\[2\] | ~250–350 MB (est.) | all 9 Indic (English via FastConformer) | IndicVoices-paper IndicASR (130M, related): hi 15.0, bn 15.9, gu 21.4, mr 18.2, kn 30.3, **ml 40.5**, ta 31.2, te 26.8, **or 23.4** WER\[3\] | MIT (repos gated)\[1\] | Community sherpa-onnx NeMo-CTC int8 ports exist (e.g., ml/ta/te);\[4\]\[5\] RNNT export currently blocked (NeMo fork `KeyError: 'dir'`)\[4\]\[6\] | **Keep (current choice is correct)** |
| IndicConformer 600M multilingual | 600M, 22 languages, hybrid CTC+RNNT\[7\]\[8\] | ~2.5 GB (your measurement) | all | Third-party: IndicVoices hi 15.5 / te 27.4 (RNNT), 17.1 / 29.0 (CTC); Kathbath hi 8.9, te 20.9\[9\] | MIT (gated) | Too large for 3 GB phones | Reject for phone; use as offline "teacher"/eval reference |
| IndicWhisper (Vistaar) | Whisper fine-tunes (medium-class) | >1.5 GB | 12 Indian languages | Lowest WER on 39 of 59 Vistaar benchmarks, avg −4.1 WER vs. others\[10\] | MIT | whisper.cpp possible but slow on low-end CPUs; autoregressive decoder raises latency | Reject for phone; useful benchmark reference |
| Whisper tiny/base/distil | 39M–74M+ | 150–400 MB | nominal | Whisper on IndicVoices: ml 148.6, te 151.9, mr 95.2 WER (larger Whisper; small ones are worse)\[3\] | MIT | sherpa-onnx supports Whisper\[6\] | Reject (hallucinations, >100% WER on Dravidian) |
| Meta MMS-1B-all + adapters | 1B | >2 GB | all | IndicVoices: ml 76.8, or 54.6, hi 38.9 WER\[3\] | CC-BY-NC-4.0 | Heavy | Reject |
| Dolphin (DataoceanAI) CTC small | int8 239 MB / fp32 783 MB\[6\] | ~300–400 MB (est.) | hi, ta, te, gu, mr, **or**, bn — **no ml, no kn**\[6\] | Not benchmarked on IndicVoices in sources found | Check license before use | sherpa-onnx official (offline CTC)\[6\] | Possible fallback for Odia A/B test only |
| sherpa-onnx streaming Zipformer Bengali (Vosk, 2026-02-09) | ~90 MB fp32 (encoder 87 MB)\[11\]\[12\] | ~150 MB (est.) | **bn only** | Not reported; RTF 0.04 single-thread in docs example\[12\] | Vosk model (verify license) | Official, true streaming\[12\] | Only true streaming Indic option found; demo "streaming mode" for Bengali |
| bodhan-ai indic-transcribe-flex (Canary) | FastConformer + Transformer decoder, fp32 ONNX\[13\] | Large | 27 Indian languages + Indian English\[13\] | Not verified | **Indic Open Model License v1.0** (custom, attribution)\[13\] | Community sherpa-onnx port\[13\] | Research only; license must be reviewed |
| Sarvam Edge (Saaras edge) | 74M, ~294 MB\[14\] | n/a | 10 Indic + auto-LID\[15\] | Vendor claims only | **Closed SDK (not open-source)**\[16\] | Not usable | **Prohibited by SIH rules**, but useful as a slide benchmark: "a 74M multilingual model is industry state of the art on-device" |
| Sarvam Saaras V3/V4, Shuka | Cloud API / LLM-sized | n/a | 22 | Vendor | Proprietary API (Shuka open but 8B-class) | No | Prohibited / infeasible |
| Moonshine, Parakeet (multilingual), SeamlessM4T/Streaming | — | — | No real Indic coverage (Moonshine/Parakeet) or NC license + large (Seamless) | — | — | — | Reject |

**Why the current choice is right.** Per-language 120M IndicConformer is trained on IndicVoices-style spontaneous speech.\[3\] It is MIT-licensed, and it is the only open family that covers all nine Indic languages\[17\] at a size a 3 GB phone can hold with one model resident. The alternatives are not close. Whisper-class and MMS models post 55–150% WER on Dravidian languages in the IndicVoices benchmark, while the related IndicASR model scores 26–40%.\[3\]

**Flag: Malayalam and Odia.**
- **Malayalam is the hardest language across every system in the IndicVoices table.**\[3\]\[18\] It has an agglutinative morphology, so one error spoils a long word and WER inflates. Your 13.6% CER on synthetic speech will be worse on real humans. Mitigations:
  - Report **CER** alongside WER for Malayalam (and Tamil/Telugu).
  - Add a small **hotword/keyword list** for distress vocabulary (place names, "help", "injured", "water level").
  - Record 30–60 minutes of real Malayalam speech to measure the true WER. Don't rely on TTS-generated test audio.
- **Odia has the fewest open models.** MMS scores 54.6% WER and most commercial systems don't report it at all. IndicASR-class models are around 23% on IndicVoices.\[3\] Keep IndicConformer-or. Optionally A/B it against Dolphin-small (which covers Odia) on your own recordings.

**Note on the 30M model.** AI4Bharat's model page also describes an older **30M-parameter IndicConformer "to support real-time ASR systems"**.\[19\] For 2 GB phones, check whether per-language 30M checkpoints are still downloadable.

**Competitive intelligence.** A public Hugging Face repo (`sriram09764/itantra-tts-onnx`) already hosts sherpa-onnx MMS-TTS conversions for exactly these 10 languages, labelled as an "iTantra Radio Android app – SIH 2024 Project 26173".\[20\] Plain "IndicConformer + MMS on sherpa-onnx" is therefore likely to be a common baseline among teams. Your differentiation has to come from the prosody channel, the alert pipeline and the mesh.

## 1.2 Streaming vs non-streaming

- **What actually determines "words said → STT complete".** It is endpoint wait + final decode. Your final decode is already fast: 236 ms for a short utterance on the OnePlus, and desktop RTF 0.05–0.19. The dominant cost is the **fixed 0.45 s silence wait** plus VAD hangover. A streaming model would mostly help partial transcripts on screen, not make the final text meaningfully earlier.
- **Streaming Indic models available.** The only official sherpa-onnx streaming Indic model found is the **Bengali streaming Zipformer (Vosk-derived, ~90 MB)**.\[11\]\[12\] There is no official streaming Hindi, Odia, Malayalam or other Indic model.
- **Recommended approach: "pseudo-streaming".** Re-run the offline CTC model on the growing buffer every ~0.6–0.8 s. At RTF ≤0.2 this costs little and gives live partial text. At endpoint, run one final pass. Optional stretch: transmit a "partial" packet at a long internal pause (>0.7 s) so the receiver can start speaking clause 1 while clause 2 is still being spoken.
- **In PTT (walkie-talkie) mode, button release is the endpoint.** No silence timeout is needed, which saves roughly 450–700 ms per message.

## 1.3 Capturing soft, low-volume and whispered speech

**Capture path (Android).**
1. Use `AudioSource.VOICE_RECOGNITION`, which is tuned for ASR with minimal processing on most devices. Query `AutomaticGainControl`, `NoiseSuppressor` and `AcousticEchoCanceler` availability and log them per device. Use `VOICE_COMMUNICATION` only in "phone mode" full-duplex, where you need AEC.
2. **Software AGC before the VAD.** Track the noise floor as the 10th percentile of frame RMS over 3 s. Apply up to +24 dB gain to frames above floor+6 dB, with a limiter at −1 dBFS. WebRTC's AGC2 (BSD) is the production-grade open option. Normalise each finished segment to about −20 dBFS RMS before STT.
3. **Denoising is optional and must be A/B tested, because denoisers can raise WER.** RNNoise (BSD) is cheapest; DTLN (MIT, ~1M params) moderate; DeepFilterNet best but heaviest; sherpa-onnx also ships GTCRN speech enhancement. Enable a denoiser only when estimated SNR is below ~10 dB.

**VAD choices for low-energy speech.**

| VAD | Size | Latency / behaviour | License | Recommendation |
|---|---|---|---|---|
| Silero VAD (current) | ~630 KB | Robust. TEN's authors show Silero lags speech→silence transitions by "several hundred milliseconds" and can miss short gaps | MIT | Keep as primary, retune |\[21\]
| TEN VAD | ~306 KB library; RTF 0.015 on AMD Ryzen | Frame-level (10/16 ms hops), faster end detection, Android + Java JNI | Open-source (check its Apache-2.0-based terms) | Try as the **end-of-speech detector** paired with Silero |\[22\]
| WebRTC VAD | tiny | Energy/GMM-based, poor on whispers and noise | BSD | Fallback only |

**Silero tuning for whispers.** Lower the speech threshold from 0.5 to 0.30–0.35 when AGC gain is above +12 dB (a "quiet speaker" state). Set `min_speech_duration` to ~150 ms, pad segments by 200 ms, and use hysteresis (`threshold − 0.15`) for speech-off. Whispered speech has no pitch, so add an **energy-plus-spectral-flatness side detector** that holds the VAD "on" in the quiet state.

**Adaptive endpointing (replace the fixed 0.45 s).**
- **PTT mode:** endpoint = button release (+80 ms tail).
- **Hands-free mode:**
  1. The VAD reports silence ≥250 ms. Run **Pipecat Smart Turn** (BSD-2-Clause; Whisper-Tiny backbone + linear classifier; 8 MB int8 CPU version; Daily.co reports inference in 12 ms on modern CPUs and 60 ms on a low-cost AWS instance; 23 languages incl. Bengali). v3.1 keeps all 23 languages but its accuracy gains are mainly for English and Spanish, so use v3.1+ and verify accuracy for Hindi and the others.
  2. If P(turn complete) ≥0.6, end the utterance.
  3. Otherwise extend the wait to 800 ms; also extend if the last CTC token is a conjunction or postposition ("और", "कि", "ಮತ್ತು", …).
  4. Cap the wait at 1.2 s, and replace the 12 s hard cut with **"split at the longest pause after 8 s"**.
- **Decoder-confidence endpointing:** if the last ≥300 ms of CTC output are blank-dominated *and* the last word's token confidence is high, the sentence is likely finished.

## 1.4 Disfluency- and paralinguistics-aware STT

- **Filler transcription is a known gap.** Most Indic ASR is trained to *ignore* hesitations; the 2026 IndicContextEval benchmark's instruction explicitly says "hesitations must be ignored".\[18\] Expect IndicConformer to drop "umm/aaa". CrisperWhisper (verbatim Whisper) and Whisper prompting can transcribe fillers, but only for English/European languages and at Whisper cost — not viable on-device for your 10 languages.
- **Practical method: detect fillers acoustically, not lexically (demo-ready).**
  - A VAD-speech region of ≥200 ms that yields **no CTC tokens (blank-dominated)** is a candidate.
  - With **stable voiced pitch and low spectral change** it is a *filled pause*: nasal → "hmm/umm", open vowel → "aaa".
  - Unvoiced with high-frequency noise → *breath*.
  - Emit it inline, e.g. `⟨F:a:420⟩` = filler "aa", 420 ms. Pauses come straight from VAD/CTC timestamps.
- **Sound events (demo-ready, unique for distress).** sherpa-onnx ships AudioSet audio-tagging models;\[23\] a small tagger yields 1-byte tags such as *Screaming, Crying, Siren, Explosion, Water, Breathing* that set urgency.
- **Emotion/urgency.** Heuristic urgency score (demo-ready): z-scored energy + pitch mean + pitch range + speaking rate relative to the speaker's running baseline, combined with distress-keyword hits ("बचाओ", "help", "காப்பாத்துங்க", …). Stretch: a small SER head trained on Indic emotional speech (Rasa has 6 emotions for Assamese/Bengali/Tamil);\[24\] present as future work, since open Indic SER data is thin.\[25\]
- **Prosody extraction (demo-ready).** YIN/pYIN pitch at 10 ms, RMS energy, syllable rate from vowel nuclei or CTC token rate, summarised per word into 4-bit classes.

## 1.5 "Text + prosody" frame: byte budget and codec comparison

**Proposed iTantra Prosody Frame (IPF v1).**

| Field | Encoding | Bytes |
|---|---|---|
| Version / flags (has-embedding, has-events, partial) | bitfield | 1 |
| Language + script ID | 4+4 bits | 1 |
| Global: speaking rate (8 levels), pitch mean class (16), pitch range (8), energy (8) | packed | 2 |
| Emotion/urgency (8 classes × 4 intensity levels) + urgency score | 5+3 bits | 1 |
| Voice class (gender ×3 × age ×3 × pitch band ×4 → nearest-voice bank index) | 1 byte | 1 |
| Per-word: pause-after (0–2.55 s in 10 ms steps), 4-bit pitch accent + 4-bit emphasis | 2 B/word | ~16 for 8 words |
| Inline filler/breath tokens (type + duration) | 2 B each | ~2–4 |
| Sound-event tags (up to 2) | 1 B each | 0–2 |
| **Subtotal without embedding** | | **~24–28 B** |
| Optional speaker embedding (64-D int8 PCA of a 192/256-D ECAPA/ERes2Net vector), sent once per session and cached | | +64 |

**Text compression (key trick).** Each Indic script occupies a 128-code-point Unicode block (e.g., Devanagari U+0900–U+097F). Send a 1-byte script ID plus a 1-byte offset per character — about one-third of UTF-8's 3 B per Indic char. A ~45-character Hindi sentence becomes ~46 B instead of ~135 B.

**Resulting packet for a 3.5 s Hindi sentence (estimate).** Header (~14 B) + compressed text ~46 B + IPF ~26 B + Ed25519 signature 64 B ≈ **150 B**; with ChaCha20-Poly1305 for private messages, ~180 B. That is **under one BLE extended-advertising PDU (254 B of AdvData)** and far under one GATT write at MTU 517. Legacy 31-byte advertisements should only carry beacons and packet IDs.

**Bitrate equivalence.** 150 B per 3.5 s ≈ **340 bps**, including signature and prosody — versus Codec2 700C (700 bps, audio only, no auth) and Opus 16 kbps (~7,000 B, your measurement).

**Neural and parametric codecs as an alternative or hybrid mode.**

| Codec | Bitrate | Phone CPU feasibility | Quality / notes | License | Verdict |
|---|---|---|---|---|---|
| Codec2 700C / 1200 / 1300 | 0.7–1.3 kbps | Trivial (tens of MIPS) | Robotic but intelligible. FreeDV 700C modes are "not actively maintained" | LGPL-2.1 | Viable fallback voice mode |\[26\]
| LSPnet (IIT Bombay 2026 course project) | 1.2 kbps (Codec2 encoder + 0.9M-param WaveRNN-style decoder) | Real-time CPU decoding claimed | Research code, English (LibriTTS) | MIT (except codec2 dir) | Interesting stretch |\[27\]
| LPCNet 1.6 kbps | 1.6 kbps | 3 GFLOPS. Real-time used 68% of one core on Snapdragon 845 (1.47x RT) and 31% on Snapdragon 855\[28\] | Much better than MELP;\[29\] **no longer actively developed** (FARGAN successor ~600 MFLOPS) | BSD | Mid-range phones only; low-end ARMv7 marginal |\[30\]
| Lyra v2 (SoundStream) | 3.2/6/9.2 kbps | Designed for mobile real-time | Good | Apache-2.0 | Above your ~1 kbps target |
| EnCodec / Mimi / WavTokenizer / BigCodec (~1 kbps) | 0.5–1.5 kbps | Heavy decoders; not real-time on low-end CPU | High quality at low rate | Mixed (EnCodec CC-BY-NC) | Research only |
| STCTS (arXiv 2512.00451, 2025) | **80 bps** (context-aware text encoding 70 bps + sparse prosody <14 bps at 0.1–1 Hz + amortised speaker embedding) | Uses heavy STT/TTS components | Per the arXiv abstract: NISQA MOS >4.26 on LibriSpeech; 75x bitrate reduction versus Opus 6 kbps and 12x versus EnCodec 1 kbps | Research | **Academic validation of your exact design**; cite on the slide |
| Urazayev et al. 2025 (IEEE CSCN) | STT → optional LLM summary → TTS over LoRa\[31\] | — | Reports ~50% transmission-time reduction even without semantic compression for short texts\[31\] | Research | Related work; you go further (prosody + mesh + Indic) |

**Hybrid-mode recommendation (stretch).** When a Wi-Fi Direct/Aware link is up and the queue is empty, attach real voice (Codec2 1300 universally, or LPCNet on arm64 phones that benchmark >2x RT) to the text+prosody packet. The receiver plays the real voice if all fragments arrive within 1.5 s, else falls back to TTS. Over BLE multi-hop, always use text+prosody. Present this as "graceful degradation: voice → expressive TTS → text → siren".

## 1.6 Integration steps into your current stack (Part 1)

1. **`AudioCapture`:** VOICE_RECOGNITION source, device-effect logging, `SoftAgc` (C++/JNI, 10 ms frames), segment RMS normalisation.
2. **`SentenceSegmenter`:** refactor into an `EndpointPolicy` interface (`PttPolicy`, `AdaptivePolicy` with Silero + optional TEN VAD + Smart Turn ONNX, `LegacyFixedPolicy` for A/B). Split-at-longest-pause instead of the 12 s hard cut; emit word/pause timestamps.
3. **`SttEngine`:** CTC token timestamps, pseudo-streaming partials off the UI thread, distress hotword boosting, pre-warm on language selection, per-utterance RTF logging.
4. **New `ProsodyExtractor` and optional `EventTagger`:** output an `IpfFrame`; run the tagger only in ALERT mode or when urgency is high.
5. **Evaluation harness:** real human recordings (10 speakers × 10 languages × 20 sentences, incl. whispered and far-field sets); WER/CER per language, soft-speech deletion rate, endpoint latency, and idle-listening CPU% via Perfetto (explicitly scored).

---

# Part 2 — Expressive, voice-preserving TTS on low-end phones

## 2.1 TTS model landscape

| Model | Languages (of your 10) | Size | Mobile speed | Quality / expressiveness | License | sherpa-onnx / ONNX path | Verdict |
|---|---|---|---|---|---|---|---|
| **Meta MMS-TTS (VITS), per language** (current) | all 10 | ~109 MB each, 16 kHz, single speaker | Your desktop RTF 0.54–0.89; phone load 654 ms | Intelligible, flat prosody | **CC-BY-NC-4.0** | Native (VITS) | Keep for demo; flag NC |
| ai4bharat vits_rasa_13 | misses hi, gu, or, en | 40M | Fast | Rasa emotions for some languages | CC-BY-4.0 | VITS → ONNX feasible | Use for bn/ta emotional demo only |
| AI4Bharat Indic-TTS (FastPitch + HiFi-GAN) | 13 Indic incl. hi, bn, gu, mr, kn, ml, ta, te, or (male + female)\[32\] | tens of MB per model | FastPitch non-autoregressive, fast; HiFi-GAN V1 heavier | Better MOS than prior Indic systems;\[24\] **explicit pitch/duration control** | Check repo/model terms | Coqui → ONNX export needed | Good candidate for pitch-controllable voices (stretch) |
| **Indic Parler-TTS** (AI4Bharat) | 20 Indic + English\[33\] | **3.75 GB** safetensors\[34\] | Not phone-feasible | 69 voices; caption-controlled pitch/speed/expressivity; emotions incl. Command, Anger, Fear, Happy, Sad, Surprise, News, Narration\[33\]\[35\] | **Apache-2.0**\[34\] | No | **Use offline as a data "teacher"** |
| IndicF5 | 11 Indic (**no English**)\[36\] | F5-TTS class (iterative flow matching) | Not phone-feasible | Near-human, **zero-shot cloning from reference audio**\[37\] | MIT (gated) | No | Research only; teacher for voice-bank data |
| SYSPIN / LIMMITS (IISc) | hi, bn, mr, te, kn (+ SPICOR en, gu) | varies | varies | 40+ h per speaker, studio quality | Data **CC-BY-4.0** (900+ h corpus) | Train your own | **Best commercial-friendly training data** |\[38\]
| Kokoro-82M | English + **Hindi** only (among yours) | 82M | Near-real-time on mid-range arm64 | Very natural | Apache-2.0 | sherpa-onnx supported\[39\] | Optional English/Hindi premium voice |
| Piper (VITS) / Matcha-TTS | Train-your-own | 15–60 MB per voice | Real-time on low-end CPUs | Depends on data | MIT / Apache | sherpa-onnx supported | **Target architecture for the long-term model** |
| Sarvam Bulbul edge | 10 Indic, 8 speakers\[40\] | 24M / ~60 MB\[14\] | Vendor claims | — | **Closed** | No | Prohibited; proof that one ~60 MB multilingual model is feasible |
| Kitten TTS, Supertonic, MeloTTS, StyleTTS2 | No Indic coverage (StyleTTS2 heavy) | — | — | — | — | — | Reject |

**Licensing flag for the slide.** MMS-TTS is **CC-BY-NC-4.0**: fine for a non-commercial hackathon prototype, but ISRO or NDMA adoption requires a swap. The clean path is Piper/Matcha voices trained on **SYSPIN (CC-BY-4.0)** + IndicVoices-R, plus synthetic data from **Indic Parler-TTS (Apache-2.0)**. Check IITM IndicTTS database terms before commercial use.

## 2.2 Emotion and style control

- **On-device today:** VITS in sherpa-onnx exposes `length_scale` (speed), `noise_scale` and `noise_scale_w`. Three usable styles without retraining: Calm (`length_scale` 1.05, noise 0.5); Neutral (defaults); Urgent (`length_scale` 0.85, noise 0.75, +3 dB, +2 semitone post-hoc pitch shift).
- **Stretch (train):** a multi-speaker VITS/Matcha with a **style/emotion ID embedding**, trained on SYSPIN/IndicTTS (neutral), Rasa (bn/ta/as emotions), and — the key idea — **synthetic urgent/fearful/calm data for all 10 languages generated offline with Indic Parler-TTS captions** ("A male speaker speaks fast and urgently with a fearful tone, very clear audio"). This distils caption control into a 20–40M-parameter on-device model: a genuinely novel, license-clean contribution.

## 2.3 Voice preservation

| Option | What is sent | Receiver cost | Realism on a low-end phone |
|---|---|---|---|
| **Voice bank + nearest match** (recommended now) | 1 byte (gender/age/pitch-band class) | Choose 1 of N preset voices (MMS + pitch/formant variants, or 2–4 trained voices per language) | **Demo-ready.** "Similar enough" and deterministic |
| Speaker embedding → multi-speaker TTS conditioning | 64 B int8 (PCA of ECAPA/ERes2Net/TitaNet; sherpa-onnx ships speaker-embedding extractors) | Needs a multi-speaker TTS trained with embedding input (YourTTS-style) | Stretch (requires training) |
| Tone-colour converter (OpenVoice), FreeVC, Seed-VC, kNN-VC | Reference audio or embedding | kNN-VC needs WavLM-Large-class features (300M+ params); others 100M+ and slow | **Research only** on 3 GB phones |
| Zero-shot TTS (IndicF5) | Reference clip | GPU-class | Research only |

**Practical demo trick.** The sender computes median F0 and gender class. The receiver renders with the matching bank voice, then shifts pitch toward the sender's median F0 (±3 semitones max, WSOLA/PSOLA in C++). It costs a few ms per second of audio and noticeably increases "sounds like them".

## 2.4 Rendering pauses and fillers (what is quick vs what needs training)

| Feature | Quick method (weeks) | Better method (training) |
|---|---|---|
| Pauses | Split text at IPF pause markers; synthesise phrases separately and insert silence of the measured duration (clamped 80–1,500 ms) with 10 ms fades | Duration-controllable model (FastPitch/Matcha) with pause tokens |
| Fillers ("अं…", "hmm", "umm", "aaa") | **Pre-synthesised filler clips per language × voice** (same voice, so timbre matches), time-stretched to the measured duration | Filler tokens in training transcripts (IndicVoices has spontaneous speech) |
| Breath | Short bank of breath samples at −18 dB | — |
| Emphasis | Per-phrase +2 dB gain and +1 semitone on high-emphasis words | Pitch/energy conditioning (FastPitch) |
| Speaking rate | `length_scale = clamp(sender_rate / voice_rate, 0.75, 1.3)` per phrase | — |
| Urgency | Urgent style preset + ALERT playback path | Emotion-ID model (2.2) |

**Latency fix you need regardless.** At TTS RTF 0.54–0.89 (desktop), a 3.5 s sentence takes 1.9–3.1 s to synthesise in one shot. Because the renderer already splits at pauses, **synthesise phrase 1, start playback, and synthesise phrase 2 while phrase 1 plays** (sherpa-onnx's generate-with-callback supports this). Time-to-first-audio becomes roughly 0.3–0.7 s. Consider Piper "low" voices for 2 GB phones.

## 2.5 Best TTS strategy

- **Now (demo in 2–4 weeks):** keep MMS-TTS and build the `ProsodyRenderer`: phrase splitting, measured silences, filler clips, rate/pitch/gain per phrase, urgency preset, voice-bank selection with pitch shift, streaming phrase playback. Alerts stay on the ALARM stream at max volume, with an urgent style and a spoken prefix ("चेतावनी"/"Alert").
- **Later (2–4 months):** train one **multilingual, multi-speaker Piper/Matcha (~20–40M)** model on SYSPIN (CC-BY-4.0) + IndicVoices-R (1,704 h, 10,496 speakers, 22 languages)\[41\]\[42\] + IndicTTS (check terms) + Parler-generated emotional data, conditioned on language, voice-class and emotion IDs, exported to ONNX int8. Target ≤60–80 MB for all 10 languages (Sarvam's closed 24M/60 MB model shows this is achievable), replacing ~1.1 GB of per-language MMS voices and removing the NC license.

## 2.6 Integration steps (Part 2)

1. **`TtsEngine`:** expose `lengthScale`, `noiseScale`, `noiseScaleW` per call; add callback-based streaming synthesis to the Pigeon bridge; LRU of 2 voices (1 on 2 GB devices).
2. **New `ProsodyRenderer` (Kotlin + small C++ DSP):** text + IpfFrame → queue of PCM chunks to `AudioTrack` (silence insertion, filler mixer, ±3 st pitch shift, gain envelope, 10 ms crossfades).
3. **`VoiceBank` asset pack:** 3–6 voice variants per language plus filler/breath clips (~50–200 KB per language).
4. **`AlertPlayer`:** keep ALARM stream, siren, ×3 repeat; add urgent style and a pre-synthesised "Alert" prefix so the first sound plays within ~50 ms of packet receipt.
5. **MOS test:** 10+ native listeners per demo language rate plain MMS vs MMS+ProsodyRenderer for naturalness and similarity to the original speaker.

---

# Part 3 — Phone-only multi-hop mesh: real-world lessons and range extension

## 3.1 What actually happened in real events

| Event | App / tech | Documented facts | Lesson |
|---|---|---|---|
| Iraq, June 2014 (internet restrictions) | FireChat (Open Garden) | ~40,000 Iraqi downloads reported by The Guardian\[43\] | Adoption spikes only *after* a shutdown; pre-installation matters |
| **Hong Kong Umbrella Movement, Sep–Oct 2014** | FireChat (BLE + Wi-Fi peer-to-peer) | CNN Business (Oct 16, 2014): between Sep 27 and Oct 10, 500,000 downloads in Hong Kong alone (61% Android, 39% iOS), 10.2 million chat sessions and 1.6 million chatrooms; up to 37,000 concurrent mesh users\[44\] | Works at **crowd density**. Anonymity caused confusion,\[44\] so **authenticated senders** matter. FireChat later shut down (2018)\[45\] |
| Hong Kong 2019–20, Nigeria, Thailand | Bridgefy | Protest usage;\[46\]\[47\] Bridgefy claims 12.5M+ users, ~330 ft (≈100 m) direct range\[48\]\[49\] | Early versions criticised for weak crypto (E2E added 2020).\[47\] **Security is judged harshly** |
| Indonesia, Aug 2025 | BitChat | ~12,581 downloads during protests (developer-reported)\[50\] | — |
| **Nepal Gen-Z protests, Sep 2025** (social-media ban) | BitChat | 48,781 downloads on Sep 8 alone, >38% of installs at the time (developer "calle", via Forbes)\[50\]\[51\] | Mass adoption within hours of a shutdown |
| Madagascar, Sep–Oct 2025 | BitChat | ~21,000 downloads in 24 h and ~71,000 in a week; ~365,000 total since the July launch (Chrome-Stats, via Cryptopolitan)\[52\] | — |
| Uganda & Iran, Jan 2026 | BitChat | Reported download rises during blackouts\[53\] | — |
| **India, July 23, 2026** | BitChat, Briar, Bridgefy | After the Jantar Mantar protests, I4C (MHA) directed Google to disable Play Store listings for all three within three hours and told Apple to remove BitChat and Bridgefy; GitHub also got a notice. On July 24 officials orally told tech companies and telecom operators they did not need to comply (MediaNama). As of July 29 all three were still on the Play Store (Outlook Business/Moneycontrol). In 2023 Briar was blocked in J&K under Section 69A | **Regulatory risk in India.** Position iTantra as an authenticated, government-aligned disaster and alert tool, not anonymous chat |
| Kerala floods 2018, Chennai 2015, Assam | — | Sources document **WhatsApp/Twitter/social media** coordination,\[54\] *not* large-scale phone-mesh use | No verified evidence of mesh apps at scale in Indian floods; frame the flood gap as the opportunity |
| "Singapore incident" | — | **No verifiable report found** of a Singapore disaster where phone mesh hopping was used | Don't put it on a slide without a primary source; it may be confused with Hong Kong 2014/2019 |

**Security lessons from BitChat.** Independent auditors (BARGHEST) found a **cache-poisoning and replay flaw** in BitChat's mesh sync that allowed unauthenticated message injection.\[55\] The BISI analysis notes static session keys (weak forward secrecy) and metadata exposure.\[56\] Your design signs every packet with Ed25519: **verify signatures before caching or relaying**, add a timestamp + nonce replay window, and say so on a slide.

## 3.2 Survey of phone-to-phone mesh systems and radios

| System | Transport | Multi-hop | Open source | Notes for you |
|---|---|---|---|---|
| BitChat | BLE mesh (GATT), Noise, TTL 7, LZ4, Nostr fallback\[57\] | Flood, max 7 hops\[57\] | Yes (Unlicense iOS; Android GPL) | Your reference. **No voice over mesh** in BitChat;\[45\] you already exceed it |
| Bridgefy SDK | BLE (+ Wi-Fi) | Yes | **Proprietary SDK** | Not allowed |
| Briar | Bluetooth, Wi-Fi, Tor\[58\] | Store-and-forward via contacts | GPL | Good security model; contact-based |
| Meshtastic | LoRa | Yes | GPL | Needs hardware; reference only |
| Berty / Wesh | BLE, Wi-Fi Aware/Multipeer, IPFS | Yes | Apache/MIT | Reference for Android Wi-Fi Aware + BLE drivers |
| Google Nearby Connections | BLE + Wi-Fi + BT Classic | Point-to-point/star | **Proprietary Play Services** | Violates the open-source-only rule; avoid |
| **Wi-Fi Aware (NAN)** | Wi-Fi 2.4/5 GHz | Android API is 1-hop data paths | Android API (8.0+/API 26) | Higher throughput and range than BLE. **Not on all phones**; may be unavailable while Wi-Fi Direct, hotspot or tethering is active. RTT ranging ~1 m accuracy to ~15 m on supported chips |\[59\]
| Wi-Fi Direct | Wi-Fi | One group per device; multi-group needs tricks (Funai et al.) | Android API | Good for bulk/voice; poor for mesh fan-out |\[60\]

**Realistic single-hop ranges (phone to phone; planning estimates — measure your own).**

| Radio mode | Typical outdoor LOS | Indoor / crowd | Evidence |
|---|---|---|---|
| BLE 1M PHY (phones) | ~30–100 m | ~10–30 m | Bridgefy's ~100 m claim; common experience |
| BLE Coded PHY S=8 (125 kbps) | ~2x 1M range; phone↔device field tests 50–150 yd consistently, up to 250 yd | Improves wall penetration | Nordic nRF52840 Rev C dev-kit outdoor test at 0 dBm: 1,300 m Coded vs 681.9 m 1M when connected (654.92 m unconnected, a 98.49% increase); ~8 dB real gain vs 12 dB theoretical. Simbex: phone links 50–250 yd\[61\] |
| Wi-Fi Direct / Wi-Fi Aware | ~50–150 m | ~20–50 m | Standard Wi-Fi behaviour; device-dependent |
| Acoustic modem (ggwave) | 1–5 m | 1–3 m | Tens of bytes per second; last resort only |

**Coded PHY support on phones is patchy.** Some phones report Coded PHY but cannot *scan* Coded advertisements (e.g., a Cat S42 on Android 11);\[62\] Samsung and OnePlus are reported as the best-supported.\[63\] At runtime check `isLeCodedPhySupported()`, `isLeExtendedAdvertisingSupported()` and `getLeMaximumAdvertisingDataLength()`, adapt per device, and **build a support matrix of the team's phones for the PPT**.

## 3.3 Techniques to extend coverage and reliability (phones only)

1. **Fix the known bugs first:**
   - Wi-Fi Direct group-owner tiebreak: deterministic rule (higher public-key hash becomes group owner; set `groupOwnerIntent`).
   - Fragments relayed twice: dedup on `(packetId, fragIndex)`.
   - Duplicate BLE links: keep the link initiated by the lower node ID.
   - Sender-clock latency: measure with **round-trip ACK timing / 2** or an NTP-style 4-timestamp offset exchange.
2. **Dual-mode BLE: connectionless "advertising flood" for ALERTs + GATT for bulk.** ALERT and short voice-text packets (≤254 B) go out as **extended advertisements** (Coded PHY where supported, else 1M), and every phone in incident mode scans continuously and rebroadcasts (~100 ms interval for 1–2 s per relay). No connection setup saves roughly 0.3–1 s per hop, and there is no GATT link cap (typically ~4–7 concurrent links).
3. **Counter-based suppressed flooding:** after the random jitter (keep 10–220 ms), cancel your rebroadcast if you have already heard the packet ≥3 times. This cuts broadcast storms in crowds while keeping reach in sparse chains.
4. **Priority queues:** ALERT > voice-text > receipts > sync; ALERTs pre-empt fragments and are never density-capped.
5. **Adaptive, geo-scoped TTL:** ALERT TTL 12–16 with optional geo-scope (sender's last GPS fix + radius); chat TTL 5–7.
6. **DTN store-carry-forward:** priority-weighted retention (ALERT 72 h instead of 24 h); **anti-entropy sync on encounter** by exchanging a Bloom filter or Golomb-coded set of held packet IDs; **Spray-and-Wait** (L = 8) for directed messages, epidemic spreading for ALERTs.
7. **Roles:** Relay (charging/stationary: higher duty, bigger cache), Mule (moving, detected via accelerometer/GPS: aggressive sync on encounter), Gateway (has cellular: forwards summaries by **SMS** via `SmsManager` to a control-room number), Normal (duty-cycled). Battery <20% → leaf only.
8. **Duty cycling:** scan 10–20% when idle, 100% for 5 min after any ALERT ("incident mode").
9. **Keep relays alive on Android:** foreground service type `connectedDevice`, battery-optimisation exemption, Android 12+ `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` permissions, and guided OEM "auto-start" settings (Xiaomi/Oppo/Vivo/Realme kill background apps aggressively — the #1 real-world failure on Indian budget phones).
10. **Old/spare phones as fixed repeaters:** a "Repeater mode" (screen off, charger, Coded PHY if supported) on rooftops or water tanks — still phones only, and the cheapest way to bridge 100–300 m gaps between clusters. At relief camps with power, a `LocalOnlyHotspot` cluster can carry bulk data over TCP.

**Other phone-only ideas, assessed honestly.** ggwave acoustic data (MIT) works only over a few metres at tens of bytes per second — use it for pairing/key exchange, not range. FM receivers are receive-only and not exposed as a data API. Satellite SMS is proprietary and absent from Indian budget phones (mention only as a gateway opportunity). Wi-Fi Aware RTT can show "distance to sender ~X m" in rescue mode, on supported phones only.\[64\]

## 3.4 Stand-out mesh features beyond BitChat (with estimates)

| Feature | Design | Cost |
|---|---|---|
| **Single-frame voice message** | Compressed text + IPF + signature ≈150–180 B → 1 ext-adv PDU or 1 GATT write, no fragmentation | 0 extra hops of latency |
| **"Heard by N" receipts** | Each receiver emits a 24 B signed receipt (packetId hash + node ID + hop count + RSSI + flags); relays aggregate; the sender sees "Alert heard by 37 phones, max 5 hops" | ~24 B per receiver, suppressed by aggregation |
| **Coverage heatmap** | Receipts include a coarse geohash (7 chars ≈150 m) when GPS is available; the sender renders reached cells offline | +4 B |
| **Automatic relay backbone** | Phones with most neighbours + charging + stationary self-elect as relays (greedy connected-dominating-set approximation from beacon neighbour lists); non-backbone phones rebroadcast only ALERTs | Beacon +8 B |
| **Gateway bridging** | Any phone that regains cellular forwards ALERT summaries (≤160 chars) by SMS to a control-room number and posts receipts back into the mesh | SMS cost only |
| **Authenticated alert authority** | Optional pre-installed public key for NDMA/ISRO/district officials marks verified broadcasts; unverified ALERTs show as "citizen alert" | 64 B signature (already present) |

**Coverage vs number of phones (planning estimate; validate by simulation).** Assume ~60 m effective 1M-PHY range in open terrain and ~120 m where Coded PHY works on both ends. A connected chain needs a phone about every 50 m (1M) or 100 m (Coded). TTL 7 then gives a linear reach of ~350 m (1M) to ~700–800 m (Coded); TTL 12–16 for ALERTs gives ~0.6–1.8 km. Area coverage in a random 2D scatter needs roughly 5+ neighbours in range: with 60 m range that is ≈450 phones/km², easily exceeded in towns and relief camps but not in sparse rural floods — which is exactly why DTN mules and rooftop repeaters matter.

**Latency per hop (estimate).** Advertising flood: jitter 10–220 ms + ~100 ms advertising interval + scan detection → **~60–350 ms per hop**. GATT on an existing link: ~30–150 ms. Seven hops ≈0.5–2.5 s — acceptable for alerts, and the number to measure and show.

## 3.5 Simulation and evaluation plan

- **Python simulator (1 week, recommended for the PPT):** random geometric graph (networkx), log-distance path-loss per PHY, event-driven flooding with jitter/counter-suppression/TTL/priority, random-waypoint mobility for DTN. Outputs: delivery ratio vs density, hop-count distribution, latency CDF, transmissions per delivered message, TX-count battery proxy; sweep 1M vs Coded, pure vs counter-based flood, with/without repeaters.
- **The ONE simulator** (DTN): Epidemic vs Spray-and-Wait vs PRoPHET with walking mules between two camps several km apart (built-in routers). ns-3/OMNeT++ only if a team member already knows them (BLE support is community-module only).
- **Real-device test plan (6–10 mixed-brand phones):** (1) single-hop range per PHY in an open field (10 m steps, 100 packets each, RSSI + PDR); (2) 5–7-phone chain with round-trip latency; (3) 8–10-phone crowd test for duplicates; (4) two clusters 500 m apart with one walking mule; (5) 1 h idle-listening battery and CPU%; (6) 30-min screen-off background-kill test on Xiaomi/Realme/Samsung.

## 3.6 Integration steps (Part 3)

1. **Packet format v2:** `ver|type|priority|ttl|hop|flags|packetId(8)|senderId(8)|ts(4)|geo(opt 4)|payload|sig(64)`; types VOICE_TEXT, ALERT, RECEIPT, SYNC_FILTER, SPEAKER_EMB, CODEC_AUDIO. Verify before caching/relaying; reject timestamps outside ±10 min or replayed IDs.
2. **`MeshRouter`:** counter-based suppression, priority queues, per-type TTL, geo-scope, role logic.
3. **New `BleAdvertiserTransport`:** extended advertising (Coded PHY if supported) + continuous incident-mode scanner; keep GATT for bulk.
4. **`WifiDirectTransport`:** fix the tiebreak; add `WifiAwareTransport` behind a feature check.
5. **`Outbox` → `DtnStore`:** priority retention + Bloom/GCS anti-entropy; **Gateway module** with consented `SmsManager` forwarding; receipts and an on-device debug map for the demo.

---

# Final Section — Recommended end-to-end architecture

## A. Component view (Phone A → mesh → Phone B)

**Phone A (sender)**
1. **Mic capture:** VOICE_RECOGNITION source, 16 kHz mono, 10 ms frames.
2. **Front-end:** SoftAgc (WebRTC AGC2-style) → optional denoiser (RNNoise/GTCRN when SNR <10 dB) → segment RMS normalisation.
3. **VAD + endpointing:** Silero VAD (retuned; quiet-speaker state), optional TEN VAD. EndpointPolicy: PTT release, or Smart Turn v3 (8 MB) + CTC-context rules, max wait 1.2 s, split at the longest pause after 8 s.
4. **STT:** IndicConformer 120M per-language int8 CTC\[65\] (English: FastConformer CTC) via sherpa-onnx `OfflineRecognizer`, pseudo-streaming partials, token timestamps and a distress hotword list.
5. **Paralinguistics:** ProsodyExtractor (pitch/energy/rate/pauses/fillers/breath), urgency score, optional audio-event tagger, voice class (+ optional 64 B embedding once per session).
6. **Packetiser:** script-offset text compression + IPF frame + header → Ed25519 sign (+ X25519/HKDF/ChaCha20-Poly1305 for private messages). ALERT is set by the user button *or* urgency above threshold + keyword hit (with a 3 s "cancel" window against false alarms).
7. **Transport selector:** ALERT and VOICE_TEXT ≤254 B by BLE extended advertising (Coded PHY if both ends support it) *and* to connected GATT peers; bulk and hybrid codec audio by Wi-Fi Direct / Wi-Fi Aware.

**Mesh**
8. **MeshRouter:** verify → dedup → priority queue → counter-suppressed rebroadcast with jitter → TTL/geo-scope → DTN store (anti-entropy on encounter) → roles (Relay/Mule/Gateway/Leaf) → receipts ("heard by N") → gateway SMS when cellular appears.

**Phone B (receiver)**
9. **Receiver:** verify/decrypt → decompress text → IPF parse.
10. **Expressive TTS:** ProsodyRenderer splits into phrases → MMS-TTS VITS (demo) or multilingual Piper/Matcha (future) with per-phrase `length_scale`/noise → pitch shift toward sender F0 class, voice-bank choice, filler/breath clips, measured silences → streaming AudioTrack.
11. **Playback:** normal messages play as voice notes (with text shown). ALERTs use the ALARM stream at max volume, "Alert" prefix clip, siren, repeat ×3, full-screen overlay until ACK, and a receipt sent back.

## B. Model choices, sizes and RAM (3 GB target; 2 GB fallback)

| Stage | Model | Disk | Resident RAM (est.) | 2 GB-phone setting |
|---|---|---|---|---|
| VAD | Silero (+ TEN VAD optional) | 0.6 MB (+0.3 MB) | <10 MB | Same |
| Endpoint | Smart Turn v3 int8 | 8 MB | ~20–30 MB | Disable (PTT only) |
| STT | IndicConformer 120M int8 CTC (1 language) | ~190 MB | ~250–350 MB | Same; investigate 30M variant |
| Prosody/events | DSP + optional small audio tagger | 0–25 MB | <40 MB | DSP only |
| Speaker class/embedding | DSP F0 class (+ optional ERes2Net/ECAPA small) | 0–25 MB | <40 MB | F0 class only |
| TTS | MMS VITS × up to 2 languages | 109 MB each | ~150–200 MB each | 1 voice resident |
| Voice bank / filler clips | PCM assets | ~2 MB total | on demand | Same |
| App (Flutter + Kotlin + mesh + crypto) | — | — | ~150–250 MB | Same |
| **Total** | | ~0.5 GB per installed language pair (packs downloaded on demand) | **~0.8–1.1 GB peak** | **~0.6–0.8 GB** |

(RAM figures are engineering estimates; replace them with `dumpsys meminfo` measurements on a real 3 GB phone before the PPT.)

## C. Latency budget: "sentence said on A → audio starts on B"

| Stage | PTT mode (target) | Hands-free mode (target) | Basis |
|---|---|---|---|
| End-of-utterance detection | ~80 ms (release tail) | 250–800 ms (adaptive) vs 450 ms + VAD hangover today | Design |
| Final STT decode (3–4 s utterance) | 200–400 ms | 200–400 ms | 236 ms measured (OnePlus, short utterance); RTF 0.05–0.19 desktop |
| Prosody + packetise + sign | 10–30 ms | 10–30 ms | Estimate (Ed25519 sign <1 ms) |
| Transport, 1 hop | 30–150 ms (GATT) / 60–350 ms (adv flood) | same | Estimate; measure |
| Each additional hop | +60–350 ms | same | Estimate |
| Receive + verify + parse | <10 ms | <10 ms | Estimate |
| TTS time-to-first-audio (first phrase) | 300–700 ms | 300–700 ms | From RTF 0.54–0.89 with phrase streaming; measure on phone |
| AudioTrack start | ~20–50 ms | ~20–50 ms | Estimate |
| **Total, 1 hop** | **≈0.65–1.3 s** | **≈0.8–2.1 s** | |
| **Total, 5 hops (adv flood)** | **≈1.4–2.7 s** | **≈1.6–3.5 s** | |

Report STT and TTS RTF on the real phone per language, plus **idle-listening CPU%** with VAD only (scored): aim for <5% of one core, using 20–30 ms `AudioRecord` buffers and VAD batching.


## Caveats

- **The accuracy numbers are not all for your exact checkpoints.** Per-language IndicVoices WERs come from AI4Bharat's 130M IndicASR model in the IndicVoices paper, a close relative;\[3\] the 600M figures are third-party (svanita-0.6b card).\[9\] No primary per-language WER was found for the released 120M checkpoints, so measure your own on real speech.
- **Your current metrics come from synthetic TTS speech on a desktop.** Real human, whispered and noisy speech will be worse, especially for Malayalam, Tamil and Telugu.
- **Many figures are estimates, not measurements** — range, per-hop latency, RAM totals and coverage density — and must be validated on your phones.
- **Some sources are vendor or developer claims.** Sarvam Edge figures are vendor claims (and the stack is closed, so prohibited). BitChat download counts come from developer posts relayed by crypto/tech media.
- **The "Singapore incident" could not be verified,** and no flood-era Indian mesh deployments were found; documented mesh use cases are protest shutdowns.
- **Licenses need checking before any non-hackathon use:** MMS-TTS and MMS ASR are non-commercial; verify IndicTTS database, TEN VAD, Dolphin and Indic Open Model License terms; IndicConformer and IndicF5 repos are MIT but gated.\[8\]\[66\]

## Sources

1. [ai4bharat/indicconformer\_stt\_or\_hybrid\_ctc\_rnnt\_large · Hugging Face](https://huggingface.co/ai4bharat/indicconformer_stt_or_hybrid_ctc_rnnt_large)
2. [ps-dev-090202323123/indicconformer-pa-sherpa-onnx · Hugging Face](https://huggingface.co/ps-dev-090202323123/indicconformer-pa-sherpa-onnx)
3. [IndicVoices: Towards building an Inclusive Multilingual Speech Dataset for Indian Languages](https://arxiv.org/html/2403.01926v1)
4. [csndhanasekar/sherpa-onnx-indicconformer-ml-int8 · Hugging Face](https://huggingface.co/csndhanasekar/sherpa-onnx-indicconformer-ml-int8)
5. [csndhanasekar/sherpa-onnx-indicconformer-te-int8 · Hugging Face](https://huggingface.co/csndhanasekar/sherpa-onnx-indicconformer-te-int8)
6. [Is there any ASR pretrained model to work with hindi, tamil and other indic languages? · k2-fsa sherpa-onnx · Discussion #3199](https://github.com/k2-fsa/sherpa-onnx/discussions/3199)
7. [README.md · ai4bharat/indic-conformer-600m-multilingual at main](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual/blob/main/README.md)
8. [ai4bharat/indic-conformer-600m-multilingual · Hugging Face](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual)
9. [prasadvittaldev/svanita-0.6b · Hugging Face](https://huggingface.co/prasadvittaldev/svanita-0.6b)
10. [Vistaar: Benchmarks for Indian ASR](https://www.emergentmind.com/papers/2305.15386)
11. [I Tried to Build a Bengali Voice Dialer for Android. Here Is What Actually Happened and How I Finally got it right. - DEV Community](https://dev.to/devksarkar/i-tried-to-build-a-bengali-voice-dialer-for-android-here-is-what-actually-happened-and-how-i-40ak)
12. [Zipformer-transducer-based Models — sherpa 1.3 documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)
13. [iniquitous/indic-transcribe-flex-onnx · Hugging Face](https://huggingface.co/iniquitous/indic-transcribe-flex-onnx)
14. [Sarvam Edge: India's First On-Device AI That Works Without Internet](https://www.adwaitx.com/sarvam-edge-on-device-ai-india/)
15. [Sarvam Edge: India's Offline AI Powerhouse Takes on Google Gemini](https://www.kanthalaraghu.in/p/sarvam-edge-indias-offline-ai)
16. [Sarvam Edge: A Beginner’s Guide to On-Device AI for India - Analytics Vidhya](https://www.analyticsvidhya.com/blog/2026/03/sarvam-edge/)
17. [GitHub - AI4Bharat/IndicConformerASR](https://github.com/AI4Bharat/IndicConformerASR)
18. [IndicContextEval: A Benchmark for Evaluating Context Utilisation in Audio Large Language Models Across 8 Indic Languages](https://arxiv.org/pdf/2606.19157)
19. [AI4Bharat Models](https://models.ai4bharat.org/)
20. [sriram09764/itantra-tts-onnx · Hugging Face](https://huggingface.co/sriram09764/itantra-tts-onnx)
21. [README.md · TEN-framework/ten-vad at main](https://huggingface.co/TEN-framework/ten-vad/blob/main/README.md)
22. [TEN VAD | TEN Framework](https://theten.ai/docs/ten_vad)
23. [sherpa-onnx — sherpa 1.3 documentation](https://k2-fsa.github.io/sherpa/onnx/index.html)
24. [AI4Bharat](https://ai4bharat.iitm.ac.in/areas/tts/)
25. [A Unified Framework for Collecting Text-to-Speech Synthesis Datasets for 22 Indian Languages](https://arxiv.org/html/2410.14197v1)
26. [GitHub - drowe67/codec2: Open source speech codec designed for communications quality speech between 700 and 3200 bit/s. The main application is low bandwidth HF/VHF digital radio. · GitHub](https://github.com/drowe67/codec2)
27. [GitHub - ArchIsAman/lsp-net: Hybrid parametric–neural speech codec at 1.2 kbps. Keeps the Codec2 encoder and replaces its vocoder with a 0.9M-parameter dual-rate autoregressive (WaveRNN-style) decoder trained on LibriTTS. Includes the full WAV→bitstream→WAV pipeline, pretrained weights and real-time CPU decoding.](https://github.com/ArchIsAman/lsp-net)
28. [A Real-Time Wideband Neural Vocoder at 1.6 kb/s Using LPCNet](https://arxiv.org/pdf/1903.12087)
29. [\[1903.12087\] A Real-Time Wideband Neural Vocoder at 1.6 kb/s Using LPCNet](https://arxiv.org/abs/1903.12087)
30. [GitHub - xiph/LPCNet: Efficient neural speech synthesis · GitHub](https://github.com/xiph/lpcnet)
31. [An Ultra-Low Bitrate Neural Audio Codec Under NB-IoT | Request PDF](https://www.researchgate.net/publication/384307270_An_Ultra-Low_Bitrate_Neural_Audio_Codec_Under_NB-IoT)
32. [Towards Developing State-of-the-Art TTS Synthesisers for 13 Indian Languages with Signal Processing aided Alignments](https://arxiv.org/pdf/2210.17153)
33. [Indic Parler-TTS: An Open-Source Multilingual Text-to-Speech Model - Supports 21 Languages Including Multiple Indian Languages and English](https://model.aibase.com/models/details/1915693256136089601)
34. [ai4bharat/indic-parler-tts at main](https://huggingface.co/ai4bharat/indic-parler-tts/tree/main)
35. [indic-parler-tts | PromptLayer Models](https://www.promptlayer.com/models/indic-parler-tts/)
36. [IndicF5: Text-to-Audio model — overview, use cases, alternatives](https://www.aimodels.fyi/models/huggingFace/indicf5-ai4bharat)
37. [ai4bharat/IndicF5 · Hugging Face](https://huggingface.co/ai4bharat/IndicF5)
38. [SYSPIN Dataset](https://vaani.iisc.ac.in/dataset/syspindataset)
39. [GitHub - k2-fsa/sherpa-onnx: Speech-to-text, text-to-speech, speaker diarization, speech enhancement, source separation, and VAD using next-gen Kaldi with onnxruntime without Internet connection. Support embedded systems, Android, iOS, HarmonyOS, Raspberry Pi, RISC-V, RK NPU, Axera NPU, Ascend NPU, x86\_64 servers, websocket server/client, support 12 programming languages · GitHub](https://github.com/k2-fsa/sherpa-onnx)
40. [Announcing Sarvam Edge | Sarvam AI](https://www.sarvam.ai/blogs/sarvam-edge)
41. [INDICVOICES-R: Unlocking a Massive Multilingual Multi- ...](https://proceedings.neurips.cc/paper_files/paper/2024/file/7dfcaf4512bbf2a807a783b90afb6c09-Paper-Datasets_and_Benchmarks_Track.pdf)
42. [GitHub - AI4Bharat/IndicVoices-R: A Massive Multilingual Multi-speaker Speech Corpus for Scaling Indian TTS · GitHub](https://github.com/AI4Bharat/IndicVoices-R)
43. [FireChat](https://en.wikipedia.org/wiki/FireChat)
44. [FireChat: The App That Fueled Hong Kong’s Umbrella Revolution](https://www.yahoo.com/news/firechat-app-fueled-hong-kong-umbrella-revolution-181002978.html)
45. [What Is BitChat? Bluetooth Mesh Messaging Explained](https://blockspot.io/what-is-bitchat/)
46. [Smartphone ad hoc network](https://en.wikipedia.org/wiki/Smartphone_ad_hoc_network)
47. [Bridgefy launches end-to-end encrypted messaging for the app used during protests and disasters](https://techcrunch.com/2020/11/02/bridgefy-launches-end-to-end-encrypted-messaging-for-the-app-used-during-protests-and-disasters/embed)
48. [Bridgefy - Offline Messages - Apps on Google Play](https://play.google.com/store/apps/details?id=me.bridgefy.main&hl=en-US)
49. [internet shutdown here are top offline messaging apps 786930](https://www.deccanherald.com/amp/specials/internet-shutdown-here-are-top-offline-messaging-apps-786930.html)
50. [Jack Dorsey's Bitchat Explodes in Madagascar as Protesters Ditch Government-Controlled Comms](https://cryptonews.com/news/dorseys-bitchat-explodes-in-madagascar-as-protesters-ditch-government-controlled-comms/)
51. [Jack Dorsey’s Bitchat Gains Traction During Nepal’s Unrest](https://www.forbes.com/sites/digital-assets/2025/09/11/jack-dorseys-bitchat-gains-traction-during-nepals-unrest/)
52. [Bitchat downloads surge in Madagascar amid nationwide protests](https://bitcoinethereumnews.com/finance/bitchat-downloads-surge-in-madagascar-amid-nationwide-protests/)
53. [BitChat](https://en.wikipedia.org/wiki/BitChat)
54. [(PDF) Role of social media during Kerala floods 2018](https://www.researchgate.net/publication/335856589_Role_of_social_media_during_Kerala_floods_2018)
55. [BitChat cache poisoning and replay in Bluetooth mesh](https://barghest.asia/blog/bitchat-cache-poisoning/)
56. [Bitchat: Bluetooth Mesh Networks and Internet Shutdowns — Bloomsbury Intelligence and Security Institute (BISI)](https://bisi.org.uk/reports/bitchat-bluetooth-mesh-networks-and-internet-shutdowns)
57. [GitHub - permissionlesstech/bitchat: bluetooth mesh chat, IRC vibes · GitHub](https://github.com/permissionlesstech/bitchat)
58. [When Bluetooth Became a Battleground | Inside India's Bid to Block BitChat, Briar and Bridgefy](https://cyberpeace.org/resources/blogs/when-bluetooth-became-a-battleground-inside-indias-bid-to-block-bitchat-briar-and-bridgefy)
59. [Wi-Fi Aware allows neighboring 8.0 Android phones to connect](https://techxplore.com/news/2021-03-wi-fi-aware-neighboring-android.html)
60. [Supporting Multi-hop Device-to-Device Networks Through WiFi Direct Multi-group Networking](https://arxiv.org/pdf/1601.00028)
61. [Bluetooth 5.0 Coded PHY for Low-Energy Long-Range Communication](https://simbex.com/bluetooth-5-0-coded-phy-for-low-energy-long-range-communication/)
62. [List of Coded PHY Supported/Tested Phones](https://lightrun.com/answers/nordicsemiconductor-android-ble-library-list-of-coded-phy-supportedtested-phones)
63. [Coded PHY: Bluetooth Long-Range Feature Explained](https://novelbits.io/bluetooth-long-range-coded-phy/)
64. [WifiNanScan App - Apps on Google Play](https://play.google.com/store/apps/details?id=com.google.android.apps.location.rtt.wifinanscan&hl=en)
65. [csndhanasekar/sherpa-onnx-indicconformer-ta-int8 · Hugging Face](https://huggingface.co/csndhanasekar/sherpa-onnx-indicconformer-ta-int8)
66. [ai4bharat/indicconformer\_stt\_ml\_hybrid\_ctc\_rnnt\_large · Hugging Face](https://huggingface.co/ai4bharat/indicconformer_stt_ml_hybrid_ctc_rnnt_large)
