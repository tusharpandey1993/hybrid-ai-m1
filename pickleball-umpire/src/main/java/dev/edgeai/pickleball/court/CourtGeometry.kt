package dev.edgeai.pickleball.court

import kotlin.math.abs
import kotlin.math.hypot

data class CameraPoint(val x: Double, val y: Double)

data class CourtPoint(val xFeet: Double, val yFeet: Double)

data class CourtLine(
    val id: String,
    val start: CourtPoint,
    val end: CourtPoint,
)

data class LineDistanceResult(
    val line: CourtLine,
    val signedDistanceFeet: Double,
    val absoluteDistanceFeet: Double,
)

object PickleballCourtGeometry {
    const val WIDTH_FEET = 20.0
    const val LENGTH_FEET = 44.0
    const val NET_Y_FEET = 22.0
    const val KITCHEN_OFFSET_FEET = 7.0
    const val NEAR_KITCHEN_Y_FEET = NET_Y_FEET - KITCHEN_OFFSET_FEET
    const val FAR_KITCHEN_Y_FEET = NET_Y_FEET + KITCHEN_OFFSET_FEET

    val calibrationAnchors: List<NamedCourtPoint> = listOf(
        NamedCourtPoint("NEAR_LEFT_BASELINE", CourtPoint(0.0, 0.0)),
        NamedCourtPoint("NEAR_RIGHT_BASELINE", CourtPoint(WIDTH_FEET, 0.0)),
        NamedCourtPoint("NEAR_LEFT_KITCHEN", CourtPoint(0.0, NEAR_KITCHEN_Y_FEET)),
        NamedCourtPoint("NEAR_RIGHT_KITCHEN", CourtPoint(WIDTH_FEET, NEAR_KITCHEN_Y_FEET)),
        NamedCourtPoint("FAR_LEFT_KITCHEN", CourtPoint(0.0, FAR_KITCHEN_Y_FEET)),
        NamedCourtPoint("FAR_RIGHT_KITCHEN", CourtPoint(WIDTH_FEET, FAR_KITCHEN_Y_FEET)),
        NamedCourtPoint("FAR_LEFT_BASELINE", CourtPoint(0.0, LENGTH_FEET)),
        NamedCourtPoint("FAR_RIGHT_BASELINE", CourtPoint(WIDTH_FEET, LENGTH_FEET)),
    )

    val lines: List<CourtLine> = listOf(
        CourtLine("LEFT_SIDELINE", CourtPoint(0.0, LENGTH_FEET), CourtPoint(0.0, 0.0)),
        CourtLine("RIGHT_SIDELINE", CourtPoint(WIDTH_FEET, 0.0), CourtPoint(WIDTH_FEET, LENGTH_FEET)),
        CourtLine("NEAR_BASELINE", CourtPoint(0.0, 0.0), CourtPoint(WIDTH_FEET, 0.0)),
        CourtLine("FAR_BASELINE", CourtPoint(WIDTH_FEET, LENGTH_FEET), CourtPoint(0.0, LENGTH_FEET)),
        CourtLine("NEAR_KITCHEN_LINE", CourtPoint(0.0, NEAR_KITCHEN_Y_FEET), CourtPoint(WIDTH_FEET, NEAR_KITCHEN_Y_FEET)),
        CourtLine("FAR_KITCHEN_LINE", CourtPoint(0.0, FAR_KITCHEN_Y_FEET), CourtPoint(WIDTH_FEET, FAR_KITCHEN_Y_FEET)),
        CourtLine("NEAR_CENTERLINE", CourtPoint(WIDTH_FEET / 2.0, 0.0), CourtPoint(WIDTH_FEET / 2.0, NEAR_KITCHEN_Y_FEET)),
        CourtLine("FAR_CENTERLINE", CourtPoint(WIDTH_FEET / 2.0, FAR_KITCHEN_Y_FEET), CourtPoint(WIDTH_FEET / 2.0, LENGTH_FEET)),
    )

    fun isInsideBoundary(point: CourtPoint): Boolean =
        point.xFeet in 0.0..WIDTH_FEET && point.yFeet in 0.0..LENGTH_FEET

    fun distanceToLine(point: CourtPoint, line: CourtLine): LineDistanceResult {
        val dx = line.end.xFeet - line.start.xFeet
        val dy = line.end.yFeet - line.start.yFeet
        val length = hypot(dx, dy)
        require(length > 0.0) { "Court lines must have non-zero length" }

        val projection = (((point.xFeet - line.start.xFeet) * dx +
            (point.yFeet - line.start.yFeet) * dy) / (length * length)).coerceIn(0.0, 1.0)
        val nearestX = line.start.xFeet + projection * dx
        val nearestY = line.start.yFeet + projection * dy
        val unsignedDistance = hypot(point.xFeet - nearestX, point.yFeet - nearestY)
        val signedDistance = ((dx * (point.yFeet - nearestY) -
            dy * (point.xFeet - nearestX)) / length)

        return LineDistanceResult(line, signedDistance, abs(unsignedDistance))
    }

    fun nearestLine(point: CourtPoint): LineDistanceResult = lines
        .map { distanceToLine(point, it) }
        .minBy { it.absoluteDistanceFeet }
}

data class NamedCourtPoint(val name: String, val point: CourtPoint)

class HomographyTransformer(matrix: DoubleArray) {
    private val values = matrix.copyOf().also {
        require(it.size == 9) { "A 3x3 homography requires nine values" }
    }

    fun cameraToCourt(point: CameraPoint): CourtPoint {
        val denominator = values[6] * point.x + values[7] * point.y + values[8]
        require(abs(denominator) > 1e-12) { "Point maps to infinity" }
        return CourtPoint(
            xFeet = (values[0] * point.x + values[1] * point.y + values[2]) / denominator,
            yFeet = (values[3] * point.x + values[4] * point.y + values[5]) / denominator,
        )
    }

    fun courtToCamera(point: CourtPoint): CameraPoint =
        inverse(values).let { inverse ->
            val denominator = inverse[6] * point.xFeet + inverse[7] * point.yFeet + inverse[8]
            require(abs(denominator) > 1e-12) { "Point maps to infinity" }
            CameraPoint(
                (inverse[0] * point.xFeet + inverse[1] * point.yFeet + inverse[2]) / denominator,
                (inverse[3] * point.xFeet + inverse[4] * point.yFeet + inverse[5]) / denominator,
            )
        }

    fun values(): DoubleArray = values.copyOf()

    private fun inverse(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(determinant) > 1e-12) { "Homography matrix is singular" }
        val scale = 1.0 / determinant
        return doubleArrayOf(
            (e * i - f * h) * scale,
            (c * h - b * i) * scale,
            (b * f - c * e) * scale,
            (f * g - d * i) * scale,
            (a * i - c * g) * scale,
            (c * d - a * f) * scale,
            (d * h - e * g) * scale,
            (b * g - a * h) * scale,
            (a * e - b * d) * scale,
        )
    }
}