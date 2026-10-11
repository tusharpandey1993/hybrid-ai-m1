package dev.edgeai.pickleball.ball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WasbHeatmapDecoderTest {
    @Test
    fun decodesHeatmapPeakIntoSourceCameraCoordinates() {
        val heatmap = FloatArray(WasbModelContract.WIDTH * WasbModelContract.HEIGHT)
        val peakX = 400
        val peakY = 100
        heatmap[peakY * WasbModelContract.WIDTH + peakX] = 0.91f

        val result = WasbHeatmapDecoder.decode(
            heatmap = heatmap,
            timestampNs = 1_100_000_000L,
            sourceFrame = frame(),
            frameIntervalsNs = listOf(33_000_000L, 34_000_000L),
        )

        assertEquals(BallDetectionStatus.DETECTED, result.status)
        assertEquals(peakX, result.peakX)
        assertEquals(peakY, result.peakY)
        assertEquals((peakX + 0.5f) * 2.5f, result.cameraX!!, 0.001f)
        assertEquals((peakY + 0.5f) * 2.5f, result.cameraY!!, 0.001f)
        assertEquals(0.91f, result.confidence, 0.0001f)
        assertEquals(1_100_000_000L, result.timestampNs)
    }

    @Test
    fun keepsLowConfidencePeakAsBelowThresholdRatherThanCallingItDetected() {
        val heatmap = FloatArray(WasbModelContract.WIDTH * WasbModelContract.HEIGHT)
        heatmap[10 * WasbModelContract.WIDTH + 20] = 0.49f

        val result = WasbHeatmapDecoder.decode(
            heatmap = heatmap,
            timestampNs = 1_100_000_000L,
            sourceFrame = frame(),
            frameIntervalsNs = emptyList(),
        )

        assertEquals(BallDetectionStatus.BELOW_THRESHOLD, result.status)
        assertTrue(result.confidence < WasbModelContract.DEFAULT_PEAK_THRESHOLD)
        assertNotNull(result.peakX)
        assertNotNull(result.cameraX)
    }

    @Test
    fun skippedBusyResultDoesNotPretendToBeAnObservation() {
        val window = TemporalFrameWindow.from(listOf(
            frame(1_000_000_000L),
            frame(1_033_000_000L),
            frame(1_066_000_000L),
        ))

        val skipped = WasbHeatmapDecoder.skipped(window)

        assertEquals(BallDetectionStatus.SKIPPED_BUSY, skipped.status)
        assertNull(skipped.cameraX)
        assertNull(skipped.cameraY)
        assertEquals(0f, skipped.confidence, 0f)
    }

    private fun frame(timestampNs: Long = 1_000_000_000L) = TemporalRgbFrame(
        timestampNs = timestampNs,
        cameraWidth = 1280,
        cameraHeight = 720,
        imageNetNormalizedRgbNchw = FloatArray(WasbModelContract.VALUES_PER_FRAME),
    )
}