# Voice Lab: Milestone 1 Conversation State Machine

Date: 2026-10-07. This follows the frozen [Milestone 0 baseline](voice-baseline.md).

## Milestone Report

**MILESTONE:** 1 - Voice Domain Model and Deterministic State Machine.

**STATUS:** PASS for the pure-Kotlin milestone acceptance gates. The pre-existing
optional external-oracle test remains skipped and is not counted as verified.

**CHANGES:** six new Kotlin domain files and one new JVM test file. No existing
Android runtime, UI, manifest, Gradle configuration, dependencies or GLiNER
behavior was changed. This document records policy, results and limits.

**TESTS:** 26 new conversation tests passed, including a 77-case state/event matrix
and 49-case state/semantic-type matrix. Complete JVM run: 48 discovered, 47 passed,
1 existing oracle skip, 0 failures/errors. Both existing native GLiNER tests passed.
Debug APK, androidTest APK and lint gates passed; lint retains 0 errors/16 existing
warnings. No tests or assertions were disabled or weakened.

**RESULTS:** synthetic speech immediately emits a pause action for active playback;
backchannel classification preserves/resumes the response; clarification preserves
the original response and cursor and emits ANSWER_THEN_RESUME; STOP/new-topic,
incomplete continuation, transcript corrections and stale events have tested policies.

**PERFORMANCE:** warmed desktop JVM transition diagnostic, 10,000 timed samples:
p50 0.209 microseconds, p95 0.667 microseconds. This is not Android, audio reaction,
native inference or physical-phone latency. Android/controller memory and battery
effects are NOT MEASURED. No model instance is created by these domain classes.

**KNOWN LIMITATIONS:** declarative actions only; no microphone, STT, TTS, VAD,
endpointing, semantic inference, generated answers, spoken clarification or voice UI.
Only one logical suspended-response slot; nested clarification is explicitly rejected.
Resume cursor is a text offset, not an audio sample or a TTS pause/resume guarantee.

**FILES CHANGED:** only the seven new domain/test files listed below and this document.

**NEXT MILESTONE:** 2 - AudioRecord capture, after explicit approval. Stop here.

## Files and Boundaries

| New file | Responsibility |
| --- | --- |
| [TurnType.kt](../app/src/main/java/dev/edgeai/prototype/voice/turn/TurnType.kt#L1) | Seven semantic types supplied by a caller or synthetic test, not inferred here. |
| [ConversationState.kt](../app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationState.kt#L1) | Seven state tags, immutable transition result and explicit event disposition. |
| [ConversationContext.kt](../app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationContext.kt#L1) | Thread/turn/playback identities, transcripts, current/suspended responses and validated resume cursor. |
| [ConversationEvent.kt](../app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationEvent.kt#L1) | Eleven typed synthetic event kinds, including playback progress. |
| [ConversationAction.kt](../app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationAction.kt#L1) | Six declarative output actions. |
| [ConversationController.kt](../app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationController.kt#L1) | Stateless synchronous `transition(context, event)` function. |
| [ConversationControllerTest.kt](../app/src/test/java/dev/edgeai/prototype/voice/conversation/ConversationControllerTest.kt#L1) | Matrices, event traces, malformed input validation, stale/duplicate tests and a labeled host timing diagnostic. |

The state tags are IDLE, LISTENING, USER_SPEAKING, THINKING, ASSISTANT_SPEAKING,
TEMPORARILY_PAUSED and INTERRUPTED. Data is held in immutable context/response
snapshots rather than in mutable Android state or the controller itself.

```text
ConversationContext + ConversationEvent
                  |
        ConversationController.transition
                  |
  next context + declarative actions + event disposition
```

There are no Android, AndroidX, audio, GLiNER, LiteRT, coroutine or network imports
in the six domain files. The source-boundary test checks this. These classes are
packaged in the existing app module, but are not connected to the text UI or any
audio engine. No shared-runtime refactoring was attempted and no additional GLiNER
copy was loaded. Existing `AiRouter` remains unchanged.

## State and Event Policy

Events are supplied by an external owner in a serialized order. The controller
has no scheduler, timers, side effects, mutable global state or hidden inference.
The owner retains the returned context and performs actions in their returned order.
For example, CANCEL followed by ANSWER means cancel old work before preparing the
new answer; the controller itself neither cancels a job nor produces speech.

### Fast Path

`ASSISTANT_SPEAKING + SpeechStarted(new turn)` -> TEMPORARILY_PAUSED,
with TEMPORARILY_PAUSE. Response text, response ID and logical resume position
are retained before any transcript or semantic classification is required.

A prepared response in THINKING is not active playback: new speech clears/cancels
that pending response and enters USER_SPEAKING with CANCEL + KEEP_LISTENING.
This distinction was caught by a failing focused test and repaired before the
full validation run.

### Transcript Path

- SpeechEnded does not finalize the transcript or invoke a classifier. It returns
  to LISTENING or remains TEMPORARILY_PAUSED, with KEEP_LISTENING.
- PartialTranscript replaces the partial text, including corrections and empty
  revisions. It never commits a turn or emits an answer action.
- UserTurnCommitted records nonblank text and closes the current input turn.
  With active/paused response it remains TEMPORARILY_PAUSED; otherwise THINKING.
- TurnClassified is accepted only for the current committed, not-yet-classified
  turn in an applicable state. It consumes an externally supplied TurnType.
- INCOMPLETE reopens that same turn, removes the prior commitment and keeps the
  original response. Subsequent speech/partials can continue with the same turn ID;
  recommit resets the semantic result so the completed request can be classified.
- No elapsed-time heuristic is present. Long-pause endpointing belongs to a later
  milestone; this state machine does not declare when a real utterance is complete.

### Semantic Actions

| TurnType after a committed interruption | Policy |
| --- | --- |
| BACKCHANNEL | Retain the current response/cursor, return to ASSISTANT_SPEAKING, emit CONTINUE. Without current playback, return to LISTENING. |
| CLARIFICATION | Preserve current response in the one suspended slot, enter INTERRUPTED, emit ANSWER_THEN_RESUME. Without an original response, treat it as ANSWER. |
| QUESTION | Cancel current/suspended response if present, enter THINKING, emit ANSWER after cancellation. No automatic return to the earlier explanation. |
| COMMAND | Same conservative replacement policy as QUESTION; this is not tool authorization or command execution. |
| NEW_TOPIC | CANCEL old work and ANSWER the new request; discard the old current/suspended response. Conversation thread ID remains the owner's stable session ID. |
| STOP | CANCEL, enter IDLE, discard current/suspended response and transcripts; retain the turn watermark and advance playback epoch. |
| INCOMPLETE | KEEP_LISTENING; preserve paused response, reopen the current input turn, do not answer. |

Unsupported state/event combinations explicitly return IGNORED_INVALID_STATE with
unchanged context and no actions. Wrong-thread/older-turn/incorrect-playback
callbacks return IGNORED_STALE, also without mutation or actions. Nested
clarification returns REJECTED_NESTED_CLARIFICATION as described below. Malformed
response/context construction fails with validation exceptions rather than being
silently accepted.

The test matrices exhaustively enumerate the defined seven state tags with the
eleven event kinds (77 representative contexts/payloads), and every state with
every semantic type (49 cases). They are not a mathematical proof over all
possible payloads or arbitrarily long event sequences; dedicated trace and
boundary tests cover additional important combinations.

## Preservation and Stale-Event Rules

The context keeps:

- Stable conversation `threadId`, monotonically advancing user `turnId`, and
  `playbackEpoch` for each playback generation.
- Current assistant response: immutable thread ID, response ID, generation turn,
  full original text and resume position.
- One suspended main response for a clarification interruption.
- Current partial/committed text and the interruption transcript.

New user turns require a larger positive turn ID. Same-ID SpeechStarted is accepted
only to continue an open unfinished turn after a pause; duplicate starts in an
inapplicable state do nothing. Matching current turn IDs are required for transcript
and semantic completion. A turn-41 STOP callback cannot change turn 42.

Playback callbacks additionally require the current response ID and playback
epoch. Speech-start advances the epoch, so a late completion from the suppressed
old playback cannot destroy the response after BACKCHANNEL emits CONTINUE.
Duplicate commitments/classifications and repeated cancellation do not reissue
their earlier actions. A cancelled unsolicited initial greeting cannot restart
the conversation through the initial-response path.

AssistantResponseReady is accepted only when an answer is actually awaited for
the current turn (or the one fresh initial introduction). It does not bypass
pending semantic classification. Synthetic TtsStarted/TtsCompleted events allow
tests to prove that a clarification response hands the saved response back with
CONTINUE. No response generation or speech implementation is hidden behind those
events.

### Resume Position

The cursor is a UTF-16 text offset in `0..text.length`, must not move backwards,
and must not split a surrogate pair. It is caller-reported logical progress.
It is not necessarily a word/grapheme boundary, an audible position or an audio
sample index. SpeechStarted uses the last reported cursor; no exact acoustic
stop position is invented. Mapping a real TTS implementation to text progress and
audible suppression must be tested in the later TTS milestones.

### Nested Interruption Policy

There is one suspended main response, not an arbitrary interruption stack.
A clarification during clarification playback is temporarily paused by the fast
path, but the second CLARIFICATION semantic result is rejected with KEEP_LISTENING.
It cannot overwrite either the saved main response or the paused clarification.
The owner can collect another turn or cancel. No second clarification answer is
created; no automatic nested spoken recovery is claimed. The explicit policy and
preservation are tested. BACKCHANNEL may continue the current clarification;
STOP or a replacement request can discard both response slots.

## Tests, Builds and Regression Evidence

Commands from the workspace root:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" hybrid-ai-m1/gradlew -p hybrid-ai-m1 \
  :app:testDebugUnitTest --tests \
  dev.edgeai.prototype.voice.conversation.ConversationControllerTest --console=plain

ANDROID_HOME="$HOME/Library/Android/sdk" hybrid-ai-m1/gradlew -p hybrid-ai-m1 \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --rerun-tasks --console=plain

adb -s emulator-5554 install -r hybrid-ai-m1/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r \
  hybrid-ai-m1/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  dev.edgeai.prototype.test/androidx.test.runner.AndroidJUnitRunner
```

| Suite | Discovered | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| ConversationControllerTest | 26 | 26 | 0 | 0 |
| TextRouterTest | 12 | 12 | 0 | 0 |
| AiRouterTest | 4 | 4 | 0 | 0 |
| GlinerViewModelTest | 3 | 3 | 0 | 0 |
| DecideDecoderTest | 3 | 2 | 1 | 0 |
| **JVM total** | **48** | **47** | **1** | **0** |
| Existing native GLiNER instrumentation | 2 | 2 | 0 | 0 |

The existing optional 361-fixture decoder oracle test is still skipped because
its external dataset is not configured. No assertion/tolerance was changed.
Instrumented regression ran on the existing ARM64 Pixel_10_Pro emulator, Android
17/API 37, with model files left in place by the direct runner. No new Android
instrumentation test was needed for pure domain logic; the existing two tests
were rerun to protect native routing.

The fresh native adapter sample still returned exactly 0.35081786 for LOCAL_CODE
and the same serialized alternative scores. One warmed emulator request measured
459.334 ms total / 453.736 ms graph, 144 tokens in s256. Timing variation versus
Milestone 0 is not evidence of improvement; the inference code is unchanged.

All 39 pre-existing source/test/configuration inputs still match the Milestone 0
fingerprint (new voice files excluded):

```text
a17f5d4f4298e100297588d8091f8dffde313f3da28069ccdc900da50746ae40
```

No new dependency, permission, native library, model asset or model copy was added.
The old UI remains a text router/calculator; there is no new user-facing voice
screen in this milestone.

## Performance and Reproducibility Limits

The timing test performs 10,000 warm-up transitions, then 10,000 individually
timed transitions interleaving seven canonical contexts and eleven event kinds.
It uses `System.nanoTime`, checks the returned thread ID so results are consumed,
and reports nearest-rank p50/p95 in the JUnit XML's `system-out`.

Observed warmed host JVM: p50 0.209 microseconds, p95 0.667 microseconds, including
the assertion. This is a coarse diagnostic, not JMH, an allocation benchmark,
Android Microbenchmark or a real-audio measurement. JVM/JIT/clock/environment
effects make sub-microsecond numbers unsuitable as product latency promises.
No timing threshold is asserted to make a noisy host sample into a correctness gate.

NOT MEASURED: physical-device controller latency/RAM/CPU/energy; state allocation
size; audio-to-action, audible TTS suppression, STT partial, endpointing, semantic
classification or resumption latency. The domain stores text snapshots only in
memory, adds no persistence or logging, and never starts models or hardware.
Cloud/API inference cost for this milestone is $0.

## Acceptance and Stop

Acceptance gates passed: pure Kotlin tests, no Android/audio/model dependency in
the domain, required interruption/preservation policies, stale-event guards,
successful APK builds/lint, and unchanged native routing regression.

This proves state-management mechanics under synthetic events. It does not prove
spoken answer-then-resume behavior, interruption detection accuracy or real-time
voice performance. AudioRecord capture, permission handling and the audio debug
screen belong to Milestone 2 and have not been started.

**Stop after Milestone 1. Await explicit approval before Milestone 2.**