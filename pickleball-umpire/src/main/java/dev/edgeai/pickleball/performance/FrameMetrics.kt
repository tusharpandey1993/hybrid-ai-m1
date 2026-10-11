package dev.edgeai.pickleball.performance

data class FrameMetrics(
    val cameraTimestampNs: Long,
    val cameraFps: Double,
    val processingFps: Double,
    val averageProcessingMs: Double,
    val suspectedFrameGaps: Int,
    val maxFrameIntervalMs: Double,
    val frameWidth: Int,
    val frameHeight: Int,
)

class FrameMetricsAccumulator(
    private val targetFps: Double = 30.0,
    private val reportWindowNs: Long = 1_000_000_000L,
) {
    private var windowStartNs = 0L
    private var previousTimestampNs = 0L
    private var frameCount = 0
    private var processingTotalNs = 0L
    private var suspectedFrameGaps = 0
    private var maxFrameIntervalNs = 0L

    init {
        require(targetFps > 0.0)
        require(reportWindowNs > 0L)
    }

    fun record(
        cameraTimestampNs: Long,
        processingDurationNs: Long,
        frameWidth: Int,
        frameHeight: Int,
    ): FrameMetrics? {
        require(cameraTimestampNs > 0L)
        require(processingDurationNs >= 0L)
        require(frameWidth > 0 && frameHeight > 0)

        if (frameCount == 0) {
            windowStartNs = cameraTimestampNs
        } else {
            val intervalNs = cameraTimestampNs - previousTimestampNs
            if (intervalNs > 0L) {
                maxFrameIntervalNs = maxOf(maxFrameIntervalNs, intervalNs)
                if (intervalNs > 1_500_000_000.0 / targetFps) {
                    suspectedFrameGaps++
                }
            }
        }

        previousTimestampNs = cameraTimestampNs
        frameCount++
        processingTotalNs += processingDurationNs

        val windowDurationNs = cameraTimestampNs - windowStartNs
        if (windowDurationNs < reportWindowNs || windowDurationNs <= 0L) return null

        val measuredDurationNs = maxOf(1L, windowDurationNs)
        val metrics = FrameMetrics(
            cameraTimestampNs = cameraTimestampNs,
            cameraFps = (frameCount - 1) * 1_000_000_000.0 / measuredDurationNs,
            processingFps = frameCount * 1_000_000_000.0 / measuredDurationNs,
            averageProcessingMs = processingTotalNs / frameCount / 1_000_000.0,
            suspectedFrameGaps = suspectedFrameGaps,
            maxFrameIntervalMs = maxFrameIntervalNs / 1_000_000.0,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )

        windowStartNs = cameraTimestampNs
        frameCount = 1
        processingTotalNs = 0L
        suspectedFrameGaps = 0
        maxFrameIntervalNs = 0L
        return metrics
    }
}