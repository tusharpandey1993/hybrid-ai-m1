package dev.edgeai.pickleball.ball

import kotlin.math.exp

@JvmInline
value class BallTrackId(val value: String = "BALL_1")

enum class BallTrackingStatus {
    TRACKED,
    PREDICTED,
    LOST,
    REACQUIRED,
}

data class BallObservation(
    val timestampNs: Long,
    val cameraX: Float,
    val cameraY: Float,
    val confidence: Float,
    val heatmapX: Int,
    val heatmapY: Int,
    val rotationDegrees: Int,
)

data class BallTrackState(
    val trackId: BallTrackId,
    val timestampNs: Long,
    val status: BallTrackingStatus,
    val observed: BallObservation?,
    val predictedCameraX: Float?,
    val predictedCameraY: Float?,
    val velocityXPerSecond: Float,
    val velocityYPerSecond: Float,
    val accelerationXPerSecondSquared: Float,
    val accelerationYPerSecondSquared: Float,
    val trackingConfidence: Float,
    val missingObservationCount: Int,
)

data class BallTrajectorySample(
    val timestampNs: Long,
    val observedCameraX: Float?,
    val observedCameraY: Float?,
    val estimatedCameraX: Float?,
    val estimatedCameraY: Float?,
    val confidence: Float,
    val status: BallTrackingStatus,
)

class BallTrajectory(
    private val retentionNs: Long = 2_000_000_000L,
    private val maxSamples: Int = 240,
) {
    private val samples = ArrayDeque<BallTrajectorySample>()

    init {
        require(retentionNs > 0L)
        require(maxSamples > 0)
    }

    fun add(sample: BallTrajectorySample) {
        val previous = samples.lastOrNull()
        if (previous != null && sample.timestampNs <= previous.timestampNs) return
        samples.addLast(sample)
        prune(sample.timestampNs)
    }

    fun snapshot(nowNs: Long? = null): List<BallTrajectorySample> {
        if (nowNs != null) prune(nowNs)
        return samples.toList()
    }

    fun clear() = samples.clear()

    private fun prune(nowNs: Long) {
        val oldestAllowed = nowNs - retentionNs
        while (samples.isNotEmpty() &&
            (samples.first().timestampNs < oldestAllowed || samples.size > maxSamples)
        ) {
            samples.removeFirst()
        }
    }
}

class BallTracker(
    private val trackId: BallTrackId = BallTrackId(),
    private val peakThreshold: Float = WasbModelContract.DEFAULT_PEAK_THRESHOLD,
    private val lostAfterNs: Long = 300_000_000L,
    private val predictionHorizonNs: Long = 500_000_000L,
    private val positionGain: Float = 0.72f,
    private val velocityGain: Float = 0.22f,
    private val confidenceDecayNs: Long = 250_000_000L,
    val trajectory: BallTrajectory = BallTrajectory(),
) {
    private var lastTimestampNs = 0L
    private var lastObservationTimestampNs = 0L
    private var positionX: Float? = null
    private var positionY: Float? = null
    private var velocityX = 0f
    private var velocityY = 0f
    private var accelerationX = 0f
    private var accelerationY = 0f
    private var trackingConfidence = 0f
    private var missingObservationCount = 0
    private var hasBeenLost = false

    init {
        require(peakThreshold in 0f..1f)
        require(lostAfterNs > 0L)
        require(predictionHorizonNs >= lostAfterNs)
        require(positionGain in 0f..1f)
        require(velocityGain in 0f..1f)
        require(confidenceDecayNs > 0L)
    }

    fun update(result: BallDetectionResult): BallTrackState? {
        if (result.timestampNs <= lastTimestampNs) return currentState()
        lastTimestampNs = result.timestampNs
        return when (result.status) {
            BallDetectionStatus.DETECTED -> {
                val x = result.cameraX ?: return updateMissing(result.timestampNs)
                val y = result.cameraY ?: return updateMissing(result.timestampNs)
                if (result.confidence < peakThreshold) return updateMissing(result.timestampNs)
                updateObservation(
                    BallObservation(
                        timestampNs = result.timestampNs,
                        cameraX = x,
                        cameraY = y,
                        confidence = result.confidence,
                        heatmapX = result.peakX ?: -1,
                        heatmapY = result.peakY ?: -1,
                        rotationDegrees = result.rotationDegrees,
                    ),
                )
            }
            BallDetectionStatus.BELOW_THRESHOLD -> updateMissing(result.timestampNs)
            BallDetectionStatus.SKIPPED_BUSY -> updateMissing(result.timestampNs, countAsMiss = false)
        }
    }

    fun currentState(): BallTrackState? = if (lastTimestampNs == 0L) null else stateAt(lastTimestampNs)

    fun reset() {
        lastTimestampNs = 0L
        lastObservationTimestampNs = 0L
        positionX = null
        positionY = null
        velocityX = 0f
        velocityY = 0f
        accelerationX = 0f
        accelerationY = 0f
        trackingConfidence = 0f
        missingObservationCount = 0
        hasBeenLost = false
        trajectory.clear()
    }

    private fun updateObservation(observation: BallObservation): BallTrackState {
        val wasLost = hasBeenLost
        val previousTimestampNs = lastObservationTimestampNs
        val previousX = positionX
        val previousY = positionY
        var status = if (wasLost) BallTrackingStatus.REACQUIRED else BallTrackingStatus.TRACKED

        if (previousX == null || previousY == null || wasLost) {
            if (wasLost) {
                velocityX = 0f
                velocityY = 0f
                accelerationX = 0f
                accelerationY = 0f
            }
            positionX = observation.cameraX
            positionY = observation.cameraY
        } else {
            val deltaNs = observation.timestampNs - previousTimestampNs
            val deltaSeconds = deltaNs / 1_000_000_000f
            if (deltaNs <= 0L || deltaNs > predictionHorizonNs) {
                velocityX = 0f
                velocityY = 0f
                accelerationX = 0f
                accelerationY = 0f
                positionX = observation.cameraX
                positionY = observation.cameraY
                status = BallTrackingStatus.REACQUIRED
            } else {
                val priorVelocityX = velocityX
                val priorVelocityY = velocityY
                val predictedX = previousX + priorVelocityX * deltaSeconds
                val predictedY = previousY + priorVelocityY * deltaSeconds
                val residualX = observation.cameraX - predictedX
                val residualY = observation.cameraY - predictedY
                positionX = predictedX + positionGain * residualX
                positionY = predictedY + positionGain * residualY
                velocityX += velocityGain * residualX / deltaSeconds
                velocityY += velocityGain * residualY / deltaSeconds
                accelerationX = (velocityX - priorVelocityX) / deltaSeconds
                accelerationY = (velocityY - priorVelocityY) / deltaSeconds
            }
        }

        lastObservationTimestampNs = observation.timestampNs
        trackingConfidence = observation.confidence.coerceIn(0f, 1f)
        missingObservationCount = 0
        hasBeenLost = false
        val state = makeState(observation.timestampNs, status, observation)
        trajectory.add(
            BallTrajectorySample(
                timestampNs = observation.timestampNs,
                observedCameraX = observation.cameraX,
                observedCameraY = observation.cameraY,
                estimatedCameraX = state.predictedCameraX,
                estimatedCameraY = state.predictedCameraY,
                confidence = state.trackingConfidence,
                status = state.status,
            ),
        )
        return state
    }

    private fun updateMissing(timestampNs: Long, countAsMiss: Boolean = true): BallTrackState {
        if (countAsMiss) missingObservationCount++
        val timeSinceObservation = timestampNs - lastObservationTimestampNs
        val status = when {
            lastObservationTimestampNs == 0L -> BallTrackingStatus.LOST
            timeSinceObservation < lostAfterNs -> BallTrackingStatus.PREDICTED
            else -> BallTrackingStatus.LOST
        }
        if (lastObservationTimestampNs > 0L && timeSinceObservation >= lostAfterNs) {
            hasBeenLost = true
        }
        val state = makeState(timestampNs, status, observation = null)
        trajectory.add(
            BallTrajectorySample(
                timestampNs = timestampNs,
                observedCameraX = null,
                observedCameraY = null,
                estimatedCameraX = state.predictedCameraX,
                estimatedCameraY = state.predictedCameraY,
                confidence = state.trackingConfidence,
                status = state.status,
            ),
        )
        return state
    }

    private fun stateAt(timestampNs: Long): BallTrackState {
        val status = if (timestampNs - lastObservationTimestampNs < lostAfterNs) {
            BallTrackingStatus.PREDICTED
        } else {
            BallTrackingStatus.LOST
        }
        return makeState(timestampNs, status, observation = null)
    }

    private fun makeState(
        timestampNs: Long,
        status: BallTrackingStatus,
        observation: BallObservation?,
    ): BallTrackState {
        val sinceObservationNs = if (lastObservationTimestampNs == 0L) {
            Long.MAX_VALUE
        } else {
            (timestampNs - lastObservationTimestampNs).coerceAtLeast(0L)
        }
        val predictionAllowed = sinceObservationNs < minOf(predictionHorizonNs, lostAfterNs)
        val seconds = if (predictionAllowed) sinceObservationNs / 1_000_000_000f else 0f
        val predictedX = positionX?.takeIf { predictionAllowed }?.plus(velocityX * seconds)
        val predictedY = positionY?.takeIf { predictionAllowed }?.plus(velocityY * seconds)
        val decayedConfidence = if (trackingConfidence <= 0f || !predictionAllowed) {
            0f
        } else {
            trackingConfidence * exp(-sinceObservationNs.toFloat() / confidenceDecayNs)
        }
        return BallTrackState(
            trackId = trackId,
            timestampNs = timestampNs,
            status = status,
            observed = observation,
            predictedCameraX = predictedX,
            predictedCameraY = predictedY,
            velocityXPerSecond = velocityX,
            velocityYPerSecond = velocityY,
            accelerationXPerSecondSquared = accelerationX,
            accelerationYPerSecondSquared = accelerationY,
            trackingConfidence = decayedConfidence,
            missingObservationCount = missingObservationCount,
        )
    }
}
