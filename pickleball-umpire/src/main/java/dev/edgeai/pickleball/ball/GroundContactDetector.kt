package dev.edgeai.pickleball.ball

import kotlin.math.abs
import kotlin.math.hypot

/** A camera-plane kinematic discontinuity candidate; not a confirmed ground bounce. */
data class ContactCandidate(
    val timestampNs: Long,
    val cameraX: Float,
    val cameraY: Float,
    val confidence: Float,
    val localizationUncertaintyPx: Float,
    val supportingObservations: List<BallObservation>,
    val reason: String,
    val groundCourtPositionAvailable: Boolean = false,
)

class GroundContactDetector(
    private val maxObservationGapNs: Long = 120_000_000L,
    private val candidateCooldownNs: Long = 250_000_000L,
    private val minimumSpeedPxPerSecond: Float = 180f,
    private val minimumDirectionChangeDegrees: Double = 70.0,
    private val minimumSpeedRatioChange: Float = 1.8f,
    private val historyLimit: Int = 8,
) {
    private val observations = ArrayDeque<BallObservation>()
    private var lastCandidateTimestampNs = Long.MIN_VALUE

    init {
        require(maxObservationGapNs > 0L)
        require(candidateCooldownNs > 0L)
        require(minimumSpeedPxPerSecond >= 0f)
        require(minimumDirectionChangeDegrees in 0.0..180.0)
        require(minimumSpeedRatioChange >= 1f)
        require(historyLimit >= 4)
    }

    fun update(state: BallTrackState): ContactCandidate? {
        val observation = state.observed
        if (observation == null) {
            if (state.status == BallTrackingStatus.LOST) observations.clear()
            return null
        }
        if (state.status == BallTrackingStatus.REACQUIRED) observations.clear()
        val previous = observations.lastOrNull()
        if (previous != null && observation.timestampNs - previous.timestampNs > maxObservationGapNs) {
            observations.clear()
        }
        observations.addLast(observation)
        while (observations.size > historyLimit) observations.removeFirst()
        if (observations.size < 4) return null

        val samples = observations.takeLast(4)
        val velocityBefore = velocity(samples[0], samples[1])
        val velocityAfter = velocity(samples[2], samples[3])
        if (velocityBefore == null || velocityAfter == null) return null
        val speedBefore = hypot(velocityBefore.first, velocityBefore.second)
        val speedAfter = hypot(velocityAfter.first, velocityAfter.second)
        if (speedBefore < minimumSpeedPxPerSecond || speedAfter < minimumSpeedPxPerSecond) return null

        val dot = velocityBefore.first * velocityAfter.first + velocityBefore.second * velocityAfter.second
        val cosine = (dot / (speedBefore * speedAfter)).coerceIn(-1.0, 1.0)
        val directionChange = Math.toDegrees(kotlin.math.acos(cosine))
        val speedRatio = maxOf(speedBefore, speedAfter) / minOf(speedBefore, speedAfter)
        val directionDiscontinuity = directionChange >= minimumDirectionChangeDegrees
        val speedDiscontinuity = speedRatio >= minimumSpeedRatioChange
        if (!directionDiscontinuity && !speedDiscontinuity) return null

        val eventTimestamp = (samples[1].timestampNs + samples[2].timestampNs) / 2L
        if (lastCandidateTimestampNs != Long.MIN_VALUE &&
            eventTimestamp - lastCandidateTimestampNs < candidateCooldownNs
        ) return null
        lastCandidateTimestampNs = eventTimestamp

        val directionScore = ((directionChange - minimumDirectionChangeDegrees) /
            (180.0 - minimumDirectionChangeDegrees).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val speedScore = ((speedRatio - 1.0) / (minimumSpeedRatioChange * 2.0)).coerceIn(0.0, 1.0)
        val confidence = (0.25 + 0.3 * directionScore + 0.2 * speedScore).coerceAtMost(0.75).toFloat()
        val candidateX = (samples[1].cameraX + samples[2].cameraX) / 2f
        val candidateY = (samples[1].cameraY + samples[2].cameraY) / 2f
        val localizationUncertainty = hypot(
            samples[2].cameraX - samples[1].cameraX,
            samples[2].cameraY - samples[1].cameraY,
        ) / 2f

        return ContactCandidate(
            timestampNs = eventTimestamp,
            cameraX = candidateX,
            cameraY = candidateY,
            confidence = confidence,
            localizationUncertaintyPx = localizationUncertainty,
            supportingObservations = samples,
            reason = when {
                directionDiscontinuity && speedDiscontinuity -> "DIRECTION_AND_SPEED_DISCONTINUITY"
                directionDiscontinuity -> "DIRECTION_DISCONTINUITY"
                else -> "SPEED_DISCONTINUITY"
            },
        )
    }

    fun reset() {
        observations.clear()
        lastCandidateTimestampNs = Long.MIN_VALUE
    }

    private fun velocity(first: BallObservation, second: BallObservation): Pair<Double, Double>? {
        val elapsedNs = second.timestampNs - first.timestampNs
        if (elapsedNs <= 0L || elapsedNs > maxObservationGapNs) return null
        val elapsedSeconds = elapsedNs / 1_000_000_000.0
        return ((second.cameraX - first.cameraX) / elapsedSeconds) to
            ((second.cameraY - first.cameraY) / elapsedSeconds)
    }
}
