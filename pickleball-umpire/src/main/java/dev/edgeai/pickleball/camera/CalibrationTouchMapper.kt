package dev.edgeai.pickleball.camera

import android.graphics.PointF
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import dev.edgeai.pickleball.court.CameraPoint

@androidx.annotation.OptIn(markerClass = [TransformExperimental::class])
class CalibrationTouchMapper {
    @Volatile
    private var analysisOutputTransform: OutputTransform? = null
    @Volatile
    private var imageWidth = 0
    @Volatile
    private var imageHeight = 0

    fun updateAnalysisTransform(transform: OutputTransform, width: Int, height: Int) {
        analysisOutputTransform = transform
        imageWidth = width
        imageHeight = height
    }

    fun previewToCamera(
        previewTransform: OutputTransform,
        previewX: Float,
        previewY: Float,
    ): CameraPoint? {
        val analysisTransform = analysisOutputTransform ?: return null
        val point = floatArrayOf(previewX, previewY)
        CoordinateTransform(previewTransform, analysisTransform).mapPoints(point)
        if (imageWidth <= 0 || imageHeight <= 0) return null
        return CameraPoint(
            point[0].coerceIn(0f, imageWidth.toFloat()).toDouble(),
            point[1].coerceIn(0f, imageHeight.toFloat()).toDouble(),
        )
    }

    fun cameraToPreview(
        previewTransform: OutputTransform,
        cameraPoint: CameraPoint,
    ): PointF? {
        val analysisTransform = analysisOutputTransform ?: return null
        val point = floatArrayOf(cameraPoint.x.toFloat(), cameraPoint.y.toFloat())
        CoordinateTransform(analysisTransform, previewTransform).mapPoints(point)
        return PointF(point[0], point[1])
    }
}