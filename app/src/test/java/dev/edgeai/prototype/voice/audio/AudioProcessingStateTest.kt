package dev.edgeai.prototype.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioProcessingStateTest {
    @Test fun defaultStateDoesNotClaimUnavailableOrUnattachedEffectsAreEnabled() {
        assertEquals(AudioProcessingState(), AudioProcessingState(
            aecAvailable = false,
            aecAttached = false,
            aecEnabled = false,
            noiseSuppressorAvailable = false,
            noiseSuppressorAttached = false,
            noiseSuppressorEnabled = false,
            audioSessionId = null
        ))
    }
}