package dev.edgeai.prototype.voice.session

import dev.edgeai.prototype.voice.conversation.ConversationContext
import dev.edgeai.prototype.voice.conversation.ConversationController
import dev.edgeai.prototype.voice.conversation.ConversationEvent
import dev.edgeai.prototype.voice.conversation.ConversationTransition
import dev.edgeai.prototype.voice.conversation.UserTranscript
import dev.edgeai.prototype.voice.conversation.AssistantResponse

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

    fun assistantResponseReady(response: AssistantResponse): Boolean =
        apply(ConversationEvent.AssistantResponseReady(response)).disposition ==
            dev.edgeai.prototype.voice.conversation.EventDisposition.ACCEPTED

    fun assistantDemoResponseReady(response: AssistantResponse): Boolean =
        apply(ConversationEvent.AssistantDemoResponseReady(response)).disposition ==
            dev.edgeai.prototype.voice.conversation.EventDisposition.ACCEPTED

    fun ttsStarted(responseId: String, playbackEpoch: Long): Boolean =
        apply(ConversationEvent.TtsStarted(responseId, playbackEpoch)).disposition ==
            dev.edgeai.prototype.voice.conversation.EventDisposition.ACCEPTED

    fun ttsCompleted(responseId: String, playbackEpoch: Long) {
        apply(ConversationEvent.TtsCompleted(responseId, playbackEpoch))
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

    fun partialTranscript(transcript: UserTranscript) {
        apply(ConversationEvent.PartialTranscript(context.threadId, context.turnId, transcript))
    }

    fun acceptTranscript(transcript: UserTranscript, isFinal: Boolean) {
        if (isFinal) commitTranscript(transcript) else partialTranscript(transcript)
    }

    fun commitTranscript(transcript: UserTranscript) {
        if (!context.userTurnOpen) speechStarted()
        partialTranscript(transcript)
        apply(ConversationEvent.UserTurnCommitted(context.threadId, context.turnId, transcript))
    }

    fun falseInterruptionDetected(): Boolean =
        apply(ConversationEvent.FalseInterruptionDetected(context.threadId, context.turnId)).actions
            .contains(dev.edgeai.prototype.voice.conversation.ConversationAction.CONTINUE)

    private fun apply(event: ConversationEvent): ConversationTransition {
        val transition = controller.transition(context, event)
        context = transition.context
        return transition
    }
}