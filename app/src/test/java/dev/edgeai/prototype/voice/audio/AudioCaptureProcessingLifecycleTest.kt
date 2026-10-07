package dev.edgeai.prototype.voice.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureProcessingLifecycleTest {
    @Test fun processingTracksRecorderSessionAndReleasesEffectsAcrossRestart() = runTest {
        var nextSessionId = 21
        val recorders = mutableListOf<FakeRecorder>()
        val effects = mutableListOf<FakeEffect>()
        val factory = object : AudioProcessingEffectFactory {
            override fun isAecAvailable() = true
            override fun isNoiseSuppressorAvailable() = true
            override fun createAec(audioSessionId: Int): AudioEffectHandle =
                FakeEffect().also(effects::add)
            override fun createNoiseSuppressor(audioSessionId: Int): AudioEffectHandle =
                FakeEffect().also(effects::add)
        }
        val processing = SessionAudioProcessingController(factory)
        val loop = AudioCaptureLoop(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            { FakeRecorder(nextSessionId++).also(recorders::add) },
            { true },
            { true },
            processingController = processing
        )

        loop.start()
        runCurrent()
        assertEquals(21, processing.state.value.audioSessionId)
        assertTrue(processing.state.value.aecEnabled)
        assertTrue(processing.state.value.noiseSuppressorEnabled)
        loop.stop()
        assertTrue(effects.all { it.released })
        assertEquals(null, processing.state.value.audioSessionId)

        loop.start()
        runCurrent()
        assertEquals(22, processing.state.value.audioSessionId)
        loop.stop()

        assertEquals(2, recorders.size)
        assertTrue(recorders.all { it.released })
        assertTrue(effects.all { it.released })
    }

    private class FakeRecorder(override val audioSessionId: Int) : PcmRecorder {
        override val sampleRate = AudioFrame.SAMPLE_RATE
        override val routeType = 15
        override val isSilenced = false
        var released = false
        override fun start() = Unit
        override fun read(target: ShortArray, offset: Int, count: Int): Int {
            target.fill(0, offset, offset + count)
            return count
        }
        override fun stop() = Unit
        override fun release() { released = true }
    }

    private class FakeEffect : AudioEffectHandle {
        private var enabledState = false
        var released = false
        override val enabled: Boolean get() = enabledState
        override fun enable(): Boolean {
            enabledState = true
            return true
        }
        override fun release() { released = true }
    }
}
