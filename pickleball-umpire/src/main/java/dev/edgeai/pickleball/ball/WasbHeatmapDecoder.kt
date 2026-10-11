package dev.edgeai.pickleball.ball

object WasbHeatmapDecoder {
    fun decode(
        heatmap: FloatArray,
        timestampNs: Long,
        sourceFrame: TemporalRgbFrame,
        frameIntervalsNs: List<Long>,
        threshold: Float = WasbModelContract.DEFAULT_PEAK_THRESHOLD,
    ): BallDetectionResult {
        require(heatmap.size == WasbModelContract.WIDTH * WasbModelContract.HEIGHT)
        require(threshold in 0f..1f)

        var maximum = Float.NEGATIVE_INFINITY
        var maximumIndex = -1
        heatmap.forEachIndexed { index, value ->
            if (value.isFinite() && value > maximum) {
                maximum = value
                maximumIndex = index
            }
        }
        require(maximumIndex >= 0) { "Heatmap has no finite values" }

        val x = maximumIndex % WasbModelContract.WIDTH
        val y = maximumIndex / WasbModelContract.WIDTH
        val detected = maximum >= threshold
        val cameraPoint = sourceFrame.modelToCamera(x, y)
        return BallDetectionResult(
            timestampNs = timestampNs,
            status = if (detected) BallDetectionStatus.DETECTED else BallDetectionStatus.BELOW_THRESHOLD,
            peakX = x,
            peakY = y,
            cameraX = cameraPoint.x.toFloat(),
            cameraY = cameraPoint.y.toFloat(),
            confidence = maximum,
            frameIntervalsNs = frameIntervalsNs,
            rotationDegrees = sourceFrame.rotationDegrees,
        )
    }

    fun skipped(window: TemporalFrameWindow) = BallDetectionResult(
        timestampNs = window.timestampNs,
        status = BallDetectionStatus.SKIPPED_BUSY,
        peakX = null,
        peakY = null,
        cameraX = null,
        cameraY = null,
        confidence = 0f,
        frameIntervalsNs = window.frameIntervalsNs,
        rotationDegrees = window.frames.last().rotationDegrees,
    )
}