package dev.edgeai.pickleball.court

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourtGeometryTest {
    @Test
    fun courtDimensionsAndCalibrationAnchorsMatchOfficialLayout() {
        assertEquals(20.0, PickleballCourtGeometry.WIDTH_FEET, 0.0)
        assertEquals(44.0, PickleballCourtGeometry.LENGTH_FEET, 0.0)
        assertEquals(8, PickleballCourtGeometry.calibrationAnchors.size)
        assertEquals(15.0, PickleballCourtGeometry.NEAR_KITCHEN_Y_FEET, 0.0)
        assertEquals(29.0, PickleballCourtGeometry.FAR_KITCHEN_Y_FEET, 0.0)
    }

    @Test
    fun homographyMapsCameraAndCourtCoordinatesInBothDirections() {
        val transformer = HomographyTransformer(
            doubleArrayOf(0.1, 0.0, -10.0, 0.0, 0.1, -20.0, 0.0, 0.0, 1.0),
        )

        val court = transformer.cameraToCourt(CameraPoint(300.0, 400.0))
        val camera = transformer.courtToCamera(court)

        assertEquals(20.0, court.xFeet, 1e-9)
        assertEquals(20.0, court.yFeet, 1e-9)
        assertEquals(300.0, camera.x, 1e-9)
        assertEquals(400.0, camera.y, 1e-9)
    }

    @Test
    fun lineDistanceIsSignedAndUsesFiniteSegment() {
        val sideline = PickleballCourtGeometry.lines.first { it.id == "LEFT_SIDELINE" }

        val inside = PickleballCourtGeometry.distanceToLine(CourtPoint(1.0, 22.0), sideline)
        val outside = PickleballCourtGeometry.distanceToLine(CourtPoint(-1.0, 22.0), sideline)
        val beyondEnd = PickleballCourtGeometry.distanceToLine(CourtPoint(0.0, -2.0), sideline)

        assertEquals(1.0, inside.absoluteDistanceFeet, 1e-9)
        assertEquals(1.0, inside.signedDistanceFeet, 1e-9)
        assertEquals(-1.0, outside.signedDistanceFeet, 1e-9)
        assertEquals(2.0, beyondEnd.absoluteDistanceFeet, 1e-9)
    }

    @Test
    fun boundaryMembershipAndNearestLineAreIndependentOfUi() {
        val inside = CourtPoint(0.5, 12.0)
        val outside = CourtPoint(-0.2, 12.0)

        assertTrue(PickleballCourtGeometry.isInsideBoundary(inside))
        assertFalse(PickleballCourtGeometry.isInsideBoundary(outside))
        assertEquals("LEFT_SIDELINE", PickleballCourtGeometry.nearestLine(inside).line.id)
        assertEquals(0.5, PickleballCourtGeometry.nearestLine(inside).absoluteDistanceFeet, 1e-9)
    }
}