package dev.edgeai.pickleball.court

enum class CourtTeamZone {
    NEAR,
    FAR,
}

enum class CourtTeam {
    A,
    B,
}

enum class ActiveCourtRelation {
    ACTIVE_COURT,
    EXTENDED_TRACKING_REGION,
    FOREIGN,
    UNKNOWN,
}

data class CourtRegionBounds(
    val minXFeet: Double,
    val minYFeet: Double,
    val maxXFeet: Double,
    val maxYFeet: Double,
) {
    init {
        require(minXFeet.isFinite() && minYFeet.isFinite())
        require(maxXFeet.isFinite() && maxYFeet.isFinite())
        require(minXFeet < maxXFeet && minYFeet < maxYFeet)
    }

    operator fun contains(point: CourtPoint): Boolean =
        point.xFeet in minXFeet..maxXFeet && point.yFeet in minYFeet..maxYFeet
}

data class ActiveCourtPlayerAssignment(
    val playerId: String,
    val team: CourtTeam,
    val teamZone: CourtTeamZone,
    val assignedManually: Boolean,
)

data class ActiveCourtState(
    val activeCourtId: String,
    val courtPolygonCamera: List<CameraPoint>,
    val netLineCamera: List<CameraPoint>,
    val nearTeamZone: CourtRegionBounds,
    val farTeamZone: CourtRegionBounds,
    val extendedTrackingRegion: CourtRegionBounds,
    val transformer: HomographyTransformer,
    val calibrationQuality: CalibrationQuality,
    val calibrationConfidence: Float,
    val expectedPlayerCount: Int,
    val assignedPlayers: Map<String, ActiveCourtPlayerAssignment> = emptyMap(),
    val activeBallTrackId: String? = null,
    val courtLocked: Boolean = false,
)

data class CourtIsolationResult(
    val relation: ActiveCourtRelation,
    val courtPoint: CourtPoint?,
    val teamZone: CourtTeamZone?,
)

data class ActiveCourtEvent(
    val type: String,
    val fields: Map<String, Any?>,
)

data class ActiveCourtUpdate(
    val state: ActiveCourtState,
    val events: List<ActiveCourtEvent> = emptyList(),
)

class ActiveCourtIsolationEngine(
    private val trackingMarginFeet: Double = DEFAULT_TRACKING_MARGIN_FEET,
) {
    private var active: ActiveCourtState? = null

    init {
        require(trackingMarginFeet >= 0.0 && trackingMarginFeet.isFinite())
    }

    fun selectCourt(
        courtId: String,
        calibration: CourtCalibration,
        expectedPlayerCount: Int,
    ): ActiveCourtUpdate {
        require(courtId.isNotBlank())
        require(expectedPlayerCount == 2 || expectedPlayerCount == 4)
        val previouslySelectedCourtId = active?.activeCourtId
        val bounds = CourtRegionBounds(0.0, 0.0, PickleballCourtGeometry.WIDTH_FEET, PickleballCourtGeometry.LENGTH_FEET)
        val extension = CourtRegionBounds(
            minXFeet = bounds.minXFeet - trackingMarginFeet,
            minYFeet = bounds.minYFeet - trackingMarginFeet,
            maxXFeet = bounds.maxXFeet + trackingMarginFeet,
            maxYFeet = bounds.maxYFeet + trackingMarginFeet,
        )
        val polygon = listOf(
            CourtPoint(bounds.minXFeet, bounds.minYFeet),
            CourtPoint(bounds.maxXFeet, bounds.minYFeet),
            CourtPoint(bounds.maxXFeet, bounds.maxYFeet),
            CourtPoint(bounds.minXFeet, bounds.maxYFeet),
        ).map(calibration.transformer::courtToCamera)
        val confidence = calibrationConfidence(calibration)
        val state = ActiveCourtState(
            activeCourtId = courtId,
            courtPolygonCamera = polygon,
            netLineCamera = listOf(
                calibration.transformer.courtToCamera(CourtPoint(0.0, PickleballCourtGeometry.NET_Y_FEET)),
                calibration.transformer.courtToCamera(CourtPoint(PickleballCourtGeometry.WIDTH_FEET, PickleballCourtGeometry.NET_Y_FEET)),
            ),
            nearTeamZone = CourtRegionBounds(0.0, 0.0, PickleballCourtGeometry.WIDTH_FEET, PickleballCourtGeometry.NET_Y_FEET),
            farTeamZone = CourtRegionBounds(
                0.0,
                PickleballCourtGeometry.NET_Y_FEET,
                PickleballCourtGeometry.WIDTH_FEET,
                PickleballCourtGeometry.LENGTH_FEET,
            ),
            extendedTrackingRegion = extension,
            transformer = calibration.transformer,
            calibrationQuality = calibration.quality,
            calibrationConfidence = confidence,
            expectedPlayerCount = expectedPlayerCount,
        )
        active = state
        val events = buildList {
            if (previouslySelectedCourtId != null) {
                add(ActiveCourtEvent("COURT_TRACKING_LOST", mapOf(
                    "activeCourtId" to previouslySelectedCourtId,
                    "reason" to "COURT_RECALIBRATED",
                )))
            }
            add(ActiveCourtEvent(
                "ACTIVE_COURT_SELECTED",
                mapOf(
                    "activeCourtId" to courtId,
                    "calibrationQuality" to calibration.quality.name,
                    "calibrationConfidence" to confidence,
                    "expectedPlayerCount" to expectedPlayerCount,
                    "trackingMarginFeet" to trackingMarginFeet,
                    "courtPolygonCamera" to polygon.map { mapOf("x" to it.x, "y" to it.y) },
                ),
            ))
        }
        return ActiveCourtUpdate(state, events)
    }

    fun classify(cameraPoint: CameraPoint): CourtIsolationResult {
        val state = active ?: return CourtIsolationResult(ActiveCourtRelation.UNKNOWN, null, null)
        if (!cameraPoint.x.isFinite() || !cameraPoint.y.isFinite()) {
            return CourtIsolationResult(ActiveCourtRelation.UNKNOWN, null, null)
        }
        val courtPoint = runCatching { state.transformer.cameraToCourt(cameraPoint) }.getOrNull()
            ?: return CourtIsolationResult(ActiveCourtRelation.UNKNOWN, null, null)
        if (!courtPoint.xFeet.isFinite() || !courtPoint.yFeet.isFinite()) {
            return CourtIsolationResult(ActiveCourtRelation.UNKNOWN, null, null)
        }
        val relation = when {
            courtPoint in state.nearTeamZone || courtPoint in state.farTeamZone -> ActiveCourtRelation.ACTIVE_COURT
            courtPoint in state.extendedTrackingRegion -> ActiveCourtRelation.EXTENDED_TRACKING_REGION
            else -> ActiveCourtRelation.FOREIGN
        }
        val teamZone = when {
            courtPoint in state.nearTeamZone -> CourtTeamZone.NEAR
            courtPoint in state.farTeamZone -> CourtTeamZone.FAR
            else -> null
        }
        return CourtIsolationResult(relation, courtPoint, teamZone)
    }

    fun assignPlayer(playerId: String, team: CourtTeam, teamZone: CourtTeamZone): ActiveCourtUpdate {
        require(playerId.isNotBlank())
        val previous = requireNotNull(active) { "Select an active court before assigning players" }
        val previousAssignment = previous.assignedPlayers[playerId]
        val assignments = previous.assignedPlayers + (playerId to ActiveCourtPlayerAssignment(playerId, team, teamZone, true))
        val next = previous.copy(
            assignedPlayers = assignments,
            courtLocked = previous.calibrationConfidence >= MINIMUM_LOCK_CONFIDENCE &&
                hasBalancedAssignments(assignments, previous.expectedPlayerCount),
        )
        active = next
        val events = mutableListOf(
            ActiveCourtEvent(
                if (previousAssignment == null) "PLAYER_ASSIGNED" else "PLAYER_REASSIGNED",
                mapOf("playerId" to playerId, "team" to team.name, "teamZone" to teamZone.name, "source" to "MANUAL"),
            ),
        )
        if (!previous.courtLocked && next.courtLocked) {
            events += ActiveCourtEvent("COURT_LOCKED", mapOf(
                "activeCourtId" to next.activeCourtId,
                "calibrationConfidence" to next.calibrationConfidence,
                "assignedPlayerCount" to next.assignedPlayers.size,
            ))
        } else if (previous.courtLocked && !next.courtLocked) {
            events += ActiveCourtEvent("COURT_TRACKING_LOST", mapOf(
                "activeCourtId" to next.activeCourtId,
                "reason" to "PLAYER_ASSIGNMENTS_UNBALANCED",
            ))
        }
        return ActiveCourtUpdate(next, events)
    }

    fun unassignPlayer(playerId: String): ActiveCourtUpdate {
        val previous = requireNotNull(active) { "Select an active court before changing assignments" }
        val removed = previous.assignedPlayers[playerId] ?: return ActiveCourtUpdate(previous)
        val next = previous.copy(
            assignedPlayers = previous.assignedPlayers - playerId,
            courtLocked = false,
        )
        active = next
        val events = mutableListOf(ActiveCourtEvent(
            "PLAYER_REASSIGNED",
            mapOf("playerId" to playerId, "previousTeam" to removed.team.name, "previousTeamZone" to removed.teamZone.name, "team" to "UNASSIGNED", "teamZone" to "UNASSIGNED"),
        ))
        if (previous.courtLocked) {
            events += ActiveCourtEvent("COURT_TRACKING_LOST", mapOf(
                "activeCourtId" to next.activeCourtId,
                "reason" to "PLAYER_UNASSIGNED",
            ))
        }
        return ActiveCourtUpdate(next, events)
    }

    fun setActiveBallTrack(trackId: String?): ActiveCourtUpdate {
        val previous = requireNotNull(active) { "Select an active court before assigning a ball" }
        val next = previous.copy(activeBallTrackId = trackId)
        active = next
        val events = if (trackId == null) emptyList() else listOf(ActiveCourtEvent(
            "ACTIVE_BALL_ASSIGNED",
            mapOf("activeCourtId" to next.activeCourtId, "trackId" to trackId),
        ))
        return ActiveCourtUpdate(next, events)
    }

    fun snapshot(): ActiveCourtState? = active

    fun clear(): ActiveCourtUpdate? {
        val previous = active ?: return null
        active = null
        return ActiveCourtUpdate(
            previous.copy(courtLocked = false, activeBallTrackId = null),
            listOf(ActiveCourtEvent("COURT_TRACKING_LOST", mapOf(
                "activeCourtId" to previous.activeCourtId,
                "reason" to "COURT_CLEARED",
            ))),
        )
    }

    private fun calibrationConfidence(calibration: CourtCalibration): Float {
        val qualityScore = when (calibration.quality) {
            CalibrationQuality.EXCELLENT -> 0.95f
            CalibrationQuality.GOOD -> 0.8f
            CalibrationQuality.POOR -> 0.4f
            CalibrationQuality.INVALID -> 0f
        }
        val inlierRatio = if (calibration.pointCount > 0) {
            (calibration.inlierCount.toFloat() / calibration.pointCount).coerceIn(0f, 1f)
        } else {
            0f
        }
        return qualityScore * inlierRatio
    }

    private fun hasBalancedAssignments(
        assignments: Map<String, ActiveCourtPlayerAssignment>,
        expectedPlayerCount: Int,
    ): Boolean = assignments.size == expectedPlayerCount &&
        assignments.values.count { it.team == CourtTeam.A } == expectedPlayerCount / 2 &&
        assignments.values.count { it.team == CourtTeam.B } == expectedPlayerCount / 2

    private companion object {
        const val DEFAULT_TRACKING_MARGIN_FEET = 3.0
        const val MINIMUM_LOCK_CONFIDENCE = 0.75f
    }
}