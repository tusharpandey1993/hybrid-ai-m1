package dev.edgeai.prototype.voice.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgeai.prototype.R
import dev.edgeai.prototype.voice.debug.VoiceLabActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioInputDeviceTest {
    @Test fun realCaptureSupportsStopRestartWithoutDuplicateSession() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.RECORD_AUDIO)
        val audio = AndroidAudioInput.get(context)
        ActivityScenario.launch<VoiceLabActivity>(Intent(context, VoiceLabActivity::class.java)).use { scenario ->
            try {
                awaitStartEnabled(scenario)
                scenario.onActivity { it.findViewById<Button>(R.id.audio_start).performClick() }
                val initial = await(audio) { it.status == CaptureStatus.CAPTURING && it.frames >= 30 }
                assertEquals(16000, initial.sampleRate)
                assertEquals(0L, initial.droppedFrames)
                assertTrue(initial.firstFrameMillis != null)
                val frame = withTimeout(5000) { audio.frames.first() }
                assertEquals(320, frame.sampleCount)
                assertEquals(initial.sessionId, frame.sessionId)
                val timestamps = withTimeout(8000) {
                    audio.frames.map { it.timestampNanos }.take(100).toList()
                }
                val gaps = timestamps.zipWithNext { first, second -> (second - first) / 1_000_000.0 }.sorted()
                val measured = audio.state.value
                File(context.filesDir, "audio-capture-smoke.json").writeText(
                    JSONObject().put("target", "ARM64 emulator; not physical phone")
                        .put("sample_rate", measured.sampleRate).put("frame_samples", 320)
                        .put("frames", measured.frames).put("dropped_frames", measured.droppedFrames)
                        .put("first_frame_ms", measured.firstFrameMillis)
                        .put("interval_samples", gaps.size).put("gap_p50_ms", gaps[49])
                        .put("gap_p95_ms", gaps[94]).put("gap_max_ms", gaps.last())
                        .put("route_type", measured.routeType).toString(2)
                )
                audio.start()
                assertEquals(initial.sessionId, audio.state.value.sessionId)
                scenario.onActivity { it.findViewById<Button>(R.id.audio_stop).performClick() }
                await(audio) { it.status == CaptureStatus.IDLE }
                val manager = context.getSystemService(AudioManager::class.java)
                withTimeout(5000) {
                    while (manager.activeRecordingConfigurations.isNotEmpty()) delay(10)
                }
                assertTrue(manager.activeRecordingConfigurations.isEmpty())
                awaitStartEnabled(scenario)
                scenario.onActivity { it.findViewById<Button>(R.id.audio_start).performClick() }
                val restarted = await(audio) { it.status == CaptureStatus.CAPTURING && it.frames >= 10 }
                assertTrue(restarted.sessionId > initial.sessionId)
                println("AUDIO_EMULATOR rate=${restarted.sampleRate} frames=${restarted.frames} " +
                    "drops=${restarted.droppedFrames} first_frame_ms=${restarted.firstFrameMillis} " +
                    "last_gap_ms=${restarted.lastFrameGapMillis} route_type=${restarted.routeType}")
            } finally {
                audio.stop()
            }
        }
    }

    @Test fun rotationAndBackgroundStopRatherThanAutomaticallyReopenMicrophone() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.RECORD_AUDIO)
        val audio = AndroidAudioInput.get(context)
        ActivityScenario.launch<VoiceLabActivity>(Intent(context, VoiceLabActivity::class.java)).use { scenario ->
            try {
                awaitStartEnabled(scenario)
                scenario.onActivity { it.findViewById<Button>(R.id.audio_start).performClick() }
                await(audio) { it.status == CaptureStatus.CAPTURING && it.frames > 5 }
                scenario.recreate()
                val stopped = await(audio) { it.status == CaptureStatus.IDLE }
                assertFalse(stopped.status == CaptureStatus.CAPTURING)
                awaitStartEnabled(scenario)
                scenario.onActivity { it.findViewById<Button>(R.id.audio_start).performClick() }
                await(audio) { it.status == CaptureStatus.CAPTURING && it.frames > 5 }
                scenario.moveToState(Lifecycle.State.CREATED)
                await(audio) { it.status == CaptureStatus.IDLE }
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals(CaptureStatus.IDLE, audio.state.value.status)
                awaitStartEnabled(scenario)
                scenario.onActivity { it.findViewById<Button>(R.id.audio_start).performClick() }
                await(audio) { it.status == CaptureStatus.CAPTURING && it.frames > 5 }
            } finally {
                audio.stop()
            }
        }
    }

    private suspend fun await(audio: AudioInput, condition: (AudioCaptureState) -> Boolean): AudioCaptureState =
        withTimeout(8000) {
            audio.state.first {
                check(it.status != CaptureStatus.ERROR) { "${it.failure}: ${it.cause}" }
                condition(it)
            }
        }

    private suspend fun awaitStartEnabled(scenario: ActivityScenario<VoiceLabActivity>) {
        withTimeout(8000) {
            while (true) {
                var enabled = false
                scenario.onActivity { enabled = it.findViewById<Button>(R.id.audio_start).isEnabled }
                if (enabled) return@withTimeout
                delay(20)
            }
        }
    }

}