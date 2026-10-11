package dev.edgeai.pickleball.ball

import org.junit.Assert.assertEquals
import org.junit.Test

class WasbImageGeometryTest {
    @Test
    fun matchesFullFrameScaleAtModelAspectRatio() {
        val transform = WasbImageGeometry.transform(1280, 720)

        assertEquals(0.4, transform.scale, 0.0)
        assertEquals(0.0, transform.translateX, 0.0)
        assertEquals(0.0, transform.translateY, 0.0)
        assertEquals(1.25f, transform.modelXToCamera(0, 512), 0.001f)
        assertEquals(1.25f, transform.modelYToCamera(0, 288), 0.001f)
    }

    @Test
    fun centerCropsFourByThreeInputAndMapsPeakBackToSourcePixels() {
        val transform = WasbImageGeometry.transform(640, 480)

        assertEquals(0.8, transform.scale, 0.0)
        assertEquals(0.0, transform.translateX, 0.0)
        assertEquals(-48.0, transform.translateY, 0.0)
        assertEquals(320.625f, transform.modelXToCamera(256, 512), 0.001f)
        assertEquals(240.625f, transform.modelYToCamera(144, 288), 0.001f)
    }

    @Test
    fun portraitInputUsesCenteredPaddingInsteadOfStretchingOrCroppingSides() {
        val transform = WasbImageGeometry.transform(720, 1280)

        assertEquals(0.4, transform.scale, 0.0)
        assertEquals(112.0, transform.translateX, 0.0)
        assertEquals(-112.0, transform.translateY, 0.0)
        assertEquals(361.25f, transform.modelXToCamera(256, 512), 0.001f)
        assertEquals(641.25f, transform.modelYToCamera(144, 288), 0.001f)
    }

    @Test
    fun rotatesModelPeakBackToRawCameraCoordinates() {
        val rawFrame = TemporalRgbFrame(
            timestampNs = 1L,
            cameraWidth = 720,
            cameraHeight = 1280,
            imageNetNormalizedRgbNchw = FloatArray(WasbModelContract.VALUES_PER_FRAME),
            modelToCameraScaleX = 2.5f,
            modelToCameraScaleY = 2.5f,
            rotationDegrees = 90,
        )

        val cameraPoint = rawFrame.modelToCamera(256, 144)

        assertEquals(361.25, cameraPoint.x, 0.001)
        assertEquals(638.75, cameraPoint.y, 0.001)
    }
}