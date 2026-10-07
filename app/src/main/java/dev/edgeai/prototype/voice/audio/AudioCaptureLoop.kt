package dev.edgeai.prototype.voice.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface PcmRecorder {
    val sampleRate: Int
    val routeType: Int?
    val isSilenced: Boolean
    fun start()
    fun read(target: ShortArray, offset: Int, count: Int): Int
    fun stop()
    fun release()
}

class AudioCaptureException(val reason: CaptureFailure, val readCode: Int? = null,
    message: String) : IllegalStateException(message)

class AudioCaptureLoop(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val factory: () -> PcmRecorder,
    private val permissionGranted: () -> Boolean,
    private val foreground: () -> Boolean,
    private val bus: AudioFrameBus = AudioFrameBus()
) : AudioInput {
    private val mutableState = MutableStateFlow(AudioCaptureState())
    override val state = mutableState.asStateFlow()
    override val frames = bus.frames
    private val mutex = Mutex()
    private var capture: Job? = null
    private var nextSession = 0L

    override suspend fun start() = withContext(dispatcher) {
        mutex.withLock {
            if (capture?.isActive == true) return@withLock
            capture?.join()
            if (!permissionGranted()) {
                val failure = SecurityException("Microphone permission is not granted")
                mutableState.update { it.copy(status = CaptureStatus.ERROR,
                    failure = CaptureFailure.PERMISSION, failureType = failure.javaClass.simpleName,
                    cause = failure) }
                return@withLock
            }
            if (!foreground()) return@withLock
            val session = ++nextSession
            mutableState.update { AudioCaptureState(status = CaptureStatus.STARTING, sessionId = session) }
            capture = scope.launch(dispatcher) { captureSession(session) }
        }
    }

    override suspend fun stop() = withContext(dispatcher) {
        mutex.withLock {
            if (capture?.isActive == true) {
                mutableState.update { it.copy(status = CaptureStatus.STOPPING) }
            }
            capture?.cancelAndJoin()
            capture = null
            mutableState.update { if (it.cause != null) it.copy(status = CaptureStatus.ERROR)
                else it.copy(status = CaptureStatus.IDLE) }
        }
    }

    private suspend fun captureSession(session: Long) {
        var recorder: PcmRecorder? = null
        var failure: Exception? = null
        var reason = CaptureFailure.INITIALIZATION
        val pcm = ShortArray(AudioFrame.SAMPLE_COUNT)
        val started = System.nanoTime()
        try {
            checkPermission()
            if (!foreground()) return
            recorder = factory()
            if (recorder.sampleRate != AudioFrame.SAMPLE_RATE) {
                throw AudioCaptureException(CaptureFailure.FORMAT, message = "Unsupported capture sample rate")
            }
            recorder.start()
            mutableState.update { it.copy(status = CaptureStatus.CAPTURING, sampleRate = recorder.sampleRate) }
            reason = CaptureFailure.READ
            var filled = 0
            var sequence = 0L
            var previousFrame = 0L
            var lastData = System.nanoTime()
            while (currentCoroutineContext().isActive && foreground()) {
                checkPermission()
                if (recorder.isSilenced) {
                    throw AudioCaptureException(CaptureFailure.SILENCED, message = "System silenced microphone capture")
                }
                val count = recorder.read(pcm, filled, pcm.size - filled)
                if (count < 0) throw AudioCaptureException(CaptureFailure.READ, count, "AudioRecord read failed")
                if (count > pcm.size - filled) {
                    throw AudioCaptureException(CaptureFailure.READ, count, "Invalid PCM read count")
                }
                if (count == 0) {
                    if (System.nanoTime() - lastData > 2_000_000_000L) {
                        throw AudioCaptureException(CaptureFailure.READ, 0, "No microphone frames for two seconds")
                    }
                    delay(5)
                    continue
                }
                lastData = System.nanoTime()
                filled += count
                if (filled == pcm.size) {
                    val timestamp = System.nanoTime()
                    val frame = AudioFrame(session, sequence++, timestamp, pcm)
                    val accepted = bus.offer(frame)
                    val gap = if (previousFrame == 0L) null else (timestamp - previousFrame) / 1_000_000.0
                    previousFrame = timestamp
                    mutableState.update { it.copy(frames = sequence,
                        droppedFrames = it.droppedFrames + if (accepted) 0 else 1,
                        peakAmplitude = frame.peakAmplitude, rmsAmplitude = frame.rmsAmplitude,
                        routeType = recorder.routeType,
                        firstFrameMillis = it.firstFrameMillis ?: (timestamp - started) / 1_000_000.0,
                        lastFrameGapMillis = gap) }
                    filled = 0
                    delay(1)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
            reason = when (error) {
                is SecurityException -> CaptureFailure.PERMISSION
                is AudioCaptureException -> error.reason
                else -> reason
            }
        } finally {
            try {
                recorder?.stop()
            } catch (error: Exception) {
                if (failure == null) { failure = error; reason = CaptureFailure.RELEASE }
                else failure.addSuppressed(error)
            }
            try {
                recorder?.release()
            } catch (error: Exception) {
                if (failure == null) { failure = error; reason = CaptureFailure.RELEASE }
                else failure.addSuppressed(error)
            }
            pcm.fill(0)
            val cause = failure
            mutableState.update { it.copy(status = if (cause == null) CaptureStatus.IDLE else CaptureStatus.ERROR,
                failure = if (cause == null) null else reason,
                failureType = cause?.javaClass?.simpleName, cause = cause,
                readCode = (cause as? AudioCaptureException)?.readCode) }
        }
    }

    private fun checkPermission() {
        if (!permissionGranted()) throw SecurityException("Microphone permission was revoked")
    }
}