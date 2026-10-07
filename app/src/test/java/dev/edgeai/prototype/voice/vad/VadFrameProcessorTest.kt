package dev.edgeai.prototype.voice.vad

import dev.edgeai.prototype.voice.audio.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadFrameProcessorTest {
    @Test
    fun assemblesExactNonOverlappingWindowsAcrossCaptureFrames() {
        val engine = RecordingEngine()
        val processor = VadFrameProcessor(engine)

        repeat(8) { sequence ->
            processor.process(frame(sessionId = 1, sequence = sequence.toLong(), value = sequence + 1))
        }

        assertEquals(5, engine.windows.size)
        val flattened = engine.windows.flatten()
        assertEquals(2560, flattened.size)
        assertEquals(1 / 32768.0f, flattened.first())
        assertEquals(8 / 32768.0f, flattened.last())
        assertTrue(flattened.all { it > 0f })
    }

    @Test
    fun reportsOnlySpeechStateEdges() {
        val engine = RecordingEngine(listOf(0.1f, 0.8f, 0.9f, 0.1f, 0.1f))
        val processor = VadFrameProcessor(engine, silenceWindowsToEnd = 2)
        val observations = (0 until 8).flatMap { sequence ->
            processor.process(frame(1, sequence.toLong(), 100))
        }

        assertEquals(5, observations.size)
        assertEquals(listOf(0.1f, 0.8f, 0.9f, 0.1f, 0.1f), observations.map { it.probability })
        assertEquals(listOf(false, false, true, true, false), observations.map { it.speechDetected })
        assertEquals(listOf(false, false, true, false, false), observations.map { it.speechStarted })
        assertEquals(listOf(false, false, false, false, true), observations.map { it.speechEnded })
        assertTrue(observations.all { it.inferenceMillis >= 0.0 })
    }

    @Test
    fun resetsWindowAndSpeechStateWhenCaptureSessionChanges() {
        val engine = RecordingEngine(listOf(0.8f, 0.8f, 0.1f, 0.1f))
        val processor = VadFrameProcessor(engine)
        val first = (0 until 4).flatMap { processor.process(frame(1, it.toLong(), 100)) }
        val second = (0 until 4).flatMap { processor.process(frame(2, it.toLong(), 100)) }

        assertEquals(2, first.size)
        assertFalse(first.first().speechDetected)
        assertTrue(first.last().speechStarted)
        assertEquals(2, second.size)
        assertTrue(second.all { !it.speechDetected })
        assertTrue(second.all { !it.speechEnded })
        assertEquals(2, engine.resetCount)
    }

    private fun frame(sessionId: Long, sequence: Long, value: Int) =
        AudioFrame(sessionId, sequence, sequence * 20_000_000L, ShortArray(AudioFrame.SAMPLE_COUNT) { value.toShort() })

    private class RecordingEngine(private val states: List<Float> = emptyList()) : VadWindowEngine {
        val windows = mutableListOf<List<Float>>()
        var resetCount = 0

        override fun computeProbability(samples: FloatArray): Float {
            windows += samples.toList()
            return states.getOrElse(windows.size - 1) { 0f }
        }

        override fun reset() { resetCount++ }
        override fun close() = Unit
    }
}