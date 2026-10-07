package dev.edgeai.prototype.voice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

class AndroidAudioInput private constructor(context: Context) : AudioInput {
    private val app = context.applicationContext
    private val visible = AtomicBoolean(false)
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EdgeVoiceCapture").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val loop = AudioCaptureLoop(scope, dispatcher, { AndroidPcmRecorder(app) },
        { hasPermission() }, { visible.get() })
    override val state = loop.state
    override val frames = loop.frames

    override suspend fun start() = loop.start()
    override suspend fun stop() = loop.stop()

    fun setForeground(foreground: Boolean) {
        visible.set(foreground)
        if (!foreground) scope.launch { stop() }
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(app,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private class AndroidPcmRecorder(private val context: Context) : PcmRecorder {
        private val record: AudioRecord

        init {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) throw SecurityException("Microphone permission is not granted")
            val minimum = AudioRecord.getMinBufferSize(AudioFrame.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw AudioCaptureException(CaptureFailure.FORMAT, minimum,
                "16 kHz mono PCM16 is unsupported")
            val candidate = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioFrame.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, AudioFrame.SAMPLE_COUNT * 2 * 8))
                .build()
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release()
                throw AudioCaptureException(CaptureFailure.INITIALIZATION,
                    message = "AudioRecord failed initialization")
            }
            record = candidate
        }

        override val sampleRate: Int get() = record.sampleRate
        override val routeType: Int? get() = record.routedDevice?.type
        override val isSilenced: Boolean get() = Build.VERSION.SDK_INT >= 29 &&
            record.activeRecordingConfiguration?.isClientSilenced == true
        override fun start() {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) throw SecurityException("Microphone permission was revoked")
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        }
        override fun read(target: ShortArray, offset: Int, count: Int): Int =
            record.read(target, offset, count, AudioRecord.READ_NON_BLOCKING)
        override fun stop() {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
        override fun release() = record.release()
    }

    companion object {
        @Volatile private var instance: AndroidAudioInput? = null
        @Synchronized fun get(context: Context): AndroidAudioInput =
            instance ?: AndroidAudioInput(context).also { instance = it }
    }
}