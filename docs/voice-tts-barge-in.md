# Voice Lab: Milestone 4 TTS and Fast Barge-In

Date: 2026-10-07. Implements Android `TextToSpeech` as the first local speech
output and connects the existing VAD speech-start event to its stop method.

## Milestone Report

**MILESTONE:** 4 - Basic TTS plus fast barge-in.

**STATUS:** BLOCKED for acceptance. The Kotlin implementation compiles, but the
physical handset was locked during this run, so audible TTS interruption could
not be exercised or measured. Android reports Google's TTS package installed.

**CHANGES:** Added a minimal `SpeechSynthesizer` interface and
`AndroidTtsSynthesizer` using the platform `TextToSpeech` API. Voice Lab has one
long test passage and a Stop control. When VAD emits `speechStarted`, the frame
collector invokes `TextToSpeech.stop()` before passing that frame to STT. It does
not wait for transcription or classification. The demo passage is approximately
30 seconds. Explicit capture Stop, background transition, and ViewModel teardown
also stop/shut down TTS. Android TTS stop is treated as interruption, not
resumable playback.

**TESTS:** `:app:compileDebugKotlin` passes. No new automated test cases were
added, per the current instruction to defer test expansion. The physical Speak,
VAD-interrupt, manual Stop, and noise/cough checks were not run because the phone
remained on its lockscreen.

**RESULTS:** The VAD callback is ordered ahead of Sherpa STT decode in the same
frame collector. The UI reports the synchronous `stop()` call duration, explicitly
not a measurement of when sound becomes inaudible.

**PERFORMANCE:** Audible speech-start-to-suppression latency is NOT MEASURED.
The phone remained locked, so this run has no numeric TTS-stop result.

**KNOWN LIMITATIONS:** Platform TTS `stop()` does not provide sample-accurate
pause/resume; this milestone honestly cancels the current utterance. Speaker
echo into the microphone may trigger VAD and remains untested. The 30-60 second
sample is intended for manual interruption on an unlocked physical phone. No
claim is made that the fast-barge-in acceptance criterion passes yet.

**FILES CHANGED:** TTS interface and Android implementation, Voice Lab ViewModel,
Activity/layout/strings, this report, README, and roadmap review.

**NEXT MILESTONE:** M5 - Streaming offline STT is already present as an
experimental implementation, but still needs its full accuracy/latency acceptance
work. The prompt requires stopping after each milestone; do not proceed to M6
until M4's physical audible-suppression acceptance is verified.

## Manual Acceptance Procedure

On an unlocked ARM64 phone:

1. Open Voice Capture Lab and start capture.
2. Tap Speak test passage and confirm TTS reaches Speaking.
3. Say “wait” or “stop” near the microphone while the passage is audible.
4. Verify VAD changes to Speech detected and TTS output becomes inaudible.
5. Repeat with cough, keyboard, room noise and speaker leakage; record false
   suppressions separately from true interruption.
6. Measure from first audible user phoneme to the last audible TTS sample. The
   displayed `stop()` call duration alone is not this metric.

TTS is enabled only in the explicit foreground Voice Lab. No audio is saved or
uploaded. This report must be updated with device identity and measured results
before M4 can be marked PASS.