package dev.edgeai.prototype.voice.debug

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.edgeai.prototype.voice.audio.CaptureStatus
import dev.edgeai.prototype.voice.audio.AudioInput
import dev.edgeai.prototype.voice.conversation.AssistantResponse
import dev.edgeai.prototype.voice.conversation.InterruptionEvent
import dev.edgeai.prototype.voice.conversation.InterruptionMonitor
import dev.edgeai.prototype.voice.conversation.InterruptionState
import dev.edgeai.prototype.voice.conversation.LexicalSelfSpeechDetector
import dev.edgeai.prototype.voice.conversation.SelfSpeechResult
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
import java.util.concurrent.atomic.AtomicLong
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
    private val interruptionMonitor = InterruptionMonitor()
    private val selfSpeechDetector = LexicalSelfSpeechDetector()
    private val ttsStartedAtNanos = AtomicLong(0)
    private val mutableSelfSpeechResult = MutableStateFlow<SelfSpeechResult?>(null)
    val selfSpeechResult = mutableSelfSpeechResult.asStateFlow()
    private val mutableInterruptionState = MutableStateFlow(InterruptionState())
    val interruptionState = mutableInterruptionState.asStateFlow()
    private val mutableInterruptionEvents = MutableStateFlow<List<InterruptionEvent>>(emptyList())
    val interruptionEvents = mutableInterruptionEvents.asStateFlow()
    private val nextDemoResponseId = AtomicLong()
    private val mutableConversationContext = MutableStateFlow(conversation.context)
    val conversationContext = mutableConversationContext.asStateFlow()
    val ttsState = speechSynthesizer.state
    private var lastCompletedPlaybackSequence = 0L

    init {
        viewModelScope.launch {
            speechSynthesizer.state.collect { state ->
                val responseId = state.completedResponseId
                if (state.status == SpeechSynthesisStatus.READY && responseId != null &&
                    state.completedPlaybackSequence > lastCompletedPlaybackSequence) {
                    ttsStartedAtNanos.set(0)
                    lastCompletedPlaybackSequence = state.completedPlaybackSequence
                    conversation.ttsCompleted(responseId, conversation.context.playbackEpoch)
                    recordInterruptionEvents(interruptionMonitor.assistantPlaybackStopped(responseId))
                    mutableConversationContext.value = conversation.context
                }
            }
        }
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
                var lastLoggedTranscriptText = ""
                mutableVadState.update {
                    it.copy(modelStatus = VoiceModelStatus.READY,
                        modelLoadMillis = (System.nanoTime() - startedAt) / 1_000_000.0)
                }
                Log.i(TAG, "speech_models_ready load_ms=${(System.nanoTime() - startedAt) / 1_000_000.0}")
                audio.frames.collect { frame ->
                    val observations: List<dev.edgeai.prototype.voice.vad.VadObservation>
                    observations = withContext(Dispatchers.Default) { processor.process(frame) }
                    observations.forEach { observation ->
                        if (observation.speechStarted) {
                            val playing = speechSynthesizer.state.value.status in setOf(
                                SpeechSynthesisStatus.STARTING, SpeechSynthesisStatus.SPEAKING
                            )
                            val responseId = conversation.context.response?.responseId.takeIf { playing }
                            val ttsStart = ttsStartedAtNanos.get()
                            val insideTtsStartupGuard = playing && ttsStart > 0L &&
                                System.nanoTime() - ttsStart < TTS_STARTUP_VAD_GUARD_NANOS
                            if (insideTtsStartupGuard) {
                                Log.i(TAG, "vad_ignored_tts_startup probability=${observation.probability} " +
                                    "response_id=${responseId ?: "none"}")
                            } else if (playing) {
                                conversation.speechStarted()
                                val stopCallMillis = speechSynthesizer.stop()
                                val stopState = speechSynthesizer.state.value.status
                                ttsStartedAtNanos.set(0)
                                val suppressionConfirmed = stopState == SpeechSynthesisStatus.READY
                                recordInterruptionEvents(interruptionMonitor.potentialSpeechStarted(
                                    responseId.takeIf { suppressionConfirmed }))
                                responseId?.let {
                                    if (suppressionConfirmed) {
                                        recordInterruptionEvents(interruptionMonitor.assistantPlaybackStopped(it))
                                    }
                                    Log.i(TAG, "playback_stop response_id=$it " +
                                        "state=$stopState suppression_confirmed=$suppressionConfirmed " +
                                        "stop_call_ms=$stopCallMillis")
                                }
                            } else {
                                recordInterruptionEvents(interruptionMonitor.potentialSpeechStarted(null))
                            }
                            Log.i(TAG, "vad_started probability=${observation.probability} " +
                                "tts_playing=$playing response_id=${responseId ?: "none"}")
                        }
                        if (observation.speechEnded) {
                            conversation.speechEnded()
                            recordInterruptionEvents(interruptionMonitor.potentialSpeechEnded())
                            Log.i(TAG, "vad_ended probability=${observation.probability}")
                        }
                    }
                    val transcription = withContext(Dispatchers.Default) { sttEngine.accept(frame) }
                    transcription.transcript?.let {
                        val currentContext = conversation.context
                        val assistantResponseActive = currentContext.response != null ||
                            currentContext.suspendedResponse != null
                        mutableSelfSpeechResult.value = selfSpeechDetector.evaluate(
                            currentContext.response ?: currentContext.suspendedResponse, it)
                        recordInterruptionEvents(interruptionMonitor.transcriptCandidate(it))
                        if (assistantResponseActive) {
                            Log.i(TAG, "transcript_candidate_held response_id=" +
                                "${currentContext.response?.responseId ?: currentContext.suspendedResponse?.responseId} " +
                                "final=${transcription.isFinal} chars=${it.text.length}")
                        } else {
                            if (!currentContext.userTurnOpen) conversation.speechStarted()
                            conversation.acceptTranscript(it, transcription.isFinal)
                        }
                        if (it.text != lastLoggedTranscriptText || transcription.isFinal) {
                            lastLoggedTranscriptText = it.text
                            val similarity = mutableSelfSpeechResult.value
                            Log.i(TAG, "stt_candidate final=${transcription.isFinal} chars=${it.text.length} " +
                                "self_similarity=${similarity?.similarity} " +
                                "likely_playback_leakage=${similarity?.likelyPlaybackLeakage}")
                        }
                    }
                    if (transcription.isFinal && transcription.transcript == null &&
                        interruptionMonitor.state.status ==
                        dev.edgeai.prototype.voice.conversation.InterruptionStatus.POTENTIAL_AUDIO_ACTIVITY) {
                        var playbackResumed = false
                        var resumedResponseId: String? = null
                        if (conversation.falseInterruptionDetected()) {
                            val response = conversation.context.response
                            if (response != null) {
                                val remaining = response.copy(
                                    text = response.text.substring(response.resumePosition),
                                    resumePosition = 0
                                )
                                playbackResumed = requestSpeech(remaining)
                                if (playbackResumed) {
                                    val epoch = conversation.context.playbackEpoch
                                    conversation.ttsStarted(response.responseId, epoch)
                                    resumedResponseId = response.responseId
                                }
                            }
                        }
                        recordInterruptionEvents(
                            interruptionMonitor.falseInterruptionDetected(playbackResumed))
                        resumedResponseId?.let {
                            recordInterruptionEvents(interruptionMonitor.assistantPlaybackStarted(it))
                        }
                        Log.i(TAG, "false_interruption playback_resumed=$playbackResumed " +
                            "response_preserved=${conversation.context.response != null}")
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
                            text = transcription.transcript?.text ?: old.text,
                            isFinal = if (transcription.transcript != null) transcription.isFinal else old.isFinal,
                            framesProcessed = old.framesProcessed + 1,
                            lastInferenceMillis = transcription.inferenceMillis,
                            maxInferenceMillis = max(old.maxInferenceMillis, transcription.inferenceMillis)
                        )
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "voice_pipeline_failed type=${failure.javaClass.simpleName}", failure)
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

    fun speakDemo(text: String): Boolean {
        val response = AssistantResponse(
            threadId = "voice-lab",
            responseId = "demo-${nextDemoResponseId.incrementAndGet()}",
            turnId = conversation.context.turnId,
            text = text
        )
        if (!conversation.assistantDemoResponseReady(response)) {
            Log.w(TAG, "playback_state_rejected response_id=${response.responseId} " +
                "conversation_state=${conversation.context.state} turn=${conversation.context.turnId} " +
                "turn_type=${conversation.context.turnType} committed=${conversation.context.committedTranscript != null}")
            return false
        }
        val playbackEpoch = conversation.context.playbackEpoch
        if (!requestSpeech(response)) return false
        recordInterruptionEvents(interruptionMonitor.assistantPlaybackStarted(response.responseId))
        conversation.ttsStarted(response.responseId, playbackEpoch)
        mutableConversationContext.value = conversation.context
        Log.i(TAG, "playback_started response_id=${response.responseId} chars=${response.text.length} " +
            "capture=${audio.state.value.status}")
        return true
    }
    fun stopSpeech() {
        speechSynthesizer.stop()
        ttsStartedAtNanos.set(0)
    }

    private fun requestSpeech(response: AssistantResponse): Boolean {
        ttsStartedAtNanos.set(System.nanoTime())
        val accepted = speechSynthesizer.speak(response)
        if (!accepted) ttsStartedAtNanos.set(0)
        return accepted
    }

    private fun recordInterruptionEvents(events: List<InterruptionEvent>) {
        if (events.isEmpty()) return
        mutableInterruptionEvents.update { (it + events).takeLast(20) }
        mutableInterruptionState.value = interruptionMonitor.state
        events.forEach { Log.i(TAG, "interruption_event=${it::class.simpleName} state=${interruptionMonitor.state}") }
    }

    private companion object {
        const val TAG = "VoiceDuplex"
        const val TTS_STARTUP_VAD_GUARD_NANOS = 300_000_000L
    }

    override fun onCleared() {
        speechSynthesizer.close()
        super.onCleared()
    }
}