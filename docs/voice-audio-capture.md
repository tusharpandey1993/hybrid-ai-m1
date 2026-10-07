# Voice Lab: Milestone 2 Audio Capture

Date: 2026-10-07. Builds on the frozen [baseline](voice-baseline.md) and
[conversation-domain milestone](voice-state-machine.md). Only audio capture is
implemented here; no VAD, STT, TTS, endpointing or GLiNER voice inference.

## Milestone Report

**MILESTONE:** 2 - Single-source Android AudioRecord capture.

**STATUS:** PASS for the exercised unit/emulator capture gates, with physical
acoustics, wired/Bluetooth routing and energy/thermal limits explicitly unverified.

**CHANGES:** one process-owned microphone source, bounded PCM fan-out, permission
and lifecycle handling, and a capture-only debug screen. RECORD_AUDIO is the only
new permission. No internet permission, foreground service, model download or new
external dependency was added. No raw audio is persisted or uploaded.

**TESTS:** 15 audio unit tests pass (6 frame/bus, 9 capture-loop); the complete
JVM suite has 63 discovered, 62 passed, 1 pre-existing oracle skip and 0 failures.
Four instrumentation tests pass: two new real-capture/lifecycle tests and both
existing GLiNER tests. Both APKs build. Lint has 0 errors / 19 warnings.

**RESULTS:** real AudioRecord delivers 320-sample frames; start, stop, repeated
start, restart, recreation, background/foreground, denial, grant and revocation
checks succeeded on the ARM emulator. The OS reported no active recording after
stop. GLiNER scores remain unchanged.

**PERFORMANCE:** final emulator sample: 131 frames / 0 application-level drops,
52.065 ms capture-worker entry to first frame; 99 frame-arrival gaps, p50 15.849 ms,
p95 33.032 ms, max 37.642 ms. Capture-only process snapshot: 56.4 MiB PSS /
189.1 MiB RSS. These are not physical-microphone or voice-pipeline benchmarks.

**KNOWN LIMITATIONS:** 16 kHz is provisional until a downstream model is selected;
unsupported hardware fails visibly rather than silently changing format. System
default input routing only; no forced Bluetooth SCO/device picker. Emulator input
is not evidence of speech intelligibility. Framework permission callback emits
deprecation warnings; live UI counters prevent UIAutomator's idle snapshot.

**FILES CHANGED:** audio/debug implementation, layout/strings/manifest/navigation,
two unit-test files, one instrumentation-test file, the pure-domain import check's
package scope, this document and the root Android README.

**NEXT MILESTONE:** 3 - On-device VAD and speech-start signal, only after approval.

## Architecture

```text
Explicit user Start + permission + foreground
                        |
           AndroidAudioInput (process singleton)
                        |
     AudioCaptureLoop (one serial capture coroutine)
                        |
     AudioRecord -> mono PCM16 -> 320-sample AudioFrame
                        |
      bounded SharedFlow, no replay, no blocking emit
                        |
           current debug subscriber
      future VAD/STT subscribers: NOT IMPLEMENTED
```

Capture does not feed ConversationController or GLiNER yet. No microphone is
opened by a VAD/STT implementation, because neither exists. The singleton source
prevents the lab's Activities and recreation from creating competing recorders.

### Files

| File | Role |
| --- | --- |
| [AudioFrame.kt](../app/src/main/java/dev/edgeai/prototype/voice/audio/AudioFrame.kt#L1) | PCM ownership, session/sequence/timestamp and normalized peak/RMS. |
| [AudioInput.kt](../app/src/main/java/dev/edgeai/prototype/voice/audio/AudioInput.kt#L1) | Suspend start/stop, shared frames, state and structured failure contract. |
| [AudioFrameBus.kt](../app/src/main/java/dev/edgeai/prototype/voice/audio/AudioFrameBus.kt#L1) | Eight-frame bounded nonblocking fan-out; no replay. |
| [AudioCaptureLoop.kt](../app/src/main/java/dev/edgeai/prototype/voice/audio/AudioCaptureLoop.kt#L1) | Serialized recorder ownership, partial-read assembly, permission/foreground checks, counters and cleanup. |
| [AndroidAudioInput.kt](../app/src/main/java/dev/edgeai/prototype/voice/audio/AndroidAudioInput.kt#L1) | One process source, Android recorder wrapper and native permission checks. |
| [VoiceLabViewModel.kt](../app/src/main/java/dev/edgeai/prototype/voice/debug/VoiceLabViewModel.kt#L1) | UI command scope and capture state exposure; owns no model or second microphone. |
| [VoiceLabActivity.kt](../app/src/main/java/dev/edgeai/prototype/voice/debug/VoiceLabActivity.kt#L1) | Permission UI, lifecycle stop and metadata display. |
| [activity_voice_lab.xml](../app/src/main/res/layout/activity_voice_lab.xml#L1) | Status, amplitude bar, start/stop and counters/timing. |
| [AudioFrameTest.kt](../app/src/test/java/dev/edgeai/prototype/voice/audio/AudioFrameTest.kt#L1) | Format, amplitude, copy isolation and backpressure tests. |
| [AudioCaptureLoopTest.kt](../app/src/test/java/dev/edgeai/prototype/voice/audio/AudioCaptureLoopTest.kt#L1) | Recorder ownership and observable failure/resource cases. |
| [AudioInputDeviceTest.kt](../app/src/androidTest/java/dev/edgeai/prototype/voice/audio/AudioInputDeviceTest.kt#L1) | Real AudioRecord and Activity lifecycle tests; metadata-only timing sample. |

Existing GLiNER Activity/layout gained only a Voice Capture Lab navigation button;
the manifest gained RECORD_AUDIO, an optional microphone feature and the lab's
Activity/launcher alias. Existing routing/inference code and its tests are unchanged.
The conversation import-boundary test now checks its `conversation` and `turn`
packages explicitly rather than forbidding Android imports in all future `voice`
subpackages. It still verifies all six original pure domain files; no assertion
was removed and all 26 conversation tests continue to pass.

## Verified Platform APIs and Selection

Primary Android documentation inspected before implementation:

- [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord):
  initialization checks, minimum buffer size, nonblocking PCM16 reads and release.
- [AudioRouting](https://developer.android.com/reference/android/media/AudioRouting):
  the actual routed device can differ from a preferred device.
- [Audio focus](https://developer.android.com/media/optimize/audio-focus): focus
  coordinates playback and does not grant microphone ownership.

No external speech dependency/model was introduced. AudioRecord is available in
the existing Android SDK, and the existing AndroidX/coroutine/test dependencies
are reused. This is not a Sherpa integration or a claim about its future format.

The lab requests 16,000 Hz, one channel, PCM signed 16-bit using
VOICE_RECOGNITION. Each delivered frame has 320 samples, representing 20 ms of
configured stream time. AudioRecord's buffer is at least its reported minimum or
eight frame payloads (5,120 bytes), whichever is greater. The actual sample rate
is checked; failures are explicit. Android documents 44.1 kHz, not 16 kHz, as the
universally supported rate. Resampling and alternate format selection are not
implemented. Check the selected VAD/STT model contract before connecting it.

## Ownership, Backpressure and Privacy

`AndroidAudioInput.get` returns one application-context instance. Its explicit
SupervisorJob/capture dispatcher lives for the process; it does not retain an
Activity. A mutex serializes start/stop. Repeated start while active is idempotent;
restart creates a new monotonic session ID after prior cleanup.

Capture uses nonblocking reads, assembles partial reads until 320 samples, and
yields briefly when no samples are available. It never blocks Main on native
reading. A two-second no-data condition produces a READ failure rather than an
unbounded busy loop. Stop cancels/joins the worker; finally attempts both native
stop and release and clears the scratch PCM buffer. Cleanup failures are retained
as primary/suppressed causes, not swallowed. The process source/idle dispatcher
remain reusable, but the AudioRecord is not kept resident after stop.

Each frame defensively copies its PCM; subscribers obtain their own copy only
when requesting PCM, so one consumer cannot mutate another's input. The bus has
replay 0 and capacity 8. `tryEmit` drops the incoming frame when subscribers cannot
accept it; zero subscribers is also an observable rejection. These full-frame
producer rejections increment `droppedFrames`. A slow subscriber cannot grow an
unbounded queue or block microphone polling. All subscribers share the admission
policy; separate per-consumer queues may be needed later and are not invented here.

The drop count does **not** detect every hardware/HAL overrun, describe dropped
partial tails at stop, or establish acoustic continuity. Eight queued PCM payloads
are 5,120 bytes, excluding object metadata, native buffers and subscribers' copies.
Actual total memory is measured separately below.

Normal UI use writes no PCM, transcripts or capture report. Instrumentation writes
only `files/audio-capture-smoke.json`: sample rate, counters, interval statistics,
route type and first-frame timing. It never exports PCM arrays. Structured failures
retain exceptions in memory; UI shows reason/type/read code, not private speech.
No cloud/API traffic occurs and no internet permission was added.

## Permission, Lifecycle and Routing Policy

- Permission is checked before start, every capture iteration and at native
  AudioRecord build/start. Permission races/read errors become structured failures.
- Permission grant never automatically starts recording: after allowing access,
  press Start capture again. Denial leaves the lab stopped and can be requested
  again unless Android's permanent-denial policy applies.
- `onPause` marks the source non-foreground and schedules stop. Rotation, leaving
  the screen and backgrounding stop/release capture. Returning does not restart it.
  There is no background microphone service or automatic resumption.
- API 29+ system-silenced capture is reported as SILENCED rather than counted as
  a successful source of valid speech. Older devices still rely on permission and
  read results; not every OS/vendor interruption case is covered.
- Capture-only mode does not request playback audio focus or mute other apps.
  This is not a claim of exclusive system-wide capture. Playback focus management
  belongs to the approved TTS milestone; microphone contention remains governed
  by Android capture policies and observable silencing/read errors.
- Input selection is Android's default. The current `routedDevice.type` is read
  while recording and displayed; the emulator reported type 15 (built-in mic).
  Preferred-route requests, Bluetooth SCO activation, Bluetooth permission and a
  device selector were not added. Wired/Bluetooth hardware behavior is NOT TESTED.

The native permission entry points passed lint without suppressions. A framework
permission callback is used because the current ActivityResult lint check required
a Fragment version that this native-widget project does not include. No unused
Fragment dependency was added. The callback is deprecated in ComponentActivity;
compiler warnings remain visible and are documented, not hidden.

## Build and Test Results

```sh
cd hybrid-ai-m1
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --rerun-tasks --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  dev.edgeai.prototype.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 exec-out run-as dev.edgeai.prototype cat files/audio-capture-smoke.json
```

Fresh complete host run: both APKs build, all 80 actionable tasks execute, lint
passes with 0 errors / 19 warnings (plus visible permission-callback deprecation
and existing LiteRT namespace messages). Complete JVM counts:

| Suite | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| AudioFrameTest | 6 | 0 | 0 |
| AudioCaptureLoopTest | 9 | 0 | 0 |
| ConversationControllerTest | 26 | 0 | 0 |
| TextRouterTest | 12 | 0 | 0 |
| AiRouterTest | 4 | 0 | 0 |
| GlinerViewModelTest | 3 | 0 | 0 |
| DecideDecoderTest | 2 | 1 | 0 |
| **JVM total** | **62** | **1** | **0** |

The one skip is the unchanged optional external-oracle corpus check. New audio
tests cover full-negative-scale amplitude without overflow, PCM copy isolation,
partial-frame rejection, bounded fan-out, one-recorder repeated start/restart,
permission denial/revocation, background stop/start prevention, bad read code,
unsupported rate, system silencing and retained cleanup exceptions.

All four native instrumentation tests pass together, reported time 11.194 seconds:

1. Existing tokenizer/native fixture parity.
2. Existing five-route GLiNER adapter parity with no internet permission.
3. Real AudioRecord start, at least 30 frames, format checks, no observed drops,
   repeated-start session identity, UI stop, OS-level zero active recordings,
   explicit restart and cadence metadata.
4. Activity recreation and background transition stop capture; foreground return
   remains idle; explicit start works again.

JUnit return-type initialization and native permission lint failures were fixed
and the same checks rerun. No tests were disabled or tolerances weakened. The
final GLiNER winner and all alternatives remain unchanged (LOCAL_CODE 0.35081786
for the arithmetic fixture); its one warmed emulator request was 479.212 ms total /
470.130 ms graph, not evidence of an optimization or voice classification.

## Measurements and Limits

ARM64 Pixel_10_Pro emulator, Android 17/API 37; not a physical Pixel/phone. The
emulator was originally launched with audio output/input disabled at its host
backend. AudioRecord frame delivery and very low-amplitude input are observed,
but speech quality, audible input and physical-microphone validity are unverified.

Final metadata sample:

| Metric | Observed |
| --- | ---: |
| Rate / frame payload | 16,000 Hz / 320 samples |
| Frames / application-level dropped frames | 131 / 0 |
| Capture worker entry -> first complete frame | 52.065 ms |
| Frame-arrival intervals measured | 99 |
| Arrival-gap p50 / p95 / max | 15.849 / 33.032 / 37.642 ms |
| Routed device type | 15 |

An earlier isolated run had 106.661 ms first-frame time and p50/p95 gap
15.542/33.658 ms. Arrival timestamps are taken after each frame read/assembly,
not at the ADC. A 20 ms sample payload does not imply uniform 20 ms wall-clock
delivery; AudioRecord buffering and polling cause bursts/jitter. First-frame
timing excludes the permission dialog and initial UI/coroutine dispatch. Do not
call it audio-to-VAD latency or time-to-first-STT-partial.

Manual capture-only process snapshot after directly launching the lab, without
GLiNER loading: PSS 57,777 KiB (56.4 MiB), RSS 193,616 KiB (189.1 MiB). This is not
peak monitoring or an allocation-per-frame measurement. Opening the lab from an
existing GLiNER Activity can retain that model on the back stack; do not mix that
memory state with a fresh capture-only process. No second GLiNER instance is created.

NOT MEASURED: physical-phone speech fidelity, hardware overrun rate, wired or
Bluetooth routes, CPU utilization/energy per frame, battery, thermals, sustained
phone capture, focus/contention across real apps, and all VAD/STT/TTS boundaries.
Nonblocking polling and per-frame copies/UI updates have CPU/allocation costs;
no battery-efficiency claim is made. Cloud/API cost for this milestone is $0.

## Manual Procedure and Observed Emulator Checks

Open the Voice Capture Lab launcher icon, or the button on the GLiNER screen.
The independent launcher avoids loading the model:

```sh
adb shell am start -n dev.edgeai.prototype/.VoiceLab
```

1. Deny microphone permission in the real dialog: observed permission not granted,
   Not capturing, zero frames. Requesting again reopened the permission dialog.
2. Grant While using the app, then press Start capture: observed Capturing, stable
   advancing counters and 0 drops. Screenshot showed 2,492 frames, peak/RMS 0.0002,
   no failure and no text overlap. This is not an intelligible-speech test.
3. Stop and start again: automated native checks verify a new session and no active
   Android recording configuration after stop.
4. Rotate/recreate and background/foreground: native tests verify capture stops and
   does not auto-restart. Explicit start afterward succeeds.
5. Revoke while recording via Settings or development `pm revoke`: on this emulator
   Android killed the app process; reopening showed permission not granted and Not
   capturing. The unit test separately exercises in-process permission loss and
   retains its SecurityException. Process-kill behavior is not a caught callback.
6. On a physical device later, test built-in mic, wired headset, Bluetooth mic,
   privacy switch, another recorder/call, and unsupported-rate behavior. These checks
   remain unperformed and must not be represented as passing.

The live counters prevented UIAutomator's idle-wait XML snapshot while capturing.
A screenshot and native tests were used instead; idle/permission XML snapshots
worked. No assertion was removed to accommodate this automation limitation.

The lab was left open and not recording after verification. No future milestone
code was added. **Stop here and await explicit approval before Milestone 3 VAD.**