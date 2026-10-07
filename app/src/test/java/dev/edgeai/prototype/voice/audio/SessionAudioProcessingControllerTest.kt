package dev.edgeai.prototype.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAudioProcessingControllerTest {
    @Test fun unavailableEffectsRemainDetachedAndDisabled() {
        val factory = FakeFactory(aecAvailable = false, noiseAvailable = false)
        val controller = SessionAudioProcessingController(factory)

        controller.attach(12)

        assertEquals(AudioProcessingState(audioSessionId = 12), controller.state.value)
        assertEquals(0, factory.createCount)
    }

    @Test fun reportedAvailabilityDoesNotImplySuccessfulCreationOrEnablement() {
        val factory = FakeFactory(aecAvailable = true, noiseAvailable = true)
        val controller = SessionAudioProcessingController(factory)

        controller.attach(12)

        assertEquals(AudioProcessingState(
            aecAvailable = true,
            noiseSuppressorAvailable = true,
            audioSessionId = 12
        ), controller.state.value)
    }

    @Test fun stateReflectsCreatedAndEnabledEffectsForTheCurrentSession() {
        val factory = FakeFactory(aecAvailable = true, noiseAvailable = true).apply {
            aecToCreate = FakeEffect(enableResult = true)
            noiseToCreate = FakeEffect(enableResult = false)
        }
        val controller = SessionAudioProcessingController(factory)

        controller.attach(14)

        assertEquals(AudioProcessingState(
            aecAvailable = true,
            aecAttached = true,
            aecEnabled = true,
            noiseSuppressorAvailable = true,
            noiseSuppressorAttached = true,
            noiseSuppressorEnabled = false,
            audioSessionId = 14
        ), controller.state.value)
    }

    @Test fun replacingAndReleasingSessionsReleasesBothEffectHandles() {
        val factory = FakeFactory(aecAvailable = true, noiseAvailable = true)
        val oldAec = FakeEffect(true)
        val oldNoise = FakeEffect(true)
        factory.aecToCreate = oldAec
        factory.noiseToCreate = oldNoise
        val controller = SessionAudioProcessingController(factory)

        controller.attach(14)
        factory.aecToCreate = FakeEffect(true)
        factory.noiseToCreate = FakeEffect(true)
        controller.attach(15)

        assertEquals(1, oldAec.releaseCount)
        assertEquals(1, oldNoise.releaseCount)
        assertEquals(15, controller.state.value.audioSessionId)

        controller.release()
        controller.release()

        assertFalse(controller.state.value.aecAttached)
        assertFalse(controller.state.value.aecEnabled)
        assertFalse(controller.state.value.noiseSuppressorAttached)
        assertFalse(controller.state.value.noiseSuppressorEnabled)
        assertEquals(null, controller.state.value.audioSessionId)
    }

    @Test fun nonPositiveSessionIdsAreRejected() {
        val controller = SessionAudioProcessingController(FakeFactory(true, true))
        var rejected = false
        try {
            controller.attach(0)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private class FakeFactory(
        private val aecAvailable: Boolean,
        private val noiseAvailable: Boolean
    ) : AudioProcessingEffectFactory {
        var createCount = 0
        var aecToCreate: FakeEffect? = null
        var noiseToCreate: FakeEffect? = null
        override fun isAecAvailable() = aecAvailable
        override fun isNoiseSuppressorAvailable() = noiseAvailable
        override fun createAec(audioSessionId: Int): AudioEffectHandle? {
            createCount++
            return aecToCreate
        }
        override fun createNoiseSuppressor(audioSessionId: Int): AudioEffectHandle? {
            createCount++
            return noiseToCreate
        }
    }

    private class FakeEffect(private val enableResult: Boolean) : AudioEffectHandle {
        private var enabledState = false
        var releaseCount = 0
        override val enabled: Boolean get() = enabledState
        override fun enable(): Boolean {
            enabledState = enableResult
            return enableResult
        }
        override fun release() { releaseCount++ }
    }
}
