package dev.edgeai.pickleball.match

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.edgeai.pickleball.court.ActiveCourtIsolationEngine
import dev.edgeai.pickleball.court.ActiveCourtPlayerAssignment
import dev.edgeai.pickleball.court.ActiveCourtUpdate
import dev.edgeai.pickleball.court.CourtCalibration
import dev.edgeai.pickleball.court.CourtTeam
import dev.edgeai.pickleball.court.CourtTeamZone
import dev.edgeai.pickleball.logging.SessionLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class MatchScreen {
    SETUP,
    LIVE,
    SUMMARY,
    HISTORY,
    EVENTS,
}

enum class ActiveCourtUiStatus {
    NOT_SELECTED,
    SELECTED,
    LOCKED,
    CALIBRATION_UNCERTAIN,
}

data class MatchDraft(
    val matchType: MatchType = MatchType.SINGLES,
    val targetScore: Int = 11,
    val teamAName: String = "TEAM A",
    val teamBName: String = "TEAM B",
    val startingServingTeam: Team = Team.A,
    val cameraFps: Int = 30,
    val cameraQuality: CameraQuality = CameraQuality.HD,
    val recordVideo: Boolean = false,
)

data class MatchLogRecord(
    val event: String,
    val fields: Map<String, Any?> = emptyMap(),
)

data class MatchUiState(
    val screen: MatchScreen = MatchScreen.SETUP,
    val draft: MatchDraft = MatchDraft(),
    val score: MatchScoreState? = null,
    val elapsedMs: Long = 0L,
    val matchStartedEpochMs: Long = 0L,
    val cameraStatus: String = "Camera initializing",
    val cameraFpsOptions: List<Int> = emptyList(),
    val cameraQualityOptions: List<CameraQuality> = emptyList(),
    val activeCourtId: String? = null,
    val activeCourtConfidence: Float = 0f,
    val activeCourtStatus: ActiveCourtUiStatus = ActiveCourtUiStatus.NOT_SELECTED,
    val activeCourtLocked: Boolean = false,
    val assignedCourtPlayers: List<ActiveCourtPlayerAssignment> = emptyList(),
    val activeBallTrackId: String? = null,
    val trackingStatus: String = "Not started",
    val cameraFps: Float? = null,
    val modelStatus: String = "Ball model not loaded",
    val lastDecision: String = "NO CONFIRMED LINE CALL",
    val lastDecisionSource: String = "AI",
    val reviewRequired: Boolean = false,
    val feedback: String? = null,
    val ballDetectionCount: Int = 0,
    val lineEventCount: Int = 0,
    val uncertainEventCount: Int = 0,
    val logPath: String? = null,
    val recordingPath: String? = null,
    val recordingPaths: List<String> = emptyList(),
    val recordingStatus: String = "OFF",
    val history: List<MatchSummary> = emptyList(),
    val selectedSummary: MatchSummary? = null,
    val selectedEvents: List<MatchHistoryEvent> = emptyList(),
)

sealed interface MatchAction {
    data class MatchTypeChanged(val value: MatchType) : MatchAction
    data class TargetChanged(val value: Int) : MatchAction
    data class TeamANameChanged(val value: String) : MatchAction
    data class TeamBNameChanged(val value: String) : MatchAction
    data class StartingTeamChanged(val value: Team) : MatchAction
    data class CameraFpsChanged(val value: Int) : MatchAction
    data class CameraQualityChanged(val value: CameraQuality) : MatchAction
    data class RecordingChanged(val value: Boolean) : MatchAction
    data object StartMatch : MatchAction
    data object PauseResume : MatchAction
    data class StopMatch(val logPath: String?, val recordingPath: String? = null) : MatchAction
    data object ResetConfirmed : MatchAction
    data class UmpireResolution(val resolution: RallyResolution) : MatchAction
    data class AwardRally(val team: Team) : MatchAction
    data object UndoScore : MatchAction
    data class CorrectServe(val team: Team, val serverNumber: Int) : MatchAction
    data class CameraStateChanged(val status: String) : MatchAction
    data class CameraCapabilitiesLoaded(val fps: List<Int>, val qualities: List<CameraQuality>) : MatchAction
    data class TrackingStateChanged(val status: String, val modelStatus: String? = null) : MatchAction
    data class CameraFpsReported(val fps: Float) : MatchAction
    data class BallDetectionsReported(val count: Int) : MatchAction
    data class ModelStatusChanged(val status: String) : MatchAction
    data class RecordingStatusChanged(
        val status: String,
        val path: String? = null,
        val finalized: Boolean = false,
    ) : MatchAction
    data class CourtCalibrationCompleted(val calibration: CourtCalibration) : MatchAction
    data class CourtPlayerAssigned(val playerId: String, val team: CourtTeam, val teamZone: CourtTeamZone) : MatchAction
    data class CourtPlayerUnassigned(val playerId: String) : MatchAction
    data class ActiveBallTrackChanged(val trackId: String?) : MatchAction
    data class LineDecisionReported(val decision: String, val uncertain: Boolean, val source: String = "AI") : MatchAction
    data object ShowHistory : MatchAction
    data class OpenSummary(val summary: MatchSummary) : MatchAction
    data object ShowEvents : MatchAction
    data object BackToSummary : MatchAction
    data object NewMatch : MatchAction
    data object ClearFeedback : MatchAction
}

class MatchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MatchHistoryRepository(application)
    private val activeCourtEngine = ActiveCourtIsolationEngine()
    private var courtCalibration: CourtCalibration? = null
    val sessionLogger = SessionLogger(application.applicationContext)
    private val _state = MutableStateFlow(MatchUiState(history = repository.load()))
    val state: StateFlow<MatchUiState> = _state.asStateFlow()
    private var startedElapsedRealtime = 0L
    private var timerJob: Job? = null

    fun dispatch(action: MatchAction): List<MatchLogRecord> {
        return when (action) {
            is MatchAction.MatchTypeChanged -> {
                _state.update { it.copy(draft = it.draft.copy(matchType = action.value)) }
                courtCalibration?.let(::selectActiveCourt) ?: emptyList()
            }
            is MatchAction.TargetChanged -> updateDraft { it.copy(targetScore = action.value) }
            is MatchAction.TeamANameChanged -> updateDraft { it.copy(teamAName = action.value.take(40)) }
            is MatchAction.TeamBNameChanged -> updateDraft { it.copy(teamBName = action.value.take(40)) }
            is MatchAction.StartingTeamChanged -> updateDraft { it.copy(startingServingTeam = action.value) }
            is MatchAction.CameraFpsChanged -> updateDraft { it.copy(cameraFps = action.value) }
            is MatchAction.CameraQualityChanged -> updateDraft { it.copy(cameraQuality = action.value) }
            is MatchAction.RecordingChanged -> updateDraft { it.copy(recordVideo = action.value) }
            MatchAction.StartMatch -> startMatch()
            MatchAction.PauseResume -> pauseOrResume()
            is MatchAction.StopMatch -> stopMatch(action.logPath, action.recordingPath)
            MatchAction.ResetConfirmed -> resetMatch()
            is MatchAction.UmpireResolution -> applyResolution(action.resolution)
            is MatchAction.AwardRally -> awardManually(action.team)
            MatchAction.UndoScore -> undoScore()
            is MatchAction.CorrectServe -> correctServe(action.team, action.serverNumber)
            is MatchAction.CameraStateChanged -> {
                _state.update { it.copy(cameraStatus = action.status) }
                emptyList()
            }
            is MatchAction.CameraCapabilitiesLoaded -> {
                _state.update { current ->
                    val fpsOptions = action.fps.distinct().sorted()
                    val preferredFps = fpsOptions.firstOrNull { it == 30 }
                        ?: fpsOptions.filter { it < 30 }.maxOrNull()
                        ?: fpsOptions.minOrNull()
                    val preferredQuality = when {
                        current.draft.cameraQuality in action.qualities -> current.draft.cameraQuality
                        CameraQuality.HD in action.qualities -> CameraQuality.HD
                        CameraQuality.SD in action.qualities -> CameraQuality.SD
                        else -> action.qualities.firstOrNull()
                    }
                    current.copy(
                        cameraFpsOptions = fpsOptions,
                        cameraQualityOptions = action.qualities.distinct(),
                        draft = current.draft.copy(
                            cameraFps = current.draft.cameraFps.takeIf { it in fpsOptions }
                                ?: preferredFps
                                ?: current.draft.cameraFps,
                            cameraQuality = preferredQuality
                                ?: current.draft.cameraQuality,
                        ),
                    )
                }
                emptyList()
            }
            is MatchAction.TrackingStateChanged -> {
                _state.update {
                    it.copy(trackingStatus = action.status, modelStatus = action.modelStatus ?: it.modelStatus)
                }
                emptyList()
            }
            is MatchAction.CameraFpsReported -> {
                _state.update { it.copy(cameraFps = action.fps) }
                emptyList()
            }
            is MatchAction.RecordingStatusChanged -> {
                _state.update { current ->
                    val paths = if (action.finalized && action.path != null) {
                        (current.recordingPaths + action.path).distinct()
                    } else {
                        current.recordingPaths
                    }
                    current.copy(
                        recordingStatus = action.status,
                        recordingPath = paths.lastOrNull() ?: current.recordingPath,
                        recordingPaths = paths,
                    )
                }
                emptyList()
            }
            is MatchAction.CourtCalibrationCompleted -> selectActiveCourt(action.calibration)
            is MatchAction.CourtPlayerAssigned -> {
                val update = activeCourtEngine.assignPlayer(action.playerId, action.team, action.teamZone)
                publishCourtState(update)
                update.events.map { MatchLogRecord(it.type, it.fields) }
            }
            is MatchAction.CourtPlayerUnassigned -> {
                val update = activeCourtEngine.unassignPlayer(action.playerId)
                publishCourtState(update)
                update.events.map { MatchLogRecord(it.type, it.fields) }
            }
            is MatchAction.ActiveBallTrackChanged -> {
                val update = activeCourtEngine.setActiveBallTrack(action.trackId)
                publishCourtState(update)
                update.events.map { MatchLogRecord(it.type, it.fields) }
            }
            is MatchAction.BallDetectionsReported -> {
                if (action.count > 0) _state.update { it.copy(ballDetectionCount = it.ballDetectionCount + action.count) }
                emptyList()
            }
            is MatchAction.ModelStatusChanged -> {
                _state.update { it.copy(modelStatus = action.status) }
                emptyList()
            }
            is MatchAction.LineDecisionReported -> {
                _state.update {
                    it.copy(
                        lastDecision = action.decision,
                        lastDecisionSource = action.source,
                        lineEventCount = it.lineEventCount + 1,
                        uncertainEventCount = it.uncertainEventCount + if (action.uncertain) 1 else 0,
                        reviewRequired = action.uncertain,
                        feedback = action.decision,
                    )
                }
                listOf(MatchLogRecord("LINE_DECISION", mapOf(
                    "decision" to action.decision,
                    "uncertain" to action.uncertain,
                    "source" to "UMPIRE_ENGINE",
                )))
            }
            MatchAction.ShowHistory -> {
                _state.update { it.copy(screen = MatchScreen.HISTORY, history = repository.load()) }
                emptyList()
            }
            is MatchAction.OpenSummary -> {
                _state.update { it.copy(screen = MatchScreen.SUMMARY, selectedSummary = action.summary) }
                emptyList()
            }
            MatchAction.ShowEvents -> {
                val current = _state.value
                val path = current.selectedSummary?.logPath ?: current.logPath
                _state.update { it.copy(screen = MatchScreen.EVENTS, selectedEvents = emptyList()) }
                viewModelScope.launch(Dispatchers.IO) {
                    if (path == sessionLogger.currentFile?.absolutePath) sessionLogger.awaitStopped(5_000L)
                    val events = repository.readEvents(path)
                    _state.update { it.copy(selectedEvents = events) }
                }
                emptyList()
            }
            MatchAction.BackToSummary -> {
                _state.update { it.copy(screen = MatchScreen.SUMMARY) }
                emptyList()
            }
            MatchAction.NewMatch -> {
                timerJob?.cancel()
                _state.update {
                    MatchUiState(
                        screen = MatchScreen.SETUP,
                        draft = it.draft,
                        cameraStatus = it.cameraStatus,
                        trackingStatus = "Not started",
                        modelStatus = it.modelStatus,
                        history = repository.load(),
                    )
                }
                emptyList()
            }
            MatchAction.ClearFeedback -> {
                _state.update { it.copy(feedback = null) }
                emptyList()
            }
        }
    }

    private fun updateDraft(change: (MatchDraft) -> MatchDraft): List<MatchLogRecord> {
        _state.update { it.copy(draft = change(it.draft)) }
        return emptyList()
    }

    private fun selectActiveCourt(calibration: CourtCalibration): List<MatchLogRecord> {
        courtCalibration = calibration
        val expectedPlayers = if (_state.value.draft.matchType == MatchType.SINGLES) 2 else 4
        val update = activeCourtEngine.selectCourt(ACTIVE_COURT_ID, calibration, expectedPlayers)
        publishCourtState(update)
        return update.events.map { MatchLogRecord(it.type, it.fields) }
    }

    private fun publishCourtState(update: ActiveCourtUpdate) {
        val court = update.state
        _state.update {
            it.copy(
                activeCourtId = court.activeCourtId,
                activeCourtConfidence = court.calibrationConfidence,
                activeCourtLocked = court.courtLocked,
                activeCourtStatus = when {
                    court.courtLocked -> ActiveCourtUiStatus.LOCKED
                    court.calibrationConfidence < MINIMUM_COURT_CONFIDENCE -> ActiveCourtUiStatus.CALIBRATION_UNCERTAIN
                    else -> ActiveCourtUiStatus.SELECTED
                },
                assignedCourtPlayers = court.assignedPlayers.values.toList(),
                activeBallTrackId = court.activeBallTrackId,
            )
        }
    }

    private fun startMatch(): List<MatchLogRecord> {
        if (_state.value.score?.phase == MatchPhase.LIVE) return emptyList()
        val draft = _state.value.draft
        val settings = MatchSettings(
            id = UUID.randomUUID().toString(),
            matchType = draft.matchType,
            targetScore = draft.targetScore,
            teamAName = draft.teamAName.trim().ifBlank { "TEAM A" },
            teamBName = draft.teamBName.trim().ifBlank { "TEAM B" },
            startingServingTeam = draft.startingServingTeam,
            cameraFps = draft.cameraFps,
            cameraQuality = draft.cameraQuality,
            recordVideo = draft.recordVideo,
        )
        val score = ScoreEngine.start(settings)
        startedElapsedRealtime = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                screen = MatchScreen.LIVE,
                score = score,
                elapsedMs = 0L,
                matchStartedEpochMs = System.currentTimeMillis(),
                reviewRequired = true,
                feedback = "RALLY REVIEW REQUIRED",
                ballDetectionCount = 0,
                lineEventCount = 0,
                uncertainEventCount = 0,
                logPath = null,
                recordingPath = null,
            )
        }
        startTimer()
        return listOf(
            MatchLogRecord("MATCH_CREATED", mapOf(
                "matchId" to settings.id,
                "matchType" to settings.matchType.name,
                "targetScore" to settings.targetScore,
                "teamA" to settings.teamAName,
                "teamB" to settings.teamBName,
                "scoringMode" to "TRADITIONAL_SIDE_OUT",
                "cameraFpsRequested" to settings.cameraFps,
                "cameraQuality" to settings.cameraQuality.name,
                "recordVideo" to settings.recordVideo,
            )),
            MatchLogRecord("MATCH_STARTED", mapOf("matchId" to settings.id)),
            MatchLogRecord("RALLY_REVIEW_REQUIRED", mapOf("reason" to "WINNER_NOT_ESTABLISHED")),
        ) + listOfNotNull(_state.value.activeCourtId?.let { courtId ->
            MatchLogRecord("ACTIVE_COURT_SELECTED", mapOf(
                "activeCourtId" to courtId,
                "calibrationConfidence" to _state.value.activeCourtConfidence,
                "courtLocked" to _state.value.activeCourtLocked,
            ))
        })
    }

    private fun pauseOrResume(): List<MatchLogRecord> {
        val score = _state.value.score ?: return emptyList()
        return when (score.phase) {
            MatchPhase.LIVE -> {
                val paused = ScoreEngine.pause(score)
                timerJob?.cancel()
                _state.update { it.copy(score = paused, elapsedMs = elapsedNow()) }
                listOf(MatchLogRecord("MATCH_PAUSED", scoreFields(score, paused)))
            }
            MatchPhase.PAUSED -> {
                val resumed = ScoreEngine.resume(score)
                startedElapsedRealtime = SystemClock.elapsedRealtime() - _state.value.elapsedMs
                _state.update { it.copy(score = resumed) }
                startTimer()
                listOf(MatchLogRecord("MATCH_RESUMED", scoreFields(score, resumed)))
            }
            else -> emptyList()
        }
    }

    private fun stopMatch(logPath: String?, recordingPath: String?): List<MatchLogRecord> {
        val current = _state.value
        val score = current.score ?: return emptyList()
        if (score.phase == MatchPhase.STOPPED || score.phase == MatchPhase.SETUP) return emptyList()
        timerJob?.cancel()
        val stopped = ScoreEngine.stop(score)
        val elapsed = if (score.phase == MatchPhase.LIVE) elapsedNow() else current.elapsedMs
        val summary = MatchSummary(
            id = score.settings.id,
            dateEpochMs = current.matchStartedEpochMs,
            matchType = score.settings.matchType,
            targetScore = score.settings.targetScore,
            teamAName = score.settings.teamAName,
            teamBName = score.settings.teamBName,
            scoreA = stopped.scoreA,
            scoreB = stopped.scoreB,
            winner = stopped.winner,
            durationMs = elapsed,
            logPath = logPath,
            recordingPath = recordingPath,
            recordingPaths = (current.recordingPaths + listOfNotNull(recordingPath)).distinct(),
            ballDetectionCount = current.ballDetectionCount,
            lineEventCount = current.lineEventCount,
            uncertainEventCount = current.uncertainEventCount,
        )
        repository.save(summary)
        _state.update {
            it.copy(
                screen = MatchScreen.SUMMARY,
                score = stopped,
                elapsedMs = elapsed,
                logPath = logPath,
                recordingPath = recordingPath,
                selectedSummary = summary,
                history = repository.load(),
                feedback = "MATCH STOPPED",
            )
        }
        return listOf(MatchLogRecord("MATCH_STOPPED", mapOf(
            "matchId" to summary.id,
            "scoreA" to summary.scoreA,
            "scoreB" to summary.scoreB,
            "durationMs" to summary.durationMs,
        )))
    }

    private fun resetMatch(): List<MatchLogRecord> {
        val score = _state.value.score ?: return emptyList()
        if (score.phase != MatchPhase.LIVE && score.phase != MatchPhase.PAUSED &&
            score.phase != MatchPhase.COMPLETED
        ) return emptyList()
        val reset = ScoreEngine.reset(score)
        startedElapsedRealtime = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                score = reset,
                elapsedMs = 0L,
                ballDetectionCount = 0,
                lineEventCount = 0,
                uncertainEventCount = 0,
                reviewRequired = true,
                feedback = "RALLY REVIEW REQUIRED",
            )
        }
        startTimer()
        return listOf(MatchLogRecord("MATCH_RESET", scoreFields(score, reset)))
    }

    private fun applyResolution(resolution: RallyResolution): List<MatchLogRecord> {
        val score = _state.value.score ?: return emptyList()
        val transition = ScoreEngine.applyRally(score, resolution)
        val feedback = when {
            transition.state.phase == MatchPhase.COMPLETED -> "GAME OVER"
            transition.reviewReason != null -> "RALLY REVIEW REQUIRED"
            transition.pointAwardedTo != null && resolution is RallyResolution.Confirmed &&
                resolution.source == RallySource.MANUAL -> "MANUAL POINT AWARDED: ${transition.pointAwardedTo.name}"
            transition.pointAwardedTo != null -> "AI POINT AWARDED: ${transition.pointAwardedTo.name}"
            transition.events.any { it.type == "SIDE_OUT" } -> "SIDE OUT"
            else -> null
        }
        _state.update {
            it.copy(
                score = transition.state,
                reviewRequired = transition.reviewReason != null,
                feedback = feedback,
            )
        }
        if (transition.state.phase == MatchPhase.COMPLETED) timerJob?.cancel()
        return transition.events.map(::toLogRecord)
    }

    private fun awardManually(team: Team): List<MatchLogRecord> = applyResolution(
        RallyResolution.Confirmed(
            winner = team,
            confidence = 1f,
            evidence = setOf(RallyEvidence.MANUAL_CONFIRMATION),
            source = RallySource.MANUAL,
        ),
    )

    private fun undoScore(): List<MatchLogRecord> {
        val score = _state.value.score ?: return emptyList()
        val transition = ScoreEngine.undoLastScore(score)
        _state.update { it.copy(score = transition.state, feedback = "SCORE UNDONE", reviewRequired = false) }
        if (transition.state.phase == MatchPhase.LIVE && score.phase == MatchPhase.COMPLETED) {
            startedElapsedRealtime = SystemClock.elapsedRealtime() - _state.value.elapsedMs
            startTimer()
        }
        return transition.events.map(::toLogRecord)
    }

    private fun correctServe(team: Team, serverNumber: Int): List<MatchLogRecord> {
        val score = _state.value.score ?: return emptyList()
        val transition = ScoreEngine.correctServingTeam(score, team, serverNumber)
        _state.update { it.copy(score = transition.state, feedback = "SERVE CORRECTED") }
        return transition.events.map(::toLogRecord)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                if (_state.value.score?.phase == MatchPhase.LIVE) {
                    _state.update { it.copy(elapsedMs = elapsedNow()) }
                }
            }
        }
    }

    private fun elapsedNow() = (SystemClock.elapsedRealtime() - startedElapsedRealtime).coerceAtLeast(0L)

    private fun scoreFields(previous: MatchScoreState, current: MatchScoreState) = mapOf(
        "previousScoreA" to previous.scoreA,
        "previousScoreB" to previous.scoreB,
        "scoreA" to current.scoreA,
        "scoreB" to current.scoreB,
        "previousServingTeam" to previous.servingTeam.name,
        "servingTeam" to current.servingTeam.name,
        "previousServerNumber" to previous.serverNumber,
        "serverNumber" to current.serverNumber,
    )

    private fun toLogRecord(event: ScoreEvent) = MatchLogRecord(
        event = event.type,
        fields = scoreFieldsFromSnapshots(event.previous, event.current) + event.details,
    )

    private fun scoreFieldsFromSnapshots(previous: ScoreSnapshot, current: ScoreSnapshot) = mapOf(
        "previousScoreA" to previous.scoreA,
        "previousScoreB" to previous.scoreB,
        "scoreA" to current.scoreA,
        "scoreB" to current.scoreB,
        "previousServingTeam" to previous.servingTeam.name,
        "servingTeam" to current.servingTeam.name,
        "previousServerNumber" to previous.serverNumber,
        "serverNumber" to current.serverNumber,
    )

    override fun onCleared() {
        timerJob?.cancel()
        sessionLogger.stopSession("VIEWMODEL_CLEARED")
        super.onCleared()
    }

    private companion object {
        const val ACTIVE_COURT_ID = "COURT_1"
        const val MINIMUM_COURT_CONFIDENCE = 0.75f
    }
}