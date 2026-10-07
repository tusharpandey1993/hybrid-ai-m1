package dev.edgeai.prototype.voice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

class AndroidAudioInput private constructor(context: Context) : AudioInput {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val visible = AtomicBoolean(false)
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EdgeVoiceCapture").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val processingController = SessionAudioProcessingController(AndroidAudioProcessingEffectFactory())
    private val loop = AudioCaptureLoop(scope, dispatcher, { AndroidPcmRecorder(app, audioManager) },
        { hasPermission() }, { visible.get() }, processingController = processingController)
    override val state = loop.state
    override val frames = loop.frames
    val audioProcessingState = processingController.state
    val audioSourceName = "VOICE_COMMUNICATION"

    init {
        scope.launch {
            var lastCaptureState: List<Any?>? = null
            loop.state.collect { state ->
                val snapshot = listOf(state.status, state.sessionId, state.failure, state.failureType)
                if (snapshot != lastCaptureState) {
                    lastCaptureState = snapshot
                    Log.i(TAG, "capture status=${state.status} capture_session=${state.sessionId} " +
                        "source=$audioSourceName sample_rate=${state.sampleRate} failure=${state.failure} " +
                        "failure_type=${state.failureType ?: "none"} frames=${state.frames} " +
                        "dropped_frames=${state.droppedFrames} audio_mode=${audioModeName(audioManager.mode)} " +
                        "communication_output=${communicationOutputName()}")
                }
            }
        }
        scope.launch {
            processingController.state.collect { state ->
                Log.i(TAG, "processing audio_session=${state.audioSessionId ?: "none"} " +
                    "aec_available=${state.aecAvailable} aec_attached=${state.aecAttached} " +
                    "aec_enabled=${state.aecEnabled} ns_available=${state.noiseSuppressorAvailable} " +
                    "ns_attached=${state.noiseSuppressorAttached} ns_enabled=${state.noiseSuppressorEnabled}")
            }
        }
    }

    override suspend fun start() = loop.start()
    override suspend fun stop() = loop.stop()

    fun setForeground(foreground: Boolean) {
        visible.set(foreground)
        if (!foreground) scope.launch { stop() }
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(app,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private class AndroidPcmRecorder(
        private val context: Context,
        private val audioManager: AudioManager
    ) : PcmRecorder {
        private val record: AudioRecord
        private var previousAudioMode: Int? = null
        private var previousCommunicationDevice: AudioDeviceInfo? = null
        private var selectedCommunicationDevice: AudioDeviceInfo? = null

        init {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) throw SecurityException("Microphone permission is not granted")
            val minimum = AudioRecord.getMinBufferSize(AudioFrame.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw AudioCaptureException(CaptureFailure.FORMAT, minimum,
                "16 kHz mono PCM16 is unsupported")
            val originalMode = audioManager.mode
            val originalCommunicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.communicationDevice
            } else null
            var selectedDevice: AudioDeviceInfo? = null
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null && audioManager.setCommunicationDevice(speaker)) {
                        selectedDevice = speaker
                    }
                    Log.i(TAG, "communication_speaker_request available=${speaker != null} " +
                        "accepted=${selectedDevice != null} active=${audioManager.communicationDevice?.productName ?: "none"}")
                }
                val candidate = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
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
                previousAudioMode = originalMode
                previousCommunicationDevice = originalCommunicationDevice
                selectedCommunicationDevice = selectedDevice
            } catch (failure: Exception) {
                restoreAudioRouting(originalMode, originalCommunicationDevice, selectedDevice)
                throw failure
            }
        }

        override val sampleRate: Int get() = record.sampleRate
        override val audioSessionId: Int get() = record.audioSessionId
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
        override fun release() {
            try {
                record.release()
            } finally {
                restoreAudioRouting(previousAudioMode, previousCommunicationDevice, selectedCommunicationDevice)
                previousAudioMode = null
                previousCommunicationDevice = null
                selectedCommunicationDevice = null
            }
        }

        private fun restoreAudioRouting(
            originalMode: Int?,
            originalDevice: AudioDeviceInfo?,
            selectedDevice: AudioDeviceInfo?
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && selectedDevice != null &&
                audioManager.communicationDevice?.id == selectedDevice.id) {
                if (originalDevice != null) audioManager.setCommunicationDevice(originalDevice)
                else audioManager.clearCommunicationDevice()
            }
            if (originalMode != null && audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = originalMode
            }
        }
    }

    private fun audioModeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        else -> mode.toString()
    }

    private fun communicationOutputName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.communicationDevice?.let { "${it.productName}:${it.type}" } ?: "none"
    } else "unavailable"

    companion object {
        private const val TAG = "VoiceDuplex"
        @Volatile private var instance: AndroidAudioInput? = null
        @Synchronized fun get(context: Context): AndroidAudioInput =
            instance ?: AndroidAudioInput(context).also { instance = it }
    }
}