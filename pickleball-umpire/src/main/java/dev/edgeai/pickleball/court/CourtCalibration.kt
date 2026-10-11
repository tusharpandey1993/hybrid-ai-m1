package dev.edgeai.pickleball.court

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint2f
import kotlin.math.hypot
import kotlin.math.sqrt

enum class CalibrationQuality {
    EXCELLENT,
    GOOD,
    POOR,
    INVALID,
}

data class CourtCalibration(
    val transformer: HomographyTransformer,
    val quality: CalibrationQuality,
    val reprojectionErrorPixels: Double,
    val inlierCount: Int,
    val pointCount: Int,
)

object CourtCalibrator {
    fun estimate(
        cameraPoints: List<CameraPoint>,
        courtPoints: List<CourtPoint>,
        ransacThresholdFeet: Double = 0.5,
    ): CourtCalibration {
        require(cameraPoints.size == courtPoints.size)
        require(cameraPoints.size >= 4)
        require(ransacThresholdFeet > 0.0)

        val source = MatOfPoint2f().apply {
            fromArray(*cameraPoints.map { org.opencv.core.Point(it.x, it.y) }
                .toTypedArray())
        }
        val destination = MatOfPoint2f().apply {
            fromArray(*courtPoints.map { org.opencv.core.Point(it.xFeet, it.yFeet) }
                .toTypedArray())
        }
        val mask = MatOfByte()
        val homography = Calib3d.findHomography(
            source,
            destination,
            Calib3d.RANSAC,
            ransacThresholdFeet,
            mask,
        )

        try {
            if (homography.empty()) return invalid(cameraPoints.size)
            val values = DoubleArray(9)
            homography.get(0, 0, values)
            val transformer = HomographyTransformer(values)
            val inverse = Mat()
            Core.invert(homography, inverse)
            val projectedCameraPoints = MatOfPoint2f()
            try {
                Core.perspectiveTransform(destination, projectedCameraPoints, inverse)
                val projected = projectedCameraPoints.toArray()
                val inlierMask = mask.toArray()
                val inlierIndices = inlierMask.indices.filter { inlierMask[it].toInt() != 0 }
                val inliers = inlierIndices.size
                if (inliers < 4) return invalid(cameraPoints.size)
                val inlierErrors = inlierIndices.map { index ->
                    hypot(
                        projected[index].x - cameraPoints[index].x,
                        projected[index].y - cameraPoints[index].y,
                    )
                }
                val rms = sqrt(inlierErrors.sumOf { it * it } / inlierErrors.size)
                val quality = qualityFor(rms, inliers, cameraPoints.size)
                return CourtCalibration(transformer, quality, rms, inliers, cameraPoints.size)
            } finally {
                inverse.release()
                projectedCameraPoints.release()
            }
        } catch (_: Exception) {
            return invalid(cameraPoints.size)
        } finally {
            source.release()
            destination.release()
            mask.release()
            homography.release()
        }
    }

    private fun qualityFor(errorPixels: Double, inliers: Int, total: Int): CalibrationQuality {
        if (inliers < 4 || inliers.toDouble() / total < 0.75 || errorPixels > 24.0) {
            return CalibrationQuality.POOR
        }
        return when {
            errorPixels <= 5.0 -> CalibrationQuality.EXCELLENT
            errorPixels <= 12.0 -> CalibrationQuality.GOOD
            else -> CalibrationQuality.POOR
        }
    }

    private fun invalid(pointCount: Int) = CourtCalibration(
        transformer = HomographyTransformer(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
        quality = CalibrationQuality.INVALID,
        reprojectionErrorPixels = Double.POSITIVE_INFINITY,
        inlierCount = 0,
        pointCount = pointCount,
    )
}