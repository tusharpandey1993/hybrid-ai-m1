# Voice Lab: Milestone 5 STT

Date: 2026-10-07. Adds local streaming speech-to-text to the existing
[AudioRecord](voice-audio-capture.md) and [VAD](voice-vad.md) Voice Lab.

## Milestone Report

**MILESTONE:** 5 - Local streaming speech-to-text.

**STATUS:** PASS for Sherpa model initialization, streaming text/final endpoint,
shared physical-microphone processing, capture cleanup and Voice Lab UI. The
small-model transcript is a functional smoke result, not production-quality
accuracy certification.

**CHANGES:** one Sherpa-ONNX `OnlineRecognizer` and one stream consume the
existing 320-sample/20 ms frames. A fixed 320-float scratch array converts PCM16
to normalized float; no full-utterance PCM buffer or second microphone is added.
Sherpa partial text replaces the visible transcript; endpointed text is marked
final. Model/runtime resources close with the ViewModel collection.

**TESTS:** the model-backed streaming test and all five VAD/STT device tests pass
on the Samsung SM-S931B / Android 16/API 36. The existing two physical capture
restart/background tests pass. The live microphone test confirms both VAD and STT
frame counters advance from shared frames and Android has no active recorder
after Stop. The full JVM suite, lint and APK builds pass: 65 tests passed, one
pre-existing optional oracle test skipped, 0 failures; lint has 0 errors and 20
warnings. The debug APK is 81 MB and instrumentation APK is 427 KB.

**RESULTS:** the 20M English int8 streaming recognizer produced partial and final
text from the staged spoken fixture. The live lab displayed a changing partial
(`HALLO THIS IS`) during 361 microphone frames. The saved smoke-fixture output
was rough and omitted early words; no accuracy rate or WER is claimed.

**PERFORMANCE:** the staged waveform's first nonempty partial arrived 2.96 s
after its start. Maximum measured decode work for one 20 ms frame was 12.3 ms in
the model test. The post-load, stopped Voice Lab process measured 175,867 KiB PSS
and 303,300 KiB RSS (about 172 MiB / 296 MiB). The selected ONNX/token assets
total about 42 MB; the downloaded archive is 127,887,156 bytes. The 349 ms UI
model-load value shown in the physical session is for VAD plus STT initialization.
These are one-device smoke measurements, not a benchmark.

**KNOWN LIMITATIONS:** the 20M recognizer trades accuracy for memory. The speech
fixture is generated speech and is not a representative human-speech benchmark.
Language coverage, accent/noise tests, word error rate, sustained thermals,
battery use, and application PSS under long capture are not measured. Partial
text may revise while speaking. Endpoint behavior follows Sherpa's recognizer
defaults. There is no transcript history, editing, cloud fallback, or model
unload policy beyond ViewModel teardown. Android TextToSpeech was added later in
Milestone 4; audible interruption remains unverified.

**FILES CHANGED:** minimal Sherpa streaming adapter, Voice Lab ViewModel/activity
and transcript view, model setup/ignore/provenance, instrumented streaming test,
README and this report.

**NEXT MILESTONE:** 6 - Deterministic endpointing, only after M4 barge-in acceptance passes.

## Signal Path

```text
one AndroidAudioInput / AudioRecord
                 |
        shared 320-sample frames
          /                 \
 Silero VAD (existing)   Sherpa online ASR
          \                 /
             Voice Lab UI
```

Sherpa receives each existing frame exactly once at 16 kHz. The engine retains
one recognizer, one stream, recurrent decoder state and one fixed-size conversion
array. No transcript accumulator, audio recording, PCM persistence, or secondary
audio source is part of this milestone. Transcript text remains only in the
ViewModel/UI state.

## Model and Setup

- Runtime: already-integrated official sherpa-onnx v1.13.8 Android AAR,
  Apache-2.0, CPU provider, one inference thread.
- Model: `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, an English
  20M-parameter online transducer from LibriSpeech.
- Precision: int8 encoder and joiner; int8 decoder variant.
- Installed assets: 41 MB encoder, 528 KB decoder, 256 KB joiner and 8 KB tokens.
- Archive SHA-256:
  `9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9`.
- Model export declares Apache-2.0. Attribution is included in the packaged
  third-party NOTICE and Apache license text.

Run both model setup scripts before building:

```sh
bash scripts/setup-vad.sh
bash scripts/setup-stt.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The STT setup script checks the archive digest and stages only int8 model files
and tokens under ignored app assets. No downloads occur on the phone or at
runtime. The app has no `INTERNET` permission.

## Verification

On Android 16/API 36, the staged model WAV was fed in 320-sample increments with
two seconds of trailing silence. Sherpa produced partials and a final endpoint;
the first nonempty partial was at 2.96 s. The transcript was
`Y'S ACTIVITY DETECTION I AM SPEAKING CLEARLY WITH PASSES BETWEEN PHRASES`, which
is incomplete versus the generated source. This verifies execution and text
delivery only.

The Voice Lab screenshot showed its live transcript and frame counter during
microphone capture. A stopped-process snapshot after model initialization was
175,867 KiB PSS / 303,300 KiB RSS. The transcript and capture UI share the
existing microphone source; the device test verified zero active Android
recording configurations after stop. Raw PCM was not logged or persisted.

The staged WAV lives only in the app-specific external files directory for
instrumentation. Remove it after testing. Full natural-speech accuracy, quiet
speech, varied speakers, background TV/music, voice-route feedback, WER,
long-session battery and thermal limits remain unverified.

To run the model-backed test with the temporary WAV already staged at
`/sdcard/Android/data/dev.edgeai.prototype/files/vad-reference.wav`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.edgeai.prototype.voice.vad.VadDeviceTest \
  dev.edgeai.prototype.test/androidx.test.runner.AndroidJUnitRunner
```

Stop here until the next milestone is explicitly approved.