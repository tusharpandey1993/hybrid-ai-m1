package dev.edgeai.pickleball.ball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BallTrackerTest {
    @Test
    fun reportsObservedAndPredictedStatesSeparately() {
        val tracker = BallTracker(lostAfterNs = 100_000_000L, predictionHorizonNs = 200_000_000L)
        val observed = tracker.update(detection(1_000_000_000L, 100f, 200f))!!
        val predicted = tracker.update(belowThreshold(1_033_000_000L))!!

        assertEquals(BallTrackingStatus.TRACKED, observed.status)
        assertNotNull(observed.observed)
        assertEquals(BallTrackingStatus.PREDICTED, predicted.status)
        assertNull(predicted.observed)
        assertNotNull(predicted.predictedCameraX)
        assertEquals(1, predicted.missingObservationCount)
        assertEquals(BallTrackingStatus.PREDICTED, tracker.trajectory.snapshot().last().status)
        assertNull(tracker.trajectory.snapshot().last().observedCameraX)
    }

    @Test
    fun transitionsThroughLostAndReacquiredWithoutCallingPredictionObservation() {
        val tracker = BallTracker(lostAfterNs = 100_000_000L, predictionHorizonNs = 200_000_000L)
        tracker.update(detection(1_000_000_000L, 100f, 200f))

        val lost = tracker.update(belowThreshold(1_250_000_000L))!!
        val reacquired = tracker.update(detection(1_300_000_000L, 150f, 180f))!!

        assertEquals(BallTrackingStatus.LOST, lost.status)
        assertNull(lost.observed)
        assertNull(lost.predictedCameraX)
        assertEquals(BallTrackingStatus.REACQUIRED, reacquired.status)
        assertNotNull(reacquired.observed)
        assertEquals(0f, reacquired.velocityXPerSecond, 0f)
    }

    @Test
    fun skipsBusyAndRejectsOutOfOrderResultsWithoutCreatingObservations() {
        val tracker = BallTracker()
        val first = tracker.update(detection(1_000_000_000L, 12f, 34f))!!
        val skipped = tracker.update(
            BallDetectionResult(1_033_000_000L, BallDetectionStatus.SKIPPED_BUSY,
                null, null, null, null, 0f, listOf(33_000_000L)),
        )!!
        val outOfOrder = tracker.update(detection(1_010_000_000L, 999f, 999f))!!

        assertEquals(BallTrackingStatus.TRACKED, first.status)
        assertEquals(BallTrackingStatus.PREDICTED, skipped.status)
        assertNull(skipped.observed)
        assertNull(outOfOrder.observed)
        assertEquals(12f, outOfOrder.predictedCameraX!!, 0f)
        assertTrue(outOfOrder.timestampNs >= skipped.timestampNs)
    }

    private fun detection(timestampNs: Long, x: Float, y: Float) = BallDetectionResult(
        timestampNs = timestampNs,
        status = BallDetectionStatus.DETECTED,
        peakX = x.toInt(),
        peakY = y.toInt(),
        cameraX = x,
        cameraY = y,
        confidence = 0.9f,
        frameIntervalsNs = listOf(33_000_000L, 33_000_000L),
    )

    private fun belowThreshold(timestampNs: Long) = BallDetectionResult(
        timestampNs = timestampNs,
        status = BallDetectionStatus.BELOW_THRESHOLD,
        peakX = null,
        peakY = null,
        cameraX = null,
        cameraY = null,
        confidence = 0.2f,
        frameIntervalsNs = listOf(33_000_000L, 33_000_000L),
    )
}
