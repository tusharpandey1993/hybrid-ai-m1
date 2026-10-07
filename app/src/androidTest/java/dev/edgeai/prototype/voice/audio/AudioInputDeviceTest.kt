package dev.edgeai.prototype.voice.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.widget.TextView
import kotlinx.coroutines.TimeoutCancellationException
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
                assertEquals("VOICE_COMMUNICATION", audio.audioSourceName)
                val processing = audio.audioProcessingState.value
                assertTrue(requireNotNull(processing.audioSessionId) > 0)
                assertFalse(processing.aecEnabled && !processing.aecAttached)
                assertFalse(processing.noiseSuppressorEnabled && !processing.noiseSuppressorAttached)
                val frame = withTimeout(5000) { audio.frames.first() }
                assertEquals(320, frame.sampleCount)
                assertEquals(initial.sessionId, frame.sessionId)
                val timestamps = withTimeout(8000) {
                    audio.frames.map { it.timestampNanos }.take(100).toList()
                }
                val gaps = timestamps.zipWithNext { first, second -> (second - first) / 1_000_000.0 }.sorted()
                val measured = audio.state.value
                File(context.filesDir, "audio-capture-smoke.json").writeText(
                    JSONObject().put("device_model", Build.MODEL)
                        .put("device", Build.DEVICE)
                        .put("android_release", Build.VERSION.RELEASE)
                        .put("android_api", Build.VERSION.SDK_INT)
                        .put("sample_rate", measured.sampleRate).put("frame_samples", 320)
                        .put("frames", measured.frames).put("dropped_frames", measured.droppedFrames)
                        .put("first_frame_ms", measured.firstFrameMillis)
                        .put("interval_samples", gaps.size).put("gap_p50_ms", gaps[49])
                        .put("gap_p95_ms", gaps[94]).put("gap_max_ms", gaps.last())
                        .put("route_type", measured.routeType)
                        .put("audio_source", audio.audioSourceName)
                        .put("audio_session_id", processing.audioSessionId)
                        .put("aec_available", processing.aecAvailable)
                        .put("aec_attached", processing.aecAttached)
                        .put("aec_enabled", processing.aecEnabled)
                        .put("noise_suppressor_available", processing.noiseSuppressorAvailable)
                        .put("noise_suppressor_attached", processing.noiseSuppressorAttached)
                        .put("noise_suppressor_enabled", processing.noiseSuppressorEnabled).toString(2)
                )
                audio.start()
                assertEquals(initial.sessionId, audio.state.value.sessionId)
                scenario.onActivity { it.findViewById<Button>(R.id.audio_stop).performClick() }
                await(audio) { it.status == CaptureStatus.IDLE }
                assertEquals(null, audio.audioProcessingState.value.audioSessionId)
                assertFalse(audio.audioProcessingState.value.aecAttached)
                assertFalse(audio.audioProcessingState.value.noiseSuppressorAttached)
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

    @Test fun microphoneFramesContinueWhileAssistantTtsIsSpeaking() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.RECORD_AUDIO)
        val audio = AndroidAudioInput.get(context)
        ActivityScenario.launch<VoiceLabActivity>(Intent(context, VoiceLabActivity::class.java)).use { scenario ->
            var stage = "launch"
            try {
                awaitStartEnabled(scenario)
                stage = "start capture"
                scenario.onActivity { it.findViewById<Button>(R.id.audio_start).performClick() }
                val started = await(audio) { it.status == CaptureStatus.CAPTURING && it.frames >= 10 }
                stage = "wait for TTS ready"
                withTimeout(8000) {
                    while (true) {
                        var enabled = false
                        scenario.onActivity { enabled = it.findViewById<Button>(R.id.tts_speak).isEnabled }
                        if (enabled) return@withTimeout
                        delay(20)
                    }
                }
                stage = "request TTS"
                scenario.onActivity { it.findViewById<Button>(R.id.tts_speak).performClick() }
                stage = "observe TTS speaking"
                withTimeout(8000) {
                    while (true) {
                        var speaking = false
                        scenario.onActivity {
                            speaking = it.findViewById<TextView>(R.id.tts_status).text
                                .toString().contains("speaking", ignoreCase = true)
                        }
                        if (speaking) return@withTimeout
                        delay(20)
                    }
                }
                stage = "wait for microphone frames during TTS"
                val advanced = withTimeout(5000) {
                    audio.state.first { it.frames >= started.frames + 25 }
                }
                assertEquals(CaptureStatus.CAPTURING, advanced.status)
                var stillSpeaking = false
                scenario.onActivity {
                    stillSpeaking = it.findViewById<TextView>(R.id.tts_status).text
                        .toString().contains("speaking", ignoreCase = true)
                }
                assertTrue("TTS stopped before capture continuity was checked", stillSpeaking)
                scenario.onActivity { it.findViewById<Button>(R.id.tts_stop).performClick() }
                withTimeout(8000) {
                    while (true) {
                        var ready = false
                        scenario.onActivity {
                            ready = it.findViewById<TextView>(R.id.tts_status).text
                                .toString().contains("ready", ignoreCase = true)
                        }
                        if (ready) return@withTimeout
                        delay(20)
                    }
                }
                withTimeout(8000) {
                    while (true) {
                        var enabled = false
                        scenario.onActivity { enabled = it.findViewById<Button>(R.id.tts_speak).isEnabled }
                        if (enabled) return@withTimeout
                        delay(20)
                    }
                }
                scenario.onActivity { it.findViewById<Button>(R.id.tts_speak).performClick() }
                withTimeout(8000) {
                    while (true) {
                        var speaking = false
                        scenario.onActivity {
                            speaking = it.findViewById<TextView>(R.id.tts_status).text
                                .toString().contains("speaking", ignoreCase = true)
                        }
                        if (speaking) return@withTimeout
                        delay(20)
                    }
                }
                println("FULL_DUPLEX_REPEAT_TTS frames_before=${started.frames} frames_during=${advanced.frames} " +
                    "audio_session=${audio.audioProcessingState.value.audioSessionId} " +
                    "aec_enabled=${audio.audioProcessingState.value.aecEnabled} " +
                    "noise_suppressor_enabled=${audio.audioProcessingState.value.noiseSuppressorEnabled}")
            } catch (timeout: TimeoutCancellationException) {
                var ttsText = ""
                var vadText = ""
                var conversationText = ""
                var responseText = ""
                scenario.onActivity {
                    ttsText = it.findViewById<TextView>(R.id.tts_status).text.toString()
                    vadText = it.findViewById<TextView>(R.id.vad_status).text.toString()
                    conversationText = it.findViewById<TextView>(R.id.conversation_status).text.toString()
                    responseText = it.findViewById<TextView>(R.id.assistant_response).text.toString()
                }
                throw AssertionError("Timed out at $stage; TTS=$ttsText, VAD=$vadText, " +
                    "conversation=$conversationText, response=$responseText, " +
                    "capture=${audio.state.value.status}, frames=${audio.state.value.frames}", timeout)
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