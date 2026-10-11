package dev.edgeai.pickleball.player

import dev.edgeai.pickleball.court.CameraPoint
import dev.edgeai.pickleball.court.CourtPoint
import dev.edgeai.pickleball.court.HomographyTransformer
import dev.edgeai.pickleball.court.PickleballCourtGeometry
import kotlin.math.hypot

class PlayerTracker(
    private val maxAgeNs: Long = 1_000_000_000L,
    private val minimumIou: Float = 0.15f,
    private val minimumAssociationConfidence: Float = 0.2f,
    private val trackingMarginFeet: Double = 3.0,
) {
    private val tracks = LinkedHashMap<PlayerTrackId, PlayerState>()
    private var nextId = 1
    private var lastTimestampNs = -1L

    init {
        require(maxAgeNs > 0L)
        require(minimumIou in 0f..1f)
        require(minimumAssociationConfidence in 0f..1f)
        require(trackingMarginFeet >= 0.0 && trackingMarginFeet.isFinite())
    }

    fun update(
        timestampNs: Long,
        detections: List<PlayerDetection>,
        countMode: PlayerCountMode,
        calibration: HomographyTransformer,
    ): PlayerTrackingUpdate {
        require(timestampNs >= 0L)
        require(detections.all { it.timestampNs == timestampNs }) {
            "Player detections must retain the source frame timestamp"
        }
        if (timestampNs <= lastTimestampNs) {
            return snapshot(timestampNs, rejectedOutside = 0, ignoredCapacity = detections.size)
        }
        lastTimestampNs = timestampNs
        pruneExpired(timestampNs)

        val accepted = mutableListOf<Pair<PlayerDetection, CourtPoint>>()
        var rejectedOutside = 0
        detections.asSequence()
            .filter { it.label.equals("person", ignoreCase = true) }
            .filter { it.confidence >= minimumAssociationConfidence }
            .sortedByDescending { it.confidence }
            .forEach { detection ->
                val foot = CameraPoint(detection.cameraBox.footX.toDouble(), detection.cameraBox.footY.toDouble())
                val court = runCatching { calibration.cameraToCourt(foot) }.getOrNull()
                if (court == null || !isInsideTrackingRegion(court)) {
                    rejectedOutside++
                } else {
                    accepted += detection to court
                }
            }

        val unmatchedTracks = tracks.keys.toMutableSet()
        val matchedDetections = mutableListOf<Pair<PlayerDetection, CourtPoint>>()
        for ((detection, courtPoint) in accepted) {
            val best = unmatchedTracks
                .mapNotNull { id -> tracks[id]?.let { state -> Triple(id, state, detection.cameraBox.intersectionOverUnion(state.boundingBoxCamera)) } }
                .filter { (_, state, overlap) ->
                    overlap >= minimumIou || footDistancePixels(detection, state) <= MAX_FOOT_DISTANCE_PX
                }
                .maxByOrNull { candidate ->
                    candidate.third - footDistancePixels(detection, candidate.second) * DISTANCE_TIE_BREAK_WEIGHT
                }

            if (best != null) {
                unmatchedTracks.remove(best.first)
                tracks[best.first] = stateFrom(best.first, detection, courtPoint, best.second)
            } else {
                matchedDetections += detection to courtPoint
            }
        }

        trimToCapacity(countMode.maxPlayers)
        val availableSlots = (countMode.maxPlayers - tracks.size).coerceAtLeast(0)
        val admitted = matchedDetections.take(availableSlots)
        admitted.forEach { (detection, courtPoint) ->
            val id = PlayerTrackId("PLAYER_${nextId++}")
            tracks[id] = stateFrom(id, detection, courtPoint, previous = null)
        }
        return snapshot(
            timestampNs = timestampNs,
            rejectedOutside = rejectedOutside,
            ignoredCapacity = matchedDetections.size - admitted.size,
        )
    }

    fun snapshot(nowNs: Long = lastTimestampNs): PlayerTrackingUpdate =
        snapshot(nowNs, rejectedOutside = 0, ignoredCapacity = 0)

    fun reset() {
        tracks.clear()
        nextId = 1
        lastTimestampNs = -1L
    }

    private fun snapshot(
        timestampNs: Long,
        rejectedOutside: Int,
        ignoredCapacity: Int,
    ) = PlayerTrackingUpdate(
        timestampNs = timestampNs,
        players = tracks.values.map { it.copy(observed = it.lastSeenTimestampNs == timestampNs) },
        rejectedOutsideCourt = rejectedOutside,
        ignoredBeyondConfiguredCapacity = ignoredCapacity,
    )

    private fun pruneExpired(timestampNs: Long) {
        tracks.entries.removeAll { (_, state) -> timestampNs - state.lastSeenTimestampNs > maxAgeNs }
    }

    private fun trimToCapacity(maxPlayers: Int) {
        tracks.values
            .sortedWith(compareBy<PlayerState> { it.confidence }.thenBy { it.lastSeenTimestampNs })
            .take((tracks.size - maxPlayers).coerceAtLeast(0))
            .forEach { tracks.remove(it.id) }
    }

    private fun stateFrom(
        id: PlayerTrackId,
        detection: PlayerDetection,
        court: CourtPoint,
        previous: PlayerState?,
    ): PlayerState {
        val elapsedSeconds = previous?.let {
            ((detection.timestampNs - it.lastSeenTimestampNs).coerceAtLeast(1L)) / 1_000_000_000f
        } ?: 1f
        val velocityX = previous?.let { (detection.cameraBox.footX - it.footPositionCameraX) / elapsedSeconds } ?: 0f
        val velocityY = previous?.let { (detection.cameraBox.footY - it.footPositionCameraY) / elapsedSeconds } ?: 0f
        return PlayerState(
            id = id,
            boundingBoxCamera = detection.cameraBox,
            footPositionCameraX = detection.cameraBox.footX,
            footPositionCameraY = detection.cameraBox.footY,
            courtXFeet = court.xFeet,
            courtYFeet = court.yFeet,
            velocityXPerSecond = velocityX,
            velocityYPerSecond = velocityY,
            confidence = detection.confidence,
            lastSeenTimestampNs = detection.timestampNs,
            observed = true,
        )
    }

    private fun footDistancePixels(detection: PlayerDetection, state: PlayerState): Float = hypot(
        detection.cameraBox.footX - state.footPositionCameraX,
        detection.cameraBox.footY - state.footPositionCameraY,
    )

    private fun isInsideTrackingRegion(point: CourtPoint): Boolean =
        point.xFeet >= -trackingMarginFeet &&
            point.xFeet <= PickleballCourtGeometry.WIDTH_FEET + trackingMarginFeet &&
            point.yFeet >= -trackingMarginFeet &&
            point.yFeet <= PickleballCourtGeometry.LENGTH_FEET + trackingMarginFeet

    private companion object {
        const val MAX_FOOT_DISTANCE_PX = 120f
        const val DISTANCE_TIE_BREAK_WEIGHT = 0.0001f
    }
}
