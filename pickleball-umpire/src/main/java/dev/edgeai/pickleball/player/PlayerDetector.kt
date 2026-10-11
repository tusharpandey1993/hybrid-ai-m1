package dev.edgeai.pickleball.player

import dev.edgeai.pickleball.ball.TemporalFrameWindow

interface PlayerDetector : AutoCloseable {
    suspend fun detect(frames: TemporalFrameWindow): List<PlayerDetection>
}

data class PlayerBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left <= right && top <= bottom)
    }

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val footX: Float get() = centerX
    val footY: Float get() = bottom
    val area: Float get() = (right - left) * (bottom - top)

    fun intersectionOverUnion(other: PlayerBoundingBox): Float {
        val intersectionWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val intersectionHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = area + other.area - intersection
        return if (union > 0f) intersection / union else 0f
    }
}

data class PlayerDetection(
    val timestampNs: Long,
    val cameraBox: PlayerBoundingBox,
    val confidence: Float,
    val label: String = "person",
) {
    init {
        require(timestampNs >= 0L)
        require(confidence in 0f..1f)
    }
}

enum class PlayerCountMode(val maxPlayers: Int) {
    SINGLES(2),
    DOUBLES(4),
}

data class PlayerTrackId(val value: String)

data class PlayerState(
    val id: PlayerTrackId,
    val boundingBoxCamera: PlayerBoundingBox,
    val footPositionCameraX: Float,
    val footPositionCameraY: Float,
    val courtXFeet: Double,
    val courtYFeet: Double,
    val velocityXPerSecond: Float,
    val velocityYPerSecond: Float,
    val confidence: Float,
    val lastSeenTimestampNs: Long,
    val observed: Boolean,
)

data class PlayerTrackingUpdate(
    val timestampNs: Long,
    val players: List<PlayerState>,
    val rejectedOutsideCourt: Int,
    val ignoredBeyondConfiguredCapacity: Int,
)
