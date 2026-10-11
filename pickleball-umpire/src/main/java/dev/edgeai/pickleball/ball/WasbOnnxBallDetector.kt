package dev.edgeai.pickleball.ball

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class WasbOnnxBallDetector private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val peakThreshold: Float,
) : BallDetector {
    private val inFlight = AtomicBoolean(false)
    private val inputBuffer = ByteBuffer
        .allocateDirect(WasbModelContract.INPUT_VALUES * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val outputBuffer = FloatArray(WasbModelContract.WIDTH * WasbModelContract.HEIGHT)

    override suspend fun detect(window: TemporalFrameWindow): BallDetectionResult {
        if (!inFlight.compareAndSet(false, true)) return WasbHeatmapDecoder.skipped(window)
        try {
            return withContext(Dispatchers.Default) {
                inputBuffer.clear()
                window.frames.forEach { inputBuffer.put(it.imageNetNormalizedRgbNchw) }
                inputBuffer.flip()
                val tensor = OnnxTensor.createTensor(environment, inputBuffer, INPUT_SHAPE)
                try {
                    session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                        val output = result.get(OUTPUT_NAME).orElseThrow {
                            IllegalStateException("WASB model did not return '$OUTPUT_NAME'")
                        }
                        val heatmap = output as? OnnxTensor
                            ?: throw IllegalStateException("WASB output '$OUTPUT_NAME' is not a tensor")
                        val shape = heatmap.info.shape
                        require(shape.contentEquals(OUTPUT_SHAPE)) {
                            "Unexpected WASB output shape: ${shape.contentToString()}"
                        }
                        heatmap.floatBuffer.get(outputBuffer)
                        WasbHeatmapDecoder.decode(
                            heatmap = outputBuffer,
                            timestampNs = window.timestampNs,
                            sourceFrame = window.frames.last(),
                            frameIntervalsNs = window.frameIntervalsNs,
                            threshold = peakThreshold,
                        )
                    }
                } finally {
                    tensor.close()
                }
            }
        } finally {
            inFlight.set(false)
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val INPUT_NAME = "frames"
        private const val OUTPUT_NAME = "heatmap"
        private val INPUT_SHAPE = longArrayOf(1, 9, 288, 512)
        private val OUTPUT_SHAPE = longArrayOf(1, 1, 288, 512)

        suspend fun loadFromAssets(
            context: Context,
            peakThreshold: Float = WasbModelContract.DEFAULT_PEAK_THRESHOLD,
        ): WasbOnnxBallDetector = withContext(Dispatchers.IO) {
            require(peakThreshold in 0f..1f)
            val modelDirectory = File(context.filesDir, "models/wasb")
            check(modelDirectory.exists() || modelDirectory.mkdirs()) {
                "Could not create private model directory"
            }
            val modelFile = File(modelDirectory, "wasb_tennis_v1.onnx")
            val dataFile = File(modelDirectory, "wasb_tennis_v1.onnx.data")
            copyAssetIfNeeded(context, WasbModelContract.INPUT_ASSET, modelFile)
            copyAssetIfNeeded(context, WasbModelContract.EXTERNAL_DATA_ASSET, dataFile)

            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(2)
                val session = environment.createSession(modelFile.absolutePath, options)
                WasbOnnxBallDetector(environment, session, peakThreshold)
            } finally {
                options.close()
            }
        }

        private fun copyAssetIfNeeded(context: Context, assetName: String, destination: File) {
            if (destination.exists() && destination.length() > 0L) return
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            context.assets.open(assetName).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(destination)) { "Could not stage model asset $assetName" }
        }
    }
}