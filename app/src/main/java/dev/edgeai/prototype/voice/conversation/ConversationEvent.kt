package dev.edgeai.prototype.voice.conversation

import dev.edgeai.prototype.voice.turn.TurnType

sealed interface ConversationEvent {
    data object StartListening : ConversationEvent
    data object Cancel : ConversationEvent
    data class SpeechStarted(val threadId: String, val turnId: Long) : ConversationEvent
    data class SpeechEnded(val threadId: String, val turnId: Long) : ConversationEvent
    data class PartialTranscript(val threadId: String, val turnId: Long, val text: String) : ConversationEvent
    data class UserTurnCommitted(val threadId: String, val turnId: Long, val text: String) : ConversationEvent {
        init { require(text.isNotBlank()) }
    }
    data class TurnClassified(val threadId: String, val turnId: Long, val type: TurnType) : ConversationEvent
    data class AssistantResponseReady(val response: AssistantResponse) : ConversationEvent
    data class TtsStarted(val responseId: String, val playbackEpoch: Long) : ConversationEvent
    data class TtsCompleted(val responseId: String, val playbackEpoch: Long) : ConversationEvent
    data class PlaybackProgress(
        val responseId: String,
        val playbackEpoch: Long,
        val resumePosition: Int
    ) : ConversationEvent
}