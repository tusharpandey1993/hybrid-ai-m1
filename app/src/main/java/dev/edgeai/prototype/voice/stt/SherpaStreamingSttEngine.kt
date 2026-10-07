package dev.edgeai.prototype.voice.stt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.edgeai.prototype.voice.audio.AudioFrame

data class SttUpdate(
    val text: String,
    val isFinal: Boolean,
    val inferenceMillis: Double
)

class SherpaStreamingSttEngine(assetManager: AssetManager) : AutoCloseable {
    private val recognizer = OnlineRecognizer(
        assetManager = assetManager,
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioFrame.SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = ENCODER_ASSET,
                    decoder = DECODER_ASSET,
                    joiner = JOINER_ASSET
                ),
                tokens = TOKENS_ASSET,
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer"
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 1
        )
    )
    private val stream: OnlineStream = recognizer.createStream()
    private val input = FloatArray(AudioFrame.SAMPLE_COUNT)
    private var sessionId: Long? = null

    fun accept(frame: AudioFrame): SttUpdate {
        if (sessionId != frame.sessionId) {
            recognizer.reset(stream)
            sessionId = frame.sessionId
        }

        val pcm = frame.copyPcm()
        for (index in pcm.indices) input[index] = pcm[index] / 32768.0f

        val startedAt = System.nanoTime()
        stream.acceptWaveform(input, AudioFrame.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        val isFinal = recognizer.isEndpoint(stream)
        val inferenceMillis = (System.nanoTime() - startedAt) / 1_000_000.0
        if (isFinal) recognizer.reset(stream)
        return SttUpdate(result.text, isFinal, inferenceMillis)
    }

    override fun close() {
        stream.release()
        recognizer.release()
    }

    companion object {
        const val ENCODER_ASSET = "stt/encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER_ASSET = "stt/decoder-epoch-99-avg-1.int8.onnx"
        const val JOINER_ASSET = "stt/joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS_ASSET = "stt/tokens.txt"
    }
}