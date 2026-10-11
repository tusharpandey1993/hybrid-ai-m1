package dev.edgeai.pickleball.ball

import androidx.camera.core.ImageProxy
import dev.edgeai.pickleball.camera.Yuv420Frame
import dev.edgeai.pickleball.camera.Yuv420Plane
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.nio.ByteBuffer

class WasbYuvPreprocessor : Closeable {
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var sourceRotation = -1
    private var orientedWidth = 0
    private var orientedHeight = 0
    private var affine = WasbImageGeometry.transform(2, 2)
    private var yuvBytes = ByteArray(0)
    private var rgbBytes = ByteArray(WasbModelContract.WIDTH * WasbModelContract.HEIGHT * 3)
    private var normalizedPixels = FloatArray(WasbModelContract.VALUES_PER_FRAME)
    private var yuvMat: Mat? = null
    private var rgbMat: Mat? = null
    private var orientedRgbMat: Mat? = null
    private var resizedRgbMat: Mat? = null

    fun preprocess(image: ImageProxy): TemporalRgbFrame {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "WASB preprocessing requires YUV_420_888"
        }
        return preprocess(Yuv420Frame.fromImageProxy(image))
    }

    fun preprocess(frame: Yuv420Frame): TemporalRgbFrame {
        ensureBuffers(frame.width, frame.height, frame.rotationDegrees)
        copyPlane(frame.planes[0], frame.width, frame.height, yuvBytes, 0)
        val chromaSize = frame.width * frame.height / 4
        copyPlane(frame.planes[1], frame.width / 2, frame.height / 2,
            yuvBytes, frame.width * frame.height)
        copyPlane(frame.planes[2], frame.width / 2, frame.height / 2,
            yuvBytes, frame.width * frame.height + chromaSize)

        val sourceYuv = requireNotNull(yuvMat)
        val sourceRgb = requireNotNull(rgbMat)
        val orientedRgb = requireNotNull(orientedRgbMat)
        val targetRgb = requireNotNull(resizedRgbMat)
        sourceYuv.put(0, 0, yuvBytes)
        Imgproc.cvtColor(sourceYuv, sourceRgb, Imgproc.COLOR_YUV2RGB_I420)
        when (frame.rotationDegrees) {
            0 -> sourceRgb.copyTo(orientedRgb)
            90 -> Core.rotate(sourceRgb, orientedRgb, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(sourceRgb, orientedRgb, Core.ROTATE_180)
            270 -> Core.rotate(sourceRgb, orientedRgb, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
        val affineMatrix = Mat(2, 3, CvType.CV_64F).apply {
            put(0, 0, affine.scale, 0.0, affine.translateX)
            put(1, 0, 0.0, affine.scale, affine.translateY)
        }
        try {
            Imgproc.warpAffine(
                orientedRgb,
                targetRgb,
                affineMatrix,
                Size(WasbModelContract.WIDTH.toDouble(), WasbModelContract.HEIGHT.toDouble()),
                Imgproc.INTER_LINEAR,
                org.opencv.core.Core.BORDER_CONSTANT,
                org.opencv.core.Scalar(0.0, 0.0, 0.0),
            )
        } finally {
            affineMatrix.release()
        }
        targetRgb.get(0, 0, rgbBytes)
        normalizeRgb()

        return TemporalRgbFrame(
            timestampNs = frame.timestampNs,
            cameraWidth = frame.width,
            cameraHeight = frame.height,
            imageNetNormalizedRgbNchw = normalizedPixels,
            modelToCameraScaleX = (1.0 / affine.scale).toFloat(),
            modelToCameraScaleY = (1.0 / affine.scale).toFloat(),
            modelToCameraOffsetX = affine.sourceOffsetX.toFloat(),
            modelToCameraOffsetY = affine.sourceOffsetY.toFloat(),
            rotationDegrees = frame.rotationDegrees,
        )
    }

    private fun ensureBuffers(width: Int, height: Int, rotationDegrees: Int) {
        if (sourceWidth == width && sourceHeight == height && sourceRotation == rotationDegrees) return
        closeMats()
        sourceWidth = width
        sourceHeight = height
        sourceRotation = rotationDegrees
        val isQuarterTurn = rotationDegrees == 90 || rotationDegrees == 270
        orientedWidth = if (isQuarterTurn) height else width
        orientedHeight = if (isQuarterTurn) width else height
        affine = WasbImageGeometry.transform(orientedWidth, orientedHeight)
        yuvBytes = ByteArray(width * height * 3 / 2)
        yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
        rgbMat = Mat(height, width, CvType.CV_8UC3)
        orientedRgbMat = Mat(orientedHeight, orientedWidth, CvType.CV_8UC3)
        resizedRgbMat = Mat(WasbModelContract.HEIGHT, WasbModelContract.WIDTH, CvType.CV_8UC3)
    }

    private fun copyPlane(
        plane: Yuv420Plane,
        planeWidth: Int,
        planeHeight: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ) {
        val source = plane.buffer.duplicate()
        val sourceBase = source.position()
        var destinationIndex = destinationOffset
        for (row in 0 until planeHeight) {
            val rowOffset = row * plane.rowStride
            for (column in 0 until planeWidth) {
                destination[destinationIndex++] = source.get(
                    sourceBase + rowOffset + column * plane.pixelStride,
                )
            }
        }
    }

    private fun normalizeRgb() {
        val planeSize = WasbModelContract.WIDTH * WasbModelContract.HEIGHT
        for (pixel in 0 until planeSize) {
            val byteOffset = pixel * 3
            normalizedPixels[pixel] = ((rgbBytes[byteOffset].toInt() and 0xff) / 255f - 0.485f) / 0.229f
            normalizedPixels[planeSize + pixel] = ((rgbBytes[byteOffset + 1].toInt() and 0xff) / 255f - 0.456f) / 0.224f
            normalizedPixels[planeSize * 2 + pixel] = ((rgbBytes[byteOffset + 2].toInt() and 0xff) / 255f - 0.406f) / 0.225f
        }
    }

    private fun closeMats() {
        yuvMat?.release()
        rgbMat?.release()
        orientedRgbMat?.release()
        resizedRgbMat?.release()
        yuvMat = null
        rgbMat = null
        orientedRgbMat = null
        resizedRgbMat = null
    }

    override fun close() {
        closeMats()
        sourceWidth = 0
        sourceHeight = 0
        sourceRotation = -1
    }
}