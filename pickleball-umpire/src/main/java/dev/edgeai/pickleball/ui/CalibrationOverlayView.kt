package dev.edgeai.pickleball.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.OutputTransform
import dev.edgeai.pickleball.camera.CalibrationTouchMapper
import dev.edgeai.pickleball.ball.BallTrackState
import dev.edgeai.pickleball.ball.BallTrackingStatus
import dev.edgeai.pickleball.court.CameraPoint
import dev.edgeai.pickleball.court.CalibrationQuality
import dev.edgeai.pickleball.court.CourtCalibration
import dev.edgeai.pickleball.court.PickleballCourtGeometry

@androidx.annotation.OptIn(markerClass = [TransformExperimental::class])
class CalibrationOverlayView(context: Context) : View(context) {
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC101713.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }
    private val acceptedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val courtLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val predictedBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val contactCandidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7A30.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var active = false
    private var statusBarInset = 0
    private var previewTransform: OutputTransform? = null
    private var mapper: CalibrationTouchMapper? = null
    private var selectedCameraPoints: List<CameraPoint> = emptyList()
    private var calibration: CourtCalibration? = null
    private var ballCameraPoint: CameraPoint? = null
    private var ballIsPrediction = false
    private var contactCandidateCameraPoint: CameraPoint? = null
    var onPointSelected: ((CameraPoint) -> Unit)? = null

    fun setStatusBarInset(inset: Int) {
        statusBarInset = inset.coerceAtLeast(0)
        invalidate()
    }

    fun setCalibrationState(
        active: Boolean,
        selectedCameraPoints: List<CameraPoint>,
        calibration: CourtCalibration? = null,
        previewTransform: OutputTransform? = this.previewTransform,
        mapper: CalibrationTouchMapper? = this.mapper,
    ) {
        this.active = active
        this.selectedCameraPoints = selectedCameraPoints
        this.calibration = calibration
        this.previewTransform = previewTransform
        this.mapper = mapper
        visibility = if (active || selectedCameraPoints.isNotEmpty() || ballCameraPoint != null) VISIBLE else GONE
        invalidate()
    }

    fun setBallCameraPoint(point: CameraPoint?) {
        ballCameraPoint = point
        ballIsPrediction = false
        visibility = if (active || selectedCameraPoints.isNotEmpty() || point != null) VISIBLE else GONE
        invalidate()
    }

    fun setBallTrackState(state: BallTrackState?) {
        ballIsPrediction = state?.status == BallTrackingStatus.PREDICTED
        ballCameraPoint = when (state?.status) {
            BallTrackingStatus.TRACKED, BallTrackingStatus.REACQUIRED -> state.observed?.let {
                CameraPoint(it.cameraX.toDouble(), it.cameraY.toDouble())
            }
            BallTrackingStatus.PREDICTED -> {
                val x = state.predictedCameraX
                val y = state.predictedCameraY
                if (x != null && y != null) CameraPoint(x.toDouble(), y.toDouble()) else null
            }
            BallTrackingStatus.LOST, null -> null
        }
        visibility = if (active || selectedCameraPoints.isNotEmpty() || ballCameraPoint != null) VISIBLE else GONE
        invalidate()
    }

    fun setContactCandidateCameraPoint(point: CameraPoint?) {
        contactCandidateCameraPoint = point
        visibility = if (active || selectedCameraPoints.isNotEmpty() ||
            ballCameraPoint != null || point != null
        ) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (active) {
            val index = selectedCameraPoints.size.coerceAtMost(
                PickleballCourtGeometry.calibrationAnchors.lastIndex,
            )
            val prompt = if (index < PickleballCourtGeometry.calibrationAnchors.size) {
                "Tap ${index + 1}: ${PickleballCourtGeometry.calibrationAnchors[index].name}"
            } else {
                "Calibrating court..."
            }
            val width = textPaint.measureText(prompt) + 36f
            val top = statusBarInset + 20f
            canvas.drawRoundRect(20f, top, width, top + 46f, 6f, 6f, fillPaint)
            canvas.drawText(prompt, 38f, top + 31f, textPaint)
        }
        val transform = previewTransform ?: return
        val coordinateMapper = mapper ?: return
        val acceptedCalibration = calibration?.takeIf {
            it.quality == CalibrationQuality.EXCELLENT || it.quality == CalibrationQuality.GOOD
        }
        acceptedCalibration?.let { accepted ->
            PickleballCourtGeometry.lines.forEach { line ->
                val screenStart = coordinateMapper.cameraToPreview(
                    transform,
                    accepted.transformer.courtToCamera(line.start),
                )
                val screenEnd = coordinateMapper.cameraToPreview(
                    transform,
                    accepted.transformer.courtToCamera(line.end),
                )
                if (screenStart != null && screenEnd != null) {
                    canvas.drawLine(screenStart.x, screenStart.y, screenEnd.x, screenEnd.y, courtLinePaint)
                }
            }
        }
        selectedCameraPoints.forEachIndexed { index, cameraPoint ->
            val screenPoint = coordinateMapper.cameraToPreview(transform, cameraPoint) ?: return@forEachIndexed
            val paint = if (acceptedCalibration != null) acceptedPaint else markerPaint
            canvas.drawCircle(screenPoint.x, screenPoint.y, 14f, paint)
            canvas.drawText((index + 1).toString(), screenPoint.x + 12f, screenPoint.y - 12f, textPaint)
        }
        ballCameraPoint?.let { point ->
            coordinateMapper.cameraToPreview(transform, point)?.let { screenPoint ->
                if (ballIsPrediction) {
                    canvas.drawCircle(screenPoint.x, screenPoint.y, 13f, predictedBallPaint)
                } else {
                    canvas.drawCircle(screenPoint.x, screenPoint.y, 10f, ballPaint)
                    canvas.drawCircle(screenPoint.x, screenPoint.y, 14f, acceptedPaint)
                }
            }
        }
        contactCandidateCameraPoint?.let { point ->
            coordinateMapper.cameraToPreview(transform, point)?.let { screenPoint ->
                canvas.drawCircle(screenPoint.x, screenPoint.y, 20f, contactCandidatePaint)
                canvas.drawLine(screenPoint.x - 12f, screenPoint.y, screenPoint.x + 12f, screenPoint.y, contactCandidatePaint)
                canvas.drawLine(screenPoint.x, screenPoint.y - 12f, screenPoint.x, screenPoint.y + 12f, contactCandidatePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || !active) return active
        val transform = previewTransform ?: return true
        val cameraPoint = mapper?.previewToCamera(transform, event.x, event.y) ?: return true
        onPointSelected?.invoke(cameraPoint)
        return true
    }
}