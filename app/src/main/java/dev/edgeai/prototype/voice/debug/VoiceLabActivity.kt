package dev.edgeai.prototype.voice.debug

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.edgeai.prototype.R
import dev.edgeai.prototype.voice.audio.AndroidAudioInput
import dev.edgeai.prototype.voice.audio.AudioFrame
import dev.edgeai.prototype.voice.audio.CaptureStatus
import dev.edgeai.prototype.voice.stt.SherpaStreamingSttEngine
import dev.edgeai.prototype.voice.tts.AndroidTtsSynthesizer
import dev.edgeai.prototype.voice.tts.SpeechSynthesisStatus
import dev.edgeai.prototype.voice.vad.SherpaSileroVadEngine
import kotlinx.coroutines.launch

class VoiceLabActivity : ComponentActivity() {
    private val audio by lazy { AndroidAudioInput.get(applicationContext) }
    private val model: VoiceLabViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == VoiceLabViewModel::class.java)
                return requireNotNull(modelClass.cast(VoiceLabViewModel(
                    audio,
                    { SherpaSileroVadEngine(applicationContext.assets) },
                    { SherpaStreamingSttEngine(applicationContext.assets) },
                    AndroidTtsSynthesizer(applicationContext)
                )))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_voice_lab)
        val root = findViewById<android.view.View>(R.id.voice_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val start = findViewById<Button>(R.id.audio_start)
        val stop = findViewById<Button>(R.id.audio_stop)
        val ttsSpeak = findViewById<Button>(R.id.tts_speak)
        val ttsStop = findViewById<Button>(R.id.tts_stop)
        start.setOnClickListener {
            if (!audio.hasPermission()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 20)
            else model.start()
        }
        stop.setOnClickListener { model.stop() }
        ttsSpeak.setOnClickListener { model.speakDemo(getString(R.string.tts_demo_text)) }
        ttsStop.setOnClickListener { model.stopSpeech() }
        val stats = findViewById<TextView>(R.id.audio_stats)
        val capture = findViewById<TextView>(R.id.audio_status)
        val vadStatus = findViewById<TextView>(R.id.vad_status)
        val vadStats = findViewById<TextView>(R.id.vad_stats)
        val sttStatus = findViewById<TextView>(R.id.stt_status)
        val sttTranscript = findViewById<TextView>(R.id.stt_transcript)
        val sttStats = findViewById<TextView>(R.id.stt_stats)
        val conversationStatus = findViewById<TextView>(R.id.conversation_status)
        val ttsStatus = findViewById<TextView>(R.id.tts_status)
        val amplitude = findViewById<ProgressBar>(R.id.audio_amplitude)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.state.collect { state ->
                        capture.setText(when (state.status) {
                            CaptureStatus.IDLE -> R.string.audio_idle
                            CaptureStatus.STARTING -> R.string.audio_starting
                            CaptureStatus.CAPTURING -> R.string.audio_capturing
                            CaptureStatus.STOPPING -> R.string.audio_stopping
                            CaptureStatus.ERROR -> R.string.audio_error
                        })
                        start.isEnabled = state.status in setOf(CaptureStatus.IDLE, CaptureStatus.ERROR) &&
                            model.vadState.value.modelStatus == VoiceModelStatus.READY
                        stop.isEnabled = state.status in setOf(CaptureStatus.STARTING, CaptureStatus.CAPTURING)
                        amplitude.progress = (state.peakAmplitude * 1000).toInt()
                        stats.text = getString(R.string.audio_stats_format, state.sampleRate,
                            AudioFrame.SAMPLE_COUNT, AudioFrame.FRAME_MILLIS, state.sessionId,
                            state.frames, state.droppedFrames, state.peakAmplitude, state.rmsAmplitude,
                            state.routeType?.toString() ?: getString(R.string.audio_route_default),
                            state.firstFrameMillis?.let { getString(R.string.audio_millis, it) }
                                ?: getString(R.string.audio_not_measured),
                            state.lastFrameGapMillis?.let { getString(R.string.audio_millis, it) }
                                ?: getString(R.string.audio_not_measured),
                            state.failure?.name ?: getString(R.string.audio_no_failure),
                            state.failureType ?: "", state.readCode?.toString() ?: "")
                    }
                }
                launch {
                    model.vadState.collect { state ->
                        vadStatus.text = when (state.modelStatus) {
                            VoiceModelStatus.LOADING -> getString(R.string.vad_loading)
                            VoiceModelStatus.READY -> getString(
                                if (state.speechDetected) R.string.vad_speech else R.string.vad_silence
                            )
                            VoiceModelStatus.ERROR -> getString(R.string.vad_error, state.error ?: "")
                        }
                        vadStats.text = getString(R.string.vad_stats_format,
                            state.probability?.let { getString(R.string.vad_probability, it) }
                                ?: getString(R.string.audio_not_measured),
                            state.windowsProcessed, state.speechStarts, state.speechEnds,
                            state.lastInferenceMillis ?: 0.0, state.maxInferenceMillis,
                            state.modelLoadMillis ?: 0.0)
                        start.isEnabled = audio.state.value.status in setOf(CaptureStatus.IDLE, CaptureStatus.ERROR) &&
                            state.modelStatus == VoiceModelStatus.READY
                    }
                }
                launch {
                    model.sttState.collect { state ->
                        sttStatus.text = getString(
                            if (state.isFinal) R.string.stt_final else R.string.stt_partial,
                            state.text.ifBlank { getString(R.string.stt_no_text) }
                        )
                        sttTranscript.text = state.text
                        sttStats.text = getString(R.string.stt_stats_format,
                            state.framesProcessed, state.lastInferenceMillis, state.maxInferenceMillis)
                    }
                }
                launch {
                    model.conversationContext.collect { context ->
                        conversationStatus.text = getString(R.string.conversation_state_format,
                            context.state.name, context.turnId)
                    }
                }
                launch {
                    model.ttsState.collect { state ->
                        ttsStatus.text = getString(
                            when (state.status) {
                                SpeechSynthesisStatus.INITIALIZING -> R.string.tts_initializing
                                SpeechSynthesisStatus.READY -> R.string.tts_ready
                                SpeechSynthesisStatus.STARTING -> R.string.tts_starting
                                SpeechSynthesisStatus.SPEAKING -> R.string.tts_speaking
                                SpeechSynthesisStatus.ERROR -> R.string.tts_error
                                SpeechSynthesisStatus.CLOSED -> R.string.tts_closed
                            }, state.error ?: "")
                        state.stopCallMillis?.let { millis ->
                            ttsStatus.append(getString(R.string.tts_stop_call_format, millis))
                        }
                        ttsSpeak.isEnabled = state.status == SpeechSynthesisStatus.READY &&
                            audio.state.value.status == CaptureStatus.CAPTURING
                        ttsStop.isEnabled = state.status in setOf(
                            SpeechSynthesisStatus.STARTING, SpeechSynthesisStatus.SPEAKING)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        audio.setForeground(true)
        showPermission()
    }

    override fun onPause() {
        model.stopSpeech()
        audio.setForeground(false)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 20) showPermission()
    }

    private fun showPermission() {
        findViewById<TextView>(R.id.audio_permission).setText(
            if (audio.hasPermission()) R.string.audio_permission_granted else R.string.audio_permission_needed
        )
    }
}