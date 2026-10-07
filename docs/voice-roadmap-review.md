# Voice Lab Roadmap Review

Reviewed against the supplied **Android On-Device Real-Time Voice Conversation
Lab** master prompt on 2026-10-07. This is a code/status review and backlog only;
no production code was changed and no tests were run for this review.

## Findings

1. **Earlier milestone labels did not match the supplied prompt.** The master
  defines M4 as Android TTS plus immediate VAD barge-in, M5 as streaming offline
  STT, and M6 as endpointing. The reports have now been relabeled; the
  conversation bridge is recorded as preparatory wiring, not an official
  numbered milestone.

2. **M4 implementation exists, but audible acceptance is unverified.** The
  Voice Lab now wraps Android `TextToSpeech` and calls `stop()` from the VAD
  `speechStarted` branch before STT decode. The physical device is locked in the
  current session, so Speak/interrupt could not be manually exercised and
  speech-start-to-last-audible-sample latency is NOT MEASURED. Do not mark M4
  PASS yet.

3. **M6 endpointing is bypassed by the current turn bridge.**
   [SherpaStreamingSttEngine.kt](../app/src/main/java/dev/edgeai/prototype/voice/stt/SherpaStreamingSttEngine.kt#L37)
   enables Sherpa's built-in endpoint and exposes its `isEndpoint` value as
   `isFinal` at [line 59](../app/src/main/java/dev/edgeai/prototype/voice/stt/SherpaStreamingSttEngine.kt#L59).
   [VoiceLabViewModel.kt](../app/src/main/java/dev/edgeai/prototype/voice/debug/VoiceLabViewModel.kt#L94)
   passes that directly to `acceptTranscript`, which commits the turn. There is
   no configurable `EndpointDetector`, transcript-stability window, maximum
   wait, or false-early-commit measurement. The current behavior can prematurely
   commit a long-pause or incomplete utterance.

4. **Conversation actions are currently discarded.**
   [VoiceConversationSession.kt](../app/src/main/java/dev/edgeai/prototype/voice/session/VoiceConversationSession.kt#L50)
   retains only `ConversationTransition.context`; its `actions` and `disposition`
   are not exposed to an owner. That is acceptable for this non-executing lab
   bridge, but M4/M9 cannot pause, cancel, continue, answer, or resume until the
   action result is surfaced and deliberately handled.

5. **The M5 STT contract is only partially represented.** The current
   `SttUpdate` contains text, finality and decode duration, but no transcript
   timestamp or `StreamingSpeechRecognizer` interface. The selected 20M int8
   model has produced rough smoke transcripts; it has not met the master prompt's
   Indian-English, technical-word, pause, speed, or accuracy evaluation criteria.

6. **Several later research gates remain unproven.** The physical Samsung run is
   a useful integration smoke check, not the M12 100-scenario corpus or M13
   sustained phone benchmark. GLiNER remains approximately 2.45 GB and no shared
   single-runtime `AiRouter`/`VoiceTurnClassifier` design or M14 suitability
   decision has been completed.

## Ordered To-Do

- [x] **M0 - Baseline audit.** Recorded in `voice-baseline.md`.
- [x] **M1 - Pure conversation state machine.** Existing state, event and
  transition implementation is in place.
- [x] **M2 - Single-source audio capture.** One `AndroidAudioInput`; no second
  microphone path.
- [x] **M3 - VAD/speech-start signal.** Sherpa Silero is integrated. Natural
  human-speech and varied-noise accuracy still need the M3 acceptance matrix.
- [~] **M4 - Android TTS and fast barge-in.** The `SpeechSynthesizer` abstraction,
  Android `TextToSpeech`, demo controls, and VAD-first `stop()` call are
  implemented. Still required: manual physical Speak/interrupt checks, false
  suppression cases, and measurement to last audible output. Acceptance blocked
  until that can be performed on an unlocked phone.
- [~] **M5 - Streaming offline STT.** Sherpa streaming ASR is already present
  ahead of the prompt's milestone order. After M4, reconcile it with the required
  `Transcript(text, isFinal, timestamp)` contract and complete the requested
  offline, partial-latency, utterance-variety and word-error measurements.
- [ ] **M6 - Deterministic endpointing.** Add `EndpointConfig` and
  `EndpointDetector`; separate STT partials from turn commitment. Exercise long
  pauses/incomplete turns and measure false-commit rate plus speech-end-to-commit
  latency.
- [ ] **M7 - Shared GLiNER runtime.** Only after measuring need, share one loaded
  runtime between the unchanged `AiRouter` and a future voice classifier. Prove
  existing routing parity and prevent duplicate model copies.
- [ ] **M8 - Voice turn classifier.** Add `VoiceTurnClassifier` behind an
  interface; classify committed turns only. Build the prompt's 100+ contextual
  fixtures and report per-class precision/recall and confusion matrix.
- [ ] **M9 - Semantic interruption recovery.** Connect VAD, STT, endpointing,
  turn classification, controller actions and TTS suppression. Preserve stale
  turn guards; test backchannel continuation, stop, topic changes, clarification
  and incomplete speech.
- [ ] **M10 - Clarification answer/resume.** Prove deterministic canned
  clarification and original-response resumption, including an explicit nested
  clarification policy.
- [ ] **M11 - Voice diagnostics.** Add event/timing trace and structured JSON
  export with VAD, partial, endpoint, classifier, action and TTS boundaries;
  do not persist microphone audio.
- [ ] **M12 - Conversation corpus.** Create the requested 100+ text/audio
  scenarios, false-interruption cases and honest confusion/accuracy results.
- [ ] **M13 - Physical-device benchmark.** Run cold/warm, sustained 5/15/30-minute
  measurements for latency p50/p95, memory, CPU, battery and thermal state on a
  named ARM64 handset; keep emulator data separate.
- [ ] **M14 - GLiNER suitability decision.** Compare accuracy/latency/RAM/model
  size against smaller classifiers and deterministic rules before deciding
  whether GLiNER belongs in an always-available voice path.
- [ ] **M15-M20 - Later stages only after evidence:** local LLM, local RAG,
  optional cloud escalation, streaming response pipeline, advanced barge-in and
  speculative execution.

## Execution Notes

The supplied master prompt requires one milestone at a time, its acceptance gates,
and a stop after each report. The latest user direction defers test expansion and
UI polish until feature implementation is complete; keep the UI minimal while
implementing features, then reconcile that preference with the prompt's required
test gates before declaring future milestones accepted.

**Current gate:** finish M4's physical audible-suppression acceptance. M5 STT
code exists, but its full accuracy/latency acceptance follows M4. Do not start
M6 endpointing or later work until M4 is measured and reported.