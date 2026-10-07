package dev.edgeai.prototype.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AndroidTtsSynthesizer(context: Context) : SpeechSynthesizer {
    private val mutableState = MutableStateFlow(SpeechSynthesisState())
    override val state = mutableState.asStateFlow()
    private val nextUtteranceId = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var activeUtteranceId: String? = null
    private val tts = TextToSpeech(context.applicationContext, ::onInitialized)

    private fun onInitialized(result: Int) {
        if (closed) return
        if (result != TextToSpeech.SUCCESS) {
            mutableState.update { it.copy(status = SpeechSynthesisStatus.ERROR, error = "TTS initialization failed") }
            return
        }

        if (tts.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) {
            mutableState.update { it.copy(status = SpeechSynthesisStatus.ERROR, error = "English TTS voice unavailable") }
            return
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                if (!closed && utteranceId == activeUtteranceId) {
                    mutableState.update { it.copy(status = SpeechSynthesisStatus.SPEAKING, error = null) }
                }
            }

            override fun onDone(utteranceId: String) {
                if (!closed && utteranceId == activeUtteranceId) {
                    activeUtteranceId = null
                    mutableState.update { it.copy(status = SpeechSynthesisStatus.READY) }
                }
            }

            override fun onError(utteranceId: String) {
                if (!closed && utteranceId == activeUtteranceId) {
                    activeUtteranceId = null
                    mutableState.update {
                        it.copy(status = SpeechSynthesisStatus.ERROR, error = "TTS playback failed")
                    }
                }
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (!closed && utteranceId == activeUtteranceId) {
                    activeUtteranceId = null
                    mutableState.update { it.copy(status = SpeechSynthesisStatus.READY) }
                }
            }
        })
        mutableState.update { it.copy(status = SpeechSynthesisStatus.READY, error = null) }
    }

    override fun speak(text: String): Boolean {
        require(text.isNotBlank())
        if (state.value.status != SpeechSynthesisStatus.READY) return false
        val utteranceId = "voice-lab-${nextUtteranceId.incrementAndGet()}"
        activeUtteranceId = utteranceId
        mutableState.update { it.copy(status = SpeechSynthesisStatus.STARTING, error = null) }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            activeUtteranceId = null
            mutableState.update { it.copy(status = SpeechSynthesisStatus.ERROR, error = "TTS request failed") }
            return false
        }
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
        mutableState.update {
            it.copy(
                status = if (result == TextToSpeech.SUCCESS) SpeechSynthesisStatus.READY
                    else SpeechSynthesisStatus.ERROR,
                stopCallMillis = stopCallMillis,
                error = if (result == TextToSpeech.SUCCESS) null else "TTS stop request failed"
            )
        }
        return stopCallMillis
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        tts.shutdown()
        mutableState.update { it.copy(status = SpeechSynthesisStatus.CLOSED) }
    }
}