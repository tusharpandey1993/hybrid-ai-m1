package dev.edgeai.prototype.voice.audio

import kotlin.math.abs
import kotlin.math.sqrt

class AudioFrame(
    val sessionId: Long,
    val sequence: Long,
    val timestampNanos: Long,
    samples: ShortArray
) {
    private val pcm = samples.copyOf()
    val sampleCount: Int get() = pcm.size
    val peakAmplitude: Double
    val rmsAmplitude: Double

    init {
        require(sessionId > 0 && sequence >= 0 && timestampNanos >= 0)
        require(samples.size == SAMPLE_COUNT)
        var peak = 0
        var squares = 0.0
        pcm.forEach { sample ->
            val value = sample.toInt()
            peak = maxOf(peak, abs(value))
            squares += value.toDouble() * value
        }
        peakAmplitude = peak / 32768.0
        rmsAmplitude = sqrt(squares / pcm.size) / 32768.0
    }

    fun copyPcm(): ShortArray = pcm.copyOf()

    companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLE_COUNT = 320
        const val FRAME_MILLIS = 20
    }
}