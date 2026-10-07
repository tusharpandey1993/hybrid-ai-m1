package dev.edgeai.prototype.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.edgeai.prototype.voice.conversation.AssistantResponse
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AndroidTtsSynthesizer(context: Context) : SpeechSynthesizer {
    private val mutableState = MutableStateFlow(SpeechSynthesisState())
    override val state = mutableState.asStateFlow()
    private val nextUtteranceId = AtomicLong()
    private val completedPlaybackSequence = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var activeResponseId: String? = null
    private val tts = TextToSpeech(context.applicationContext, ::onInitialized)

    private fun onInitialized(result: Int) {
        if (closed) return
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "tts_initialization_failed result=$result")
            mutableState.update { it.copy(status = SpeechSynthesisStatus.ERROR, error = "TTS initialization failed") }
            return
        }

        if (tts.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) {
            Log.e(TAG, "tts_language_unavailable language=en-US")
            mutableState.update { it.copy(status = SpeechSynthesisStatus.ERROR, error = "English TTS voice unavailable") }
            return
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (tts.setAudioAttributes(audioAttributes) != TextToSpeech.SUCCESS) {
            Log.e(TAG, "tts_audio_attributes_failed usage=VOICE_COMMUNICATION")
            mutableState.update {
                it.copy(status = SpeechSynthesisStatus.ERROR, error = "TTS audio configuration failed")
            }
            return
        }
        Log.i(TAG, "tts_ready engine=${tts.defaultEngine ?: "unknown"}")

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                if (!closed && utteranceId == activeUtteranceId) {
                    Log.i(TAG, "tts_started response_id=${activeResponseId ?: "unknown"}")
                    mutableState.update { it.copy(status = SpeechSynthesisStatus.SPEAKING, error = null) }
                }
            }

            override fun onDone(utteranceId: String) {
                if (!closed && utteranceId == activeUtteranceId) {
                    activeUtteranceId = null
                    val responseId = activeResponseId
                    activeResponseId = null
                    val completionSequence = completedPlaybackSequence.incrementAndGet()
                    Log.i(TAG, "tts_completed response_id=${responseId ?: "unknown"} " +
                        "completion_sequence=$completionSequence")
                    mutableState.update {
                        it.copy(status = SpeechSynthesisStatus.READY, completedResponseId = responseId,
                            completedPlaybackSequence = completionSequence)
                    }
                }
            }

            override fun onError(utteranceId: String) {
                if (!closed && utteranceId == activeUtteranceId) {
                    Log.e(TAG, "tts_error response_id=${activeResponseId ?: "unknown"}")
                    activeUtteranceId = null
                    activeResponseId = null
                    mutableState.update {
                        it.copy(status = SpeechSynthesisStatus.ERROR, error = "TTS playback failed")
                    }
                }
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (!closed && utteranceId == activeUtteranceId) {
                    Log.i(TAG, "tts_stopped response_id=${activeResponseId ?: "unknown"} " +
                        "interrupted=$interrupted")
                    activeUtteranceId = null
                    activeResponseId = null
                    mutableState.update { it.copy(status = SpeechSynthesisStatus.READY) }
                }
            }
        })
        mutableState.update { it.copy(status = SpeechSynthesisStatus.READY, error = null) }
    }

    override fun speak(response: AssistantResponse): Boolean {
        if (state.value.status != SpeechSynthesisStatus.READY) {
            Log.w(TAG, "tts_rejected response_id=${response.responseId} state=${state.value.status}")
            return false
        }
        val utteranceId = "voice-lab-${nextUtteranceId.incrementAndGet()}"
        activeUtteranceId = utteranceId
        activeResponseId = response.responseId
        mutableState.update {
            it.copy(status = SpeechSynthesisStatus.STARTING, error = null, completedResponseId = null)
        }
        val result = tts.speak(response.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "tts_request_failed response_id=${response.responseId} result=$result")
            activeUtteranceId = null
            activeResponseId = null
            mutableState.update { it.copy(status = SpeechSynthesisStatus.ERROR, error = "TTS request failed") }
            return false
        }
        Log.i(TAG, "tts_queued response_id=${response.responseId} chars=${response.text.length}")
        return true
    }

    override fun stop(): Double {
        if (state.value.status !in setOf(SpeechSynthesisStatus.STARTING, SpeechSynthesisStatus.SPEAKING)) {
            return 0.0
        }
        val startedAt = System.nanoTime()
        val result = tts.stop()
        val stopCallMillis = (System.nanoTime() - startedAt) / 1_000_000.0
        activeUtteranceId = null
        activeResponseId = null
        mutableState.update {
            it.copy(
                status = if (result == TextToSpeech.SUCCESS) SpeechSynthesisStatus.READY
                    else SpeechSynthesisStatus.ERROR,
                stopCallMillis = stopCallMillis,
                error = if (result == TextToSpeech.SUCCESS) null else "TTS stop request failed"
            )
        }
        Log.i(TAG, "tts_stop_requested result=$result elapsed_ms=$stopCallMillis")
        return stopCallMillis
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        tts.shutdown()
        mutableState.update { it.copy(status = SpeechSynthesisStatus.CLOSED) }
    }

    private companion object {
        const val TAG = "VoiceDuplex"
    }
}