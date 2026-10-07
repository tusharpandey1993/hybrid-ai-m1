package dev.edgeai.prototype.voice.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

class AndroidAudioProcessingEffectFactory : AudioProcessingEffectFactory {
    override fun isAecAvailable(): Boolean = AcousticEchoCanceler.isAvailable()
    override fun isNoiseSuppressorAvailable(): Boolean = NoiseSuppressor.isAvailable()

    override fun createAec(audioSessionId: Int): AudioEffectHandle? =
        AcousticEchoCanceler.create(audioSessionId)?.let(::AndroidAudioEffectHandle)

    override fun createNoiseSuppressor(audioSessionId: Int): AudioEffectHandle? =
        NoiseSuppressor.create(audioSessionId)?.let(::AndroidAudioEffectHandle)

    private class AndroidAudioEffectHandle(
        private val effect: android.media.audiofx.AudioEffect
    ) : AudioEffectHandle {
        override val enabled: Boolean get() = effect.enabled
        override fun enable(): Boolean = effect.setEnabled(true) == android.media.audiofx.AudioEffect.SUCCESS
        override fun release() = effect.release()
    }
}