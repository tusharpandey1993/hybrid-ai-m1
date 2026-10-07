package dev.edgeai.prototype.voice.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalSelfSpeechDetectorTest {
    private val detector = LexicalSelfSpeechDetector()

    @Test fun exactAssistantTextHasHighPlaybackSimilarity() {
        val result = detector.evaluate(
            AssistantResponse("main", "answer", 1, "GLiNER is a decision model that runs locally."),
            UserTranscript("GLiNER is a decision model that runs locally.")
        )

        assertTrue(result.similarity > 0.99f)
        assertTrue(result.likelyPlaybackLeakage)
    }

    @Test fun clarificationOverlapIsReportedButNotFlaggedAsLikelyPlayback() {
        val result = detector.evaluate(
            AssistantResponse("main", "answer", 1,
                "Kotlin coroutines make asynchronous programming easier."),
            UserTranscript("Why do coroutines make asynchronous programming easier?")
        )

        assertTrue(result.similarity > 0.65f)
        assertFalse(result.likelyPlaybackLeakage)
    }

    @Test fun shortStopCommandIsNotFlaggedAsEcho() {
        val result = detector.evaluate(
            AssistantResponse("main", "answer", 1, "GLiNER runs locally on the device."),
            UserTranscript("Stop")
        )

        assertTrue(result.similarity == 0f)
        assertFalse(result.likelyPlaybackLeakage)
    }

    @Test fun caseAndPunctuationNormalizationFindsExactPlaybackText() {
        val result = detector.evaluate(
            AssistantResponse("main", "answer", 1, "Quantization can reduce model memory."),
            UserTranscript("quantization can reduce model memory")
        )

        assertTrue(result.similarity > 0.99f)
        assertTrue(result.likelyPlaybackLeakage)
    }

    @Test fun unrelatedNewTopicHasLowSimilarity() {
        val result = detector.evaluate(
            AssistantResponse("main", "answer", 1, "GLiNER runs locally on the device."),
            UserTranscript("Actually tell me about Kotlin.")
        )

        assertTrue(result.similarity < 0.2f)
        assertFalse(result.likelyPlaybackLeakage)
    }

    @Test fun missingAssistantResponseCannotBeClassifiedAsPlaybackLeakage() {
        val result = detector.evaluate(null, UserTranscript("Stop"))

        assertTrue(result.similarity == 0f)
        assertFalse(result.likelyPlaybackLeakage)
    }
}
