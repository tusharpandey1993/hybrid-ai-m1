package dev.edgeai.pickleball.camera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

data class Yuv420Plane(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
)

data class Yuv420Frame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val planes: List<Yuv420Plane>,
) {
    init {
        require(timestampNs >= 0L)
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(planes.size == 3)
    }

    companion object {
        fun fromImageProxy(image: ImageProxy): Yuv420Frame = Yuv420Frame(
            timestampNs = image.imageInfo.timestamp,
            width = image.width,
            height = image.height,
            rotationDegrees = image.imageInfo.rotationDegrees,
            planes = image.planes.map { plane ->
                Yuv420Plane(plane.buffer, plane.rowStride, plane.pixelStride)
            },
        )
    }
}