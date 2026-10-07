package dev.edgeai.prototype.voice.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioFrameTest {
    @Test fun fixedFormatIsTwentyMillisecondsOfMonoPcm16() {
        assertEquals(20, AudioFrame.SAMPLE_COUNT * 1000 / AudioFrame.SAMPLE_RATE)
        assertEquals(320, frame().sampleCount)
    }

    @Test fun silenceAndFullNegativeScaleAreMeasuredWithoutOverflow() {
        assertEquals(0.0, frame().rmsAmplitude, 0.0)
        val loud = AudioFrame(1, 0, 0, ShortArray(320) { Short.MIN_VALUE })
        assertEquals(1.0, loud.peakAmplitude, 0.0)
        assertEquals(1.0, loud.rmsAmplitude, 0.0)
    }

    @Test fun pcmOwnershipIsIsolatedBetweenProducerAndSubscribers() {
        val samples = ShortArray(320) { 16384 }
        val captured = AudioFrame(1, 0, 0, samples)
        samples.fill(0)
        captured.copyPcm().fill(0)
        assertEquals(16384.toShort(), captured.copyPcm()[0])
        assertEquals(0.5, captured.rmsAmplitude, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun partialFrameIsRejected() { AudioFrame(1, 0, 0, ShortArray(319)) }

    @Test fun noSubscribersIsAnObservableDrop() {
        assertFalse(AudioFrameBus().offer(frame()))
    }

    @Test fun slowSubscriberCannotBlockProducerOrGrowQueueWithoutBound() = runTest {
        val bus = AudioFrameBus(capacity = 1)
        val blocked = CompletableDeferred<Unit>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.frames.collect { blocked.await() }
        }
        assertTrue(bus.offer(frame()))
        assertTrue(bus.offer(frame()))
        assertFalse(bus.offer(frame()))
        collector.cancel()
    }

    private fun frame() = AudioFrame(1, 0, 0, ShortArray(320))
}