package dev.edgeai.pickleball.ball

import android.content.Context
import androidx.camera.core.ImageProxy
import dev.edgeai.pickleball.camera.Yuv420Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.Closeable

class WasbLiveBallPipeline private constructor(
    private val detector: WasbOnnxBallDetector,
    private val onDetection: (BallDetectionResult) -> Unit,
    private val onTrackState: (BallTrackState) -> Unit,
    private val onContactCandidate: (ContactCandidate) -> Unit,
) : Closeable {
    private val preprocessor = WasbYuvPreprocessor()
    private val temporalFrames = TemporalFrameBuffer()
    private val tracker = BallTracker()
    private val contactDetector = GroundContactDetector()

    fun processImage(image: ImageProxy) {
        val frame = Yuv420Frame.fromImageProxy(image)
        runBlocking { process(frame) }
    }

    suspend fun process(frame: Yuv420Frame) {
        val preprocessedFrame = preprocessor.preprocess(frame)
        val window = temporalFrames.add(preprocessedFrame) ?: return
        val result = detector.detect(window)
        onDetection(result)
        tracker.update(result)?.let { state ->
            onTrackState(state)
            contactDetector.update(state)?.let(onContactCandidate)
        }
    }

    override fun close() {
        preprocessor.close()
        detector.close()
    }

    companion object {
        suspend fun load(
            context: Context,
            onDetection: (BallDetectionResult) -> Unit,
            onTrackState: (BallTrackState) -> Unit = {},
            onContactCandidate: (ContactCandidate) -> Unit = {},
        ): WasbLiveBallPipeline = withContext(Dispatchers.IO) {
            check(OpenCVLoader.initLocal()) { "OpenCV failed to initialize" }
            val detector = WasbOnnxBallDetector.loadFromAssets(context)
            WasbLiveBallPipeline(detector, onDetection, onTrackState, onContactCandidate)
        }
    }
}