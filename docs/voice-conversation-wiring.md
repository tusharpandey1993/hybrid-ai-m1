# Voice Lab: Preparatory Conversation Wiring

Date: 2026-10-07. Connects the existing VAD and streaming STT outputs to the
pure-Kotlin [ConversationController](../app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationController.kt).
This milestone adds no recognizer, audio source, classifier, response generator,
cloud call, TTS, or task execution.

## Experiment Status

**ROADMAP STATUS:** This is preparatory wiring, not an official numbered
milestone in the supplied master prompt. The prompt defines M5 as streaming STT
and M6 as endpointing.

**STATUS:** IMPLEMENTED, with dedicated verification deferred until feature
implementation is complete, as requested.

**CHANGES:** Added a small `VoiceConversationSession` bridge. VAD start/end edges
drive `SpeechStarted`/`SpeechEnded`; Sherpa partials drive `PartialTranscript`;
only a final recognizer result drives `UserTurnCommitted`. Open turns reuse their
turn ID across VAD gaps; a committed turn's next speech receives a new ID. The
Voice Lab displays controller state and turn number. Capture stop, backgrounding,
or recorder failure sends `Cancel`, clears the open turn and returns to `IDLE`;
the next explicit Start returns the controller to `LISTENING`.

**TESTS:** No dedicated M5 test cases are retained. Existing M0-M4 tests remain
unchanged. Broader verification will happen after the planned feature work is
complete.

**RESULTS:** partial transcript remains provisional; endpointed text becomes the
committed transcript. The bridge emits no classification or answer action, and
the Voice Lab does not execute controller actions.

**KNOWN LIMITATIONS:** the controller stays in `THINKING` after commit because
turn classification, task policy, and response generation are out of scope.
No transcript history is stored. Recognition quality and natural-speech
coverage retain the M4 limitations. No next milestone is specified by the
available roadmap.

**FILES CHANGED:** pure-Kotlin conversation session bridge; Voice Lab
ViewModel/Activity/layout/strings; README and milestone reports.

**NEXT OFFICIAL MILESTONE:** M4 acceptance remains blocked on a physical audible
barge-in measurement. After M4 passes, complete M5 STT acceptance, then M6
endpointing, each separately.

## Event Mapping

```text
VAD speechStarted  -> ConversationEvent.SpeechStarted
STT partial text   -> ConversationEvent.PartialTranscript
VAD speechEnded    -> ConversationEvent.SpeechEnded
STT final text     -> ConversationEvent.UserTurnCommitted
```

`VoiceConversationSession` owns no audio or model resources. Its single
`ConversationController` is advanced synchronously from the existing frame
collector. Events retain the stable `voice-lab` thread ID and the controller's
monotonic turn ID. Sherpa partial corrections replace the provisional text; the
final endpoint commits the result. No `TurnClassified` event is sent, so the
controller's classification and answer actions remain untouched.

## Verification

No additional M5 tests or consolidated test runs are being added now. Earlier
M4 model/capture checks predate the bridge and do not verify this integration.
No classification, GLiNER inference, task execution, LLM response, cloud service,
or playback behavior was added.