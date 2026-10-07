# Full-Duplex Voice Baseline

Audit date: 2026-10-07. Phase 0 only; no production code changed.

## Baseline Gates

- `:app:testDebugUnitTest`: PASS, 65 passed, 1 skipped, 0 failed after correcting a stale source-count assertion in `ConversationControllerTest` to include the already-present `UserTranscript.kt`.
- The skipped test is the existing optional external-oracle fixture test; its fixture corpus is not configured.
- `:app:lintDebug`: PASS.
- `:app:assembleDebug`: PASS.
- `:app:assembleDebugAndroidTest`: PASS (instrumentation APK compiles).
- Instrumentation execution: NOT RUN. `adb devices -l` reported no connected device or emulator.
- No physical-device or acoustic performance result was collected.

The focused stale-assertion test and the full JVM suite were rerun and passed. No test was disabled and no assertion was weakened.

## Architecture Before Changes

```text
AndroidAudioInput singleton
  -> AudioCaptureLoop
  -> one AudioRecord (16 kHz, mono, PCM16, 320 samples / 20 ms)
  -> bounded AudioFrameBus
  -> VoiceLabViewModel frame collector
       -> VAD
       -> streaming Sherpa STT
       -> VoiceConversationSession / ConversationController

Android TextToSpeech <- SpeechSynthesizer.speak(String)
```

At the Phase 0 baseline, `AndroidAudioInput` built one recorder with `MediaRecorder.AudioSource.VOICE_RECOGNITION`. The implementation later changes the source to `VOICE_COMMUNICATION` as an explicit AEC experiment. Frames from the shared `AudioInput.frames` flow are consumed by the same VoiceLab collector for VAD and STT. There is no second microphone. STT continues to receive frames while Android TTS is speaking; the capture loop itself is not stopped by playback.

## Current Boundaries and Behavior

- `SherpaStreamingSttEngine.accept(AudioFrame)` returns `SttUpdate(text: String, isFinal, inferenceMillis)`. It has no dependency on `SpeechSynthesizer` or the TTS implementation.
- `VoiceLabViewModel` forwards nonblank STT strings to `VoiceConversationSession.acceptTranscript`, which emits string-valued partial and committed conversation events. A `UserTranscript` inline value class already exists but is not used on this path.
- Assistant output is represented by `conversation.AssistantResponse`, a data class carrying thread ID, response ID, turn ID, text, and resume position. The synthesizer API does not use it: `SpeechSynthesizer.speak` accepts `String`.
- `AndroidTtsSynthesizer` converts that string directly into `TextToSpeech.speak`.
- The existing conversation controller has `TEMPORARILY_PAUSED`, response/resume-position state, and epoch checks. Its tests cover preservation/recovery policies. The current VoiceLab wiring does not use those primitives to control playback: each VAD `speechStarted` calls `speechSynthesizer.stop()` before STT on that frame. The actual platform TTS utterance is stopped, not paused or resumed.
- The VAD event is a local raw speech-activity observation; no confirmation/self-speech filter is applied before playback stop.
- The baseline source was `VOICE_RECOGNITION`. At baseline, `AcousticEchoCanceler` and `NoiseSuppressor` were not attached or reported.

## Relevant Existing Files

- `app/src/main/java/dev/edgeai/prototype/voice/audio/AndroidAudioInput.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/audio/AudioCaptureLoop.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/audio/AudioInput.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/audio/AudioFrame.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/vad/VadFrameProcessor.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/vad/SherpaSileroVadEngine.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/stt/SherpaStreamingSttEngine.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/conversation/UserTranscript.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationEvent.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationController.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/session/VoiceConversationSession.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/tts/SpeechSynthesizer.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/tts/AndroidTtsSynthesizer.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/debug/VoiceLabViewModel.kt`
- `app/src/main/java/dev/edgeai/prototype/voice/debug/VoiceLabActivity.kt`
- `app/src/test/java/dev/edgeai/prototype/voice/conversation/ConversationControllerTest.kt`
- `app/src/test/java/dev/edgeai/prototype/voice/audio/AudioCaptureLoopTest.kt`
- `app/src/androidTest/java/dev/edgeai/prototype/voice/audio/AudioInputDeviceTest.kt`
- `app/src/androidTest/java/dev/edgeai/prototype/voice/vad/VadDeviceTest.kt`

## GLiNER and Scope

The existing GLiNER LiteRT runtime and router are separate from the voice pipeline. Baseline JVM tests pass, including the current GLiNER tests. GLiNER voice classification is not present and is out of scope for this milestone. No LLM or cloud API is introduced.

## Phase 0 Gate Result

PASS for available host gates. Device-only instrumentation and acoustic behavior remain unverified because no Android device/emulator is connected. This is a limitation, not a substituted or fabricated result.
