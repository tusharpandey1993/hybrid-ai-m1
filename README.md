# Android On-Device GLiNER Router

The Android app now runs GLiNER2.5-Decide locally through LiteRT CPU. This is
Milestone 2's text-only edge router, not a hosted Jev integration or a chat model.

```text
Text -> Kotlin tokenizer/schema -> LiteRT CPU -> five scores -> Android UI
```

The launch screen displays `LOCAL_CODE`, `LOCAL_RAG`, `LOCAL_LLM`, `CLOUD_LLM`,
`WEB_TOOL`, their uncalibrated scores, the top-two margin, token window, and timing.
No predicted task is executed by the text router. It makes no cloud calls,
retrieval calls, or generative responses. The manifest has no `INTERNET`
permission. The separate Voice Capture Lab uses one foreground AudioRecord
source for local Silero VAD, Sherpa streaming STT, and a prototype Android TTS
speaker; audio/transcripts are not persisted. Audible barge-in acceptance is
still unmeasured.
The Calculator button preserves the earlier deterministic Kotlin prototype.

Voice-lab milestone reports: [baseline](docs/voice-baseline.md),
[pure conversation state machine](docs/voice-state-machine.md),
[audio capture](docs/voice-audio-capture.md), [VAD](docs/voice-vad.md),
[STT](docs/voice-stt.md), [TTS/barge-in](docs/voice-tts-barge-in.md),
[conversation wiring](docs/voice-conversation-wiring.md), and
[roadmap review](docs/voice-roadmap-review.md).
Open the Voice Capture Lab launcher icon to inspect capture, VAD, live
transcription, and the Android TTS prototype without loading GLiNER.

## Run in Android Studio

Open this folder as a Gradle project. Use Java 17, SDK platform 36, and an
ARM64 device/emulator with API 26 or newer. Before building, fetch the pinned
Sherpa runtime, Silero VAD model and streaming STT model. The VAD and 42 MB int8
STT assets are packaged in the APK; GLiNER's 2.45 GB model remains a separate
private-storage install.
A physical phone is recommended for microphone and performance measurements.

```sh
cd hybrid-ai-m1
export ANDROID_HOME="$HOME/Library/Android/sdk"
bash scripts/setup-vad.sh
bash scripts/setup-stt.sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.edgeai.prototype/.VoiceLab
# Optional: stage GLiNER for its separate text-routing screen.
bash scripts/setup-gliner.sh --install
```

`setup-vad.sh` downloads the official Sherpa-ONNX v1.13.8 AAR and Silero model;
`setup-stt.sh` downloads the Sherpa English streaming model archive and extracts
only its int8 networks and tokens. Both verify pinned SHA-256 digests and stage
assets locally on the host. Internet is needed for setup only. Downloaded files
and staged models are ignored by Git; rerun both scripts after a fresh checkout.

When several devices are connected, set `ANDROID_SERIAL` to the intended device
first. The script downloads approximately 2.45 GB on the computer, verifies each
asset's SHA-256, stops the app, stages one file at a time through `/data/local/tmp`,
and copies it into the debug app's private `files/` with `run-as`. It then launches
the GLiNER screen. The model files are not bundled into the APK or committed.
Allow at least 3.3 GB free device disk space for the installed assets plus the
largest temporary staging file, with additional OS/runtime headroom.

The initial host download needs internet; inference on the phone does not.
`--download-only` prepares the computer-side cache without contacting a device.
`--install` checks cached hashes before reuse, but currently still fetches the
small checksum manifest. No API keys or paid service are required.

The debug app and model are already installed on the Pixel_10_Pro ARM emulator
started during development. Type a question and tap Classify. Models load and
warm up on background workers; the UI shows Loading, Ready, Running, or Unavailable.
Questions and predictions are not persisted by normal UI use.

## Implementation

- `AiRouter.kt`: replaceable suspend interface, five-capability taxonomy and score
  contract; no inference-specific types are exposed to the UI.
- `GlinerAiRouter.kt`: LiteRT CPU adapter, identical label descriptions to the
  Python experiment, serialized work, input validation and resource cleanup.
- `GlinerViewModel.kt`: lifecycle state, loading/retry, no concurrent submissions,
  stale-result rejection when text changes, and release on ViewModel clearance.
- `GlinerActivity.kt`: lifecycle-aware collection and native Android widgets.
- `com/gliner25decide/`: imported tokenizer, schema, embedding lookup, graph runner,
  decoder and fixture utilities. Source attribution is bundled under
  `app/src/main/assets/third-party/`.
- `scripts/setup-gliner.sh`: pinned/checksummed model setup; no downloads in app.

The tokenizer reproduces upstream word splitting and label-marker placement.
Embeddings are memory-mapped FP16 values upcast to FP32. The graph returns label
logits, and the decoder applies single-label softmax. Label descriptions plus
request must fit 512 encoded tokens; long requests fail instead of being silently
truncated. More than 4096 text characters and blank input are rejected before inference.

Cancellation discards stale results and pending coroutine work. It does not
promise to preempt an already-running native CPU forward pass. The native runner
confines graph operations to one worker and owns one process-wide LiteRT Environment.
Compiled graphs and buffers are closed with the adapter; Activity rotation retains
the ViewModel rather than reloading the model. A later resource-policy milestone
must address background idle unloading and memory-pressure handling.

The Voice Lab feeds the existing 320-sample capture frames into
`VadFrameProcessor`, which assembles non-overlapping 512-sample Silero windows.
`SherpaSileroVadEngine` runs one local CPU probability inference per window;
two consecutive scores at or above 0.5 report speech start, and scores below
0.35 for eight windows report speech end. Probability and model/inference timings
remain visible in the lab. The same frame collector feeds one Sherpa online
recognizer configured for one CPU thread; partial and endpointed text replace a
single on-screen transcript. The recognizer owns one stream and a fixed 320-float
conversion buffer. It does not retain PCM history, invoke GLiNER, or open another
microphone.

## Provenance and Dependencies

[Published LiteRT Android sample](https://huggingface.co/litert-community/GLiNER2.5-Decide-LiteRT/tree/main/android),
pinned to `600fe62bfd9a374e48874f02ae0d94850d11c193`.
It converts Fastino's `GLiNER2.5-Decide` classification path, source model revision
`7ee5da4c2415e32259bcdc0b1a7367c32ce8d6f6`.
This conversion's source revision differs from the Python experiment's pinned
repository revision; compare results rather than assuming identical artifacts.

Runtime: `com.google.ai.edge.litert:litert:2.2.0`, CPU FP32, four requested threads.
The project uses AGP 9.0.1 built-in Kotlin, Gradle 9.1.0 and Java 17.
LiteRT's runtime/API AARs share a namespace, so `android.uniquePackageNames=false`
is required for this pinned release with AGP 9. A namespace warning remains.
No Kotlin Android plugin, Python interpreter, or Python bridge is embedded.

Voice VAD uses the official `k2-fsa/sherpa-onnx` v1.13.8 AAR (Apache-2.0) and
`silero_vad.onnx` (Silero Team, MIT). Full license texts and attribution are
packaged under `app/src/main/assets/third-party/`. `scripts/setup-vad.sh` pins
SHA-256 values for both downloaded binaries.

Voice STT uses the same Sherpa AAR and the official 20M-parameter English
streaming Zipformer archive, selecting its int8 encoder/decoder/joiner files
(about 42 MB total). The model archive is pinned in `scripts/setup-stt.sh`; its
Apache-2.0 attribution is included with the third-party notices.

The three graphs, table and tokenizer total 2,452,978,688 bytes. Assets require
far more space than the APK, and runtime RAM is not equal to file size.
The LiteRT publisher reports Android GPU and NPU results on a Galaxy S26; this
app deliberately uses CPU only. GPU/NPU correctness and performance on the user's
phone have not been established. In particular, default GPU precision reportedly
changed decisions; do not enable it without explicit FP32 and parity tests.

## Verification

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Unit checks cover original arithmetic/policy behavior, score validation, label
taxonomy, model unavailable/retry states, cancelled stale results and lifecycle
cleanup. Some imported upstream tests require an additional external oracle
dataset and are skipped without it; they are not counted as verified parity.

Two real instrumentation tests passed on an ARM64 Pixel_10_Pro emulator running
Android 17 with a 6 GiB configured RAM limit:

1. Three published fixtures: exact tokenizer IDs and label-marker positions,
   matching chosen labels and score differences within 0.02 of the FP32 oracle.
2. Five-route adapter: `What is 27 * 14?` -> `LOCAL_CODE`; all five scores returned;
   APK has no internet permission.

The measured adapter smoke run returned 0.35081786 for `LOCAL_CODE` versus the
Python reference's 0.35068205. Its total latency was 481 ms (graph 471 ms),
144 encoded tokens in the 256-token window. A separate interactive emulator
request took about 1.05 s. These are isolated functional checks, **not** phone
latency benchmarks or proof of calibrated routing accuracy. The screenshot/UI
check confirmed visible scores and timings without overlap.

The final interactive emulator memory snapshot was 2.02 GiB PSS / 2.15 GiB RSS.
This is resident process memory after classification, not peak loading memory or
a physical-phone result. Unit-test totals were 21 passed and one external-oracle
test skipped; Android lint passed.

For a connected device with model files already installed, this direct runner
leaves the app and its assets in place:

```sh
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.gliner25decide.GlinerDeviceTest \
  dev.edgeai.prototype.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as dev.edgeai.prototype cat files/gliner-smoke.json
adb shell am start -n dev.edgeai.prototype/.GlinerActivity
```

`connectedDebugAndroidTest` also passed, but Gradle removed this app and its staged
data during cleanup on this host. If that happens, reinstall the debug APK and
rerun `setup-gliner.sh --install` before opening the app.

Physical-phone p50/p95, peak RAM during loading, energy per request, battery and
thermal behavior remain unmeasured. Measure cold/warm and sustained runs on the
target phone before committing to this model for a production edge runtime.
Cloud inference/API cost is $0. A low score never automatically authorizes cloud
use, and `WEB_TOOL` is a task label, not proof that an internet call is necessary.

Milestone 3 VAD was also exercised on a physical Samsung SM-S931B: local silence,
seeded broadband noise, a temporary generated-speech waveform, the live mic, and
the rendered Voice Lab counters all passed their focused tests. The generated
speech start arrived about 90 ms after the first audible block in the fixture;
streaming STT produced partial text from that same source. See the [VAD report](docs/voice-vad.md)
and [STT report](docs/voice-stt.md) for timing, memory, and model limitations.