package dev.edgeai.prototype.voice.conversation

import dev.edgeai.prototype.voice.turn.TurnType

data class AssistantResponse(
    val threadId: String,
    val responseId: String,
    val turnId: Long,
    val text: String,
    val resumePosition: Int = 0
) {
    init {
        require(threadId.isNotBlank() && responseId.isNotBlank())
        require(turnId >= 0 && text.isNotBlank())
        require(resumePosition in 0..text.length)
        require(resumePosition == 0 || resumePosition == text.length ||
            !(text[resumePosition - 1].isHighSurrogate() && text[resumePosition].isLowSurrogate()))
    }
}

data class ConversationContext(
    val threadId: String,
    val state: ConversationState = ConversationState.IDLE,
    val turnId: Long = 0,
    val userTurnOpen: Boolean = false,
    val partialTranscript: String = "",
    val committedTranscript: String? = null,
    val interruptionTranscript: String = "",
    val turnType: TurnType? = null,
    val response: AssistantResponse? = null,
    val suspendedResponse: AssistantResponse? = null,
    val playbackEpoch: Long = 0
) {
    init {
        require(threadId.isNotBlank() && turnId >= 0 && playbackEpoch >= 0)
        listOfNotNull(response, suspendedResponse).forEach {
            require(it.threadId == threadId && it.turnId <= turnId)
        }
        require(state !in setOf(ConversationState.ASSISTANT_SPEAKING,
            ConversationState.TEMPORARILY_PAUSED) || response != null)
        require(state != ConversationState.INTERRUPTED || suspendedResponse != null)
        require(committedTranscript == null || committedTranscript.isNotBlank())
        require(!userTurnOpen || committedTranscript == null)
    }
}