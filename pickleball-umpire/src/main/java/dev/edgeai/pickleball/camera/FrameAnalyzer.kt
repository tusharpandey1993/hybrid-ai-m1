package dev.edgeai.pickleball.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import dev.edgeai.pickleball.performance.FrameMetrics
import dev.edgeai.pickleball.performance.FrameMetricsAccumulator
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(markerClass = [TransformExperimental::class])
class FrameAnalyzer(
    targetFps: Double = 30.0,
    private val onMetrics: (FrameMetrics) -> Unit,
    private val onTransform: (OutputTransform, Int, Int) -> Unit = { _, _, _ -> },
    private val shouldCaptureTransform: () -> Boolean = { false },
    private val onImage: (ImageProxy) -> Unit = {},
    private val onFrameDropped: (Long) -> Unit = {},
    private val onProcessingError: (Exception) -> Unit = {},
) : ImageAnalysis.Analyzer, Closeable {
    private val metricsAccumulator = FrameMetricsAccumulator(targetFps = targetFps)
    private val processing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val processingExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pickleball-frame-worker")
    }
    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingCropRect = true
        isUsingRotationDegrees = false
    }

    override fun analyze(image: ImageProxy) {
        val timestampNs = image.imageInfo.timestamp
        val frameWidth = image.width
        val frameHeight = image.height
        if (closed.get() || !processing.compareAndSet(false, true)) {
            try {
                onFrameDropped(timestampNs)
            } finally {
                image.close()
            }
            return
        }

        try {
            processingExecutor.execute {
                val startedNs = SystemClock.elapsedRealtimeNanos()
                try {
                    if (shouldCaptureTransform()) {
                        onTransform(transformFactory.getOutputTransform(image), image.width, image.height)
                    }
                    onImage(image)
                } catch (error: Exception) {
                    onProcessingError(error)
                } finally {
                    val durationNs = SystemClock.elapsedRealtimeNanos() - startedNs
                    try {
                        image.close()
                    } finally {
                        processing.set(false)
                    }
                    try {
                        metricsAccumulator.record(
                            cameraTimestampNs = timestampNs,
                            processingDurationNs = durationNs,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                        )?.let(onMetrics)
                    } catch (error: Exception) {
                        onProcessingError(error)
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            processing.set(false)
            image.close()
            onFrameDropped(timestampNs)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) processingExecutor.shutdown()
    }

    fun awaitClosed(timeoutMs: Long): Boolean =
        processingExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
}