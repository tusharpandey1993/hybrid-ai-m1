package dev.edgeai.pickleball.ball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroundContactDetectorTest {
    @Test
    fun emitsOnlyAnUnconfirmedImageSpaceCandidateForVelocityDiscontinuity() {
        val detector = GroundContactDetector()
        val observations = listOf(
            observation(1_000_000_000L, 0f, 0f),
            observation(1_033_000_000L, 10f, 0f),
            observation(1_066_000_000L, 20f, 0f),
            observation(1_099_000_000L, 10f, 0f),
        )

        var candidate: ContactCandidate? = null
        observations.forEach { item ->
            candidate = detector.update(trackedState(item)) ?: candidate
        }

        assertNotNull(candidate)
        assertEquals("DIRECTION_DISCONTINUITY", candidate!!.reason)
        assertEquals(1_049_500_000L, candidate!!.timestampNs)
        assertEquals(15f, candidate!!.cameraX, 0.001f)
        assertFalse(candidate!!.groundCourtPositionAvailable)
        assertEquals(4, candidate!!.supportingObservations.size)
    }

    @Test
    fun ignoresSmoothAndLowSpeedTrajectories() {
        val detector = GroundContactDetector()
        val observations = listOf(
            observation(1_000_000_000L, 0f, 0f),
            observation(1_033_000_000L, 2f, 1f),
            observation(1_066_000_000L, 4f, 2f),
            observation(1_099_000_000L, 6f, 3f),
        )

        observations.forEach { item ->
            assertNull(detector.update(trackedState(item)))
        }
    }

    @Test
    fun longObservationGapRequiresNewHistoryBeforeCandidate() {
        val detector = GroundContactDetector(maxObservationGapNs = 80_000_000L)
        val observations = listOf(
            observation(1_000_000_000L, 0f, 0f),
            observation(1_033_000_000L, 10f, 0f),
            observation(1_300_000_000L, 20f, 0f),
            observation(1_333_000_000L, 10f, 0f),
        )

        observations.forEach { item ->
            assertNull(detector.update(trackedState(item)))
        }
    }

    private fun observation(timestampNs: Long, x: Float, y: Float) = BallObservation(
        timestampNs = timestampNs,
        cameraX = x,
        cameraY = y,
        confidence = 0.9f,
        heatmapX = x.toInt(),
        heatmapY = y.toInt(),
        rotationDegrees = 0,
    )

    private fun trackedState(observation: BallObservation) = BallTrackState(
        trackId = BallTrackId(),
        timestampNs = observation.timestampNs,
        status = BallTrackingStatus.TRACKED,
        observed = observation,
        predictedCameraX = observation.cameraX,
        predictedCameraY = observation.cameraY,
        velocityXPerSecond = 0f,
        velocityYPerSecond = 0f,
        accelerationXPerSecondSquared = 0f,
        accelerationYPerSecondSquared = 0f,
        trackingConfidence = observation.confidence,
        missingObservationCount = 0,
    )
}
