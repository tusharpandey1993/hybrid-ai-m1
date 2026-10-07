package dev.edgeai.prototype.voice.vad

import dev.edgeai.prototype.voice.audio.AudioFrame

interface VadWindowEngine : AutoCloseable {
    fun computeProbability(samples: FloatArray): Float
    fun reset()
}

data class VadObservation(
    val sessionId: Long,
    val frameSequence: Long,
    val probability: Float,
    val speechDetected: Boolean,
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val inferenceMillis: Double
)

class VadFrameProcessor(
    private val engine: VadWindowEngine,
    private val startThreshold: Float = START_THRESHOLD,
    private val endThreshold: Float = END_THRESHOLD,
    private val silenceWindowsToEnd: Int = SILENCE_WINDOWS_TO_END
) : AutoCloseable {
    private val window = FloatArray(WINDOW_SIZE)
    private var windowSamples = 0
    private var sessionId: Long? = null
    private var speechDetected = false
    private var speechCandidateWindows = 0
    private var silenceWindows = 0

    init {
        require(startThreshold in 0f..1f)
        require(endThreshold in 0f..startThreshold)
        require(silenceWindowsToEnd > 0)
    }

    fun process(frame: AudioFrame): List<VadObservation> {
        if (sessionId != frame.sessionId) {
            engine.reset()
            windowSamples = 0
            speechDetected = false
            speechCandidateWindows = 0
            silenceWindows = 0
            sessionId = frame.sessionId
        }

        val observations = mutableListOf<VadObservation>()
        for (sample in frame.copyPcm()) {
            window[windowSamples++] = sample / 32768.0f
            if (windowSamples == WINDOW_SIZE) {
                val startedAt = System.nanoTime()
                val probability = engine.computeProbability(window)
                val inferenceMillis = (System.nanoTime() - startedAt) / 1_000_000.0
                require(probability.isFinite()) { "VAD returned a non-finite probability" }
                val wasSpeechDetected = speechDetected
                val detected = updateSpeechState(probability)
                observations += VadObservation(
                    sessionId = frame.sessionId,
                    frameSequence = frame.sequence,
                    probability = probability,
                    speechDetected = detected,
                    speechStarted = detected && !wasSpeechDetected,
                    speechEnded = !detected && wasSpeechDetected,
                    inferenceMillis = inferenceMillis
                )
                speechDetected = detected
                windowSamples = 0
            }
        }
        return observations
    }

    private fun updateSpeechState(probability: Float): Boolean {
        if (!speechDetected) {
            speechCandidateWindows = if (probability >= startThreshold) speechCandidateWindows + 1 else 0
            if (speechCandidateWindows >= START_WINDOWS_REQUIRED) {
                speechDetected = true
                speechCandidateWindows = 0
                silenceWindows = 0
            }
            return speechDetected
        }

        silenceWindows = if (probability < endThreshold) silenceWindows + 1 else 0
        if (silenceWindows >= silenceWindowsToEnd) {
            speechDetected = false
            silenceWindows = 0
        }
        return speechDetected
    }

    override fun close() = engine.close()

    companion object {
        const val WINDOW_SIZE = 512
        const val START_THRESHOLD = 0.5f
        const val END_THRESHOLD = 0.35f
        const val SAMPLE_RATE = 16000
        const val START_WINDOWS_REQUIRED = 2
        const val MIN_SILENCE_MILLIS = 250
        const val SILENCE_WINDOWS_TO_END =
            (SAMPLE_RATE * MIN_SILENCE_MILLIS + WINDOW_SIZE * 1000 - 1) / (WINDOW_SIZE * 1000)
    }
}