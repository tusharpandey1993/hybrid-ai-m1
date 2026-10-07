package dev.edgeai.prototype.voice.conversation

enum class InterruptionStatus { NONE, POTENTIAL_AUDIO_ACTIVITY, TRANSCRIPT_CANDIDATE, FALSE_INTERRUPTION }

data class InterruptionState(
    val status: InterruptionStatus = InterruptionStatus.NONE,
    val playbackSuppressed: Boolean = false,
    val falseInterruptionCount: Long = 0,
    val transcriptCandidateCount: Long = 0,
    val transcriptCandidateSeen: Boolean = false,
    val suppressedResponseId: String? = null
)

sealed interface InterruptionEvent {
    data object PotentialAudioActivityStarted : InterruptionEvent
    data object PotentialAudioActivityEnded : InterruptionEvent
    data class TranscriptCandidate(val characterCount: Int) : InterruptionEvent
    data object FalseInterruptionDetected : InterruptionEvent
    data class AssistantPlaybackStarted(val responseId: String) : InterruptionEvent
    data class AssistantPlaybackStopped(val responseId: String) : InterruptionEvent
    data class AssistantPlaybackSuppressed(val responseId: String) : InterruptionEvent
    data class AssistantPlaybackResumed(val responseId: String) : InterruptionEvent
}

class InterruptionMonitor {
    var state = InterruptionState()
        private set

    fun assistantPlaybackStarted(responseId: String): List<InterruptionEvent> {
        state = state.copy(
            status = InterruptionStatus.NONE,
            playbackSuppressed = false,
            transcriptCandidateSeen = false,
            suppressedResponseId = null
        )
        return listOf(InterruptionEvent.AssistantPlaybackStarted(responseId))
    }

    fun assistantPlaybackStopped(responseId: String) =
        listOf(InterruptionEvent.AssistantPlaybackStopped(responseId))

    fun potentialSpeechStarted(responseId: String?): List<InterruptionEvent> {
        if (state.status in setOf(InterruptionStatus.POTENTIAL_AUDIO_ACTIVITY,
                InterruptionStatus.TRANSCRIPT_CANDIDATE)) return emptyList()
        state = state.copy(
            status = InterruptionStatus.POTENTIAL_AUDIO_ACTIVITY,
            playbackSuppressed = responseId != null,
            transcriptCandidateSeen = false,
            suppressedResponseId = responseId
        )
        return buildList {
            add(InterruptionEvent.PotentialAudioActivityStarted)
            if (responseId != null) add(InterruptionEvent.AssistantPlaybackSuppressed(responseId))
        }
    }

    fun potentialSpeechEnded(): List<InterruptionEvent> =
        if (state.status in setOf(InterruptionStatus.POTENTIAL_AUDIO_ACTIVITY,
                InterruptionStatus.TRANSCRIPT_CANDIDATE)) {
            listOf(InterruptionEvent.PotentialAudioActivityEnded)
        } else emptyList()

    fun transcriptCandidate(transcript: UserTranscript): List<InterruptionEvent> {
        if (state.status !in setOf(InterruptionStatus.POTENTIAL_AUDIO_ACTIVITY,
                InterruptionStatus.TRANSCRIPT_CANDIDATE) || state.transcriptCandidateSeen) return emptyList()
        state = state.copy(
            status = InterruptionStatus.TRANSCRIPT_CANDIDATE,
            transcriptCandidateCount = state.transcriptCandidateCount + 1,
            transcriptCandidateSeen = true
        )
        return listOf(InterruptionEvent.TranscriptCandidate(transcript.text.length))
    }

    fun falseInterruptionDetected(playbackResumed: Boolean): List<InterruptionEvent> {
        val before = state
        if (before.status != InterruptionStatus.POTENTIAL_AUDIO_ACTIVITY || before.transcriptCandidateSeen) {
            return emptyList()
        }
        state = state.copy(
            status = InterruptionStatus.FALSE_INTERRUPTION,
            playbackSuppressed = false,
            falseInterruptionCount = state.falseInterruptionCount + if (before.playbackSuppressed) 1 else 0,
            transcriptCandidateSeen = false
        )
        return buildList {
            add(InterruptionEvent.FalseInterruptionDetected)
            if (playbackResumed) {
                before.suppressedResponseId?.let { add(InterruptionEvent.AssistantPlaybackResumed(it)) }
            }
        }
    }
}