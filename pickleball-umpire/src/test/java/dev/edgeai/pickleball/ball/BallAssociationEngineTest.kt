package dev.edgeai.pickleball.ball

import dev.edgeai.pickleball.court.ActiveCourtIsolationEngine
import dev.edgeai.pickleball.court.CalibrationQuality
import dev.edgeai.pickleball.court.CourtCalibration
import dev.edgeai.pickleball.court.CourtPoint
import dev.edgeai.pickleball.court.CourtTeam
import dev.edgeai.pickleball.court.CourtTeamZone
import dev.edgeai.pickleball.court.HomographyTransformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BallAssociationEngineTest {
    @Test
    fun courtBallBeginsAsCandidateAndNeedsTemporalContinuityOrPlayerEvidence() {
        val engine = engine()
        val first = engine.update(1_000_000_000L, listOf(candidate(1_000_000_000L, "a", 10f, 10f)))
        val firstTrack = first.states.single()
        assertEquals(BallOwnershipStatus.CANDIDATE, firstTrack.status)
        assertNull(first.activeBall)

        val second = engine.update(1_100_000_000L, listOf(candidate(1_100_000_000L, "b", 12f, 10f)))
        assertEquals(firstTrack.trackId, second.states.single().trackId)
        assertEquals(BallOwnershipStatus.CANDIDATE, second.states.single().status)

        val third = engine.update(1_200_000_000L, listOf(candidate(1_200_000_000L, "c", 14f, 10f)))
        assertEquals(BallOwnershipStatus.ACTIVE, third.activeBall?.status)
    }

    @Test
    fun foreignBallCannotBeAssignedToActiveMatchOrStealActiveTrack() {
        val engine = engine()
        val activeCandidate = candidate(1_000_000_000L, "active", 10f, 10f)
        val trackId = engine.update(1_000_000_000L, listOf(activeCandidate)).states.single().trackId
        engine.confirmActiveBall(trackId, "PLAYER_CONFIRMATION")

        val foreign = engine.update(
            1_100_000_000L,
            listOf(candidate(1_100_000_000L, "foreign", 40f, 10f)),
        )
        assertEquals(trackId, foreign.activeBall?.trackId)
        assertEquals(BallOwnershipStatus.ACTIVE, foreign.activeBall?.status)
        assertTrue(foreign.states.any { it.status == BallOwnershipStatus.FOREIGN })
        assertTrue(foreign.events.any { it.type == "FOREIGN_BALL_DETECTED" })
        assertNotEquals("foreign", foreign.activeBall?.candidateId)
    }

    @Test
    fun ballEnteringFromAdjacentCourtStaysUnownedWithoutPlayerEvidence() {
        val engine = engine()
        val first = engine.update(1_000_000_000L, listOf(candidate(1_000_000_000L, "foreign-1", 30f, 10f)))
        assertEquals(BallOwnershipStatus.FOREIGN, first.states.single().status)
        var update = first
        repeat(4) { index ->
            val timestamp = 1_100_000_000L + index * 100_000_000L
            update = engine.update(timestamp, listOf(candidate(timestamp, "foreign-${index + 2}", 19f - index, 10f)))
        }

        assertNull(update.activeBall)
        assertEquals(BallOwnershipStatus.CANDIDATE, update.states.single().status)
        assertEquals("FOREIGN_BALL_ENTERED_ACTIVE_COURT_REQUIRES_CONFIRMATION", update.states.single().reason)
    }

    @Test
    fun activeBallRetainsIdentityOutsideCourtAndThroughShortOcclusion() {
        val engine = engine()
        val candidate = candidate(1_000_000_000L, "a", 10f, 10f)
        val trackId = engine.update(1_000_000_000L, listOf(candidate)).states.single().trackId
        engine.confirmActiveBall(trackId, "MANUAL")

        val outside = engine.update(1_100_000_000L, listOf(candidate(1_100_000_000L, "b", 21f, 10f)))
        assertEquals(BallOwnershipStatus.ACTIVE, outside.activeBall?.status)
        assertEquals(trackId, outside.activeBall?.trackId)
        assertEquals("EXTENDED_TRACKING_REGION", outside.activeBall?.activeCourtRelation?.name)

        val occluded = engine.update(1_250_000_000L, emptyList())
        assertEquals(BallOwnershipStatus.ACTIVE, occluded.activeBall?.status)
        assertTrue(occluded.activeBall?.occluded == true)
        assertTrue(occluded.trackingUncertain.not())
    }

    @Test
    fun crossingCandidateTrajectoriesRequireOwnershipReview() {
        val engine = engine()
        val first = candidate(1_000_000_000L, "active", 8f, 10f)
        val trackId = engine.update(1_000_000_000L, listOf(first)).states.single().trackId
        engine.confirmActiveBall(trackId, "MANUAL")
        engine.update(1_100_000_000L, listOf(candidate(1_100_000_000L, "active-2", 12f, 10f)))

        val crossed = engine.update(
            1_200_000_000L,
            listOf(
                candidate(1_200_000_000L, "trajectory-1", 16f, 10f),
                candidate(1_200_000_000L, "trajectory-2", 16f, 10f),
            ),
        )

        assertEquals(BallOwnershipStatus.UNKNOWN, crossed.activeBall?.status)
        assertTrue(crossed.trackingUncertain)
        assertTrue(crossed.events.any { it.type == "BALL_OWNERSHIP_UNCERTAIN" })
    }

    @Test
    fun playerHitEvidenceCanAssignActiveBallAndReleaseReturnsUnknown() {
        val engine = engine()
        val hit = candidate(1_000_000_000L, "hit", 10f, 10f, players = setOf("PLAYER_1"))
        val update = engine.update(1_000_000_000L, listOf(hit))
        val track = update.states.single()
        val assigned = engine.confirmActiveBall(track.trackId, "MANUAL_REASSIGNMENT")
        assertEquals(track.trackId, assigned.activeBall?.trackId)
        val released = engine.releaseActiveBall("USER_REJECTED_OWNER")
        assertNull(released.activeBall)
        assertTrue(released.trackingUncertain)
    }

    private fun engine(): BallAssociationEngine {
        val court = ActiveCourtIsolationEngine()
        court.selectCourt(
            "COURT_1",
            CourtCalibration(
                transformer = HomographyTransformer(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
                quality = CalibrationQuality.EXCELLENT,
                reprojectionErrorPixels = 1.0,
                inlierCount = 8,
                pointCount = 8,
            ),
            expectedPlayerCount = 2,
        )
        court.assignPlayer("PLAYER_1", CourtTeam.A, CourtTeamZone.NEAR)
        court.assignPlayer("PLAYER_2", CourtTeam.B, CourtTeamZone.FAR)
        return BallAssociationEngine(court, matchRadiusPixels = 30f)
    }

    private fun candidate(
        timestampNs: Long,
        id: String,
        x: Float,
        y: Float,
        players: Set<String> = emptySet(),
    ) = BallCandidateObservation(
        timestampNs = timestampNs,
        candidateId = id,
        cameraX = x,
        cameraY = y,
        confidence = 0.95f,
        associatedPlayerIds = players,
    )
}