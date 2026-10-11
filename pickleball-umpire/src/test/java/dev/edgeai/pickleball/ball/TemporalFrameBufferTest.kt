package dev.edgeai.pickleball.ball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TemporalFrameBufferTest {
    @Test
    fun emitsOnlyCompleteChronologicallyOrderedThreeFrameWindows() {
        val buffer = TemporalFrameBuffer()
        val pixels = FloatArray(WasbModelContract.VALUES_PER_FRAME)

        assertNull(buffer.add(frame(1_000_000_000L, pixels)))
        assertNull(buffer.add(frame(1_033_000_000L, pixels)))
        val window = buffer.add(frame(1_066_000_000L, pixels))

        assertNotNull(window)
        assertEquals(listOf(1_000_000_000L, 1_033_000_000L, 1_066_000_000L),
            window!!.frames.map { it.timestampNs })
        assertEquals(listOf(33_000_000L, 33_000_000L), window.frameIntervalsNs)
    }

    @Test
    fun rejectsDuplicateOrOutOfOrderCameraTimestamps() {
        val buffer = TemporalFrameBuffer()
        val pixels = FloatArray(WasbModelContract.VALUES_PER_FRAME)
        buffer.add(frame(1_000_000_000L, pixels))
        buffer.add(frame(1_033_000_000L, pixels))

        assertNull(buffer.add(frame(1_033_000_000L, pixels)))
        assertNull(buffer.add(frame(1_020_000_000L, pixels)))
        assertEquals(2L, buffer.rejectedTimestamps)
        assertEquals(1_033_000_000L, buffer.add(frame(1_066_000_000L, pixels))!!.frames[1].timestampNs)
    }

    @Test
    fun orientationChangeResetsTheTemporalWindow() {
        val buffer = TemporalFrameBuffer()
        val pixels = FloatArray(WasbModelContract.VALUES_PER_FRAME)
        buffer.add(frame(1_000_000_000L, pixels))
        buffer.add(frame(1_033_000_000L, pixels))
        buffer.add(frame(1_066_000_000L, pixels))

        assertNull(buffer.add(frame(1_099_000_000L, pixels, rotationDegrees = 90)))
        assertNull(buffer.add(frame(1_132_000_000L, pixels, rotationDegrees = 90)))
        assertEquals(3, buffer.add(frame(1_165_000_000L, pixels, rotationDegrees = 90))!!.frames.size)
    }

    private fun frame(timestampNs: Long, pixels: FloatArray, rotationDegrees: Int = 0) = TemporalRgbFrame(
        timestampNs = timestampNs,
        cameraWidth = 1280,
        cameraHeight = 720,
        imageNetNormalizedRgbNchw = pixels,
        rotationDegrees = rotationDegrees,
    )
}