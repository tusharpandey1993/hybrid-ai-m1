package dev.edgeai.pickleball.ball

import dev.edgeai.pickleball.court.CameraPoint
import java.io.Closeable

interface BallDetector : Closeable {
    suspend fun detect(window: TemporalFrameWindow): BallDetectionResult
}

object WasbModelContract {
    const val INPUT_FRAME_COUNT = 3
    const val WIDTH = 512
    const val HEIGHT = 288
    const val CHANNELS_PER_FRAME = 3
    const val VALUES_PER_FRAME = CHANNELS_PER_FRAME * WIDTH * HEIGHT
    const val INPUT_VALUES = INPUT_FRAME_COUNT * VALUES_PER_FRAME
    const val DEFAULT_PEAK_THRESHOLD = 0.5f
    const val INPUT_ASSET = "models/wasb_tennis_v1.onnx"
    const val EXTERNAL_DATA_ASSET = "models/wasb_tennis_v1.onnx.data"
}

enum class BallDetectionStatus {
    DETECTED,
    BELOW_THRESHOLD,
    SKIPPED_BUSY,
}

data class BallDetectionResult(
    val timestampNs: Long,
    val status: BallDetectionStatus,
    val peakX: Int?,
    val peakY: Int?,
    val cameraX: Float?,
    val cameraY: Float?,
    val confidence: Float,
    val frameIntervalsNs: List<Long>,
    val rotationDegrees: Int = 0,
)

data class TemporalRgbFrame(
    val timestampNs: Long,
    val cameraWidth: Int,
    val cameraHeight: Int,
    val imageNetNormalizedRgbNchw: FloatArray,
    val modelToCameraScaleX: Float = cameraWidth.toFloat() / WasbModelContract.WIDTH,
    val modelToCameraScaleY: Float = cameraHeight.toFloat() / WasbModelContract.HEIGHT,
    val modelToCameraOffsetX: Float = 0f,
    val modelToCameraOffsetY: Float = 0f,
    val rotationDegrees: Int = 0,
) {
    init {
        require(timestampNs >= 0L)
        require(cameraWidth > 0 && cameraHeight > 0)
        require(imageNetNormalizedRgbNchw.size == WasbModelContract.VALUES_PER_FRAME)
        require(rotationDegrees in setOf(0, 90, 180, 270))
    }

    fun modelToCamera(x: Int, y: Int): CameraPoint {
        val orientedX = (x + 0.5f) * modelToCameraScaleX + modelToCameraOffsetX
        val orientedY = (y + 0.5f) * modelToCameraScaleY + modelToCameraOffsetY
        return when (rotationDegrees) {
            0 -> CameraPoint(orientedX.toDouble(), orientedY.toDouble())
            90 -> CameraPoint(orientedY.toDouble(), (cameraHeight - orientedX).toDouble())
            180 -> CameraPoint(
                (cameraWidth - orientedX).toDouble(),
                (cameraHeight - orientedY).toDouble(),
            )
            270 -> CameraPoint((cameraWidth - orientedY).toDouble(), orientedX.toDouble())
            else -> error("Invalid frame rotation")
        }
    }
}

class TemporalFrameWindow private constructor(
    val frames: List<TemporalRgbFrame>,
) {
    val timestampNs: Long
        get() = frames.last().timestampNs

    val frameIntervalsNs: List<Long> = frames.zipWithNext { first, second ->
        second.timestampNs - first.timestampNs
    }

    companion object {
        fun from(frames: List<TemporalRgbFrame>): TemporalFrameWindow {
            require(frames.size == WasbModelContract.INPUT_FRAME_COUNT)
            require(frames.zipWithNext().all { (first, second) -> first.timestampNs < second.timestampNs }) {
                "Temporal frames must be strictly ordered by camera timestamp"
            }
            require(frames.all {
                it.cameraWidth == frames.first().cameraWidth &&
                    it.cameraHeight == frames.first().cameraHeight &&
                    it.rotationDegrees == frames.first().rotationDegrees
            }) { "Temporal frames must use the same camera resolution and orientation" }
            return TemporalFrameWindow(frames.toList())
        }
    }
}

class TemporalFrameBuffer {
    private val frames = arrayOfNulls<TemporalRgbFrame>(WasbModelContract.INPUT_FRAME_COUNT)
    private var nextIndex = 0
    private var frameCount = 0
    var rejectedTimestamps: Long = 0
        private set

    fun add(frame: TemporalRgbFrame): TemporalFrameWindow? {
        val previousIndex = (nextIndex - 1 + frames.size) % frames.size
        val previous = frames[previousIndex]
        if (previous != null && frame.timestampNs <= previous.timestampNs) {
            rejectedTimestamps++
            return null
        }
        if (previous != null && (previous.cameraWidth != frame.cameraWidth ||
            previous.cameraHeight != frame.cameraHeight ||
            previous.rotationDegrees != frame.rotationDegrees)
        ) {
            clear()
        }

        val storedPixels = frames[nextIndex]?.imageNetNormalizedRgbNchw
            ?: FloatArray(WasbModelContract.VALUES_PER_FRAME)
        frame.imageNetNormalizedRgbNchw.copyInto(storedPixels)
        frames[nextIndex] = frame.copy(imageNetNormalizedRgbNchw = storedPixels)
        nextIndex = (nextIndex + 1) % frames.size
        frameCount = minOf(frameCount + 1, frames.size)
        if (frameCount < frames.size) return null

        val oldestIndex = nextIndex
        return TemporalFrameWindow.from(List(frames.size) { offset ->
            requireNotNull(frames[(oldestIndex + offset) % frames.size])
        })
    }

    fun clear() {
        frames.fill(null)
        nextIndex = 0
        frameCount = 0
        rejectedTimestamps = 0
    }
}