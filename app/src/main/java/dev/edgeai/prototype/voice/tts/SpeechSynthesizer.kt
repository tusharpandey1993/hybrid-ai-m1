package dev.edgeai.prototype.voice.tts

import dev.edgeai.prototype.voice.conversation.AssistantResponse
import kotlinx.coroutines.flow.StateFlow

enum class SpeechSynthesisStatus { INITIALIZING, READY, STARTING, SPEAKING, ERROR, CLOSED }

data class SpeechSynthesisState(
    val status: SpeechSynthesisStatus = SpeechSynthesisStatus.INITIALIZING,
    val stopCallMillis: Double? = null,
    val error: String? = null,
    val completedResponseId: String? = null,
    val completedPlaybackSequence: Long = 0
)

interface SpeechSynthesizer : AutoCloseable {
    val state: StateFlow<SpeechSynthesisState>
    fun speak(response: AssistantResponse): Boolean
    fun stop(): Double
}