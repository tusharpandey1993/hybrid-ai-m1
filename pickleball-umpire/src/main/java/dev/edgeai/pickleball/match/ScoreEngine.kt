package dev.edgeai.pickleball.match

enum class Team {
    A,
    B,
    ;

    fun opponent(): Team = if (this == A) B else A
}

enum class MatchType {
    SINGLES,
    DOUBLES,
}

enum class CameraQuality {
    SD,
    HD,
    FHD,
}

enum class MatchPhase {
    SETUP,
    LIVE,
    PAUSED,
    COMPLETED,
    STOPPED,
}

enum class RallyEvidence {
    BALL_NOT_RETURNED,
    DOUBLE_BOUNCE,
    NET_FAULT,
    SERVE_FAULT,
    PLAYER_FAULT,
    OPPONENT_WINNING_SHOT,
    MANUAL_CONFIRMATION,
}

enum class RallySource {
    AI,
    MANUAL,
}

data class MatchSettings(
    val id: String,
    val matchType: MatchType,
    val targetScore: Int,
    val teamAName: String = "TEAM A",
    val teamBName: String = "TEAM B",
    val winningMargin: Int = 2,
    val startingServingTeam: Team = Team.A,
    val cameraFps: Int = 30,
    val cameraQuality: CameraQuality = CameraQuality.HD,
    val recordVideo: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(targetScore == 11 || targetScore == 21)
        require(winningMargin == 2)
        require(cameraFps > 0)
    }
}

sealed interface RallyResolution {
    data class Confirmed(
        val winner: Team,
        val confidence: Float,
        val evidence: Set<RallyEvidence>,
        val source: RallySource = RallySource.AI,
    ) : RallyResolution {
        init {
            require(confidence in 0f..1f)
            require(evidence.isNotEmpty())
            require(source != RallySource.AI || RallyEvidence.MANUAL_CONFIRMATION !in evidence)
        }
    }

    data class ReviewRequired(val reason: String) : RallyResolution {
        init {
            require(reason.isNotBlank())
        }
    }
}

data class ScoreSnapshot(
    val scoreA: Int,
    val scoreB: Int,
    val servingTeam: Team,
    val serverNumber: Int,
    val openingService: Boolean,
)

data class MatchScoreState(
    val settings: MatchSettings,
    val scoreA: Int = 0,
    val scoreB: Int = 0,
    val servingTeam: Team = settings.startingServingTeam,
    val serverNumber: Int = if (settings.matchType == MatchType.DOUBLES) 2 else 1,
    val openingService: Boolean = settings.matchType == MatchType.DOUBLES,
    val phase: MatchPhase = MatchPhase.SETUP,
    val undoStack: List<ScoreSnapshot> = emptyList(),
) {
    init {
        require(scoreA >= 0 && scoreB >= 0)
        require(serverNumber == 1 || (settings.matchType == MatchType.DOUBLES && serverNumber == 2))
    }

    val winner: Team?
        get() = when {
            scoreA >= settings.targetScore && scoreA - scoreB >= settings.winningMargin -> Team.A
            scoreB >= settings.targetScore && scoreB - scoreA >= settings.winningMargin -> Team.B
            else -> null
        }

    val gamePointTeam: Team?
        get() {
            if (phase != MatchPhase.LIVE) return null
            val servingScore = score(servingTeam)
            val receivingScore = score(servingTeam.opponent())
            return servingTeam.takeIf {
                servingScore + 1 >= settings.targetScore &&
                    servingScore + 1 - receivingScore >= settings.winningMargin
            }
        }

    fun score(team: Team): Int = if (team == Team.A) scoreA else scoreB

    internal fun snapshot() = ScoreSnapshot(scoreA, scoreB, servingTeam, serverNumber, openingService)
}

data class ScoreEvent(
    val type: String,
    val previous: ScoreSnapshot,
    val current: ScoreSnapshot,
    val details: Map<String, String> = emptyMap(),
)

data class ScoreTransition(
    val state: MatchScoreState,
    val events: List<ScoreEvent> = emptyList(),
    val reviewReason: String? = null,
    val pointAwardedTo: Team? = null,
)

object ScoreEngine {
    private const val MINIMUM_AUTO_CONFIDENCE = 0.9f

    fun start(settings: MatchSettings): MatchScoreState = MatchScoreState(
        settings = settings,
        servingTeam = settings.startingServingTeam,
        serverNumber = if (settings.matchType == MatchType.DOUBLES) 2 else 1,
        phase = MatchPhase.LIVE,
    )

    fun applyRally(state: MatchScoreState, resolution: RallyResolution): ScoreTransition {
        if (resolution is RallyResolution.ReviewRequired) {
            return review(state, resolution.reason)
        }
        if (state.phase != MatchPhase.LIVE) return review(state, "MATCH_NOT_LIVE")
        if (state.winner != null) return review(state, "GAME_ALREADY_COMPLETED")

        resolution as RallyResolution.Confirmed
        if (resolution.source == RallySource.AI && resolution.confidence < MINIMUM_AUTO_CONFIDENCE) {
            return review(state, "INSUFFICIENT_WINNER_CONFIDENCE")
        }
        val before = state.snapshot()
        val events = mutableListOf<ScoreEvent>()
        if (resolution.source == RallySource.AI) {
            events += ScoreEvent(
                "RALLY_WINNER_DETECTED",
                before,
                before,
                mapOf(
                    "winner" to resolution.winner.name,
                    "confidence" to resolution.confidence.toString(),
                    "evidence" to resolution.evidence.joinToString(",") { it.name },
                ),
            )
        } else {
            events += ScoreEvent(
                "MANUAL_SCORE_CORRECTION",
                before,
                before,
                mapOf("winner" to resolution.winner.name, "evidence" to "MANUAL_CONFIRMATION"),
            )
        }

        if (resolution.winner == state.servingTeam) {
            val isGamePoint = state.gamePointTeam == resolution.winner
            val scored = if (resolution.winner == Team.A) {
                state.copy(scoreA = state.scoreA + 1)
            } else {
                state.copy(scoreB = state.scoreB + 1)
            }
            val completed = scored.winner != null
            val nextState = scored.copy(
                phase = if (completed) MatchPhase.COMPLETED else MatchPhase.LIVE,
                undoStack = state.undoStack + before,
            )
            val after = nextState.snapshot()
            events += ScoreEvent(
                "SCORE_UPDATED",
                before,
                after,
                mapOf("team" to resolution.winner.name, "source" to resolution.source.name),
            )
            if (isGamePoint) {
                events += ScoreEvent("GAME_POINT", before, after, mapOf("team" to resolution.winner.name))
            }
            if (completed) events += ScoreEvent("GAME_COMPLETED", before, after, mapOf("winner" to nextState.winner?.name.orEmpty()))
            return ScoreTransition(nextState, events, pointAwardedTo = resolution.winner)
        }

        val nextServer = when {
            state.settings.matchType == MatchType.SINGLES -> state.servingTeam.opponent() to 1
            state.openingService -> state.servingTeam.opponent() to 1
            state.serverNumber == 1 -> state.servingTeam to 2
            else -> state.servingTeam.opponent() to 1
        }
        val sideOut = nextServer.first != state.servingTeam
        val nextState = state.copy(
            servingTeam = nextServer.first,
            serverNumber = nextServer.second,
            openingService = false,
        )
        val after = nextState.snapshot()
        if (sideOut) events += ScoreEvent("SIDE_OUT", before, after)
        events += ScoreEvent(
            "SERVE_CHANGED",
            before,
            after,
            mapOf("servingTeam" to nextState.servingTeam.name, "serverNumber" to nextState.serverNumber.toString()),
        )
        return ScoreTransition(nextState, events)
    }

    fun awardRallyManually(state: MatchScoreState, team: Team): ScoreTransition = applyRally(
        state,
        RallyResolution.Confirmed(
            winner = team,
            confidence = 1f,
            evidence = setOf(RallyEvidence.MANUAL_CONFIRMATION),
            source = RallySource.MANUAL,
        ),
    )

    fun correctServingTeam(state: MatchScoreState, team: Team, serverNumber: Int): ScoreTransition {
        require(serverNumber == 1 || (state.settings.matchType == MatchType.DOUBLES && serverNumber == 2))
        val before = state.snapshot()
        val next = state.copy(servingTeam = team, serverNumber = serverNumber, openingService = false)
        return ScoreTransition(
            next,
            listOf(
                ScoreEvent(
                    "MANUAL_SCORE_CORRECTION",
                    before,
                    next.snapshot(),
                    mapOf("correction" to "SERVE", "servingTeam" to team.name, "serverNumber" to serverNumber.toString()),
                ),
                ScoreEvent("SERVE_CHANGED", before, next.snapshot()),
            ),
        )
    }

    fun undoLastScore(state: MatchScoreState): ScoreTransition {
        val previous = state.undoStack.lastOrNull() ?: return ScoreTransition(state)
        val before = state.snapshot()
        val next = state.copy(
            scoreA = previous.scoreA,
            scoreB = previous.scoreB,
            servingTeam = previous.servingTeam,
            serverNumber = previous.serverNumber,
            openingService = previous.openingService,
            phase = MatchPhase.LIVE,
            undoStack = state.undoStack.dropLast(1),
        )
        return ScoreTransition(
            next,
            listOf(ScoreEvent("SCORE_UNDO", before, next.snapshot())),
        )
    }

    fun pause(state: MatchScoreState): MatchScoreState =
        if (state.phase == MatchPhase.LIVE) state.copy(phase = MatchPhase.PAUSED) else state

    fun resume(state: MatchScoreState): MatchScoreState =
        if (state.phase == MatchPhase.PAUSED) state.copy(phase = MatchPhase.LIVE) else state

    fun stop(state: MatchScoreState): MatchScoreState = when (state.phase) {
        MatchPhase.LIVE, MatchPhase.PAUSED -> state.copy(phase = MatchPhase.STOPPED)
        else -> state
    }

    fun reset(state: MatchScoreState): MatchScoreState = start(state.settings)

    private fun review(state: MatchScoreState, reason: String) = ScoreTransition(
        state = state,
        events = listOf(ScoreEvent(
            "RALLY_REVIEW_REQUIRED",
            state.snapshot(),
            state.snapshot(),
            mapOf("reason" to reason),
        )),
        reviewReason = reason,
    )
}