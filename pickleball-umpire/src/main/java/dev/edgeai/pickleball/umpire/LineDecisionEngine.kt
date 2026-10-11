package dev.edgeai.pickleball.umpire

import dev.edgeai.pickleball.court.CourtLine
import dev.edgeai.pickleball.court.CourtPoint
import dev.edgeai.pickleball.court.LineDistanceResult
import dev.edgeai.pickleball.court.PickleballCourtGeometry
import dev.edgeai.pickleball.court.CalibrationQuality

@JvmInline
value class ContactTimestampNs(val value: Long)

enum class LineDecision {
    IN,
    OUT,
    NEAR_LINE,
    UNCERTAIN,
}

enum class RallyRuleContext {
    RALLY_BOUNDARY,
    SERVE_NEAR_TO_FAR,
    SERVE_FAR_TO_NEAR,
}

data class GroundContactEstimate(
    val timestamp: ContactTimestampNs,
    val courtCenter: CourtPoint,
    val ballFootprintRadiusFeet: Double,
    val contactLocalizationUncertaintyFeet: Double,
    val calibrationErrorFeet: Double,
    val ballConfidence: Float,
    val contactConfidence: Float,
    val trackingContinuity: Float,
    val calibrationQuality: CalibrationQuality,
)

data class LineDecisionThresholds(
    val minimumBallConfidence: Float = 0.5f,
    val minimumContactConfidence: Float = 0.5f,
    val minimumTrackingContinuity: Float = 0.5f,
    val minimumCalibrationQuality: CalibrationQuality = CalibrationQuality.GOOD,
    val lineWidthFeet: Double = 2.0 / 12.0,
    val nearLineBandFeet: Double = 0.25,
)

data class LineDecisionResult(
    val timestamp: ContactTimestampNs,
    val decision: LineDecision,
    val nearestLine: CourtLine?,
    val signedDistanceFeet: Double?,
    val absoluteDistanceFeet: Double?,
    val totalUncertaintyFeet: Double,
    val confidence: Float,
    val reason: String,
)

class LineDecisionEngine(
    private val thresholds: LineDecisionThresholds = LineDecisionThresholds(),
) {
    init {
        require(thresholds.minimumBallConfidence in 0f..1f)
        require(thresholds.minimumContactConfidence in 0f..1f)
        require(thresholds.minimumTrackingContinuity in 0f..1f)
        require(thresholds.lineWidthFeet >= 0.0)
        require(thresholds.nearLineBandFeet >= 0.0)
    }

    fun decide(
        contact: GroundContactEstimate,
        ruleContext: RallyRuleContext,
    ): LineDecisionResult {
        val uncertainty = contact.contactLocalizationUncertaintyFeet + contact.calibrationErrorFeet
        if (!isFinite(contact) || !uncertainty.isFinite()) {
            return uncertain(contact, null, null, uncertainty, "INVALID_OR_NONFINITE_CONTACT_ESTIMATE")
        }
        if (contact.ballConfidence < thresholds.minimumBallConfidence) {
            return uncertain(contact, null, null, uncertainty, "LOW_BALL_CONFIDENCE")
        }
        if (contact.contactConfidence < thresholds.minimumContactConfidence) {
            return uncertain(contact, null, null, uncertainty, "LOW_CONTACT_CONFIDENCE")
        }
        if (contact.trackingContinuity < thresholds.minimumTrackingContinuity) {
            return uncertain(contact, null, null, uncertainty, "INSUFFICIENT_TRACK_CONTINUITY")
        }
        if (contact.calibrationQuality.ordinal > thresholds.minimumCalibrationQuality.ordinal) {
            return uncertain(contact, null, null, uncertainty, "CALIBRATION_QUALITY_TOO_LOW")
        }

        val boundaries = PickleballCourtGeometry.lines.filter { it.id in BOUNDARY_IDS }
        val boundaryDistances = boundaries.map { PickleballCourtGeometry.distanceToLine(contact.courtCenter, it) }
        val nearestBoundary = boundaryDistances.minBy { it.absoluteDistanceFeet }
        val signedClearance = boundaryDistances.minOf { it.signedDistanceFeet } +
            contact.ballFootprintRadiusFeet + thresholds.lineWidthFeet / 2.0
        val boundaryDecision = classifyClearance(signedClearance, uncertainty)
        if (boundaryDecision.first == LineDecision.OUT || boundaryDecision.first == LineDecision.NEAR_LINE) {
            return result(contact, boundaryDecision.first, nearestBoundary, uncertainty, boundaryDecision.second)
        }

        val serveLine = serveKitchenLine(ruleContext)
        if (serveLine != null) {
            val lineDistance = PickleballCourtGeometry.distanceToLine(contact.courtCenter, serveLine)
            val legalSideSign = if (ruleContext == RallyRuleContext.SERVE_NEAR_TO_FAR) 1.0 else -1.0
            val legalSideDistance = lineDistance.signedDistanceFeet * legalSideSign
            val kitchenClearance = legalSideDistance -
                contact.ballFootprintRadiusFeet - thresholds.lineWidthFeet / 2.0
            val kitchenDecision = classifyServeKitchen(kitchenClearance, uncertainty)
            if (kitchenDecision.first != LineDecision.IN) {
                return result(contact, kitchenDecision.first, lineDistance, uncertainty, kitchenDecision.second)
            }
        }

        return result(contact, LineDecision.IN, nearestBoundary, uncertainty, "CONTACT_REGION_CLEAR_OF_RELEVANT_LINES")
    }

    private fun classifyClearance(clearanceFeet: Double, uncertaintyFeet: Double): Pair<LineDecision, String> = when {
        clearanceFeet + uncertaintyFeet < 0.0 -> LineDecision.OUT to "CONTACT_REGION_CLEARLY_OUTSIDE_BOUNDARY"
        uncertaintyFeet == 0.0 && clearanceFeet >= 0.0 -> LineDecision.IN to "BOUNDARY_TOUCH_COUNTS_AS_IN"
        clearanceFeet - uncertaintyFeet <= thresholds.nearLineBandFeet ->
            LineDecision.NEAR_LINE to "CONTACT_REGION_OVERLAPS_BOUNDARY_UNCERTAINTY_BAND"
        else -> LineDecision.IN to "CONTACT_REGION_CLEAR_OF_BOUNDARY"
    }

    private fun classifyServeKitchen(clearanceFeet: Double, uncertaintyFeet: Double): Pair<LineDecision, String> = when {
        clearanceFeet + uncertaintyFeet < 0.0 -> LineDecision.OUT to "SERVE_CONTACT_IN_OR_TOUCHING_NON_VOLLEY_ZONE"
        clearanceFeet - uncertaintyFeet <= thresholds.nearLineBandFeet ->
            LineDecision.NEAR_LINE to "SERVE_CONTACT_NEAR_NON_VOLLEY_ZONE_LINE"
        else -> LineDecision.IN to "SERVE_CONTACT_CLEAR_OF_NON_VOLLEY_ZONE_LINE"
    }

    private fun serveKitchenLine(context: RallyRuleContext): CourtLine? = when (context) {
        RallyRuleContext.RALLY_BOUNDARY -> null
        RallyRuleContext.SERVE_NEAR_TO_FAR -> PickleballCourtGeometry.lines.first { it.id == "FAR_KITCHEN_LINE" }
        RallyRuleContext.SERVE_FAR_TO_NEAR -> PickleballCourtGeometry.lines.first { it.id == "NEAR_KITCHEN_LINE" }
    }

    private fun uncertain(
        contact: GroundContactEstimate,
        nearest: LineDistanceResult?,
        decision: LineDecision?,
        uncertainty: Double,
        reason: String,
    ) = result(contact, decision ?: LineDecision.UNCERTAIN, nearest, uncertainty, reason)

    private fun result(
        contact: GroundContactEstimate,
        decision: LineDecision,
        nearest: LineDistanceResult?,
        uncertainty: Double,
        reason: String,
    ) = LineDecisionResult(
        timestamp = contact.timestamp,
        decision = decision,
        nearestLine = nearest?.line,
        signedDistanceFeet = nearest?.signedDistanceFeet,
        absoluteDistanceFeet = nearest?.absoluteDistanceFeet,
        totalUncertaintyFeet = uncertainty,
        confidence = minOf(contact.ballConfidence, contact.contactConfidence, contact.trackingContinuity),
        reason = reason,
    )

    private fun isFinite(contact: GroundContactEstimate): Boolean =
        contact.timestamp.value >= 0L &&
            contact.courtCenter.xFeet.isFinite() && contact.courtCenter.yFeet.isFinite() &&
            contact.ballFootprintRadiusFeet.isFinite() && contact.ballFootprintRadiusFeet >= 0.0 &&
            contact.contactLocalizationUncertaintyFeet.isFinite() && contact.contactLocalizationUncertaintyFeet >= 0.0 &&
            contact.calibrationErrorFeet.isFinite() && contact.calibrationErrorFeet >= 0.0 &&
            contact.ballConfidence in 0f..1f && contact.contactConfidence in 0f..1f &&
            contact.trackingContinuity in 0f..1f

    private companion object {
        val BOUNDARY_IDS = setOf("LEFT_SIDELINE", "RIGHT_SIDELINE", "NEAR_BASELINE", "FAR_BASELINE")
    }
}
