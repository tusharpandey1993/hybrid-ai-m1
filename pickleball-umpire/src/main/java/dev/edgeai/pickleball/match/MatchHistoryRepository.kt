package dev.edgeai.pickleball.match

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

data class MatchSummary(
    val id: String,
    val dateEpochMs: Long,
    val matchType: MatchType,
    val targetScore: Int,
    val teamAName: String,
    val teamBName: String,
    val scoreA: Int,
    val scoreB: Int,
    val winner: Team?,
    val durationMs: Long,
    val logPath: String?,
    val recordingPath: String?,
    val recordingPaths: List<String> = listOfNotNull(recordingPath),
    val ballDetectionCount: Int,
    val lineEventCount: Int,
    val uncertainEventCount: Int,
)

data class MatchHistoryEvent(
    val timestamp: String,
    val type: String,
    val summary: String,
)

class MatchHistoryRepository(context: Context) {
    private val historyFile = File(context.filesDir, "match-history.json")
    private val reviewedEvents = setOf(
        "MATCH_CREATED", "MATCH_STARTED", "MATCH_PAUSED", "MATCH_RESUMED", "MATCH_STOPPED", "MATCH_RESET",
        "RALLY_WINNER_DETECTED", "RALLY_REVIEW_REQUIRED", "SCORE_UPDATED", "SERVE_CHANGED", "SIDE_OUT",
        "MANUAL_SCORE_CORRECTION", "SCORE_UNDO", "GAME_POINT", "GAME_COMPLETED", "BALL_DETECTED",
        "BALL_TRACKED", "BALL_REACQUIRED", "CONTACT_CANDIDATE", "LINE_DECISION", "VIDEO_RECORDING_STARTED",
        "VIDEO_RECORDING_STOPPED", "VIDEO_RECORDING_FAILED", "VIDEO_RECORDING_UNAVAILABLE",
        "VIDEO_RECORDING_INTERRUPTED",
    )

    @Synchronized
    fun load(): List<MatchSummary> = runCatching {
        val entries = JSONArray(historyFile.readText())
        List(entries.length()) { index -> entries.getJSONObject(index).toSummary() }
            .sortedByDescending(MatchSummary::dateEpochMs)
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(summary: MatchSummary) {
        val existing = load().filterNot { it.id == summary.id }
        val entries = JSONArray().apply {
            (listOf(summary) + existing).forEach { put(it.toJson()) }
        }
        val temporary = File(historyFile.parentFile, "${historyFile.name}.tmp")
        temporary.writeText(entries.toString())
        if (!temporary.renameTo(historyFile)) {
            historyFile.writeText(temporary.readText())
            temporary.delete()
        }
    }

    fun readEvents(path: String?): List<MatchHistoryEvent> {
        val file = path?.let(::File)?.takeIf(File::isFile) ?: return emptyList()
        return runCatching {
            file.useLines { lines ->
                val events = ArrayDeque<MatchHistoryEvent>()
                lines.forEach { line ->
                    val event = runCatching {
                        JSONObject(line).let { record ->
                            val type = record.optString("event", "EVENT")
                            if (type !in reviewedEvents) null else MatchHistoryEvent(
                                timestamp = record.optString("ts"),
                                type = type,
                                summary = record.toString(),
                            )
                        }
                    }.getOrNull()
                    if (event != null) {
                        if (events.size == MAX_REVIEWED_EVENTS) events.removeFirst()
                        events.addLast(event)
                    }
                }
                events.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun MatchSummary.toJson() = JSONObject()
        .put("id", id)
        .put("dateEpochMs", dateEpochMs)
        .put("matchType", matchType.name)
        .put("targetScore", targetScore)
        .put("teamAName", teamAName)
        .put("teamBName", teamBName)
        .put("scoreA", scoreA)
        .put("scoreB", scoreB)
        .put("winner", winner?.name)
        .put("durationMs", durationMs)
        .put("logPath", logPath)
        .put("recordingPath", recordingPath)
        .put("recordingPaths", JSONArray(recordingPaths))
        .put("ballDetectionCount", ballDetectionCount)
        .put("lineEventCount", lineEventCount)
        .put("uncertainEventCount", uncertainEventCount)

    private fun JSONObject.toSummary(): MatchSummary {
        val primaryRecording = optString("recordingPath").takeIf(String::isNotBlank)
        val savedPaths = optJSONArray("recordingPaths")?.let { paths ->
            List(paths.length()) { index -> paths.optString(index) }.filter(String::isNotBlank)
        }.orEmpty()
        return MatchSummary(
        id = getString("id"),
        dateEpochMs = optLong("dateEpochMs"),
        matchType = MatchType.valueOf(getString("matchType")),
        targetScore = getInt("targetScore"),
        teamAName = optString("teamAName", "TEAM A"),
        teamBName = optString("teamBName", "TEAM B"),
        scoreA = getInt("scoreA"),
        scoreB = getInt("scoreB"),
        winner = optString("winner").takeIf(String::isNotBlank)?.let(Team::valueOf),
        durationMs = optLong("durationMs"),
        logPath = optString("logPath").takeIf(String::isNotBlank),
        recordingPath = primaryRecording,
        recordingPaths = (savedPaths + listOfNotNull(primaryRecording)).distinct(),
        ballDetectionCount = optInt("ballDetectionCount"),
        lineEventCount = optInt("lineEventCount"),
        uncertainEventCount = optInt("uncertainEventCount"),
    )
    }

    private companion object {
        const val MAX_REVIEWED_EVENTS = 1_000
    }
}