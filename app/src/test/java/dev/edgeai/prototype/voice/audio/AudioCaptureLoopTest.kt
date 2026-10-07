package dev.edgeai.prototype.voice.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureLoopTest {
    private open class FakeRecorder : PcmRecorder {
        override var sampleRate = 16000
        override val routeType = 15
        override var isSilenced = false
        var released = false
        var started = false
        var readCode = 160
        override fun start() { started = true }
        override fun read(target: ShortArray, offset: Int, count: Int): Int {
            if (readCode < 0) return readCode
            val size = minOf(readCode, count)
            target.fill(100, offset, offset + size)
            return size
        }
        override fun stop() { started = false }
        override fun release() { released = true }
    }

    @Test fun repeatedStartUsesOneRecorderAndStopAllowsFreshSession() = runTest {
        val records = mutableListOf<FakeRecorder>()
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { FakeRecorder().also { records.add(it) } }, { true }, { true })
        loop.start()
        loop.start()
        runCurrent()
        advanceTimeBy(20)
        assertEquals(1, records.size)
        assertTrue(loop.state.value.frames > 0)
        assertEquals(loop.state.value.frames, loop.state.value.droppedFrames)
        loop.stop()
        assertTrue(records.single().released)
        assertEquals(CaptureStatus.IDLE, loop.state.value.status)
        loop.start()
        runCurrent()
        assertEquals(2, records.size)
        assertEquals(2L, loop.state.value.sessionId)
        loop.stop()
        assertTrue(records.all { it.released })
    }

    @Test fun deniedPermissionDoesNotCreateNativeRecorder() = runTest {
        var creations = 0
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { creations++; FakeRecorder() }, { false }, { true })
        loop.start()
        assertEquals(0, creations)
        assertEquals(CaptureFailure.PERMISSION, loop.state.value.failure)
        assertNotNull(loop.state.value.cause)
    }

    @Test fun readFailureRemainsObservableAfterCleanup() = runTest {
        val recorder = FakeRecorder().apply { readCode = -6 }
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { recorder }, { true }, { true })
        loop.start()
        runCurrent()
        assertEquals(CaptureStatus.ERROR, loop.state.value.status)
        assertEquals(CaptureFailure.READ, loop.state.value.failure)
        assertEquals(-6, loop.state.value.readCode)
        assertNotNull(loop.state.value.cause)
        assertTrue(recorder.released)
        loop.stop()
    }

    @Test fun foregroundLossStopsAndReleasesCapture() = runTest {
        var visible = true
        val recorder = FakeRecorder()
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { recorder }, { true }, { visible })
        loop.start()
        runCurrent()
        visible = false
        advanceTimeBy(5)
        runCurrent()
        assertTrue(recorder.released)
        assertEquals(CaptureStatus.IDLE, loop.state.value.status)
        loop.stop()
    }

    @Test fun revocationStopsCaptureAndPreservesCause() = runTest {
        var allowed = true
        val recorder = FakeRecorder()
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { recorder }, { allowed }, { true })
        loop.start()
        runCurrent()
        allowed = false
        advanceTimeBy(5)
        runCurrent()
        assertEquals(CaptureFailure.PERMISSION, loop.state.value.failure)
        assertTrue(loop.state.value.cause is SecurityException)
        assertTrue(recorder.released)
        loop.stop()
    }

    @Test fun unsupportedSampleRateFailsAndStillReleasesRecorder() = runTest {
        val recorder = FakeRecorder().apply { sampleRate = 48000 }
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { recorder }, { true }, { true })
        loop.start()
        runCurrent()
        assertEquals(CaptureFailure.FORMAT, loop.state.value.failure)
        assertTrue(recorder.released)
        loop.stop()
    }

    @Test fun systemSilencingIsReportedInsteadOfPretendingSilenceIsValidCapture() = runTest {
        val recorder = FakeRecorder().apply { isSilenced = true }
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { recorder }, { true }, { true })
        loop.start()
        runCurrent()
        assertEquals(CaptureFailure.SILENCED, loop.state.value.failure)
        assertTrue(recorder.released)
        loop.stop()
    }

    @Test fun cleanupErrorsAreRetainedAsSuppressedCauses() = runTest {
        val recorder = object : FakeRecorder() {
            override fun stop() { throw IllegalStateException("stop failure") }
            override fun release() { super.release(); throw IllegalStateException("release failure") }
        }.apply { readCode = -3 }
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { recorder }, { true }, { true })
        loop.start()
        runCurrent()
        assertEquals(CaptureFailure.READ, loop.state.value.failure)
        assertEquals(2, loop.state.value.cause?.suppressed?.size)
        assertTrue(recorder.released)
        loop.stop()
    }

    @Test fun backgroundStartNeverAllocatesRecorder() = runTest {
        var creations = 0
        val loop = AudioCaptureLoop(backgroundScope, StandardTestDispatcher(testScheduler),
            { creations++; FakeRecorder() }, { true }, { false })
        loop.start()
        runCurrent()
        assertEquals(0, creations)
        assertEquals(CaptureStatus.IDLE, loop.state.value.status)
        loop.stop()
    }
}