package dev.edgeai.pickleball.umpire

import dev.edgeai.pickleball.court.CalibrationQuality
import dev.edgeai.pickleball.court.CourtPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class LineDecisionEngineTest {
    private val engine = LineDecisionEngine()

    @Test
    fun clearInsideAndOutsideContactsReturnDecisions() {
        assertEquals(LineDecision.IN, engine.decide(contact(10.0, 20.0), RallyRuleContext.RALLY_BOUNDARY).decision)
        val outside = engine.decide(contact(-1.0, 20.0), RallyRuleContext.RALLY_BOUNDARY)
        assertEquals(LineDecision.OUT, outside.decision)
        assertEquals("LEFT_SIDELINE", outside.nearestLine?.id)
    }

    @Test
    fun uncertaintyAndNearBoundaryDoNotForceAnInOrOutCall() {
        val nearLine = engine.decide(
            contact(x = 0.0, y = 20.0, footprintRadius = 0.05, contactError = 0.1),
            RallyRuleContext.RALLY_BOUNDARY,
        )
        val poorCalibration = engine.decide(
            contact(x = 10.0, y = 20.0, quality = CalibrationQuality.POOR),
            RallyRuleContext.RALLY_BOUNDARY,
        )

        assertEquals(LineDecision.NEAR_LINE, nearLine.decision)
        assertEquals(LineDecision.UNCERTAIN, poorCalibration.decision)
        assertEquals("CALIBRATION_QUALITY_TOO_LOW", poorCalibration.reason)
    }

    @Test
    fun boundaryTouchIsInButServeKitchenLineTouchIsFault() {
        val boundaryTouch = contact(x = 0.0, y = 20.0, contactError = 0.0, calibrationError = 0.0)
        val kitchenLineTouch = contact(x = 10.0, y = 29.0, contactError = 0.0)

        assertEquals(LineDecision.IN, engine.decide(boundaryTouch, RallyRuleContext.RALLY_BOUNDARY).decision)
        assertEquals(LineDecision.OUT, engine.decide(kitchenLineTouch, RallyRuleContext.SERVE_NEAR_TO_FAR).decision)
    }

    @Test
    fun serveUsesDestinationKitchenLineButRallyDoesNot() {
        val kitchenLanding = contact(x = 10.0, y = 28.5, footprintRadius = 0.02)
        val farServiceLanding = contact(x = 10.0, y = 35.0)

        assertEquals(LineDecision.OUT, engine.decide(kitchenLanding, RallyRuleContext.SERVE_NEAR_TO_FAR).decision)
        assertEquals(LineDecision.IN, engine.decide(farServiceLanding, RallyRuleContext.SERVE_NEAR_TO_FAR).decision)
        assertEquals(LineDecision.IN, engine.decide(kitchenLanding, RallyRuleContext.RALLY_BOUNDARY).decision)
    }

    @Test
    fun lowConfidenceContactIsUncertain() {
        val estimate = contact(x = 10.0, y = 20.0, contactConfidence = 0.2f)
        val result = engine.decide(estimate, RallyRuleContext.RALLY_BOUNDARY)

        assertEquals(LineDecision.UNCERTAIN, result.decision)
        assertEquals("LOW_CONTACT_CONFIDENCE", result.reason)
    }

    private fun contact(
        x: Double,
        y: Double,
        footprintRadius: Double = 0.0,
        contactError: Double = 0.02,
        calibrationError: Double = 0.01,
        contactConfidence: Float = 0.9f,
        quality: CalibrationQuality = CalibrationQuality.EXCELLENT,
    ) = GroundContactEstimate(
        timestamp = ContactTimestampNs(1_000_000_000L),
        courtCenter = CourtPoint(x, y),
        ballFootprintRadiusFeet = footprintRadius,
        contactLocalizationUncertaintyFeet = contactError,
        calibrationErrorFeet = calibrationError,
        ballConfidence = 0.9f,
        contactConfidence = contactConfidence,
        trackingContinuity = 0.9f,
        calibrationQuality = quality,
    )
}
