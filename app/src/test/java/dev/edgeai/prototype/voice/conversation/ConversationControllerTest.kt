package dev.edgeai.prototype.voice.conversation

import dev.edgeai.prototype.voice.turn.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationControllerTest {
    private val controller = ConversationController()
    private val original = AssistantResponse("main", "original", 40,
        "Quantized models can reduce memory and computation.", 17)
    private val speaking = ConversationContext("main", ConversationState.ASSISTANT_SPEAKING,
        turnId = 40, response = original, playbackEpoch = 3)

    private fun committedInterruption(text: String): ConversationContext {
        val paused = controller.transition(speaking, ConversationEvent.SpeechStarted("main", 41)).context
        return controller.transition(paused, ConversationEvent.UserTurnCommitted("main", 41, UserTranscript(text))).context
    }

    @Test fun speechStartPausesImmediatelyWithoutClassification() {
        val result = controller.transition(speaking, ConversationEvent.SpeechStarted("main", 41))
        assertEquals(ConversationState.TEMPORARILY_PAUSED, result.context.state)
        assertEquals(listOf(ConversationAction.TEMPORARILY_PAUSE), result.actions)
        assertEquals(original, result.context.response)
        assertNull(result.context.turnType)
        assertNull(result.context.committedTranscript)
    }

    @Test fun backchannelResumesOriginalResponseAndPosition() {
        val result = controller.transition(committedInterruption("yeah"),
            ConversationEvent.TurnClassified("main", 41, TurnType.BACKCHANNEL))
        assertEquals(ConversationState.ASSISTANT_SPEAKING, result.context.state)
        assertEquals(listOf(ConversationAction.CONTINUE), result.actions)
        assertEquals(original, result.context.response)
    }

    @Test fun clarificationPreservesResponseThreadCursorAndTranscript() {
        val text = "What does quantized mean?"
        val result = controller.transition(committedInterruption(text),
            ConversationEvent.TurnClassified("main", 41, TurnType.CLARIFICATION))
        assertEquals(ConversationState.INTERRUPTED, result.context.state)
        assertEquals(listOf(ConversationAction.ANSWER_THEN_RESUME), result.actions)
        assertEquals(original, result.context.suspendedResponse)
        assertNull(result.context.response)
        assertEquals("main", result.context.threadId)
        assertEquals(text, result.context.interruptionTranscript)
    }

    @Test fun turn41CannotClassifyTurn42() {
        val turn41 = committedInterruption("What is quantization?")
        val turn42 = controller.transition(turn41, ConversationEvent.SpeechStarted("main", 42)).context
        val late = controller.transition(turn42,
            ConversationEvent.TurnClassified("main", 41, TurnType.STOP))
        assertEquals(EventDisposition.IGNORED_STALE, late.disposition)
        assertEquals(turn42, late.context)
        assertEquals(emptyList<ConversationAction>(), late.actions)
    }

    @Test fun stopCancelsAndDiscardsBothResponseSlots() {
        val result = controller.transition(committedInterruption("stop"),
            ConversationEvent.TurnClassified("main", 41, TurnType.STOP))
        assertEquals(ConversationState.IDLE, result.context.state)
        assertEquals(listOf(ConversationAction.CANCEL), result.actions)
        assertNull(result.context.response)
        assertNull(result.context.suspendedResponse)
        assertEquals(result.context, controller.transition(result.context, ConversationEvent.Cancel).context)
        assertEquals(emptyList<ConversationAction>(),
            controller.transition(result.context, ConversationEvent.Cancel).actions)
    }

    @Test fun newTopicCancelsOldExplanationBeforeAnswering() {
        val result = controller.transition(committedInterruption("Actually explain Kotlin"),
            ConversationEvent.TurnClassified("main", 41, TurnType.NEW_TOPIC))
        assertEquals(ConversationState.THINKING, result.context.state)
        assertEquals(listOf(ConversationAction.CANCEL, ConversationAction.ANSWER), result.actions)
        assertNull(result.context.response)
        assertNull(result.context.suspendedResponse)
        assertEquals("Actually explain Kotlin", result.context.committedTranscript)
    }

    @Test fun incompleteTurnKeepsListeningAndCanContinueWithTheSameId() {
        val incomplete = controller.transition(committedInterruption("but what if"),
            ConversationEvent.TurnClassified("main", 41, TurnType.INCOMPLETE))
        assertEquals(listOf(ConversationAction.KEEP_LISTENING), incomplete.actions)
        assertEquals(original, incomplete.context.response)
        val resumed = controller.transition(incomplete.context,
            ConversationEvent.SpeechStarted("main", 41))
        assertEquals(EventDisposition.ACCEPTED, resumed.disposition)
        val corrected = controller.transition(resumed.context,
            ConversationEvent.UserTurnCommitted("main", 41, UserTranscript("but what if the model is too large?")))
        val classified = controller.transition(corrected.context,
            ConversationEvent.TurnClassified("main", 41, TurnType.QUESTION))
        assertEquals(listOf(ConversationAction.CANCEL, ConversationAction.ANSWER), classified.actions)
    }

    @Test fun lateCompletionCannotConsumeThePausedExplanation() {
        val paused = controller.transition(speaking, ConversationEvent.SpeechStarted("main", 41)).context
        val late = controller.transition(paused, ConversationEvent.TtsCompleted("original", 3))
        assertEquals(EventDisposition.IGNORED_STALE, late.disposition)
        assertEquals(paused, late.context)
    }

    @Test fun falseInterruptionResumesPreservedAssistantResponse() {
        val paused = controller.transition(speaking, ConversationEvent.SpeechStarted("main", 41)).context
        val falseInterruption = controller.transition(paused,
            ConversationEvent.FalseInterruptionDetected("main", 41))

        assertEquals(ConversationState.ASSISTANT_SPEAKING, falseInterruption.context.state)
        assertEquals(listOf(ConversationAction.CONTINUE), falseInterruption.actions)
        assertEquals(original, falseInterruption.context.response)
        assertEquals(41, falseInterruption.context.turnId)
        assertEquals("", falseInterruption.context.partialTranscript)
    }

    @Test fun demoPassageCanBeSpokenAgainAfterVADTemporarilyPausesPreviousPassage() {
        val paused = controller.transition(speaking,
            ConversationEvent.SpeechStarted("main", 41)).context
        val replacement = AssistantResponse("main", "demo-2", 41, "Test passage again.")

        val ready = controller.transition(paused, ConversationEvent.AssistantDemoResponseReady(replacement))
        assertEquals(EventDisposition.ACCEPTED, ready.disposition)
        assertEquals(ConversationState.THINKING, ready.context.state)
        assertEquals(replacement, ready.context.response)
        assertNull(ready.context.suspendedResponse)
        assertEquals(0, ready.context.response?.resumePosition)

        val started = controller.transition(ready.context,
            ConversationEvent.TtsStarted(replacement.responseId, ready.context.playbackEpoch))
        assertEquals(ConversationState.ASSISTANT_SPEAKING, started.context.state)
        assertEquals(EventDisposition.IGNORED_STALE,
            controller.transition(started.context,
                ConversationEvent.TtsCompleted(original.responseId, speaking.playbackEpoch)).disposition)
    }

    @Test fun preparedButUnspokenResponseIsCancelledRatherThanPaused() {
        val prepared = speaking.copy(state = ConversationState.THINKING)
        val result = controller.transition(prepared, ConversationEvent.SpeechStarted("main", 41))
        assertEquals(ConversationState.USER_SPEAKING, result.context.state)
        assertEquals(listOf(ConversationAction.CANCEL, ConversationAction.KEEP_LISTENING), result.actions)
        assertNull(result.context.response)
    }

    @Test fun progressCannotMoveCursorBackwardsOrSplitASurrogatePair() {
        val unicode = AssistantResponse("main", "unicode", 40, "A\ud83d\ude00B", 1)
        val context = speaking.copy(response = unicode)
        val invalid = controller.transition(context, ConversationEvent.PlaybackProgress("unicode", 3, 2))
        assertEquals(EventDisposition.IGNORED_INVALID_STATE, invalid.disposition)
        assertEquals(context, invalid.context)
        val valid = controller.transition(context, ConversationEvent.PlaybackProgress("unicode", 3, 3))
        assertEquals(3, valid.context.response?.resumePosition)
        val backwards = controller.transition(valid.context,
            ConversationEvent.PlaybackProgress("unicode", 3, 1))
        assertEquals(valid.context, backwards.context)
    }

    @Test fun partialCorrectionsReplaceTextAndDoNotClassify() {
        val paused = controller.transition(speaking, ConversationEvent.SpeechStarted("main", 41)).context
        val first = controller.transition(paused, ConversationEvent.PartialTranscript("main", 41, UserTranscript("what is gli"))).context
        val corrected = controller.transition(first, ConversationEvent.PartialTranscript("main", 41, UserTranscript("what is GLiNER")))
        assertEquals("what is GLiNER", corrected.context.partialTranscript)
        assertNull(corrected.context.turnType)
        assertEquals(emptyList<ConversationAction>(), corrected.actions)
        assertEquals(original, corrected.context.response)
    }

    @Test fun speechEndDoesNotCommitOrClassify() {
        val started = controller.transition(ConversationContext("main", ConversationState.LISTENING),
            ConversationEvent.SpeechStarted("main", 1)).context
        val ended = controller.transition(started, ConversationEvent.SpeechEnded("main", 1))
        assertEquals(ConversationState.LISTENING, ended.context.state)
        assertNull(ended.context.committedTranscript)
        assertNull(ended.context.turnType)
        assertTrue(ended.context.userTurnOpen)
    }

    @Test fun everyStateAndEventKindHasAnExplicitPolicy() {
        val idle = ConversationState.IDLE
        val listening = ConversationState.LISTENING
        val user = ConversationState.USER_SPEAKING
        val thinking = ConversationState.THINKING
        val assistant = ConversationState.ASSISTANT_SPEAKING
        val paused = ConversationState.TEMPORARILY_PAUSED
        val interrupted = ConversationState.INTERRUPTED
        val keep = listOf(ConversationAction.KEEP_LISTENING)
        val cancel = listOf(ConversationAction.CANCEL)
        val pause = listOf(ConversationAction.TEMPORARILY_PAUSE)
        val accepted = EventDisposition.ACCEPTED
        val invalid = EventDisposition.IGNORED_INVALID_STATE
        val stale = EventDisposition.IGNORED_STALE
        val expected = mapOf(
            idle to listOf(
                Expected(listening, accepted, keep), Expected(idle, invalid), Expected(idle, invalid),
                Expected(idle, invalid), Expected(idle, invalid), Expected(idle, invalid), Expected(idle, invalid),
                Expected(idle, stale), Expected(idle, stale), Expected(idle, stale), Expected(idle, invalid)),
            listening to listOf(
                Expected(listening, invalid), Expected(user, accepted, keep), Expected(listening, accepted, keep),
                Expected(listening), Expected(thinking), Expected(listening, invalid), Expected(listening, invalid),
                Expected(listening, stale), Expected(listening, stale), Expected(listening, stale), Expected(idle, accepted, cancel)),
            user to listOf(
                Expected(user, invalid), Expected(user, accepted, keep), Expected(listening, accepted, keep),
                Expected(user), Expected(thinking), Expected(user, invalid), Expected(user, invalid),
                Expected(user, stale), Expected(user, stale), Expected(user, stale), Expected(idle, accepted, cancel)),
            thinking to listOf(
                Expected(thinking, invalid), Expected(user, accepted, cancel + keep), Expected(thinking, invalid),
                Expected(thinking, invalid), Expected(thinking, invalid), Expected(listening, accepted, keep), Expected(thinking, invalid),
                Expected(thinking, stale), Expected(thinking, stale), Expected(thinking, stale), Expected(idle, accepted, cancel)),
            assistant to listOf(
                Expected(assistant, invalid), Expected(paused, accepted, pause), Expected(assistant, invalid),
                Expected(assistant, invalid), Expected(assistant, invalid), Expected(assistant, invalid), Expected(assistant, invalid),
                Expected(assistant), Expected(listening, accepted, keep), Expected(assistant), Expected(idle, accepted, cancel)),
            paused to listOf(
                Expected(paused, invalid), Expected(paused, accepted, pause), Expected(paused, accepted, keep),
                Expected(paused), Expected(paused), Expected(paused, invalid), Expected(paused, invalid),
                Expected(paused, invalid), Expected(paused, invalid), Expected(paused, invalid), Expected(idle, accepted, cancel)),
            interrupted to listOf(
                Expected(interrupted, invalid), Expected(user, accepted, cancel + keep), Expected(interrupted, invalid),
                Expected(interrupted, invalid), Expected(interrupted, invalid), Expected(interrupted, invalid), Expected(thinking),
                Expected(interrupted, stale), Expected(interrupted, stale), Expected(interrupted, stale), Expected(idle, accepted, cancel))
        )
        var cases = 0
        canonicalStates().forEach { (state, context) ->
            matrixEvents().forEachIndexed { index, event ->
                val result = controller.transition(context, event)
                val wanted = expected.getValue(state)[index]
                val message = "$state / ${event::class.simpleName}"
                assertEquals(message, wanted.state, result.context.state)
                assertEquals(message, wanted.disposition, result.disposition)
                assertEquals(message, wanted.actions, result.actions)
                assertEquals(message, "main", result.context.threadId)
                if (wanted.disposition != accepted) assertEquals(message, context, result.context)
                cases++
            }
        }
        assertEquals(77, cases)
    }

    @Test fun everyTurnTypeIsCoveredInEveryState() {
        val noPlayback = mapOf(
            TurnType.BACKCHANNEL to Expected(ConversationState.LISTENING, actions = listOf(ConversationAction.KEEP_LISTENING)),
            TurnType.CLARIFICATION to Expected(ConversationState.THINKING, actions = listOf(ConversationAction.ANSWER)),
            TurnType.QUESTION to Expected(ConversationState.THINKING, actions = listOf(ConversationAction.ANSWER)),
            TurnType.COMMAND to Expected(ConversationState.THINKING, actions = listOf(ConversationAction.ANSWER)),
            TurnType.NEW_TOPIC to Expected(ConversationState.THINKING, actions = listOf(ConversationAction.CANCEL, ConversationAction.ANSWER)),
            TurnType.STOP to Expected(ConversationState.IDLE, actions = listOf(ConversationAction.CANCEL)),
            TurnType.INCOMPLETE to Expected(ConversationState.LISTENING, actions = listOf(ConversationAction.KEEP_LISTENING))
        )
        val withPlayback = mapOf(
            TurnType.BACKCHANNEL to Expected(ConversationState.ASSISTANT_SPEAKING, actions = listOf(ConversationAction.CONTINUE)),
            TurnType.CLARIFICATION to Expected(ConversationState.INTERRUPTED, actions = listOf(ConversationAction.ANSWER_THEN_RESUME)),
            TurnType.QUESTION to Expected(ConversationState.THINKING, actions = listOf(ConversationAction.CANCEL, ConversationAction.ANSWER)),
            TurnType.COMMAND to Expected(ConversationState.THINKING, actions = listOf(ConversationAction.CANCEL, ConversationAction.ANSWER)),
            TurnType.NEW_TOPIC to Expected(ConversationState.THINKING, actions = listOf(ConversationAction.CANCEL, ConversationAction.ANSWER)),
            TurnType.STOP to Expected(ConversationState.IDLE, actions = listOf(ConversationAction.CANCEL)),
            TurnType.INCOMPLETE to Expected(ConversationState.TEMPORARILY_PAUSED, actions = listOf(ConversationAction.KEEP_LISTENING))
        )
        var cases = 0
        canonicalStates().forEach { (state, sample) ->
            val context = if (state == ConversationState.TEMPORARILY_PAUSED) {
                controller.transition(sample, ConversationEvent.UserTurnCommitted("main", 41, UserTranscript("turn text"))).context
            } else sample
            TurnType.entries.forEach { type ->
                val wanted = when (state) {
                    ConversationState.THINKING -> noPlayback.getValue(type)
                    ConversationState.TEMPORARILY_PAUSED -> withPlayback.getValue(type)
                    else -> Expected(state, EventDisposition.IGNORED_INVALID_STATE)
                }
                val result = controller.transition(context, ConversationEvent.TurnClassified("main", 41, type))
                val message = "$state / $type"
                assertEquals(message, wanted.state, result.context.state)
                assertEquals(message, wanted.disposition, result.disposition)
                assertEquals(message, wanted.actions, result.actions)
                if (state == ConversationState.TEMPORARILY_PAUSED) {
                    when (type) {
                        TurnType.BACKCHANNEL, TurnType.INCOMPLETE -> assertEquals(original, result.context.response)
                        TurnType.CLARIFICATION -> assertEquals(original, result.context.suspendedResponse)
                        else -> {
                            assertNull(result.context.response)
                            assertNull(result.context.suspendedResponse)
                        }
                    }
                }
                cases++
            }
        }
        assertEquals(49, cases)
    }

    @Test fun syntheticClarificationCompletionRestoresOriginalCursor() {
        val interrupted = controller.transition(committedInterruption("What does quantized mean?"),
            ConversationEvent.TurnClassified("main", 41, TurnType.CLARIFICATION)).context
        val answer = AssistantResponse("main", "clarification", 41, "Quantization reduces precision.")
        val ready = controller.transition(interrupted, ConversationEvent.AssistantResponseReady(answer)).context
        val started = controller.transition(ready, ConversationEvent.TtsStarted(answer.responseId, ready.playbackEpoch)).context
        val result = controller.transition(started, ConversationEvent.TtsCompleted(answer.responseId, started.playbackEpoch))
        assertEquals(listOf(ConversationAction.CONTINUE), result.actions)
        assertEquals(original, result.context.response)
        assertNull(result.context.suspendedResponse)
        assertEquals(ConversationState.ASSISTANT_SPEAKING, result.context.state)
        assertEquals("What does quantized mean?", result.context.interruptionTranscript)
        assertIgnored(result.context, ConversationEvent.TtsCompleted(answer.responseId, started.playbackEpoch),
            EventDisposition.IGNORED_STALE)
    }

    @Test fun latePlaybackCompletionCannotConsumeBackchannelResume() {
        val resumed = controller.transition(committedInterruption("yeah"),
            ConversationEvent.TurnClassified("main", 41, TurnType.BACKCHANNEL)).context
        assertIgnored(resumed, ConversationEvent.TtsCompleted(original.responseId, speaking.playbackEpoch),
            EventDisposition.IGNORED_STALE)
        assertEquals(original, resumed.response)
    }

    @Test fun nestedClarificationIsExplicitlyRejectedWithoutOverwritingMainResponse() {
        val first = controller.transition(committedInterruption("What does quantized mean?"),
            ConversationEvent.TurnClassified("main", 41, TurnType.CLARIFICATION)).context
        val answer = AssistantResponse("main", "clarification", 41, "Quantization reduces precision.")
        val ready = controller.transition(first, ConversationEvent.AssistantResponseReady(answer)).context
        val speakingAnswer = controller.transition(ready, ConversationEvent.TtsStarted(answer.responseId, ready.playbackEpoch)).context
        val paused = controller.transition(speakingAnswer, ConversationEvent.SpeechStarted("main", 42)).context
        val committed = controller.transition(paused, ConversationEvent.UserTurnCommitted("main", 42, UserTranscript("What is precision?"))).context
        val result = controller.transition(committed, ConversationEvent.TurnClassified("main", 42, TurnType.CLARIFICATION))
        assertEquals(EventDisposition.REJECTED_NESTED_CLARIFICATION, result.disposition)
        assertEquals(listOf(ConversationAction.KEEP_LISTENING), result.actions)
        assertEquals(original, result.context.suspendedResponse)
        assertEquals(answer, result.context.response)
    }

    @Test fun responseIsAcceptedOnlyAfterClassificationForTheCurrentTurn() {
        val pending = canonicalStates().getValue(ConversationState.THINKING)
        val answer = AssistantResponse("main", "answer", 41, "New answer.")
        assertIgnored(pending, ConversationEvent.AssistantResponseReady(answer), EventDisposition.IGNORED_INVALID_STATE)
        val demo = controller.transition(pending, ConversationEvent.AssistantDemoResponseReady(answer))
        assertEquals(EventDisposition.ACCEPTED, demo.disposition)
        assertEquals(answer, demo.context.response)
        val classified = controller.transition(pending, ConversationEvent.TurnClassified("main", 41, TurnType.QUESTION)).context
        val ready = controller.transition(classified, ConversationEvent.AssistantResponseReady(answer)).context
        assertEquals(answer, ready.response)
        assertIgnored(ready, ConversationEvent.AssistantResponseReady(answer), EventDisposition.IGNORED_INVALID_STATE)
        val newer = controller.transition(classified, ConversationEvent.SpeechStarted("main", 42)).context
        assertIgnored(newer, ConversationEvent.AssistantResponseReady(answer), EventDisposition.IGNORED_STALE)
    }

    @Test fun duplicateCommitAndClassificationDoNotRepeatActions() {
        val committed = committedInterruption("yeah")
        assertIgnored(committed, ConversationEvent.UserTurnCommitted("main", 41, UserTranscript("corrected")),
            EventDisposition.IGNORED_INVALID_STATE)
        val event = ConversationEvent.TurnClassified("main", 41, TurnType.BACKCHANNEL)
        val resumed = controller.transition(committed, event).context
        assertIgnored(resumed, event, EventDisposition.IGNORED_INVALID_STATE)
    }

    @Test fun staleAndForeignThreadEventsCannotChangeAnyState() {
        canonicalStates().values.forEach { context ->
            listOf(
                ConversationEvent.SpeechStarted("main", 39),
                ConversationEvent.SpeechEnded("main", 39),
                ConversationEvent.PartialTranscript("main", 39, UserTranscript("stale")),
                ConversationEvent.UserTurnCommitted("main", 39, UserTranscript("stale")),
                ConversationEvent.TurnClassified("main", 39, TurnType.STOP),
                ConversationEvent.SpeechStarted("foreign", 42),
                ConversationEvent.TurnClassified("foreign", 41, TurnType.STOP),
                ConversationEvent.AssistantResponseReady(AssistantResponse("foreign", "answer", 41, "Other answer."))
            ).forEach { assertIgnored(context, it, EventDisposition.IGNORED_STALE) }
        }
    }

    @Test fun repeatedCancelPreservesTurnWatermarkAndCannotResumeOldWork() {
        canonicalStates().values.forEach { context ->
            val cancelled = controller.transition(context, ConversationEvent.Cancel).context
            assertEquals(ConversationState.IDLE, cancelled.state)
            assertEquals(context.turnId, cancelled.turnId)
            assertIgnored(cancelled, ConversationEvent.Cancel, EventDisposition.IGNORED_INVALID_STATE)
            val listening = controller.transition(cancelled, ConversationEvent.StartListening).context
            assertIgnored(listening, ConversationEvent.SpeechStarted("main", 39), EventDisposition.IGNORED_STALE)
        }
    }

    @Test fun responseAndContextValidationRejectMalformedData() {
        expectInvalid { AssistantResponse("", "response", 1, "text") }
        expectInvalid { AssistantResponse("main", "response", -1, "text") }
        expectInvalid { AssistantResponse("main", "response", 1, " ") }
        expectInvalid { AssistantResponse("main", "response", 1, "text", 5) }
        expectInvalid { AssistantResponse("main", "response", 1, "A\uD83D\uDE00B", 2) }
        expectInvalid { ConversationContext("main", ConversationState.ASSISTANT_SPEAKING) }
        expectInvalid { ConversationContext("main", ConversationState.INTERRUPTED) }
        expectInvalid { ConversationContext("main", turnId = 1, response = original) }
        expectInvalid { ConversationContext("foreign", turnId = 40, response = original) }
        expectInvalid { ConversationContext("main", turnId = 1, userTurnOpen = true, committedTranscript = "text") }
        expectInvalid { ConversationEvent.UserTurnCommitted("main", 1, UserTranscript(" ")) }
    }

    @Test fun initialAssistantResponseAndNormalPlaybackCompletionAreSupported() {
        val listening = controller.transition(ConversationContext("main"), ConversationEvent.StartListening).context
        val intro = AssistantResponse("main", "intro", 0, "Welcome.")
        val ready = controller.transition(listening, ConversationEvent.AssistantResponseReady(intro)).context
        val started = controller.transition(ready, ConversationEvent.TtsStarted("intro", ready.playbackEpoch)).context
        val completed = controller.transition(started, ConversationEvent.TtsCompleted("intro", started.playbackEpoch))
        assertEquals(ConversationState.LISTENING, completed.context.state)
        assertEquals(listOf(ConversationAction.KEEP_LISTENING), completed.actions)
        assertNull(completed.context.response)
    }

    @Test fun cancelledInitialResponseCannotRestartAfterListeningResumes() {
        val listening = controller.transition(ConversationContext("main"), ConversationEvent.StartListening).context
        val cancelled = controller.transition(listening, ConversationEvent.Cancel).context
        val restarted = controller.transition(cancelled, ConversationEvent.StartListening).context
        val oldIntro = AssistantResponse("main", "old-intro", 0, "Old greeting.")
        assertIgnored(restarted, ConversationEvent.AssistantResponseReady(oldIntro), EventDisposition.IGNORED_INVALID_STATE)
    }

    @Test fun domainSourcesDoNotImportAndroidAudioModelsOrCoroutines() {
        val project = java.io.File(requireNotNull(System.getProperty("gliner.moduleRoot")))
        val root = java.io.File(project, "app/src/main/java/dev/edgeai/prototype/voice")
        val sources = listOf(java.io.File(root, "conversation"), java.io.File(root, "turn"))
            .flatMap { folder -> folder.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertEquals(9, sources.size)
        sources.forEach { source ->
            val imports = source.readLines().filter { it.startsWith("import ") }
            assertTrue(source.name, imports.all { it == "import dev.edgeai.prototype.voice.turn.TurnType" })
        }
    }

    @Test fun reportsHostJvmTransitionTimingWithoutVoicePerformanceClaims() {
        val contexts = canonicalStates().values.toList()
        val stimuli = matrixEvents()
        repeat(10_000) { iteration ->
            controller.transition(contexts[iteration % contexts.size], stimuli[iteration % stimuli.size])
        }
        val samples = LongArray(10_000) { iteration ->
            val started = System.nanoTime()
            val result = controller.transition(contexts[iteration % contexts.size], stimuli[iteration % stimuli.size])
            assertEquals("main", result.context.threadId)
            System.nanoTime() - started
        }.sorted()
        println("CONVERSATION_HOST_JVM samples=${samples.size} p50_us=${samples[4999] / 1000.0} " +
            "p95_us=${samples[9499] / 1000.0}; includes assertion, not Android or voice latency")
    }

    private data class Expected(
        val state: ConversationState,
        val disposition: EventDisposition = EventDisposition.ACCEPTED,
        val actions: List<ConversationAction> = emptyList()
    )

    private fun canonicalStates(): Map<ConversationState, ConversationContext> = linkedMapOf(
        ConversationState.IDLE to ConversationContext("main", turnId = 41, playbackEpoch = 4),
        ConversationState.LISTENING to ConversationContext("main", ConversationState.LISTENING,
            41, userTurnOpen = true, partialTranscript = "wait", playbackEpoch = 4),
        ConversationState.USER_SPEAKING to ConversationContext("main", ConversationState.USER_SPEAKING,
            41, userTurnOpen = true, partialTranscript = "wait", playbackEpoch = 4),
        ConversationState.THINKING to ConversationContext("main", ConversationState.THINKING,
            41, committedTranscript = "explain", playbackEpoch = 4),
        ConversationState.ASSISTANT_SPEAKING to speaking.copy(turnId = 41, playbackEpoch = 4),
        ConversationState.TEMPORARILY_PAUSED to ConversationContext("main", ConversationState.TEMPORARILY_PAUSED,
            41, userTurnOpen = true, partialTranscript = "wait", response = original, playbackEpoch = 4),
        ConversationState.INTERRUPTED to ConversationContext("main", ConversationState.INTERRUPTED,
            41, committedTranscript = "why", turnType = TurnType.CLARIFICATION,
            suspendedResponse = original, playbackEpoch = 4)
    )

    private fun matrixEvents(): List<ConversationEvent> = listOf(
        ConversationEvent.StartListening,
        ConversationEvent.SpeechStarted("main", 42),
        ConversationEvent.SpeechEnded("main", 41),
        ConversationEvent.PartialTranscript("main", 41, UserTranscript("what")),
        ConversationEvent.UserTurnCommitted("main", 41, UserTranscript("what")),
        ConversationEvent.TurnClassified("main", 41, TurnType.BACKCHANNEL),
        ConversationEvent.AssistantResponseReady(AssistantResponse("main", "new", 41, "New answer.")),
        ConversationEvent.TtsStarted("original", 4),
        ConversationEvent.TtsCompleted("original", 4),
        ConversationEvent.PlaybackProgress("original", 4, 18),
        ConversationEvent.Cancel
    )

    private fun assertIgnored(context: ConversationContext, event: ConversationEvent,
        disposition: EventDisposition) {
        val result = controller.transition(context, event)
        assertEquals(disposition, result.disposition)
        assertEquals(context, result.context)
        assertTrue(result.actions.isEmpty())
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            return
        }
    }
}