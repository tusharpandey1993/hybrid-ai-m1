package dev.edgeai.prototype.voice.session

import dev.edgeai.prototype.voice.conversation.ConversationContext
import dev.edgeai.prototype.voice.conversation.ConversationController
import dev.edgeai.prototype.voice.conversation.ConversationEvent

class VoiceConversationSession(threadId: String) {
    private val controller = ConversationController()
    var context = ConversationContext(threadId)
        private set

    init {
        apply(ConversationEvent.StartListening)
    }

    fun startListening() {
        apply(ConversationEvent.StartListening)
    }

    fun cancel() {
        apply(ConversationEvent.Cancel)
    }

    fun speechStarted() {
        val turnId = if (context.userTurnOpen) context.turnId else context.turnId + 1
        apply(ConversationEvent.SpeechStarted(context.threadId, turnId))
    }

    fun speechEnded() {
        apply(ConversationEvent.SpeechEnded(context.threadId, context.turnId))
    }

    fun partialTranscript(text: String) {
        if (text.isNotBlank()) apply(ConversationEvent.PartialTranscript(context.threadId, context.turnId, text))
    }

    fun acceptTranscript(text: String, isFinal: Boolean) {
        if (text.isBlank()) return
        if (isFinal) commitTranscript(text) else partialTranscript(text)
    }

    fun commitTranscript(text: String) {
        require(text.isNotBlank())
        if (!context.userTurnOpen) speechStarted()
        partialTranscript(text)
        apply(ConversationEvent.UserTurnCommitted(context.threadId, context.turnId, text))
    }

    private fun apply(event: ConversationEvent) {
        context = controller.transition(context, event).context
    }
}