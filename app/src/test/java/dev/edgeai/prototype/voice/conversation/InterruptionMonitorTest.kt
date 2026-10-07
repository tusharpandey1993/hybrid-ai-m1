package dev.edgeai.prototype.voice.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptionMonitorTest {
    @Test fun vadActivityIsPotentialUntilTranscriptConfirmsSpeech() {
        val monitor = InterruptionMonitor()
        val started = monitor.potentialSpeechStarted("response-1")

        assertEquals(listOf(
                InterruptionEvent.PotentialAudioActivityStarted,
            InterruptionEvent.AssistantPlaybackSuppressed("response-1")
        ), started)
            assertEquals(InterruptionStatus.POTENTIAL_AUDIO_ACTIVITY, monitor.state.status)
        assertTrue(monitor.state.playbackSuppressed)
            assertEquals(listOf(InterruptionEvent.PotentialAudioActivityEnded), monitor.potentialSpeechEnded())

        val transcript = UserTranscript("Wait, what does GLiNER mean?")
            assertEquals(listOf(InterruptionEvent.TranscriptCandidate(transcript.text.length)),
                monitor.transcriptCandidate(transcript))
            assertEquals(InterruptionStatus.TRANSCRIPT_CANDIDATE, monitor.state.status)
            assertEquals(1L, monitor.state.transcriptCandidateCount)
        assertTrue(monitor.state.playbackSuppressed)
        assertTrue(monitor.falseInterruptionDetected(playbackResumed = false).isEmpty())
    }

    @Test fun falseInterruptionEmitsRecoveryAndCountsOnlyOnce() {
        val monitor = InterruptionMonitor()
        monitor.potentialSpeechStarted("response-2")
        monitor.potentialSpeechEnded()

        assertEquals(listOf(
            InterruptionEvent.FalseInterruptionDetected,
            InterruptionEvent.AssistantPlaybackResumed("response-2")
        ), monitor.falseInterruptionDetected(playbackResumed = true))
        assertEquals(InterruptionStatus.FALSE_INTERRUPTION, monitor.state.status)
        assertFalse(monitor.state.playbackSuppressed)
        assertEquals(1L, monitor.state.falseInterruptionCount)
        assertTrue(monitor.falseInterruptionDetected(playbackResumed = false).isEmpty())
    }

    @Test fun potentialSpeechWithoutActivePlaybackDoesNotEmitPlaybackSuppression() {
        val monitor = InterruptionMonitor()

        assertEquals(listOf(InterruptionEvent.PotentialAudioActivityStarted),
            monitor.potentialSpeechStarted(null))
        assertEquals(InterruptionStatus.POTENTIAL_AUDIO_ACTIVITY, monitor.state.status)
        assertFalse(monitor.state.playbackSuppressed)
        assertEquals(listOf(InterruptionEvent.TranscriptCandidate(4)),
            monitor.transcriptCandidate(UserTranscript("Stop")))
        assertEquals(InterruptionStatus.TRANSCRIPT_CANDIDATE, monitor.state.status)
        assertEquals(0L, monitor.state.falseInterruptionCount)
    }
}
