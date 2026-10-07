package dev.edgeai.prototype.voice.audio

data class AudioProcessingState(
    val aecAvailable: Boolean = false,
    val aecAttached: Boolean = false,
    val aecEnabled: Boolean = false,
    val noiseSuppressorAvailable: Boolean = false,
    val noiseSuppressorAttached: Boolean = false,
    val noiseSuppressorEnabled: Boolean = false,
    val audioSessionId: Int? = null
)