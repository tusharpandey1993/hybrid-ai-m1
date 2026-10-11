package dev.edgeai.pickleball

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.StatFs
import android.graphics.SurfaceTexture
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.OutputTransform
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.edgeai.pickleball.ball.BallDetectionResult
import dev.edgeai.pickleball.ball.BallDetectionStatus
import dev.edgeai.pickleball.ball.BallTrackState
import dev.edgeai.pickleball.ball.BallTrackingStatus
import dev.edgeai.pickleball.ball.ContactCandidate
import dev.edgeai.pickleball.ball.WasbLiveBallPipeline
import dev.edgeai.pickleball.camera.CameraCapabilities
import dev.edgeai.pickleball.camera.CalibrationTouchMapper
import dev.edgeai.pickleball.camera.FrameAnalyzer
import dev.edgeai.pickleball.camera.VideoFileFrameSource
import dev.edgeai.pickleball.court.CalibrationQuality
import dev.edgeai.pickleball.court.CameraPoint
import dev.edgeai.pickleball.court.CourtCalibration
import dev.edgeai.pickleball.court.CourtCalibrator
import dev.edgeai.pickleball.court.PickleballCourtGeometry
import dev.edgeai.pickleball.logging.SessionLogger
import dev.edgeai.pickleball.match.CameraQuality
import dev.edgeai.pickleball.match.MatchAction
import dev.edgeai.pickleball.match.MatchLogRecord
import dev.edgeai.pickleball.match.MatchPhase
import dev.edgeai.pickleball.match.MatchViewModel
import dev.edgeai.pickleball.performance.FrameMetrics
import dev.edgeai.pickleball.ui.CalibrationOverlayView
import dev.edgeai.pickleball.ui.PickleballMatchApp
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

@androidx.annotation.OptIn(markerClass = [
    androidx.camera.view.TransformExperimental::class,
    androidx.camera.camera2.interop.ExperimentalCamera2Interop::class,
])
class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var sessionButton: Button
    private lateinit var shareButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var orientationButton: Button
    private lateinit var replayVideoButton: Button
    private lateinit var ballModelStatusView: TextView
    private lateinit var calibrationOverlay: CalibrationOverlayView
    private lateinit var cameraScene: android.widget.FrameLayout
    private lateinit var matchViewModel: MatchViewModel
    private val calibrationTouchMapper = CalibrationTouchMapper()
    private val calibrationCameraPoints = mutableListOf<CameraPoint>()
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var sessionLogger: SessionLogger
    private var cameraProvider: ProcessCameraProvider? = null
    private var activePreview: Preview? = null
    private var activeAnalysis: ImageAnalysis? = null
    private var activeVideoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeRecordingFile: File? = null
    private var pendingSessionStopReason: String? = null
    private var pendingMatchStop = false
    private var lowStorageStopRequested = false
    private var activeFrameAnalyzer: FrameAnalyzer? = null
    @Volatile
    private var ballPipeline: WasbLiveBallPipeline? = null
    private var ballModelLoadInProgress = false
    private var replayActive = false
    private var replayJob: Job? = null
    private var activeVideoSource: VideoFileFrameSource? = null
    private var sessionActive = false
    private var matchStartPending = false
    private var landscapeLayout by mutableStateOf(false)
    @Volatile
    private var calibrationActive = false
    private var currentAnalysisTransform: OutputTransform? = null
    private var courtCalibration: CourtCalibration? = null
    private var supportedFpsRanges: List<Range<Int>> = emptyList()
    private val pendingBallDetectionCount = AtomicInteger()
    private var lastUiMetricsPublishNs = 0L
    private var lastPublishedTrackingStatus: BallTrackingStatus? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) initializeCamera() else statusView.text = getString(R.string.permission_message)
    }

    private val replayVideoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(::startVideoReplay) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        matchViewModel = ViewModelProvider(this)[MatchViewModel::class.java]
        sessionLogger = matchViewModel.sessionLogger
        landscapeLayout = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(LANDSCAPE_PREFERENCE, false)
        createScreen()
        setContent {
            val state by matchViewModel.state.collectAsStateWithLifecycle()
            PickleballMatchApp(
                state = state,
                cameraView = cameraScene,
                onAction = ::dispatchMatchAction,
                onCalibrate = ::toggleCalibration,
                onExport = ::shareLatestLog,
                onReviewVideo = ::reviewRecording,
                landscapeLayout = landscapeLayout,
                onToggleOrientation = ::toggleOrientationPreference,
            )
        }
        applyOrientationPreference()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initializeCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        if (sessionActive && matchViewModel.state.value.score?.phase == MatchPhase.LIVE) {
            writeMatchLogs(matchViewModel.dispatch(MatchAction.PauseResume))
        }
        activeRecording?.let { recording ->
            sessionLogger.logEvent("VIDEO_RECORDING_INTERRUPTED", mapOf(
                "reason" to "APP_BACKGROUND",
                "timestampNs" to System.nanoTime(),
            ))
            recording.stop()
        }
        if (replayActive) stopVideoReplay()
        super.onStop()
    }

    override fun onDestroy() {
        if (sessionActive && isFinishing) {
            activeRecording?.let { recording ->
                sessionLogger.logEvent("VIDEO_RECORDING_INTERRUPTED", mapOf(
                    "reason" to "ACTIVITY_DESTROYED",
                    "timestampNs" to System.nanoTime(),
                ))
                recording.stop()
            }
            stopSession("ACTIVITY_DESTROYED")
        }
        cameraProvider?.unbindAll()
        activeVideoSource?.stop()
        replayJob?.cancel()
        if (!replayActive) releaseSessionProcessing()
        analysisExecutor.shutdown()
        if (isFinishing && activeRecording == null) sessionLogger.stopSession("ACTIVITY_DESTROYED")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCameraRotation()
        previewView.post {
            updatePreviewScaleType()
            refreshCalibrationOverlay()
        }
    }

    private fun createScreen() {
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(20, 12, 20, 12)
            text = getString(R.string.permission_message)
        }
        ballModelStatusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(20, 4, 20, 8)
            text = getString(R.string.ball_model_unavailable)
        }
        ballModelStatusView.doAfterTextChanged { text ->
            val tracking = matchViewModel.state.value.trackingStatus
            matchViewModel.dispatch(MatchAction.TrackingStateChanged(tracking, text?.toString()))
        }
        sessionButton = Button(this).apply {
            text = getString(R.string.start_session)
            isEnabled = false
            setOnClickListener {
                when {
                    sessionActive -> dispatchMatchAction(MatchAction.StopMatch(sessionLogger.currentFile?.absolutePath))
                    replayActive -> stopVideoReplay()
                    else -> startSession()
                }
            }
        }
        shareButton = Button(this).apply {
            text = getString(R.string.share_log)
            isEnabled = false
            setOnClickListener { shareLatestLog() }
        }
        calibrateButton = Button(this).apply {
            text = getString(R.string.calibrate_court)
            isEnabled = false
            setOnClickListener { toggleCalibration() }
        }
        orientationButton = Button(this).apply {
            setOnClickListener { toggleOrientationPreference() }
        }
        updateOrientationButtonLabel()
        replayVideoButton = Button(this).apply {
            text = getString(R.string.replay_video)
            setOnClickListener { replayVideoPicker.launch(arrayOf("video/*")) }
        }
        calibrationOverlay = CalibrationOverlayView(this).apply {
            onPointSelected = ::addCalibrationPoint
        }
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(calibrationOverlay, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            calibrationOverlay.setStatusBarInset(systemBars.top)
            windowInsets
        }
        cameraScene = root
    }

    private fun dispatchMatchAction(action: MatchAction) {
        when (action) {
            MatchAction.StartMatch -> {
                if (sessionActive || matchStartPending) return
                matchStartPending = true
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { sessionLogger.awaitStopped(5_000L) }
                        if (!isFinishing) {
                            startSession()
                            if (sessionActive) writeMatchLogs(matchViewModel.dispatch(action))
                        }
                    } finally {
                        matchStartPending = false
                    }
                }
            }
            is MatchAction.StopMatch -> {
                if (!sessionActive) return
                if (activeRecording != null) {
                    pendingMatchStop = true
                    sessionLogger.logEvent("MATCH_STOP_REQUESTED", mapOf("timestampNs" to System.nanoTime()))
                    activeRecording?.stop()
                } else {
                    finalizeUserMatchStop()
                }
            }
            else -> writeMatchLogs(matchViewModel.dispatch(action))
        }
    }

    private fun writeMatchLogs(records: List<MatchLogRecord>) {
        records.forEach { record -> sessionLogger.logEvent(record.event, record.fields) }
    }

    private fun finalizeUserMatchStop() {
        if (!sessionActive) return
        matchViewModel.dispatch(MatchAction.BallDetectionsReported(pendingBallDetectionCount.getAndSet(0)))
        val recordingPath = activeRecordingFile?.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        writeMatchLogs(matchViewModel.dispatch(MatchAction.StopMatch(
            logPath = sessionLogger.currentFile?.absolutePath,
            recordingPath = recordingPath,
        )))
        stopSession("MATCH_STOPPED")
    }

    private fun initializeCamera() {
        statusView.text = getString(R.string.status_starting)
        matchViewModel.dispatch(MatchAction.CameraStateChanged(getString(R.string.status_starting)))
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                loadCameraCapabilities()
                val retainedMatch = matchViewModel.state.value.score?.phase in setOf(MatchPhase.LIVE, MatchPhase.PAUSED)
                val bindWhenAttached = {
                    if (retainedMatch && sessionLogger.isActive) {
                        startSession(reuseExistingLog = true)
                    } else {
                        bindPreview()
                    }
                }
                if (previewView.isAttachedToWindow) {
                    bindWhenAttached()
                } else {
                    previewView.doOnAttach { bindWhenAttached() }
                }
                calibrateButton.isEnabled = true
                sessionButton.isEnabled = true
                statusView.text = getString(R.string.status_waiting)
                matchViewModel.dispatch(MatchAction.CameraStateChanged(getString(R.string.status_waiting)))
            } catch (error: Exception) {
                statusView.text = getString(R.string.status_error, error.message ?: "Unknown")
                matchViewModel.dispatch(MatchAction.CameraStateChanged(statusView.text.toString()))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        val builder = Preview.Builder()
            .setResolutionSelector(resolutionSelector())
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
        applyPreferredFps(builder)
        val preview = builder.build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val idleAnalysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector())
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        applyPreferredFps(idleAnalysisBuilder)
        val idleAnalysis = idleAnalysisBuilder.build()
        idleAnalysis.setAnalyzer(analysisExecutor) { image -> image.close() }
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, idleAnalysis)
        activePreview = preview
        activeAnalysis = idleAnalysis
    }

    private fun startSession(reuseExistingLog: Boolean = false) {
        val provider = cameraProvider ?: return
        try {
            val logFile = if (reuseExistingLog) {
                sessionLogger.currentFile ?: return
            } else {
                sessionLogger.startSession()
            }
            if (!reuseExistingLog) sessionLogger.logEvent("DEVICE_INFO", mapOf("model" to Build.MODEL))
            if (reuseExistingLog) {
                sessionLogger.logEvent("ACTIVITY_RECREATED", mapOf(
                    "matchId" to matchViewModel.state.value.score?.settings?.id,
                    "timestampNs" to System.nanoTime(),
                ))
            }
            logCourtCalibration()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val previewBuilder = Preview.Builder()
                .setResolutionSelector(resolutionSelector())
                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            applyPreferredFps(previewBuilder)
            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector())
                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            applyPreferredFps(analysisBuilder)
            val analysis = analysisBuilder.build()
            val videoCapture = createVideoCaptureIfRequested()
            var configurationLogged = false
            val frameAnalyzer = FrameAnalyzer(
                targetFps = matchViewModel.state.value.score?.settings?.cameraFps?.toDouble()
                    ?: matchViewModel.state.value.draft.cameraFps.toDouble(),
                onMetrics = { metrics ->
                    if (!configurationLogged) {
                        configurationLogged = true
                        logCameraConfiguration(provider, selector, analysis, metrics)
                    }
                    logMetrics(metrics)
                },
                onTransform = { transform, width, height ->
                    currentAnalysisTransform = transform
                    calibrationTouchMapper.updateAnalysisTransform(transform, width, height)
                    runOnUiThread {
                        calibrationOverlay.setCalibrationState(
                            calibrationActive,
                            calibrationCameraPoints,
                            courtCalibration,
                            previewView.outputTransform,
                            calibrationTouchMapper,
                        )
                    }
                },
                shouldCaptureTransform = { calibrationActive },
                onImage = { image -> ballPipeline?.processImage(image) },
                onFrameDropped = { timestampNs ->
                    sessionLogger.logEvent("FRAME_DROPPED", mapOf(
                        "cameraTimestampNs" to timestampNs,
                        "reason" to "PROCESSOR_BUSY",
                    ))
                },
                onProcessingError = { error ->
                    sessionLogger.logEvent("FRAME_PROCESSING_ERROR", mapOf(
                        "message" to (error.message ?: error.javaClass.simpleName),
                    ))
                },
            )
            analysis.setAnalyzer(analysisExecutor, frameAnalyzer)
            provider.unbindAll()
            var videoBound = false
            if (videoCapture != null) {
                try {
                    provider.bindToLifecycle(this, selector, preview, analysis, videoCapture)
                    videoBound = true
                    activeVideoCapture = videoCapture
                } catch (error: Exception) {
                    sessionLogger.logEvent("VIDEO_RECORDING_UNAVAILABLE", mapOf(
                        "reason" to "CAMERA_USE_CASE_COMBINATION_UNSUPPORTED",
                        "message" to (error.message ?: error.javaClass.simpleName),
                    ))
                    matchViewModel.dispatch(MatchAction.RecordingStatusChanged(getString(R.string.recording_unavailable)))
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, analysis)
                }
            } else {
                provider.bindToLifecycle(this, selector, preview, analysis)
            }
            activePreview = preview
            activeAnalysis = analysis
            activeFrameAnalyzer = frameAnalyzer
            sessionActive = true
            matchViewModel.dispatch(MatchAction.TrackingStateChanged("ACTIVE - WAITING FOR BALL"))
            calibrateButton.isEnabled = false
            setActiveLabelVisible(true)
            sessionButton.text = getString(R.string.stop_session)
            replayVideoButton.isEnabled = false
            shareButton.isEnabled = false
            statusView.text = getString(R.string.status_starting)
            sessionLogger.logEvent("CAMERA_REQUEST", mapOf(
                "requestedWidth" to REQUEST_WIDTH,
                "requestedHeight" to REQUEST_HEIGHT,
                "backpressure" to "KEEP_ONLY_LATEST",
                "imageFormat" to "YUV_420_888",
                "logPath" to logFile.absolutePath,
            ))
            if (videoBound) videoCapture?.let(::startLocalRecording)
            ballModelStatusView.text = getString(R.string.ball_model_loading)
            matchViewModel.dispatch(MatchAction.ModelStatusChanged(getString(R.string.ball_model_loading)))
            loadBallPipeline()
        } catch (error: Exception) {
            sessionLogger.logEvent("CAMERA_START_FAILED", mapOf(
                "message" to (error.message ?: error.javaClass.simpleName),
                "reusedSession" to reuseExistingLog,
            ))
            if (!reuseExistingLog) sessionLogger.stopSession("CAMERA_START_FAILED")
            statusView.text = getString(R.string.status_error, error.message ?: "Unknown")
        }
    }

    private fun createVideoCaptureIfRequested(): VideoCapture<Recorder>? {
        val draft = matchViewModel.state.value.draft
        if (!draft.recordVideo) return null
        if (StatFs(filesDir.absolutePath).availableBytes < MIN_RECORDING_FREE_BYTES) {
            sessionLogger.logEvent("VIDEO_RECORDING_UNAVAILABLE", mapOf("reason" to "INSUFFICIENT_STORAGE"))
            matchViewModel.dispatch(MatchAction.RecordingStatusChanged(getString(R.string.recording_unavailable)))
            return null
        }
        val quality = when (draft.cameraQuality) {
            CameraQuality.SD -> Quality.SD
            CameraQuality.HD -> Quality.HD
            CameraQuality.FHD -> Quality.FHD
        }
        val qualitySelector = QualitySelector.from(
            quality,
            FallbackStrategy.lowerQualityOrHigherThan(quality),
        )
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        return VideoCapture.withOutput(recorder)
    }

    private fun startLocalRecording(videoCapture: VideoCapture<Recorder>) {
        val directory = File(filesDir, RECORDING_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            sessionLogger.logEvent("VIDEO_RECORDING_UNAVAILABLE", mapOf("reason" to "DIRECTORY_CREATE_FAILED"))
            matchViewModel.dispatch(MatchAction.RecordingStatusChanged(getString(R.string.recording_unavailable)))
            return
        }
        val matchId = matchViewModel.state.value.score?.settings?.id ?: System.currentTimeMillis().toString()
        val file = File(directory, "match_${matchId}_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(file)
            .setFileSizeLimit(MAX_RECORDING_BYTES)
            .build()
        try {
            activeRecordingFile = file
            lowStorageStopRequested = false
            activeRecording = videoCapture.output
                .prepareRecording(this, outputOptions)
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            sessionLogger.logEvent("VIDEO_RECORDING_STARTED", mapOf(
                                "path" to file.absolutePath,
                                "timestampNs" to System.nanoTime(),
                                "audioEnabled" to false,
                            ))
                            matchViewModel.dispatch(MatchAction.RecordingStatusChanged(
                                getString(R.string.recording_active),
                            ))
                        }
                        is VideoRecordEvent.Status -> {
                            if (!lowStorageStopRequested &&
                                StatFs(filesDir.absolutePath).availableBytes < MIN_RECORDING_REMAINING_BYTES
                            ) {
                                lowStorageStopRequested = true
                                sessionLogger.logEvent("VIDEO_RECORDING_INTERRUPTED", mapOf(
                                    "reason" to "LOW_STORAGE",
                                    "timestampNs" to System.nanoTime(),
                                ))
                                activeRecording?.stop()
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            activeRecording = null
                            val finalizedFile = file.takeIf {
                                event.error == VideoRecordEvent.Finalize.ERROR_NONE && it.isFile && it.length() > 0L
                            }
                            if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                                sessionLogger.logEvent("VIDEO_RECORDING_STOPPED", mapOf(
                                    "path" to file.absolutePath,
                                    "timestampNs" to System.nanoTime(),
                                    "durationNs" to event.recordingStats.recordedDurationNanos,
                                ))
                                matchViewModel.dispatch(MatchAction.RecordingStatusChanged(
                                    getString(R.string.recording_saved), finalizedFile?.absolutePath, finalizedFile != null,
                                ))
                            } else {
                                sessionLogger.logEvent("VIDEO_RECORDING_FAILED", mapOf(
                                    "path" to file.absolutePath,
                                    "error" to event.error,
                                    "cause" to (event.cause?.message ?: "Unknown"),
                                    "timestampNs" to System.nanoTime(),
                                ))
                                matchViewModel.dispatch(MatchAction.RecordingStatusChanged(
                                    getString(R.string.recording_unavailable), finalizedFile?.absolutePath, finalizedFile != null,
                                ))
                            }
                            if (pendingMatchStop) {
                                pendingMatchStop = false
                                finalizeUserMatchStop()
                                return@start
                            }
                            pendingSessionStopReason?.let { reason ->
                                pendingSessionStopReason = null
                                finishSessionStop(reason)
                            }
                        }
                    }
                }
        } catch (error: Exception) {
            sessionLogger.logEvent("VIDEO_RECORDING_FAILED", mapOf(
                "path" to file.absolutePath,
                "message" to (error.message ?: error.javaClass.simpleName),
            ))
            matchViewModel.dispatch(MatchAction.RecordingStatusChanged(getString(R.string.recording_unavailable)))
        }
    }

    private fun logCameraConfiguration(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        analysis: ImageAnalysis,
        metrics: FrameMetrics,
    ) {
        val camera = provider.availableCameraInfos.firstOrNull { info ->
            info.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        val camera2Info = camera?.let(Camera2CameraInfo::from)
        val fpsRanges = camera2Info
            ?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map(Range<Int>::toString)
            .orEmpty()
        val orientation = camera2Info
            ?.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
        val resolution = analysis.resolutionInfo?.resolution ?: Size(metrics.frameWidth, metrics.frameHeight)
        val capabilities = CameraCapabilities(
            width = resolution.width,
            height = resolution.height,
            supportedFpsRanges = fpsRanges,
            cameraFacing = "BACK",
            sensorOrientationDegrees = orientation,
            deviceModel = Build.MODEL,
        )
        sessionLogger.logEvent("CAMERA_CONFIGURATION", mapOf(
            "resolutionWidth" to capabilities.width,
            "resolutionHeight" to capabilities.height,
            "supportedFpsRanges" to capabilities.supportedFpsRanges,
            "cameraFacing" to capabilities.cameraFacing,
            "sensorOrientationDegrees" to capabilities.sensorOrientationDegrees,
            "deviceModel" to capabilities.deviceModel,
            "actualAnalysisWidth" to metrics.frameWidth,
            "actualAnalysisHeight" to metrics.frameHeight,
            "selector" to selector.toString(),
        ))
    }

    private fun logMetrics(metrics: FrameMetrics) {
        val nowNs = System.nanoTime()
        if (nowNs - lastUiMetricsPublishNs >= UI_METRICS_INTERVAL_NS) {
            lastUiMetricsPublishNs = nowNs
            matchViewModel.dispatch(MatchAction.CameraFpsReported(metrics.cameraFps.toFloat()))
            matchViewModel.dispatch(MatchAction.BallDetectionsReported(pendingBallDetectionCount.getAndSet(0)))
        }
        sessionLogger.logEvent("FPS", mapOf(
            "cameraFps" to metrics.cameraFps,
            "processingFps" to metrics.processingFps,
        ))
        sessionLogger.logEvent("FRAME_PROCESSING", mapOf(
            "cameraTimestampNs" to metrics.cameraTimestampNs,
            "averageMs" to metrics.averageProcessingMs,
            "width" to metrics.frameWidth,
            "height" to metrics.frameHeight,
        ))
        if (metrics.suspectedFrameGaps > 0) {
            sessionLogger.logEvent("FRAME_GAP_SUSPECTED", mapOf(
                "count" to metrics.suspectedFrameGaps,
                "maxIntervalMs" to metrics.maxFrameIntervalMs,
            ))
        }
        runOnUiThread {
            if (sessionActive && !isFinishing) {
                statusView.text = getString(
                    R.string.status_metrics,
                    metrics.cameraFps,
                    metrics.processingFps,
                    metrics.averageProcessingMs,
                    metrics.suspectedFrameGaps,
                )
            }
        }
    }

    private fun stopSession(reason: String) {
        if (!sessionActive) return
        activeRecording?.let { recording ->
            pendingSessionStopReason = reason
            recording.stop()
            return
        }
        finishSessionStop(reason)
    }

    private fun finishSessionStop(reason: String) {
        if (!sessionActive) return
        activeAnalysis?.clearAnalyzer()
        activeAnalysis?.let { cameraProvider?.unbind(it) }
        activeAnalysis = null
        activeVideoCapture = null
        releaseSessionProcessing()
        sessionActive = false
        if (isFinishing) {
            sessionLogger.stopSession(reason)
            return
        }
        calibrateButton.isEnabled = true
        replayVideoButton.isEnabled = true
        setActiveLabelVisible(false)
        sessionButton.text = getString(R.string.start_session)
        ballModelStatusView.text = getString(R.string.ball_model_stopped)
        sessionLogger.stopSession(reason)
        shareButton.isEnabled = false
        val name = sessionLogger.currentFile?.name ?: getString(R.string.no_log)
        statusView.text = getString(R.string.status_stopped, name)
        analysisExecutor.execute {
            sessionLogger.awaitStopped(5_000L)
            runOnUiThread {
                shareButton.isEnabled = sessionLogger.currentFile?.let { it.exists() && it.length() > 0L } == true
            }
        }
        bindPreview()
    }

    private fun startVideoReplay(uri: Uri) {
        if (sessionActive) {
            statusView.text = getString(R.string.replay_stop_match_first)
            return
        }
        if (replayActive || sessionLogger.isActive) return

        replayActive = true
        replayVideoButton.isEnabled = false
        sessionButton.text = getString(R.string.stop_replay)
        ballModelStatusView.text = getString(R.string.replay_loading)
        statusView.text = getString(R.string.status_starting)
        cameraProvider?.unbindAll()

        replayJob = lifecycleScope.launch {
            var source: VideoFileFrameSource? = null
            var pipeline: WasbLiveBallPipeline? = null
            var frameCount = 0
            var completed = false
            try {
                withContext(Dispatchers.IO) { sessionLogger.awaitStopped(5_000L) }
                sessionLogger.startSession()
                sessionLogger.logEvent("SESSION_SOURCE", mapOf("source" to "VIDEO_FILE"))
                sessionLogger.logEvent("REPLAY_STARTED", mapOf("uri" to uri.toString()))

                val loadedPipeline = WasbLiveBallPipeline.load(
                    applicationContext,
                    ::handleBallDetection,
                    ::handleBallTrackState,
                    ::handleContactCandidate,
                )
                pipeline = loadedPipeline
                ballPipeline = loadedPipeline
                ballModelStatusView.text = getString(R.string.ball_model_ready)

                val videoSource = VideoFileFrameSource(applicationContext, uri)
                source = videoSource
                activeVideoSource = videoSource
                videoSource.consume { frame ->
                    loadedPipeline.process(frame)
                    frameCount++
                    if (frameCount % 30 == 0) {
                        runOnUiThread {
                            if (replayActive && !isFinishing) {
                                statusView.text = getString(R.string.replay_running, frameCount)
                            }
                        }
                    }
                }
                completed = true
                sessionLogger.logEvent("REPLAY_COMPLETED", mapOf("decodedFrames" to frameCount))
            } catch (error: CancellationException) {
                sessionLogger.logEvent("REPLAY_CANCELLED", mapOf("decodedFrames" to frameCount))
                throw error
            } catch (error: Exception) {
                sessionLogger.logEvent("REPLAY_FAILED", mapOf(
                    "decodedFrames" to frameCount,
                    "message" to (error.message ?: error.javaClass.simpleName),
                ))
                if (pipeline == null) ballModelStatusView.text = getString(R.string.ball_model_unavailable)
                statusView.text = getString(R.string.status_error, error.message ?: "Replay failed")
            } finally {
                source?.stop()
                activeVideoSource = null
                ballPipeline = null
                pipeline?.close()
                replayActive = false
                sessionLogger.stopSession(if (completed) "REPLAY_COMPLETE" else "REPLAY_STOPPED")
                withContext(NonCancellable + Dispatchers.IO) { sessionLogger.awaitStopped(5_000L) }
                shareButton.isEnabled = sessionLogger.currentFile?.let { it.exists() && it.length() > 0L } == true
                sessionButton.text = getString(R.string.start_session)
                replayVideoButton.isEnabled = true
                if (completed) ballModelStatusView.text = getString(R.string.replay_finished, frameCount)
                if (!isFinishing) bindPreview()
            }
        }
    }

    private fun stopVideoReplay() {
        activeVideoSource?.stop()
        replayJob?.cancel()
    }

    private fun loadBallPipeline() {
        if (ballModelLoadInProgress || ballPipeline != null) return
        ballModelLoadInProgress = true
        lifecycleScope.launch {
            try {
                val loaded = WasbLiveBallPipeline.load(
                    applicationContext,
                    ::handleBallDetection,
                    ::handleBallTrackState,
                    ::handleContactCandidate,
                )
                if (!sessionActive || isFinishing) {
                    loaded.close()
                    return@launch
                }
                ballPipeline = loaded
                ballModelStatusView.text = getString(R.string.ball_model_ready)
                matchViewModel.dispatch(MatchAction.ModelStatusChanged(getString(R.string.ball_model_ready)))
                sessionLogger.logEvent("BALL_MODEL_READY", mapOf(
                    "model" to "WASB-SBDT-HRNet-tennis",
                    "runtime" to "ONNX Runtime CPU",
                    "inputShape" to "[1,9,288,512]",
                    "outputShape" to "[1,1,288,512]",
                ))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sessionActive && !isFinishing) {
                    ballModelStatusView.text = getString(R.string.ball_model_unavailable)
                    matchViewModel.dispatch(MatchAction.ModelStatusChanged(getString(R.string.ball_model_unavailable)))
                    sessionLogger.logEvent("BALL_MODEL_UNAVAILABLE", mapOf(
                        "message" to (error.message ?: error.javaClass.simpleName),
                    ))
                }
            } finally {
                ballModelLoadInProgress = false
            }
        }
    }

    private fun handleBallDetection(result: BallDetectionResult) {
        if (result.status == BallDetectionStatus.DETECTED) {
            pendingBallDetectionCount.incrementAndGet()
        }
        val eventName = when (result.status) {
            BallDetectionStatus.DETECTED -> "BALL_DETECTED"
            BallDetectionStatus.BELOW_THRESHOLD -> "BALL_NOT_DETECTED"
            BallDetectionStatus.SKIPPED_BUSY -> "FRAME_DROPPED"
        }
        sessionLogger.logEvent(eventName, mapOf(
            "cameraTimestampNs" to result.timestampNs,
            "rotationDegrees" to result.rotationDegrees,
            "cameraX" to result.cameraX,
            "cameraY" to result.cameraY,
            "heatmapX" to result.peakX,
            "heatmapY" to result.peakY,
            "confidence" to result.confidence,
            "status" to result.status.name,
            "frameIntervalsNs" to result.frameIntervalsNs,
        ))
    }

    private fun handleBallTrackState(state: BallTrackState) {
        val eventName = when (state.status) {
            BallTrackingStatus.TRACKED -> "BALL_TRACKED"
            BallTrackingStatus.PREDICTED -> "BALL_PREDICTED"
            BallTrackingStatus.LOST -> "BALL_LOST"
            BallTrackingStatus.REACQUIRED -> "BALL_REACQUIRED"
        }
        val observation = state.observed
        sessionLogger.logEvent(eventName, mapOf(
            "trackId" to state.trackId.value,
            "cameraTimestampNs" to state.timestampNs,
            "status" to state.status.name,
            "observed" to (observation != null),
            "observedCameraX" to observation?.cameraX,
            "observedCameraY" to observation?.cameraY,
            "observedConfidence" to observation?.confidence,
            "predictedCameraX" to state.predictedCameraX,
            "predictedCameraY" to state.predictedCameraY,
            "velocityXPerSecond" to state.velocityXPerSecond,
            "velocityYPerSecond" to state.velocityYPerSecond,
            "accelerationXPerSecondSquared" to state.accelerationXPerSecondSquared,
            "accelerationYPerSecondSquared" to state.accelerationYPerSecondSquared,
            "trackingConfidence" to state.trackingConfidence,
            "missingObservationCount" to state.missingObservationCount,
        ))
        runOnUiThread {
            if ((!sessionActive && !replayActive) || isFinishing) return@runOnUiThread
            calibrationOverlay.setBallTrackState(state)
            if (lastPublishedTrackingStatus != state.status) {
                lastPublishedTrackingStatus = state.status
                matchViewModel.dispatch(MatchAction.TrackingStateChanged(state.status.name))
            }
        }
    }

    private fun releaseSessionProcessing() {
        val analyzer = activeFrameAnalyzer.also { activeFrameAnalyzer = null }
        val pipeline = ballPipeline.also { ballPipeline = null }
        calibrationOverlay.setBallTrackState(null)
        calibrationOverlay.setContactCandidateCameraPoint(null)
        analyzer?.close()
        if (analyzer == null && pipeline == null) return

        val cleanup = Runnable {
            val workerStopped = analyzer?.awaitClosed(30_000L) ?: true
            if (workerStopped) pipeline?.close()
        }
        if (analysisExecutor.isShutdown) {
            Thread(cleanup, "pickleball-model-cleanup").apply {
                isDaemon = true
                start()
            }
        } else {
            analysisExecutor.execute(cleanup)
        }
    }

    private fun handleContactCandidate(candidate: ContactCandidate) {
        sessionLogger.logEvent("CONTACT_CANDIDATE", mapOf(
            "cameraTimestampNs" to candidate.timestampNs,
            "cameraX" to candidate.cameraX,
            "cameraY" to candidate.cameraY,
            "confidence" to candidate.confidence,
            "localizationUncertaintyPx" to candidate.localizationUncertaintyPx,
            "reason" to candidate.reason,
            "groundCourtPositionAvailable" to candidate.groundCourtPositionAvailable,
            "supportingObservations" to candidate.supportingObservations.map { observation ->
                mapOf(
                    "timestampNs" to observation.timestampNs,
                    "x" to observation.cameraX,
                    "y" to observation.cameraY,
                    "confidence" to observation.confidence,
                )
            },
        ))
        runOnUiThread {
            if ((!sessionActive && !replayActive) || isFinishing) return@runOnUiThread
            calibrationOverlay.setContactCandidateCameraPoint(
                CameraPoint(candidate.cameraX.toDouble(), candidate.cameraY.toDouble()),
            )
            ballModelStatusView.text = getString(R.string.contact_candidate_status)
        }
    }

    private fun toggleCalibration() {
        if (!OpenCVLoader.initLocal()) {
            statusView.text = getString(R.string.calibration_error, "OpenCV failed to initialize")
            return
        }
        calibrationActive = !calibrationActive
        if (calibrationActive) {
            calibrationCameraPoints.clear()
            courtCalibration = null
            calibrateButton.text = getString(R.string.cancel_calibration)
            try {
                bindCalibrationAnalyzer()
            } catch (error: Exception) {
                calibrationActive = false
                statusView.text = getString(R.string.calibration_error, error.message ?: "Camera unavailable")
            }
        } else {
            calibrateButton.text = getString(R.string.calibrate_court)
            stopCalibrationAnalyzer()
        }
        refreshCalibrationOverlay()
    }

    private fun bindCalibrationAnalyzer() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector())
            .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector())
            .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        val frameAnalyzer = FrameAnalyzer(
            onMetrics = {},
            onTransform = { transform, width, height ->
                currentAnalysisTransform = transform
                calibrationTouchMapper.updateAnalysisTransform(transform, width, height)
                runOnUiThread { refreshCalibrationOverlay() }
            },
            shouldCaptureTransform = { calibrationActive },
        )
        analysis.setAnalyzer(analysisExecutor, frameAnalyzer)
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        activePreview = preview
        activeAnalysis = analysis
        activeFrameAnalyzer = frameAnalyzer
    }

    private fun stopCalibrationAnalyzer() {
        if (sessionActive) return
        activeAnalysis?.clearAnalyzer()
        activeAnalysis?.let { cameraProvider?.unbind(it) }
        activeAnalysis = null
        releaseSessionProcessing()
        bindPreview()
    }

    private fun addCalibrationPoint(point: CameraPoint) {
        if (!calibrationActive || calibrationCameraPoints.size >= PickleballCourtGeometry.calibrationAnchors.size) return
        calibrationCameraPoints.add(point)
        if (calibrationCameraPoints.size == PickleballCourtGeometry.calibrationAnchors.size) {
            calibrationActive = false
            calibrateButton.text = getString(R.string.calibrate_court)
            val calibration = CourtCalibrator.estimate(
                cameraPoints = calibrationCameraPoints.toList(),
                courtPoints = PickleballCourtGeometry.calibrationAnchors.map { it.point },
            )
            courtCalibration = calibration
            writeMatchLogs(matchViewModel.dispatch(MatchAction.CourtCalibrationCompleted(calibration)))
            statusView.text = when (calibration.quality) {
                CalibrationQuality.EXCELLENT, CalibrationQuality.GOOD -> getString(
                    R.string.calibration_success,
                    calibration.quality.name,
                    calibration.reprojectionErrorPixels,
                )
                else -> getString(
                    R.string.calibration_error,
                    "${calibration.quality.name}, ${calibration.reprojectionErrorPixels} px; tap CALIBRATE to retry",
                )
            }
            stopCalibrationAnalyzer()
        }
        refreshCalibrationOverlay()
    }

    private fun logCourtCalibration() {
        val calibration = courtCalibration ?: run {
            sessionLogger.logEvent("COURT_CALIBRATION", mapOf("quality" to "NOT_CALIBRATED"))
            return
        }
        sessionLogger.logEvent("COURT_CALIBRATION", mapOf(
            "cameraPoints" to calibrationCameraPoints.map { mapOf("x" to it.x, "y" to it.y) },
            "courtPointsFeet" to PickleballCourtGeometry.calibrationAnchors.map {
                mapOf("name" to it.name, "x" to it.point.xFeet, "y" to it.point.yFeet)
            },
            "homography" to calibration.transformer.values().toList(),
            "quality" to calibration.quality.name,
            "reprojectionErrorPixels" to (
                calibration.reprojectionErrorPixels.takeIf { it.isFinite() } ?: "INFINITE"
            ),
            "inliers" to calibration.inlierCount,
            "pointCount" to calibration.pointCount,
        ))
    }

    private fun refreshCalibrationOverlay() {
        calibrationOverlay.setCalibrationState(
            calibrationActive,
            calibrationCameraPoints.toList(),
            courtCalibration,
            previewView.outputTransform,
            calibrationTouchMapper,
        )
    }

    private fun shareLatestLog(logPath: String? = null) {
        val file = logPath?.let(::File) ?: sessionLogger.currentFile ?: return
        lifecycleScope.launch {
            if (file == sessionLogger.currentFile) withContext(Dispatchers.IO) { sessionLogger.awaitStopped(5_000L) }
            if (!file.exists()) return@launch
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_log)))
        }
    }

    private fun reviewRecording(path: String) {
        val file = File(path).takeIf { it.isFile && it.length() > 0L } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val reviewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(reviewIntent, getString(R.string.review_video))) }
            .onFailure { statusView.text = getString(R.string.recording_unavailable) }
    }

    private fun setActiveLabelVisible(visible: Boolean) {
        val root = findViewById<android.widget.FrameLayout>(android.R.id.content)
            .getChildAt(0) as? android.widget.FrameLayout ?: return
        val label = root.findViewWithTag<TextView>(ACTIVE_LABEL_TAG)
        label?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun resolutionSelector(): ResolutionSelector {
        val quality = matchViewModel.state.value.score?.settings?.cameraQuality
            ?: matchViewModel.state.value.draft.cameraQuality
        val target = when (quality) {
            CameraQuality.SD -> Size(640, 480)
            CameraQuality.HD -> Size(REQUEST_WIDTH, REQUEST_HEIGHT)
            CameraQuality.FHD -> Size(1920, 1080)
        }
        return ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(
                target,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
            ),
        )
        .build()
    }

    private fun loadCameraCapabilities() {
        val backCamera = cameraProvider?.availableCameraInfos?.firstOrNull {
            it.lensFacing == CameraSelector.LENS_FACING_BACK
        } ?: return
        val cameraInfo = Camera2CameraInfo.from(backCamera)
        supportedFpsRanges = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        )?.toList().orEmpty()
        val sizes = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        fun supports(width: Int, height: Int) = sizes.any {
            (it.width >= width && it.height >= height) || (it.width >= height && it.height >= width)
        }
        val qualities = CameraQuality.entries.filter { quality ->
            when (quality) {
                CameraQuality.SD -> supports(640, 480)
                CameraQuality.HD -> supports(1280, 720)
                CameraQuality.FHD -> supports(1920, 1080)
            }
        }
        val fpsChoices = supportedFpsRanges.map(Range<Int>::getUpper).filter { it in 1..30 }.distinct().sorted()
        matchViewModel.dispatch(MatchAction.CameraCapabilitiesLoaded(fpsChoices, qualities))
    }

    private fun applyPreferredFps(builder: Preview.Builder) {
        val fps = matchViewModel.state.value.score?.settings?.cameraFps
            ?: matchViewModel.state.value.draft.cameraFps
        val range = supportedFpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: supportedFpsRanges.firstOrNull { it.upper == fps }
            ?: return
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            range,
        )
    }

    private fun applyPreferredFps(builder: ImageAnalysis.Builder) {
        val fps = matchViewModel.state.value.score?.settings?.cameraFps
            ?: matchViewModel.state.value.draft.cameraFps
        val range = supportedFpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: supportedFpsRanges.firstOrNull { it.upper == fps }
            ?: return
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            range,
        )
    }

    private fun toggleOrientationPreference() {
        landscapeLayout = !landscapeLayout
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(LANDSCAPE_PREFERENCE, landscapeLayout)
            .apply()
        updateOrientationButtonLabel()
        applyOrientationPreference()
    }

    private fun updateOrientationButtonLabel() {
        orientationButton.text = getString(
            if (landscapeLayout) R.string.switch_to_portrait else R.string.switch_to_landscape,
        )
    }

    private fun applyOrientationPreference() {
        requestedOrientation = if (landscapeLayout) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        updatePreviewScaleType()
        updateCameraRotation()
    }

    private fun updatePreviewScaleType() {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private fun updateCameraRotation() {
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        activePreview?.targetRotation = rotation
        activeAnalysis?.targetRotation = rotation
    }

    private companion object {
        const val REQUEST_WIDTH = 1280
        const val REQUEST_HEIGHT = 720
        const val ACTIVE_LABEL_TAG = "active-session-label"
        const val PREFERENCES_NAME = "pickleball_settings"
        const val LANDSCAPE_PREFERENCE = "landscape_layout"
        const val RECORDING_DIRECTORY = "recordings"
        const val MIN_RECORDING_FREE_BYTES = 512L * 1024L * 1024L
        const val MIN_RECORDING_REMAINING_BYTES = 128L * 1024L * 1024L
        const val MAX_RECORDING_BYTES = 2L * 1024L * 1024L * 1024L
        const val UI_METRICS_INTERVAL_NS = 1_000_000_000L
    }
}