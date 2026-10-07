package dev.edgeai.prototype.voice.audio

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class CaptureStatus { IDLE, STARTING, CAPTURING, STOPPING, ERROR }
enum class CaptureFailure { PERMISSION, FORMAT, INITIALIZATION, READ, RELEASE, SILENCED }

data class AudioCaptureState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val sessionId: Long = 0,
    val sampleRate: Int = AudioFrame.SAMPLE_RATE,
    val frames: Long = 0,
    val droppedFrames: Long = 0,
    val peakAmplitude: Double = 0.0,
    val rmsAmplitude: Double = 0.0,
    val routeType: Int? = null,
    val firstFrameMillis: Double? = null,
    val lastFrameGapMillis: Double? = null,
    val failure: CaptureFailure? = null,
    val failureType: String? = null,
    val readCode: Int? = null,
    val cause: Exception? = null
)

interface AudioInput {
    val frames: SharedFlow<AudioFrame>
    val state: StateFlow<AudioCaptureState>
    suspend fun start()
    suspend fun stop()
}