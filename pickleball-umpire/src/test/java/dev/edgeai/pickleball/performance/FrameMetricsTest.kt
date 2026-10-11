package dev.edgeai.pickleball.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameMetricsTest {
    @Test
    fun reportsTimestampBasedFpsAndProcessingDuration() {
        val accumulator = FrameMetricsAccumulator()
        var snapshot: FrameMetrics? = null

        for (frame in 0..31) {
            snapshot = accumulator.record(
            cameraTimestampNs = 1_000_000_000L + frame * 33_333_334L,
                processingDurationNs = 2_000_000L,
                frameWidth = 1280,
                frameHeight = 720,
            ) ?: snapshot
        }

        assertNotNull(snapshot)
        assertEquals(30.0, snapshot!!.cameraFps, 0.1)
        assertEquals(2.0, snapshot.averageProcessingMs, 0.01)
        assertEquals(1280, snapshot.frameWidth)
        assertEquals(720, snapshot.frameHeight)
    }

    @Test
    fun flagsLargeCameraTimestampDiscontinuityAsSuspectedGap() {
        val accumulator = FrameMetricsAccumulator(reportWindowNs = 100_000_000L)
        assertNull(accumulator.record(1_000_000_000L, 1L, 1280, 720))
        accumulator.record(1_033_333_333L, 1L, 1280, 720)
        val snapshot = accumulator.record(1_133_333_333L, 1L, 1280, 720)

        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.suspectedFrameGaps)
        assertEquals(100.0, snapshot.maxFrameIntervalMs, 0.01)
    }
}