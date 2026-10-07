package dev.edgeai.prototype.voice.tts

import kotlinx.coroutines.flow.StateFlow

enum class SpeechSynthesisStatus { INITIALIZING, READY, STARTING, SPEAKING, ERROR, CLOSED }

data class SpeechSynthesisState(
    val status: SpeechSynthesisStatus = SpeechSynthesisStatus.INITIALIZING,
    val stopCallMillis: Double? = null,
    val error: String? = null
)

interface SpeechSynthesizer : AutoCloseable {
    val state: StateFlow<SpeechSynthesisState>
    fun speak(text: String): Boolean
    fun stop(): Double
}