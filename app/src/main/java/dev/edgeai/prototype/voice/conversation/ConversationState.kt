package dev.edgeai.prototype.voice.conversation

enum class ConversationState {
    IDLE, LISTENING, USER_SPEAKING, THINKING, ASSISTANT_SPEAKING, TEMPORARILY_PAUSED, INTERRUPTED
}

enum class EventDisposition { ACCEPTED, IGNORED_STALE, IGNORED_INVALID_STATE, REJECTED_NESTED_CLARIFICATION }

data class ConversationTransition(
    val context: ConversationContext,
    val actions: List<ConversationAction> = emptyList(),
    val disposition: EventDisposition = EventDisposition.ACCEPTED
)