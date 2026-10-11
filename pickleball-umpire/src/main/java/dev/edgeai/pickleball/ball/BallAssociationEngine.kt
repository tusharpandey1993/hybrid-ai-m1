package dev.edgeai.pickleball.ball

import dev.edgeai.pickleball.court.ActiveCourtIsolationEngine
import dev.edgeai.pickleball.court.ActiveCourtRelation
import dev.edgeai.pickleball.court.CameraPoint
import kotlin.math.hypot

enum class BallOwnershipStatus {
    ACTIVE,
    CANDIDATE,
    FOREIGN,
    LOST,
    UNKNOWN,
}

data class BallCandidateObservation(
    val timestampNs: Long,
    val candidateId: String,
    val cameraX: Float,
    val cameraY: Float,
    val confidence: Float,
    val associatedPlayerIds: Set<String> = emptySet(),
    val occluded: Boolean = false,
) {
    init {
        require(timestampNs >= 0L)
        require(candidateId.isNotBlank())
        require(cameraX.isFinite() && cameraY.isFinite())
        require(confidence in 0f..1f)
    }
}

data class BallOwnershipState(
    val trackId: String,
    val candidateId: String,
    val timestampNs: Long,
    val cameraX: Float,
    val cameraY: Float,
    val velocityXPerSecond: Float,
    val velocityYPerSecond: Float,
    val confidence: Float,
    val ownershipConfidence: Float,
    val lastSeenTimestampNs: Long,
    val trajectoryContinuity: Int,
    val activeCourtRelation: ActiveCourtRelation,
    val status: BallOwnershipStatus,
    val occluded: Boolean,
    val associatedPlayerIds: Set<String>,
    val reason: String,
)

data class BallAssociationEvent(
    val type: String,
    val fields: Map<String, Any?>,
)

data class BallAssociationUpdate(
    val states: List<BallOwnershipState>,
    val activeBall: BallOwnershipState?,
    val events: List<BallAssociationEvent>,
    val trackingUncertain: Boolean,
)

class BallAssociationEngine(
    private val activeCourt: ActiveCourtIsolationEngine,
    private val maximumCandidateAgeNs: Long = 1_000_000_000L,
    private val activeOcclusionGraceNs: Long = 500_000_000L,
    private val matchRadiusPixels: Float = 180f,
    private val continuityRequired: Int = 3,
    private val playerHitConfidence: Float = 0.75f,
    private val minimumOwnershipConfidence: Float = 0.8f,
) {
    private data class Track(
        var candidateId: String,
        var timestampNs: Long,
        var lastSeenTimestampNs: Long,
        var x: Float,
        var y: Float,
        var velocityX: Float,
        var velocityY: Float,
        var confidence: Float,
        var ownershipConfidence: Float,
        var continuity: Int,
        var relation: ActiveCourtRelation,
        val originatedForeign: Boolean,
        var status: BallOwnershipStatus,
        var occluded: Boolean,
        var associatedPlayers: Set<String>,
        var reason: String,
    )

    private val tracks = LinkedHashMap<String, Track>()
    private var nextTrackId = 1
    private var lastUpdateTimestampNs = -1L
    private var activeTrackId: String? = null

    init {
        require(maximumCandidateAgeNs > 0L)
        require(activeOcclusionGraceNs > 0L)
        require(matchRadiusPixels > 0f && matchRadiusPixels.isFinite())
        require(continuityRequired > 0)
        require(playerHitConfidence in 0f..1f)
        require(minimumOwnershipConfidence in 0f..1f)
    }

    fun update(timestampNs: Long, observations: List<BallCandidateObservation>): BallAssociationUpdate {
        require(timestampNs >= 0L)
        require(observations.all { it.timestampNs == timestampNs })
        if (timestampNs <= lastUpdateTimestampNs) return snapshot(timestampNs, emptyList())
        lastUpdateTimestampNs = timestampNs

        val events = mutableListOf<BallAssociationEvent>()
        tracks.entries.removeAll { (_, track) -> timestampNs - track.lastSeenTimestampNs > maximumCandidateAgeNs }
        val matchedTracks = mutableSetOf<String>()
        val unusedTracks = tracks.keys.toMutableSet()
        val unusedObservations = observations.toMutableList()

        val activeTrack = activeTrackId?.let(tracks::get)
        val activeObservationDistances = if (activeTrack == null) {
            emptyList()
        } else {
            val elapsed = ((timestampNs - activeTrack.timestampNs).coerceAtLeast(0L)) / 1_000_000_000f
            val predictedX = activeTrack.x + activeTrack.velocityX * elapsed
            val predictedY = activeTrack.y + activeTrack.velocityY * elapsed
            observations.mapNotNull { observation ->
                val relation = activeCourt.classify(CameraPoint(observation.cameraX.toDouble(), observation.cameraY.toDouble())).relation
                if (relation == ActiveCourtRelation.FOREIGN || relation == ActiveCourtRelation.UNKNOWN) return@mapNotNull null
                Triple(observation, relation, hypot(observation.cameraX - predictedX, observation.cameraY - predictedY))
            }.filter { it.third <= matchRadiusPixels }.sortedBy { it.third }
        }
        val ambiguousActiveObservation = activeObservationDistances.size > 1 &&
            activeObservationDistances[1].third - activeObservationDistances[0].third <= CROSSING_AMBIGUITY_RADIUS_PIXELS
        if (ambiguousActiveObservation && activeTrack != null) {
            activeTrack.timestampNs = timestampNs
            activeTrack.status = BallOwnershipStatus.UNKNOWN
            activeTrack.occluded = true
            activeTrack.reason = "OVERLAPPING_CANDIDATE_TRAJECTORIES"
            activeTrackId?.let(matchedTracks::add)
            unusedTracks.remove(activeTrackId)
            events += BallAssociationEvent("BALL_OWNERSHIP_UNCERTAIN", mapOf(
                "trackId" to activeTrackId,
                "timestampNs" to timestampNs,
                "reason" to activeTrack.reason,
                "candidateIds" to activeObservationDistances.map { it.first.candidateId },
            ))
        }

        for ((observation, _, distance) in activeObservationDistances) {
            if (ambiguousActiveObservation) break
            val previousTrackId = activeTrackId?.takeIf { it in unusedTracks }
            if (previousTrackId != null && distance <= matchRadiusPixels) {
                updateTrack(previousTrackId, observation, establishedActive = true)
                matchedTracks += previousTrackId
                unusedTracks.remove(previousTrackId)
                unusedObservations.remove(observation)
            }
        }
        activeTrackId?.let(unusedTracks::remove)

        for (observation in unusedObservations) {
            val match = unusedTracks.mapNotNull { id ->
                val track = tracks[id] ?: return@mapNotNull null
                val dt = ((timestampNs - track.timestampNs).coerceAtLeast(1L)) / 1_000_000_000f
                val predictedX = track.x + track.velocityX * dt
                val predictedY = track.y + track.velocityY * dt
                val distance = hypot(observation.cameraX - predictedX, observation.cameraY - predictedY)
                if (distance <= matchRadiusPixels) id to distance else null
            }.minByOrNull { it.second }?.first
            if (match != null) {
                updateTrack(match, observation, establishedActive = match == activeTrackId)
                matchedTracks += match
                unusedTracks.remove(match)
            } else {
                val trackId = "BALL_CANDIDATE_${nextTrackId++}"
                val relation = activeCourt.classify(CameraPoint(observation.cameraX.toDouble(), observation.cameraY.toDouble())).relation
                val initialStatus = when (relation) {
                    ActiveCourtRelation.ACTIVE_COURT,
                    ActiveCourtRelation.EXTENDED_TRACKING_REGION -> BallOwnershipStatus.CANDIDATE
                    ActiveCourtRelation.FOREIGN -> BallOwnershipStatus.FOREIGN
                    ActiveCourtRelation.UNKNOWN -> BallOwnershipStatus.UNKNOWN
                }
                tracks[trackId] = Track(
                    candidateId = observation.candidateId,
                    timestampNs = timestampNs,
                    lastSeenTimestampNs = timestampNs,
                    x = observation.cameraX,
                    y = observation.cameraY,
                    velocityX = 0f,
                    velocityY = 0f,
                    confidence = observation.confidence,
                    ownershipConfidence = if (initialStatus == BallOwnershipStatus.CANDIDATE) {
                        observation.confidence * INITIAL_OWNERSHIP_FACTOR
                    } else {
                        0f
                    },
                    continuity = 1,
                    relation = relation,
                    originatedForeign = initialStatus == BallOwnershipStatus.FOREIGN,
                    status = initialStatus,
                    occluded = observation.occluded,
                    associatedPlayers = observation.associatedPlayerIds,
                    reason = "NEW_CANDIDATE_REQUIRES_OWNERSHIP_EVIDENCE",
                )
                matchedTracks += trackId
                events += candidateEvent(trackId, observation, relation)
                if (initialStatus == BallOwnershipStatus.FOREIGN) {
                    events += BallAssociationEvent("FOREIGN_BALL_DETECTED", mapOf(
                        "trackId" to trackId,
                        "candidateId" to observation.candidateId,
                        "timestampNs" to timestampNs,
                        "cameraX" to observation.cameraX,
                        "cameraY" to observation.cameraY,
                        "activeCourtRelation" to relation.name,
                        "confidence" to observation.confidence,
                    ))
                }
            }
        }

        activeTrackId?.let { id ->
            if (id !in matchedTracks) {
                tracks[id]?.let { track ->
                    val age = timestampNs - track.lastSeenTimestampNs
                    track.timestampNs = timestampNs
                    track.occluded = age <= activeOcclusionGraceNs
                    track.status = if (age <= activeOcclusionGraceNs) BallOwnershipStatus.ACTIVE else BallOwnershipStatus.LOST
                    track.reason = if (track.occluded) "ACTIVE_TRAJECTORY_TEMPORARILY_OCCLUDED" else "ACTIVE_TRACK_NOT_OBSERVED"
                }
            }
        }

        val active = activeTrackId?.let(tracks::get)
        if (active != null && active.occluded) {
            val conflicting = observations.any { observation ->
                val relation = activeCourt.classify(CameraPoint(observation.cameraX.toDouble(), observation.cameraY.toDouble())).relation
                relation == ActiveCourtRelation.ACTIVE_COURT && observation.candidateId != active.candidateId
            }
            if (conflicting) {
                active.status = BallOwnershipStatus.UNKNOWN
                active.reason = "OVERLAPPING_CANDIDATE_DURING_ACTIVE_OCCLUSION"
                events += BallAssociationEvent("BALL_OWNERSHIP_UNCERTAIN", mapOf(
                    "trackId" to activeTrackId,
                    "timestampNs" to timestampNs,
                    "reason" to active.reason,
                ))
            }
        }

        return snapshot(timestampNs, events)
    }

    fun confirmActiveBall(trackId: String, confirmedBy: String): BallAssociationUpdate {
        require(confirmedBy.isNotBlank())
        val track = requireNotNull(tracks[trackId]) { "Unknown ball candidate: $trackId" }
        val relation = activeCourt.classify(CameraPoint(track.x.toDouble(), track.y.toDouble())).relation
        require(relation == ActiveCourtRelation.ACTIVE_COURT || relation == ActiveCourtRelation.EXTENDED_TRACKING_REGION) {
            "A foreign or unknown ball cannot be assigned to the active match"
        }
        activeTrackId = trackId
        track.status = BallOwnershipStatus.ACTIVE
        track.ownershipConfidence = 1f
        track.reason = "MANUALLY_CONFIRMED_ACTIVE_BALL"
        val court = activeCourt.setActiveBallTrack(trackId)
        return snapshot(lastUpdateTimestampNs.coerceAtLeast(0L), listOf(
            BallAssociationEvent("ACTIVE_BALL_ASSIGNED", mapOf(
                "activeCourtId" to activeCourt.snapshot()?.activeCourtId,
                "trackId" to trackId,
                "candidateId" to track.candidateId,
                "timestampNs" to track.timestampNs,
                "ownershipConfidence" to 1f,
                "source" to confirmedBy,
            )),
        ) + court.events.map { event -> BallAssociationEvent(event.type, event.fields) })
    }

    fun releaseActiveBall(reason: String): BallAssociationUpdate {
        require(reason.isNotBlank())
        val previous = activeTrackId
        activeTrackId = null
        tracks[previous]?.let {
            it.status = BallOwnershipStatus.UNKNOWN
            it.reason = reason
        }
        activeCourt.setActiveBallTrack(null)
        return snapshot(lastUpdateTimestampNs.coerceAtLeast(0L), listOf(BallAssociationEvent(
            "BALL_OWNERSHIP_UNCERTAIN",
            mapOf("trackId" to previous, "reason" to reason, "timestampNs" to lastUpdateTimestampNs.coerceAtLeast(0L)),
        )))
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 1
        lastUpdateTimestampNs = -1L
        activeTrackId = null
    }

    private fun updateTrack(trackId: String, observation: BallCandidateObservation, establishedActive: Boolean) {
        val track = tracks.getValue(trackId)
        val originatedForeign = track.originatedForeign
        val dt = ((observation.timestampNs - track.timestampNs).coerceAtLeast(1L)) / 1_000_000_000f
        val rawVelocityX = (observation.cameraX - track.x) / dt
        val rawVelocityY = (observation.cameraY - track.y) / dt
        val previousContinuity = track.continuity
        val relation = activeCourt.classify(CameraPoint(observation.cameraX.toDouble(), observation.cameraY.toDouble())).relation
        val hitEvidence = observation.associatedPlayerIds.isNotEmpty() && observation.confidence >= playerHitConfidence
        val ownershipConfidence = when {
            establishedActive -> maxOf(track.ownershipConfidence, observation.confidence)
            hitEvidence && relation == ActiveCourtRelation.ACTIVE_COURT -> observation.confidence
            relation == ActiveCourtRelation.FOREIGN -> 0f
            else -> observation.confidence * (INITIAL_OWNERSHIP_FACTOR + (previousContinuity + 1) * CONTINUITY_OWNERSHIP_INCREMENT)
        }
        val shouldPromote = establishedActive || hitEvidence || (
            !originatedForeign && previousContinuity + 1 >= continuityRequired &&
                ownershipConfidence >= minimumOwnershipConfidence
        )
        track.candidateId = observation.candidateId
        track.timestampNs = observation.timestampNs
        track.lastSeenTimestampNs = observation.timestampNs
        track.x = observation.cameraX
        track.y = observation.cameraY
        track.velocityX = rawVelocityX
        track.velocityY = rawVelocityY
        track.confidence = observation.confidence
        track.ownershipConfidence = ownershipConfidence
        track.continuity = previousContinuity + 1
        track.relation = relation
        track.occluded = observation.occluded
        track.associatedPlayers = observation.associatedPlayerIds
        track.status = when {
            establishedActive -> BallOwnershipStatus.ACTIVE
            relation == ActiveCourtRelation.FOREIGN -> BallOwnershipStatus.FOREIGN
            shouldPromote -> BallOwnershipStatus.ACTIVE
            relation == ActiveCourtRelation.UNKNOWN -> BallOwnershipStatus.UNKNOWN
            else -> BallOwnershipStatus.CANDIDATE
        }
        track.reason = when {
            establishedActive && relation == ActiveCourtRelation.FOREIGN -> "CONTINUOUS_ACTIVE_TRAJECTORY_OUTSIDE_COURT"
            establishedActive -> "CONTINUOUS_ACTIVE_MATCH_TRAJECTORY"
            originatedForeign && relation != ActiveCourtRelation.FOREIGN && !hitEvidence -> "FOREIGN_BALL_ENTERED_ACTIVE_COURT_REQUIRES_CONFIRMATION"
            relation == ActiveCourtRelation.FOREIGN -> "OUTSIDE_ACTIVE_COURT_TRACKING_REGION"
            hitEvidence -> "ASSOCIATED_PLAYER_HIT_CANDIDATE"
            shouldPromote -> "TEMPORAL_CONTINUITY_AND_CONFIDENCE"
            else -> "OWNERSHIP_NOT_ESTABLISHED"
        }
        if (track.status == BallOwnershipStatus.ACTIVE && activeTrackId == null) activeTrackId = trackId
    }

    private fun snapshot(timestampNs: Long, events: List<BallAssociationEvent>): BallAssociationUpdate {
        val states = tracks.map { (id, track) -> track.toState(id, timestampNs) }
        val activeBall = activeTrackId?.let { id -> tracks[id]?.toState(id, timestampNs) }
        return BallAssociationUpdate(
            states = states,
            activeBall = activeBall,
            events = events,
            trackingUncertain = activeBall == null || activeBall.status != BallOwnershipStatus.ACTIVE,
        )
    }

    private fun Track.toState(id: String, timestampNs: Long): BallOwnershipState {
        val ageNs = (timestampNs - lastSeenTimestampNs).coerceAtLeast(0L)
        val snapshotOwnershipConfidence = when (status) {
            BallOwnershipStatus.ACTIVE -> ownershipConfidence
            BallOwnershipStatus.CANDIDATE -> ownershipConfidence.coerceAtMost(minimumOwnershipConfidence - 0.01f)
            BallOwnershipStatus.FOREIGN, BallOwnershipStatus.LOST, BallOwnershipStatus.UNKNOWN -> 0f
        }
        return BallOwnershipState(
            trackId = id,
            candidateId = candidateId,
            timestampNs = timestampNs,
            cameraX = x,
            cameraY = y,
            velocityXPerSecond = velocityX,
            velocityYPerSecond = velocityY,
            confidence = confidence,
            ownershipConfidence = snapshotOwnershipConfidence,
            lastSeenTimestampNs = lastSeenTimestampNs,
            trajectoryContinuity = continuity,
            activeCourtRelation = relation,
            status = if (id == activeTrackId && ageNs > activeOcclusionGraceNs) BallOwnershipStatus.LOST else status,
            occluded = occluded || (id == activeTrackId && ageNs > 0L),
            associatedPlayerIds = associatedPlayers,
            reason = reason,
        )
    }

    private fun candidateEvent(
        trackId: String,
        observation: BallCandidateObservation,
        relation: ActiveCourtRelation,
    ) = BallAssociationEvent("BALL_CANDIDATE", mapOf(
        "trackId" to trackId,
        "candidateId" to observation.candidateId,
        "timestampNs" to observation.timestampNs,
        "cameraX" to observation.cameraX,
        "cameraY" to observation.cameraY,
        "confidence" to observation.confidence,
        "activeCourtRelation" to relation.name,
    ))

    private companion object {
        const val INITIAL_OWNERSHIP_FACTOR = 0.45f
        const val CONTINUITY_OWNERSHIP_INCREMENT = 0.15f
        const val CROSSING_AMBIGUITY_RADIUS_PIXELS = 24f
    }
}