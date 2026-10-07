package dev.edgeai.prototype.voice.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.edgeai.prototype.voice.audio.CaptureStatus
import dev.edgeai.prototype.voice.audio.AudioInput
import dev.edgeai.prototype.voice.session.VoiceConversationSession
import dev.edgeai.prototype.voice.stt.SherpaStreamingSttEngine
import dev.edgeai.prototype.voice.tts.SpeechSynthesisStatus
import dev.edgeai.prototype.voice.tts.SpeechSynthesizer
import dev.edgeai.prototype.voice.vad.SherpaSileroVadEngine
import dev.edgeai.prototype.voice.vad.VadFrameProcessor
import dev.edgeai.prototype.voice.vad.VadWindowEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

enum class VoiceModelStatus { LOADING, READY, ERROR }

data class VadLabState(
    val modelStatus: VoiceModelStatus = VoiceModelStatus.LOADING,
    val modelLoadMillis: Double? = null,
    val speechDetected: Boolean = false,
    val probability: Float? = null,
    val windowsProcessed: Long = 0,
    val speechStarts: Long = 0,
    val speechEnds: Long = 0,
    val lastInferenceMillis: Double? = null,
    val maxInferenceMillis: Double = 0.0,
    val error: String? = null
)

data class SttLabState(
    val text: String = "",
    val isFinal: Boolean = false,
    val framesProcessed: Long = 0,
    val lastInferenceMillis: Double = 0.0,
    val maxInferenceMillis: Double = 0.0
)

class VoiceLabViewModel(
    private val audio: AudioInput,
    vadEngineFactory: () -> VadWindowEngine,
    sttEngineFactory: () -> SherpaStreamingSttEngine,
    private val speechSynthesizer: SpeechSynthesizer
) : ViewModel() {
    val state = audio.state
    private val mutableVadState = MutableStateFlow(VadLabState())
    val vadState = mutableVadState.asStateFlow()
    private val mutableSttState = MutableStateFlow(SttLabState())
    val sttState = mutableSttState.asStateFlow()
    private val conversation = VoiceConversationSession("voice-lab")
    private val mutableConversationContext = MutableStateFlow(conversation.context)
    val conversationContext = mutableConversationContext.asStateFlow()
    val ttsState = speechSynthesizer.state

    init {
        viewModelScope.launch {
            var captureAttempted = false
            audio.state.collect { captureState ->
                if (captureState.status == CaptureStatus.STARTING) captureAttempted = true
                if (captureAttempted && captureState.status in setOf(CaptureStatus.IDLE, CaptureStatus.ERROR)) {
                    conversation.cancel()
                    mutableConversationContext.value = conversation.context
                    captureAttempted = false
                }
            }
        }
        viewModelScope.launch {
            var processor: VadFrameProcessor? = null
            var sttEngine: SherpaStreamingSttEngine? = null
            try {
                val startedAt = System.nanoTime()
                val engine = withContext(Dispatchers.IO) { vadEngineFactory() }
                processor = VadFrameProcessor(engine)
                sttEngine = withContext(Dispatchers.IO) { sttEngineFactory() }
                mutableVadState.update {
                    it.copy(modelStatus = VoiceModelStatus.READY,
                        modelLoadMillis = (System.nanoTime() - startedAt) / 1_000_000.0)
                }
                audio.frames.collect { frame ->
                    val observations: List<dev.edgeai.prototype.voice.vad.VadObservation>
                    observations = withContext(Dispatchers.Default) { processor.process(frame) }
                    observations.forEach { observation ->
                        if (observation.speechStarted) {
                            if (speechSynthesizer.state.value.status in setOf(
                                SpeechSynthesisStatus.STARTING, SpeechSynthesisStatus.SPEAKING
                            )) {
                                speechSynthesizer.stop()
                            }
                            conversation.speechStarted()
                        }
                        if (observation.speechEnded) conversation.speechEnded()
                    }
                    val transcription = withContext(Dispatchers.Default) { sttEngine.accept(frame) }
                    if (transcription.text.isNotBlank()) {
                        conversation.acceptTranscript(transcription.text, transcription.isFinal)
                    }
                    mutableConversationContext.value = conversation.context
                    if (observations.isNotEmpty()) {
                        mutableVadState.update { old ->
                            observations.fold(old) { current, observation ->
                                current.copy(
                                    speechDetected = observation.speechDetected,
                                    probability = observation.probability,
                                    windowsProcessed = current.windowsProcessed + 1,
                                    speechStarts = current.speechStarts + if (observation.speechStarted) 1 else 0,
                                    speechEnds = current.speechEnds + if (observation.speechEnded) 1 else 0,
                                    lastInferenceMillis = observation.inferenceMillis,
                                    maxInferenceMillis = max(current.maxInferenceMillis, observation.inferenceMillis)
                                )
                            }
                        }
                    }
                    mutableSttState.update { old ->
                        old.copy(
                            text = transcription.text.ifBlank { old.text },
                            isFinal = if (transcription.text.isNotBlank()) transcription.isFinal else old.isFinal,
                            framesProcessed = old.framesProcessed + 1,
                            lastInferenceMillis = transcription.inferenceMillis,
                            maxInferenceMillis = max(old.maxInferenceMillis, transcription.inferenceMillis)
                        )
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableVadState.update {
                    it.copy(modelStatus = VoiceModelStatus.ERROR, error = failure.message ?: failure.javaClass.simpleName)
                }
            } finally {
                sttEngine?.let {
                    withContext(NonCancellable + Dispatchers.IO) { it.close() }
                }
                processor?.let {
                    withContext(NonCancellable + Dispatchers.IO) { it.close() }
                }
            }
        }
    }

    fun start() {
        conversation.startListening()
        mutableConversationContext.value = conversation.context
        viewModelScope.launch { audio.start() }
    }
    fun stop() {
        speechSynthesizer.stop()
        viewModelScope.launch { audio.stop() }
    }

    fun speakDemo(text: String): Boolean = speechSynthesizer.speak(text)
    fun stopSpeech() { speechSynthesizer.stop() }

    override fun onCleared() {
        speechSynthesizer.close()
        super.onCleared()
    }
}