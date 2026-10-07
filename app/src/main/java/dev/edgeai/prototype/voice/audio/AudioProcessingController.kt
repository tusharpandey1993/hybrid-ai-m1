package dev.edgeai.prototype.voice.audio

import kotlinx.coroutines.flow.StateFlow

interface AudioProcessingController {
    val state: StateFlow<AudioProcessingState>
    fun attach(audioSessionId: Int)
    fun release()
}

interface AudioEffectHandle {
    val enabled: Boolean
    fun enable(): Boolean
    fun release()
}

interface AudioProcessingEffectFactory {
    fun isAecAvailable(): Boolean
    fun isNoiseSuppressorAvailable(): Boolean
    fun createAec(audioSessionId: Int): AudioEffectHandle?
    fun createNoiseSuppressor(audioSessionId: Int): AudioEffectHandle?
}

class SessionAudioProcessingController(
    private val effects: AudioProcessingEffectFactory
) : AudioProcessingController {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(AudioProcessingState())
    override val state = mutableState

    private var aec: AudioEffectHandle? = null
    private var noiseSuppressor: AudioEffectHandle? = null

    @Synchronized
    override fun attach(audioSessionId: Int) {
        require(audioSessionId > 0)
        releaseHandles()

        val aecAvailable = effects.isAecAvailable()
        val noiseSuppressorAvailable = effects.isNoiseSuppressorAvailable()
        val next = AudioProcessingState(
            aecAvailable = aecAvailable,
            noiseSuppressorAvailable = noiseSuppressorAvailable,
            audioSessionId = audioSessionId
        )
        aec = if (aecAvailable) effects.createAec(audioSessionId) else null
        noiseSuppressor = if (noiseSuppressorAvailable) {
            effects.createNoiseSuppressor(audioSessionId)
        } else null
        mutableState.value = next.copy(
            aecAttached = aec != null,
            aecEnabled = aec?.let { it.enable() && it.enabled } == true,
            noiseSuppressorAttached = noiseSuppressor != null,
            noiseSuppressorEnabled = noiseSuppressor?.let { it.enable() && it.enabled } == true
        )
    }

    @Synchronized
    override fun release() {
        try {
            releaseHandles()
        } finally {
            mutableState.value = mutableState.value.copy(
                aecAttached = false,
                aecEnabled = false,
                noiseSuppressorAttached = false,
                noiseSuppressorEnabled = false,
                audioSessionId = null
            )
        }
    }

    private fun releaseHandles() {
        var failure: Exception? = null
        try {
            aec?.release()
        } catch (error: Exception) {
            failure = error
        }
        try {
            noiseSuppressor?.release()
        } catch (error: Exception) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        aec = null
        noiseSuppressor = null
        failure?.let { throw it }
    }
}