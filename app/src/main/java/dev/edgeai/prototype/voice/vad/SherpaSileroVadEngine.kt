package dev.edgeai.prototype.voice.vad

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

class SherpaSileroVadEngine(assetManager: AssetManager) : VadWindowEngine {
    private val vad = Vad(
        assetManager = assetManager,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_ASSET,
                windowSize = VadFrameProcessor.WINDOW_SIZE
            ),
            sampleRate = VadFrameProcessor.SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu"
        )
    )

    override fun computeProbability(samples: FloatArray): Float = vad.compute(samples)
    override fun reset() = vad.reset()
    override fun close() = vad.release()

    companion object {
        const val MODEL_ASSET = "vad/silero_vad.onnx"
    }
}