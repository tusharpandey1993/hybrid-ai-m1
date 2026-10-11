package dev.edgeai.pickleball.court

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCourtIsolationEngineTest {
    @Test
    fun selectedCalibrationProjectsCourtAndSeparatesNeighborCourt() {
        val engine = ActiveCourtIsolationEngine()
        val selected = engine.selectCourt("COURT_1", calibration(), expectedPlayerCount = 4)

        assertEquals("COURT_1", selected.state.activeCourtId)
        assertEquals(4, selected.state.courtPolygonCamera.size)
        assertEquals(2, selected.state.netLineCamera.size)
        assertEquals(CalibrationQuality.GOOD, selected.state.calibrationQuality)
        assertEquals(0.8f, selected.state.calibrationConfidence, 0.001f)
        assertEquals(ActiveCourtRelation.ACTIVE_COURT, engine.classify(CameraPoint(10.0, 20.0)).relation)
        assertEquals(ActiveCourtRelation.EXTENDED_TRACKING_REGION, engine.classify(CameraPoint(21.0, 20.0)).relation)
        assertEquals(ActiveCourtRelation.FOREIGN, engine.classify(CameraPoint(30.0, 20.0)).relation)
        assertTrue(selected.events.any { it.type == "ACTIVE_COURT_SELECTED" })
    }

    @Test
    fun assignedPlayersLockCourtAndCanBeManuallyReassigned() {
        val engine = ActiveCourtIsolationEngine()
        engine.selectCourt("COURT_1", calibration(), expectedPlayerCount = 4)
        repeat(3) { index ->
            val update = engine.assignPlayer(
                "PLAYER_${index + 1}",
                if (index % 2 == 0) CourtTeam.A else CourtTeam.B,
                if (index < 2) CourtTeamZone.NEAR else CourtTeamZone.FAR,
            )
            assertFalse(update.state.courtLocked)
        }

        val locked = engine.assignPlayer("PLAYER_4", CourtTeam.B, CourtTeamZone.FAR)
        assertTrue(locked.state.courtLocked)
        assertTrue(locked.events.any { it.type == "COURT_LOCKED" })

        val reassigned = engine.assignPlayer("PLAYER_4", CourtTeam.A, CourtTeamZone.NEAR)
        assertTrue(reassigned.events.any { it.type == "PLAYER_REASSIGNED" })
        assertFalse(reassigned.state.courtLocked)
        assertTrue(reassigned.events.any { it.type == "COURT_TRACKING_LOST" })
        assertEquals(CourtTeamZone.NEAR, reassigned.state.assignedPlayers.getValue("PLAYER_4").teamZone)
        assertEquals("PLAYER_4", reassigned.state.assignedPlayers.keys.last())
    }

    @Test
    fun singlesCourtLocksOnlyAfterOnePlayerFromEachTeamIsAssigned() {
        val engine = ActiveCourtIsolationEngine()
        engine.selectCourt("COURT_1", calibration(), expectedPlayerCount = 2)
        val first = engine.assignPlayer("PLAYER_A", CourtTeam.A, CourtTeamZone.NEAR)
        assertFalse(first.state.courtLocked)
        val second = engine.assignPlayer("PLAYER_B", CourtTeam.B, CourtTeamZone.FAR)

        assertTrue(second.state.courtLocked)
        assertTrue(second.events.any { it.type == "COURT_LOCKED" })
    }

    @Test
    fun isolatesFourActivePlayersFromFourPlayersOnAdjacentCourt() {
        val engine = ActiveCourtIsolationEngine()
        engine.selectCourt("COURT_1", calibration(), expectedPlayerCount = 4)
        val visibleFeet = listOf(
            CourtPoint(3.0, 8.0), CourtPoint(16.0, 8.0),
            CourtPoint(3.0, 36.0), CourtPoint(16.0, 36.0),
            CourtPoint(31.0, 8.0), CourtPoint(44.0, 8.0),
            CourtPoint(31.0, 36.0), CourtPoint(44.0, 36.0),
        )

        val classifications = visibleFeet.map { point ->
            engine.classify(CameraPoint(point.xFeet, point.yFeet)).relation
        }

        assertEquals(4, classifications.count { it == ActiveCourtRelation.ACTIVE_COURT })
        assertEquals(4, classifications.count { it == ActiveCourtRelation.FOREIGN })
    }

    @Test
    fun poorCalibrationCannotLockCourtAndUnprojectablePointIsUnknown() {
        val engine = ActiveCourtIsolationEngine()
        val poor = engine.selectCourt(
            "COURT_1",
            calibration(quality = CalibrationQuality.POOR),
            expectedPlayerCount = 2,
        )
        engine.assignPlayer("PLAYER_1", CourtTeam.A, CourtTeamZone.NEAR)
        val assignedSecond = engine.assignPlayer("PLAYER_2", CourtTeam.B, CourtTeamZone.FAR)

        assertFalse(poor.state.courtLocked)
        assertFalse(assignedSecond.state.courtLocked)
        assertEquals(ActiveCourtRelation.UNKNOWN, engine.classify(CameraPoint(Double.NaN, 0.0)).relation)
    }

    @Test
    fun ballAssignmentIsScopedToSelectedCourt() {
        val engine = ActiveCourtIsolationEngine()
        engine.selectCourt("COURT_2", calibration(), expectedPlayerCount = 2)
        val update = engine.setActiveBallTrack("BALL_7")

        assertEquals("BALL_7", update.state.activeBallTrackId)
        assertEquals("COURT_2", update.events.single().fields["activeCourtId"])
        assertNotNull(engine.snapshot())
    }

    private fun calibration(quality: CalibrationQuality = CalibrationQuality.GOOD) = CourtCalibration(
        transformer = HomographyTransformer(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
        quality = quality,
        reprojectionErrorPixels = 3.0,
        inlierCount = 8,
        pointCount = 8,
    )
}
