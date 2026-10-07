# Voice Lab: Milestone 3 VAD

Date: 2026-10-07. This milestone adds local speech activity detection to the
existing single-source [audio capture](voice-audio-capture.md) flow. It does not
add STT, TTS, transcript endpointing, GLiNER voice routing, or conversation
controller wiring.

## Milestone Report

**MILESTONE:** 3 - Local voice activity detection and speech edges.

**STATUS:** PASS for deterministic model, physical-device mic, lifecycle, and UI
integration checks. Natural-speech false-positive/false-negative accuracy is not
certified.

**CHANGES:** pinned Sherpa-ONNX/Silero artifacts and licenses; one probability
inference per window; lossless 320-to-512 sample reassembly; two-window speech
start confirmation; 250 ms silence hangover; model state, probability, edge counts
and timing in Voice Lab. Existing AudioRecord remains the only microphone owner.

**TESTS:** 3 focused JVM processor tests pass; 4 VAD instrumentation tests pass
on physical Samsung SM-S931B; 2 existing physical AudioRecord lifecycle tests
pass. Full JVM/lint/build gates are listed below.

**RESULTS:** two seconds of digital silence stayed below onset threshold (maximum
probability 0.0429). Two seconds of deterministic low-level broadband noise did
not start speech (maximum probability 0.1498). A temporary generated-speech
waveform produced one start and one end around known 500 ms silence pads. The
physical mic and on-screen counters were exercised together. No PCM was written
to storage.

**PERFORMANCE:** on the Galaxy, generated-speech onset arrived 90 ms after the
first 10 ms block with RMS above 0.004 (-48 dBFS); the input is processed in
512-sample / 32 ms windows. Inference p50/p95 was 0.108/0.126 ms on the latest
generated-waveform run.
Live-mic inference p50/p95 was 0.519/1.590 ms across 62 windows; 100 capture
frames had zero app-level drops. The UI showed a 42 ms model load in one launch. Inference
timings exclude capture buffering and are not a general acoustic latency claim.

**KNOWN LIMITATIONS:** the speech-positive sample is generated speech, not a
human speaker or a licensed natural-speech benchmark. The short live-mic run
included one onset whose acoustic source was not identified. Noise coverage is
limited to one deterministic low-level profile and ambient room capture. TTS
playback echo, far-field speech, speaker/device variation, sustained CPU/energy,
Bluetooth routing, and accuracy across labeled corpora remain unmeasured. The
0.5/0.35 thresholds and two-window debounce are initial lab settings, not
calibrated production thresholds. VAD end is a state edge after silence, not an
STT endpoint or utterance-segmentation contract.

**FILES CHANGED:** VAD processor/Sherpa adapter, Voice Lab ViewModel/activity and
layout/strings, setup script and ignored-asset entries, unit and instrumentation
tests, updated AudioRecord lifecycle tests, third-party notices, this report and
Android README.

**NEXT MILESTONE:** 4 - Android TTS and fast VAD-triggered barge-in, only after explicit approval.

## Signal Path

```text
AndroidAudioInput (existing singleton AudioRecord)
             |
      AudioFrameBus (320 PCM16 samples / 20 ms)
             |
  VadFrameProcessor (lossless 512-sample windows)
             |
 SherpaSileroVadEngine (CPU probability, one call/window)
             |
 threshold edges -> Voice Lab state and counters
```

`compute()` advances Silero's recurrent model state. It is called exactly once
per window; `acceptWaveform()` is not called as a second inference path. Each
capture session resets the recurrent model, partial window, onset candidate and
silence counter. PCM exists only in memory and is not logged or persisted.

Silero's 512-sample input represents 32 ms at 16 kHz. The processor starts speech
after two consecutive windows with probability at least 0.5. While speech is
active, probability below 0.35 accumulates a silence hangover; eight consecutive
windows end speech (about 256 ms). Intermediate scores reset the hangover. A
session change clears all state. This reports onset before transcription or
classification and does not create transcript segments.

## Dependencies and Reproducibility

- Sherpa-ONNX Android AAR v1.13.8, Apache-2.0, official GitHub release.
- `silero_vad.onnx`, 643,854 bytes, Silero Team MIT model distributed by
  sherpa-onnx.
- SHA-256 AAR: `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.
- SHA-256 model: `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`.
- Full license copies and attribution are packaged in
  `app/src/main/assets/third-party/`.

Run `bash scripts/setup-vad.sh` before Gradle builds. It downloads to ignored
host-cache paths, checks both hashes, and stages the model in app assets. VAD
does not use network access at runtime. The manifest has no `INTERNET`
permission. GLiNER assets remain separate and are not loaded by Voice Lab.

## Verification

The Android 16/API 36 Galaxy SM-S931B ran four VAD instrumentation checks:

1. Two seconds digital silence: 62 windows, maximum probability 0.0429, no start.
2. Two seconds seeded broadband noise at approximately -47 dBFS RMS: maximum
   probability 0.1498, no start.
3. Temporary generated-voice WAV with 500 ms leading/trailing silence: one start
  and one end; onset 90 ms after the first 10 ms block with RMS above 0.004
  (-48 dBFS); maximum probability 0.9998; inference p50/p95 0.108/0.126 ms.
4. Physical AudioRecord plus Voice Lab Start button: 100 frames, 62 windows, zero
  app-level drops; probability and counters appeared in the UI. The final short
  ambient run had no start despite one score reaching 0.5012. An earlier run
  emitted one start whose acoustic source was not identified.

The generated WAV was staged only in the phone's app-specific external files for
the test, not added to the repository. The device test also exercised the visible
Start control and verified a nonzero on-screen VAD window count. Two existing
AudioRecord restart/background lifecycle tests passed on the same handset after
the VAD-ready Start gating.

JVM tests verify sample ordering and non-overlapping windows across 320-sample
boundaries, probability passthrough, two-window onset, silence hangover, exact
edge counts, and reset on capture-session changes. The complete JVM suite has 66
tests: 65 passed, 1 pre-existing optional oracle test skipped, 0 failures. Lint
passed with 0 errors and 20 warnings; debug APK and instrumentation APK builds
passed. Device measurements are from one handset and do not establish
natural-speech accuracy or battery/thermal behavior.

To repeat the instrumented VAD checks with a temporary speech clip on a connected
device, stage a mono 16 kHz PCM16 WAV as
`/sdcard/Android/data/dev.edgeai.prototype/files/vad-reference.wav`, then run:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.edgeai.prototype.voice.vad.VadDeviceTest \
  dev.edgeai.prototype.test/androidx.test.runner.AndroidJUnitRunner
```

Stop here. Do not add STT until Milestone 4 is explicitly approved.