package dev.edgeai.pickleball.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreEngineTest {
    @Test
    fun singlesSideOutAndGameToEleven() {
        var state = ScoreEngine.start(settings(MatchType.SINGLES))
        repeat(11) { state = ScoreEngine.awardRallyManually(state, Team.A).state }

        assertEquals(11, state.scoreA)
        assertEquals(0, state.scoreB)
        assertEquals(MatchPhase.COMPLETED, state.phase)
        assertEquals(Team.A, state.winner)

        val sideOut = ScoreEngine.applyRally(
            ScoreEngine.start(settings(MatchType.SINGLES)),
            confirmed(Team.B),
        )
        assertEquals(Team.B, sideOut.state.servingTeam)
        assertEquals(0, sideOut.state.scoreA)
        assertEquals(0, sideOut.state.scoreB)
        assertTrue(sideOut.events.any { it.type == "SIDE_OUT" })
    }

    @Test
    fun doublesUsesTwoServersThenSidesOut() {
        var state = ScoreEngine.start(settings(MatchType.DOUBLES))
        assertEquals(2, state.serverNumber)

        val serverFault = ScoreEngine.applyRally(state, confirmed(Team.B))
        state = serverFault.state
        assertEquals(Team.B, state.servingTeam)
        assertEquals(1, state.serverNumber)
        assertNull(state.winner)

        val point = ScoreEngine.applyRally(state, confirmed(Team.B))
        assertEquals(1, point.state.scoreB)
        assertEquals(1, point.state.serverNumber)

        val secondServer = ScoreEngine.applyRally(point.state, confirmed(Team.A))
        assertEquals(Team.B, secondServer.state.servingTeam)
        assertEquals(2, secondServer.state.serverNumber)
        val sideOut = ScoreEngine.applyRally(secondServer.state, confirmed(Team.A))
        assertEquals(Team.A, sideOut.state.servingTeam)
        assertEquals(1, sideOut.state.serverNumber)
        assertTrue(sideOut.events.any { it.type == "SIDE_OUT" })
    }

    @Test
    fun doublesCanCompleteGameToEleven() {
        var state = ScoreEngine.start(settings(MatchType.DOUBLES))
        repeat(11) { state = awardAsServer(state, Team.A) }

        assertEquals(11, state.scoreA)
        assertEquals(0, state.scoreB)
        assertEquals(Team.A, state.winner)
    }

    @Test
    fun gameToTwentyOneRequiresTwoPointLead() {
        var state = ScoreEngine.start(settings(target = 21)).copy(
            scoreA = 20,
            scoreB = 20,
            serverNumber = 1,
            openingService = false,
        )
        state = awardAsServer(state, Team.A)
        assertEquals(MatchPhase.LIVE, state.phase)
        assertNull(state.winner)
        state = awardAsServer(state, Team.B)
        assertEquals(21, state.scoreA)
        assertEquals(21, state.scoreB)
        state = awardAsServer(state, Team.A)
        assertEquals(22, state.scoreA)
        assertEquals(21, state.scoreB)
        assertEquals(MatchPhase.LIVE, state.phase)
        state = awardAsServer(state, Team.A)
        assertEquals(23, state.scoreA)
        assertEquals(MatchPhase.COMPLETED, state.phase)
    }

    @Test
    fun deuceAndGamePointRespectWinByTwo() {
        var state = ScoreEngine.start(settings()).copy(scoreA = 10, scoreB = 10, serverNumber = 1)
        assertNull(state.gamePointTeam)
        state = awardAsServer(state, Team.A)
        assertEquals(11, state.scoreA)
        assertEquals(10, state.scoreB)
        assertNull(state.winner)
        state = awardAsServer(state, Team.A)
        assertEquals(12, state.scoreA)
        assertEquals(Team.A, state.winner)
    }

    @Test
    fun uncertainRallyNeverAwardsPointAndProducesReviewEvent() {
        val initial = ScoreEngine.start(settings())
        val transition = ScoreEngine.applyRally(initial, RallyResolution.ReviewRequired("LINE_ONLY"))

        assertEquals(initial, transition.state)
        assertEquals("LINE_ONLY", transition.reviewReason)
        assertEquals("RALLY_REVIEW_REQUIRED", transition.events.single().type)
        assertNull(transition.pointAwardedTo)
    }

    @Test
    fun lowConfidenceWinnerIsDowngradedToReview() {
        val initial = ScoreEngine.start(settings())
        val transition = ScoreEngine.applyRally(
            initial,
            RallyResolution.Confirmed(
                winner = Team.A,
                confidence = 0.6f,
                evidence = setOf(RallyEvidence.DOUBLE_BOUNCE),
            ),
        )

        assertEquals(initial, transition.state)
        assertEquals("INSUFFICIENT_WINNER_CONFIDENCE", transition.reviewReason)
        assertTrue(transition.events.none { it.type == "RALLY_WINNER_DETECTED" })
    }

    @Test
    fun undoAndServeCorrectionRestoreTheExpectedState() {
        val initial = ScoreEngine.start(settings(MatchType.DOUBLES))
        val awarded = ScoreEngine.awardRallyManually(initial, Team.A).state
        val correctedServe = ScoreEngine.correctServingTeam(awarded, Team.B, 2).state
        val undo = ScoreEngine.undoLastScore(correctedServe)

        assertEquals(0, undo.state.scoreA)
        assertEquals(Team.A, undo.state.servingTeam)
        assertEquals(2, undo.state.serverNumber)
        assertEquals("SCORE_UNDO", undo.events.single().type)
    }

    @Test
    fun pauseResumeAndResetPreserveConfiguration() {
        val initial = ScoreEngine.start(settings(MatchType.DOUBLES))
        val paused = ScoreEngine.pause(ScoreEngine.awardRallyManually(initial, Team.A).state)
        assertEquals(MatchPhase.PAUSED, paused.phase)
        assertEquals(paused, ScoreEngine.applyRally(paused, confirmed(Team.A)).state)

        val resumed = ScoreEngine.resume(paused)
        val reset = ScoreEngine.reset(resumed)
        assertEquals(MatchPhase.LIVE, reset.phase)
        assertEquals(0, reset.scoreA)
        assertEquals(MatchType.DOUBLES, reset.settings.matchType)
        assertEquals(2, reset.serverNumber)
    }

    @Test
    fun completedGameCanBeResetWithoutChangingSettings() {
        var state = ScoreEngine.start(settings())
        repeat(11) { state = ScoreEngine.awardRallyManually(state, Team.A).state }

        val reset = ScoreEngine.reset(state)

        assertEquals(MatchPhase.LIVE, reset.phase)
        assertEquals(0, reset.scoreA)
        assertEquals(11, reset.settings.targetScore)
    }

    private fun settings(
        type: MatchType = MatchType.SINGLES,
        target: Int = 11,
    ) = MatchSettings(
        id = "test-match",
        matchType = type,
        targetScore = target,
        startingServingTeam = Team.A,
    )

    private fun confirmed(winner: Team) = RallyResolution.Confirmed(
        winner = winner,
        confidence = 0.99f,
        evidence = setOf(RallyEvidence.DOUBLE_BOUNCE),
    )

    private fun awardAsServer(state: MatchScoreState, team: Team): MatchScoreState {
        var current = state
        repeat(2) {
            if (current.servingTeam != team) {
                current = ScoreEngine.applyRally(current, confirmed(team)).state
            }
        }
        return ScoreEngine.awardRallyManually(current, team).state
    }
}