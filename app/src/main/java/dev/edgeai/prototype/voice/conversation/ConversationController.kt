package dev.edgeai.prototype.voice.conversation

import dev.edgeai.prototype.voice.turn.TurnType

class ConversationController {
    fun transition(context: ConversationContext, event: ConversationEvent): ConversationTransition =
        when (event) {
            ConversationEvent.StartListening -> if (context.state == ConversationState.IDLE) {
                ConversationTransition(context.copy(state = ConversationState.LISTENING),
                    listOf(ConversationAction.KEEP_LISTENING))
            } else ignored(context)
            ConversationEvent.Cancel -> if (context.state == ConversationState.IDLE) {
                ignored(context)
            } else ConversationTransition(reset(context, ConversationState.IDLE),
                listOf(ConversationAction.CANCEL))
            is ConversationEvent.SpeechStarted -> speechStarted(context, event)
            is ConversationEvent.SpeechEnded -> withTurn(context, event.threadId, event.turnId) {
                if (!context.userTurnOpen) ignored(context) else ConversationTransition(
                    context.copy(state = if (context.response != null) {
                        ConversationState.TEMPORARILY_PAUSED
                    } else ConversationState.LISTENING), listOf(ConversationAction.KEEP_LISTENING))
            }
            is ConversationEvent.PartialTranscript -> withTurn(context, event.threadId, event.turnId) {
                if (!context.userTurnOpen) ignored(context) else ConversationTransition(
                    context.copy(partialTranscript = event.transcript.text,
                        interruptionTranscript = if (context.response != null || context.suspendedResponse != null) {
                            event.transcript.text
                        } else context.interruptionTranscript))
            }
            is ConversationEvent.UserTurnCommitted -> withTurn(context, event.threadId, event.turnId) {
                if (!context.userTurnOpen) ignored(context) else ConversationTransition(context.copy(
                    userTurnOpen = false,
                    committedTranscript = event.transcript.text,
                    partialTranscript = event.transcript.text,
                    turnType = null,
                    interruptionTranscript = if (context.response != null || context.suspendedResponse != null) {
                        event.transcript.text
                    } else context.interruptionTranscript,
                    state = if (context.response != null) ConversationState.TEMPORARILY_PAUSED
                        else ConversationState.THINKING
                ))
            }
            is ConversationEvent.FalseInterruptionDetected -> withTurn(context, event.threadId, event.turnId) {
                if (context.state != ConversationState.TEMPORARILY_PAUSED || context.response == null) {
                    ignored(context)
                } else ConversationTransition(context.copy(
                    state = ConversationState.ASSISTANT_SPEAKING,
                    userTurnOpen = false,
                    partialTranscript = "",
                    committedTranscript = null,
                    turnType = null,
                    interruptionTranscript = "",
                    playbackEpoch = context.playbackEpoch + 1
                ), listOf(ConversationAction.CONTINUE))
            }
            is ConversationEvent.TurnClassified -> withTurn(context, event.threadId, event.turnId) {
                classify(context, event.type)
            }
            is ConversationEvent.AssistantResponseReady -> responseReady(context, event.response)
            is ConversationEvent.AssistantDemoResponseReady ->
                demoResponseReady(context, event.response)
            is ConversationEvent.TtsStarted -> withPlayback(context, event.responseId, event.playbackEpoch) {
                if (context.state !in setOf(ConversationState.THINKING, ConversationState.ASSISTANT_SPEAKING)) {
                    ignored(context)
                } else ConversationTransition(context.copy(state = ConversationState.ASSISTANT_SPEAKING))
            }
            is ConversationEvent.TtsCompleted -> withPlayback(context, event.responseId, event.playbackEpoch) {
                if (context.state != ConversationState.ASSISTANT_SPEAKING) ignored(context)
                else if (context.suspendedResponse != null) ConversationTransition(context.copy(
                    response = context.suspendedResponse, suspendedResponse = null,
                    playbackEpoch = context.playbackEpoch + 1), listOf(ConversationAction.CONTINUE))
                else ConversationTransition(context.copy(state = ConversationState.LISTENING,
                    response = null, playbackEpoch = context.playbackEpoch + 1),
                    listOf(ConversationAction.KEEP_LISTENING))
            }
            is ConversationEvent.PlaybackProgress -> withPlayback(context, event.responseId, event.playbackEpoch) {
                val response = requireNotNull(context.response)
                val position = event.resumePosition
                val splitsSurrogate = position in 1 until response.text.length &&
                    response.text[position - 1].isHighSurrogate() && response.text[position].isLowSurrogate()
                if (context.state != ConversationState.ASSISTANT_SPEAKING ||
                    position !in response.resumePosition..response.text.length || splitsSurrogate) {
                    ignored(context)
                } else ConversationTransition(context.copy(response = response.copy(resumePosition = event.resumePosition)))
            }
        }

    private fun speechStarted(context: ConversationContext,
        event: ConversationEvent.SpeechStarted): ConversationTransition {
        if (event.threadId != context.threadId || event.turnId < context.turnId || event.turnId <= 0) {
            return ignored(context, EventDisposition.IGNORED_STALE)
        }
        if (context.state == ConversationState.IDLE) return ignored(context)
        if (event.turnId == context.turnId) {
            if (!context.userTurnOpen || context.state !in setOf(ConversationState.LISTENING,
                ConversationState.TEMPORARILY_PAUSED)) return ignored(context)
            return ConversationTransition(context.copy(state = if (context.response != null) {
                ConversationState.TEMPORARILY_PAUSED
            } else ConversationState.USER_SPEAKING), listOf(ConversationAction.KEEP_LISTENING))
        }
        val hasPlayback = context.response != null && context.state in
            setOf(ConversationState.ASSISTANT_SPEAKING, ConversationState.TEMPORARILY_PAUSED)
        val next = context.copy(turnId = event.turnId, userTurnOpen = true,
            partialTranscript = "", committedTranscript = null, interruptionTranscript = "", turnType = null,
            response = if (hasPlayback) context.response else null,
            state = if (hasPlayback) ConversationState.TEMPORARILY_PAUSED
                else ConversationState.USER_SPEAKING,
            playbackEpoch = context.playbackEpoch + 1)
        return ConversationTransition(next, if (hasPlayback) {
            listOf(ConversationAction.TEMPORARILY_PAUSE)
        } else if (context.state in setOf(ConversationState.THINKING, ConversationState.INTERRUPTED)) {
            listOf(ConversationAction.CANCEL, ConversationAction.KEEP_LISTENING)
        } else listOf(ConversationAction.KEEP_LISTENING))
    }

    private fun classify(context: ConversationContext, type: TurnType): ConversationTransition {
        if (context.committedTranscript == null || context.turnType != null ||
            context.state !in setOf(ConversationState.THINKING, ConversationState.TEMPORARILY_PAUSED)) {
            return ignored(context)
        }
        val classified = context.copy(turnType = type)
        return when (type) {
            TurnType.BACKCHANNEL -> if (context.response != null) {
                ConversationTransition(classified.copy(state = ConversationState.ASSISTANT_SPEAKING),
                    listOf(ConversationAction.CONTINUE))
            } else ConversationTransition(classified.copy(state = ConversationState.LISTENING),
                listOf(ConversationAction.KEEP_LISTENING))
            TurnType.CLARIFICATION -> if (context.suspendedResponse != null) {
                ConversationTransition(classified, listOf(ConversationAction.KEEP_LISTENING),
                    EventDisposition.REJECTED_NESTED_CLARIFICATION)
            } else if (context.response != null) {
                ConversationTransition(classified.copy(state = ConversationState.INTERRUPTED,
                    response = null, suspendedResponse = context.response),
                    listOf(ConversationAction.ANSWER_THEN_RESUME))
            } else ConversationTransition(classified.copy(state = ConversationState.THINKING),
                listOf(ConversationAction.ANSWER))
            TurnType.STOP -> ConversationTransition(reset(classified, ConversationState.IDLE),
                listOf(ConversationAction.CANCEL))
            TurnType.NEW_TOPIC, TurnType.QUESTION, TurnType.COMMAND -> {
                val actions = if (context.response != null || context.suspendedResponse != null || type == TurnType.NEW_TOPIC) {
                    listOf(ConversationAction.CANCEL, ConversationAction.ANSWER)
                } else listOf(ConversationAction.ANSWER)
                ConversationTransition(classified.copy(state = ConversationState.THINKING,
                    response = null, suspendedResponse = null, playbackEpoch = context.playbackEpoch + 1), actions)
            }
            TurnType.INCOMPLETE -> ConversationTransition(classified.copy(
                userTurnOpen = true, committedTranscript = null,
                state = if (context.response != null) ConversationState.TEMPORARILY_PAUSED
                    else ConversationState.LISTENING), listOf(ConversationAction.KEEP_LISTENING))
        }
    }

    private fun responseReady(
        context: ConversationContext,
        response: AssistantResponse,
        allowUnclassifiedTurn: Boolean = false
    ): ConversationTransition {
        if (response.threadId != context.threadId || response.turnId != context.turnId) {
            return ignored(context, EventDisposition.IGNORED_STALE)
        }
        val introduction = context.state == ConversationState.LISTENING && context.turnId == 0L &&
            context.playbackEpoch == 0L
        val awaitingAnswer = context.state == ConversationState.THINKING && context.turnType in
            setOf(TurnType.QUESTION, TurnType.COMMAND, TurnType.NEW_TOPIC, TurnType.CLARIFICATION)
        val awaitingClarification = context.state == ConversationState.INTERRUPTED &&
            context.turnType == TurnType.CLARIFICATION
        val awaitingUnclassifiedTranscript = allowUnclassifiedTurn &&
            context.state == ConversationState.THINKING &&
            context.committedTranscript != null && context.turnType == null
        if ((!introduction && !awaitingAnswer && !awaitingClarification && !awaitingUnclassifiedTranscript) ||
            context.response != null) {
            return ignored(context)
        }
        return ConversationTransition(context.copy(state = ConversationState.THINKING,
            response = response, playbackEpoch = context.playbackEpoch + 1))
    }

    private fun demoResponseReady(
        context: ConversationContext,
        response: AssistantResponse
    ): ConversationTransition {
        if (response.threadId != context.threadId || response.turnId != context.turnId) {
            return ignored(context, EventDisposition.IGNORED_STALE)
        }
        if (context.state == ConversationState.IDLE) return ignored(context)
        return ConversationTransition(context.copy(
            state = ConversationState.THINKING,
            userTurnOpen = false,
            partialTranscript = "",
            committedTranscript = null,
            interruptionTranscript = "",
            turnType = null,
            response = response,
            suspendedResponse = null,
            playbackEpoch = context.playbackEpoch + 1
        ))
    }

    private fun reset(context: ConversationContext, state: ConversationState) =
        ConversationContext(context.threadId, state, context.turnId,
            playbackEpoch = context.playbackEpoch + 1)

    private inline fun withTurn(context: ConversationContext, threadId: String, turnId: Long,
        block: () -> ConversationTransition): ConversationTransition =
        if (threadId != context.threadId || turnId != context.turnId) {
            ignored(context, EventDisposition.IGNORED_STALE)
        } else block()

    private inline fun withPlayback(context: ConversationContext, responseId: String, epoch: Long,
        block: () -> ConversationTransition): ConversationTransition =
        if (context.response?.responseId != responseId || context.playbackEpoch != epoch) {
            ignored(context, EventDisposition.IGNORED_STALE)
        } else block()

    private fun ignored(context: ConversationContext,
        disposition: EventDisposition = EventDisposition.IGNORED_INVALID_STATE) =
        ConversationTransition(context, disposition = disposition)
}