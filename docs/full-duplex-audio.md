# Full-Duplex Voice: Implementation and Test Guide

Date: 2026-10-07. This report separates automated verification from acoustic acceptance. TTS/microphone concurrency is verified on one handset; human double-talk and speaker-leakage rates have not been measured.

## Architecture

```text
Single AndroidAudioInput / AudioRecord (VOICE_COMMUNICATION, 16 kHz mono PCM16)
                    |
          one bounded AudioFrame stream
                 /       \
          Silero VAD   Sherpa streaming STT
              |              |
     potential activity   UserTranscript
              \              /
        VoiceConversationSession / Controller
                    |
             AssistantResponse
                    |
           SpeechSynthesizer / Android TTS
```

There is one AudioRecord. VAD and STT consume frames from the same existing fan-out. The capture coroutine continues during TTS. VoiceLab temporarily sets `MODE_IN_COMMUNICATION`, explicitly selects the built-in speaker when Android accepts it, and tags TTS as communication speech. AEC and NoiseSuppressor attach to the AudioRecord session. The prior communication route and mode are restored when capture stops.

STT returns `UserTranscript?`; conversation transcript events require `UserTranscript`; TTS requires the existing metadata-bearing `AssistantResponse`. Android TTS converts `response.text` to `String` only at the platform call. Sherpa has no TTS dependency. There is no GLiNER voice classification, LLM, cloud API, or AGC.

Raw VAD while TTS is speaking is potential audio activity, not evidence of a human interruption. A 300 ms VAD-start guard from TTS enqueue ignores the observed output-onset transient while microphone capture and STT continue. Later VAD activity can suppress playback without cancelling the response. A blank final STT endpoint with no transcript candidate can restore the response from its saved cursor. Nonblank STT output during an active or paused assistant response remains visible as a candidate and is not committed as a user turn. Neither VAD nor STT proves a human spoke. Lexical similarity remains diagnostic only. Android TextToSpeech has no arbitrary sample-accurate pause/resume here.

When VAD occurs without active assistant playback, it does not open or increment a conversation turn. A typed STT transcript opens the user turn. This avoids ambient VAD creating a phantom turn that rejects the next assistant response.

## Audio Configuration

- Previous source: `MediaRecorder.AudioSource.VOICE_RECOGNITION`.
- Current source: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`.
- Reason: the handset repeatedly emitted VAD activity immediately during its own TTS playback despite AEC/NoiseSuppressor reporting enabled. Android documents `VOICE_COMMUNICATION` as voice-communication tuned and able to take advantage of echo cancellation. The switch is an explicit experiment, not proof that acoustic leakage is solved.
- Expected consequences: the platform may apply communication-oriented preprocessing in addition to the explicitly attached effects. This can improve echo behavior but may alter STT/VAD level, frequency response, and noise handling. Re-run the same speech fixtures and handset trials before judging the result; do not compare old/new logs as a controlled A/B unless volume, route, and conditions match.
- Configured format: 16,000 Hz, mono, PCM16; 320 samples/frame (20 ms).
- Device tested: Samsung SM-S931B (`pa1q`), Android 16 / API 36.
- Captured session sample before source switch: session ID 1729; 131 frames, 0 application-level drops; first-frame 46.97 ms; 99 inter-frame intervals, p50 19.33 ms, p95 24.59 ms, max 25.59 ms. These are one instrumentation sample, not a sustained performance benchmark.
- `VOICE_COMMUNICATION` capture sample: session ID 2081; 131 frames, 0 application-level drops; first-frame 60.50 ms; 99 inter-frame intervals, p50 19.61 ms, p95 24.44 ms, max 25.28 ms.
- `VOICE_COMMUNICATION` effects: AEC available/attached/enabled=true; NoiseSuppressor available/attached/enabled=true.
- Communication route: Android accepted the `TYPE_BUILTIN_SPEAKER` request (`SM-S931B:2`); capture logged `MODE_IN_COMMUNICATION`. After stop, `dumpsys audio` showed `MODE_NORMAL` and no preferred communication route.
- TTS startup guard: the isolated loudspeaker test logged `vad_ignored_tts_startup probability=0.6369976` shortly after TTS enqueue and did not log a playback stop for that edge. This bounded guard is not general echo cancellation.
- AEC on that capture: available=true, attached=true, enabled=true.
- NoiseSuppressor on that capture: available=true, attached=true, enabled=true.
- AGC: not created or enabled.

These effect values establish successful API creation/enablement on this handset; they do not establish acoustic echo-removal quality. The communication-mode, speaker-route, and TTS-attribute build passed the full 10-test on-device suite. The clean onset trace logs `vad_ignored_tts_startup` rather than a playback stop for the immediate TTS-onset transient. Later audio activity may still suppress playback; human double-talk and longer acoustic trials remain unverified.

## Diagnostics

Android log tag: `VoiceDuplex`. Logs include capture status/session/source/rate/frame/drop counts, effect availability/attachment/enabled state, VAD activity, TTS lifecycle/response ID, transcript character count, similarity, and false-interruption recovery. Raw transcripts, assistant text, and PCM are not written to Logcat. The VoiceLab screen shows the transcript locally for manual comparison, current assistant response, effect state, VAD, TTS, potential interruption, counts, and lexical similarity.

Useful live commands from a host with ADB:

```sh
adb logcat -c
adb logcat -s VoiceDuplex:I
```

Capture just the relevant records to a text file on the host:

```sh
adb logcat -d -s VoiceDuplex:I > full-duplex-log.txt
```

Expected progression for the playback-onset transient:

```text
tts_started response_id=...
vad_ignored_tts_startup probability=... response_id=...
```

Expected progression for later potential audio activity:

```text
vad_started ... tts_playing=true response_id=...
playback_stop response_id=... state=READY suppression_confirmed=true stop_call_ms=...
transcript_candidate_held response_id=... final=... chars=...
```

A blank final with no transcript candidate may add `false_interruption playback_resumed=true response_preserved=true` and then a new `tts_queued` / `tts_started`. While an assistant response is active or paused, transcript candidates are held out of conversation state; they are not evidence of a human speaker. Activity beyond the onset guard still requires acoustic evaluation.

## Automated Verification

- Final host JVM suite: 86 passed, 1 optional external-oracle test skipped, 0 failed. Final lint, debug APK, and instrumentation APK compilation passed.
- On-device GLiNER/VAD/STT/audio suite with communication mode, speaker route, TTS attributes, and onset guard: 10/10 passed with repo SHA-verified GLiNER assets staged.
- Updated `AudioInputDeviceTest`: 3/3 passed on the handset, including start/stop/restart, rotation/background lifecycle, and TTS/capture concurrency. The concurrency test passed after the raw-VAD turn fix and log additions.
- Repeated Speak regression: passed on the SM-S931B. Two successive passage requests produced `tts_queued`, `playback_started`, and `tts_started` for `demo-1` and `demo-2` while capture remained active. The controller now replaces a suppressed demo response and advances the playback epoch so callbacks from the old response are stale.
- After the VoiceLab demo-response acceptance fix, the focused TTS/capture device test passed again on the SM-S931B. Logcat contained `tts_queued`, `playback_started ... capture=CAPTURING`, and `tts_started` for `demo-1`.
- The handset run verified the accepted speaker route, audio-mode restoration, onset guard, and playback-time STT quarantine. Controlled loudspeaker-volume trials, human double-talk, headphones, and false-interruption rate remain untested.
- The concurrency test confirms that the microphone remains capturing as TTS starts. It does not test a human speaking simultaneously and does not estimate self-interruption rate.
- Rerun after the final change set with:

```sh
cd hybrid-ai-m1
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.edgeai.prototype.test/androidx.test.runner.AndroidJUnitRunner
```

The two GLiNER device tests require the private model files. Restore them using the existing checksum-verifying helper before instrumentation if absent:

```sh
bash scripts/setup-gliner.sh --install
```

## Manual Acoustic Procedure

Run this on an unlocked handset in a quiet room. Keep the phone, speaker direction, microphone distance, speech text, and volume setting stable within each trial group. Do not infer acoustic quality from AEC being enabled.

1. Install the debug APK, open Voice Capture Lab, grant microphone permission, start capture, and wait for `CAPTURING` plus TTS `READY`.
2. Clear Logcat, start the long test passage, and confirm logs include `tts_started` while capture remains `CAPTURING` and its frame counter continues increasing.
3. Assistant-only control: stay silent. Repeat 20 trials at each 25%, 50%, 75%, and 100% volume. Record each VAD start, TTS stop, STT partial/final shown in the UI, false interruption, and whether the assistant response was recovered. Do not count a VAD event alone as a committed user interruption.
4. Human interruption: during TTS, say “stop,” then repeat with “yeah,” then “Wait, what does GLiNER mean?” Record the exact raw UI transcript and whether playback suppresses. Verify the stored response remains visible during suppression. Do not manually edit the transcript before recording it.
5. Double-talk: replay the same assistant passage and speak the clarification simultaneously. Record whether the human words remain in the raw STT result and whether they are contaminated by assistant words.
6. Controls: repeat separately with wired and Bluetooth headphones where available. Do not combine their results with loudspeaker results.
7. For every group, record device, route, volume, trials, VAD activations, STT partials/finals, false and confirmed interruptions, and response recovery. Calculate self-interruption rate as false committed interruptions divided by assistant-only trials. If no human confirmation occurs, report the value as not measured rather than treating VAD activations as false committed turns.

No assistant-only volume matrix, human double-talk transcript, headphone control, or false-interruption rate was performed. Earlier logs showed VAD stops during loudspeaker TTS despite AEC/NS. The latest isolated test routed to the built-in speaker and ignored the immediate onset edge with the guard, but later double-talk remains unmeasured. The question “can this phone reliably distinguish a person from its own loudspeaker playback?” remains unanswered.

## Files

Added:

- `app/src/main/java/dev/edgeai/prototype/voice/audio/AudioProcessingState.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/audio/AudioProcessingController.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/audio/AndroidAudioProcessingEffectFactory.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/conversation/InterruptionMonitor.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/conversation/SelfSpeechDetector.kt`
- `app/src/test/java/dev/edgeai/prototype/voice/SpeechBoundaryTest.kt`
- `app/src/test/java/dev/edgeai/prototype/voice/audio/AudioCaptureProcessingLifecycleTest.kt`
- `app/src/test/java/dev/edgeai/prototype/voice/audio/AudioProcessingStateTest.kt`
- `app/src/test/java/dev/edgeai/prototype/voice/audio/SessionAudioProcessingControllerTest.kt`
- `app/src/test/java/dev/edgeai/prototype/voice/conversation/InterruptionMonitorTest.kt`
- `app/src/test/java/dev/edgeai/prototype/voice/conversation/LexicalSelfSpeechDetectorTest.kt`
- `docs/full-duplex-baseline.md`
- `docs/full-duplex-audio.md`

Modified: shared capture/effect lifecycle, conversation events/controller/session, VoiceLab ViewModel/activity/layout/strings, Sherpa STT and TTS boundaries, and existing audio/VAD instrumentation tests. GLiNER runtime/router source was not changed.

## Known Limitations and Next Step

AEC/NS quality is hardware-, route-, source-, and vendor-dependent. This handset reports both effects enabled, but speaker echo still requires the manual assistant-only and double-talk trials above. The deterministic lexical metric can mark repeated assistant wording and can overlap heavily with a genuine clarification. Android TTS restart resumes from a saved text cursor where available, but exact phoneme position is not available. There is no semantic classifier or final self-echo rejection policy.

Next: execute and record the acoustic trial matrix on this handset, especially loudspeaker double-talk and assistant-only false interruptions. Keep GLiNER voice classification out of the pipeline until those results support proceeding.
