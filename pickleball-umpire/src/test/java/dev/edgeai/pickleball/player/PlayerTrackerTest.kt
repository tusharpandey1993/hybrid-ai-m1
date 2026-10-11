package dev.edgeai.pickleball.player

import dev.edgeai.pickleball.court.HomographyTransformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTrackerTest {
    private val cameraToCourt = HomographyTransformer(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    )

    @Test
    fun tracksTwoSinglesAndKeepsTemporaryIdsAcrossDetections() {
        val tracker = PlayerTracker()
        val first = tracker.update(
            timestampNs = 1_000_000_000L,
            detections = listOf(detection(1_000_000_000L, 1.0f, 1f), detection(1_000_000_000L, 6f, 6f)),
            countMode = PlayerCountMode.SINGLES,
            calibration = cameraToCourt,
        )
        val second = tracker.update(
            timestampNs = 1_100_000_000L,
            detections = listOf(detection(1_100_000_000L, 1.2f, 1f), detection(1_100_000_000L, 6.2f, 6f)),
            countMode = PlayerCountMode.SINGLES,
            calibration = cameraToCourt,
        )

        assertEquals(2, first.players.size)
        assertEquals(first.players.map { it.id }, second.players.map { it.id })
        assertTrue(second.players.all { it.observed })
        assertTrue(second.players.any { it.velocityXPerSecond > 0f })
    }

    @Test
    fun doublesModeAllowsFourAndSinglesModeCapsAtTwo() {
        val detections = listOf(
            detection(1_000_000_000L, 1f, 1f),
            detection(1_000_000_000L, 5f, 5f),
            detection(1_000_000_000L, 11f, 11f),
            detection(1_000_000_000L, 16f, 16f),
        )
        val tracker = PlayerTracker()

        val doubles = tracker.update(1_000_000_000L, detections, PlayerCountMode.DOUBLES, cameraToCourt)
        val singles = PlayerTracker().update(1_000_000_000L, detections, PlayerCountMode.SINGLES, cameraToCourt)

        assertEquals(4, doubles.players.size)
        assertEquals(2, singles.players.size)
        assertEquals(2, singles.ignoredBeyondConfiguredCapacity)
    }

    @Test
    fun filtersSpectatorsOutsideCourtAndExpiresStaleTracks() {
        val tracker = PlayerTracker(maxAgeNs = 500_000_000L)
        val initial = tracker.update(
            timestampNs = 1_000_000_000L,
            detections = listOf(detection(1_000_000_000L, 1f, 1f), detection(1_000_000_000L, -10f, 1f)),
            countMode = PlayerCountMode.SINGLES,
            calibration = cameraToCourt,
        )
        val later = tracker.update(
            timestampNs = 1_300_000_000L,
            detections = emptyList(),
            countMode = PlayerCountMode.SINGLES,
            calibration = cameraToCourt,
        )

        assertEquals(1, initial.players.size)
        assertEquals(1, initial.rejectedOutsideCourt)
        assertEquals(1, later.players.size)
        assertFalse(later.players.single().observed)
        val expired = tracker.update(
            timestampNs = 1_600_000_001L,
            detections = emptyList(),
            countMode = PlayerCountMode.SINGLES,
            calibration = cameraToCourt,
        )
        assertTrue(expired.players.isEmpty())
    }

    @Test
    fun preservesPlayerTrackWhenPlayerStepsJustOutsideCourtAndReturns() {
        val tracker = PlayerTracker()
        val inside = tracker.update(
            timestampNs = 1_000_000_000L,
            detections = listOf(detection(1_000_000_000L, 5f, 34f)),
            countMode = PlayerCountMode.SINGLES,
            calibration = cameraToCourt,
        ).players.single()
        val outside = tracker.update(
            timestampNs = 1_100_000_000L,
            detections = listOf(detection(1_100_000_000L, 5.1f, 36f)),
            countMode = PlayerCountMode.SINGLES,
            calibration = cameraToCourt,
        )
        val returned = tracker.update(
            timestampNs = 1_200_000_000L,
            detections = listOf(detection(1_200_000_000L, 5.2f, 34f)),
            countMode = PlayerCountMode.SINGLES,
            calibration = cameraToCourt,
        ).players.single()

        assertEquals(1, outside.players.size)
        assertEquals(inside.id, outside.players.single().id)
        assertEquals(inside.id, returned.id)
        assertEquals(0, outside.rejectedOutsideCourt)
    }

    private fun detection(timestampNs: Long, left: Float, top: Float) = PlayerDetection(
        timestampNs = timestampNs,
        cameraBox = PlayerBoundingBox(left, top, left + 2f, top + 8f),
        confidence = 0.9f,
    )
}
