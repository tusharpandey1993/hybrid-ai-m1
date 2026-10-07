package dev.edgeai.prototype.voice.vad

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgeai.prototype.voice.audio.AudioFrame
import dev.edgeai.prototype.voice.audio.AndroidAudioInput
import dev.edgeai.prototype.voice.audio.CaptureStatus
import dev.edgeai.prototype.voice.debug.VoiceLabActivity
import dev.edgeai.prototype.voice.stt.SherpaStreamingSttEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VadDeviceTest {
    @Test
    fun localModelKeepsTwoSecondsOfSilenceBelowSpeechThreshold() {
        val processor = VadFrameProcessor(SherpaSileroVadEngine(context().assets))
        val observations = (0 until 100).flatMap { sequence ->
            processor.process(frame(1, sequence.toLong(), ShortArray(AudioFrame.SAMPLE_COUNT)))
        }
        processor.close()

        assertEquals(62, observations.size)
        assertTrue(observations.all { it.probability in 0f..1f })
        assertTrue(observations.none { it.speechStarted || it.speechDetected })
        println("VAD_SILENCE windows=${observations.size} max_probability=${observations.maxOf { it.probability }}")
    }

    @Test
    fun localModelRejectsTwoSecondsOfLowLevelBroadbandNoise() {
        val random = Random(42)
        val processor = VadFrameProcessor(SherpaSileroVadEngine(context().assets))
        val observations = (0 until 100).flatMap { sequence ->
            val noise = ShortArray(AudioFrame.SAMPLE_COUNT) { random.nextInt(-256, 257).toShort() }
            processor.process(frame(3, sequence.toLong(), noise))
        }
        processor.close()

        assertTrue(observations.none { it.speechStarted })
        println("VAD_NOISE windows=${observations.size} max_probability=${observations.maxOf { it.probability }}")
    }

    @Test
    fun localModelDetectsStagedSpeechWaveform() {
        val fixture = context().getExternalFilesDir(null)?.let { File(it, "vad-reference.wav") }
        assumeTrue("Stage a 16 kHz mono PCM16 speech WAV in the app external-files directory", fixture?.isFile == true)
        requireNotNull(fixture)
        val samples = readPcm16Wave(fixture)
        val firstAudibleSample = firstSampleAboveRms(samples, 0.004)
        assertTrue("Speech fixture has no audible samples", firstAudibleSample >= 0)
        val leadingSilence = AudioFrame.SAMPLE_RATE / 2
        val paddedSamples = ShortArray(leadingSilence + samples.size + AudioFrame.SAMPLE_RATE / 2)
        samples.copyInto(paddedSamples, leadingSilence)
        val processor = VadFrameProcessor(SherpaSileroVadEngine(context().assets))
        val observations = paddedSamples.asList().chunked(AudioFrame.SAMPLE_COUNT)
            .mapIndexed { index, chunk ->
                val pcm = ShortArray(AudioFrame.SAMPLE_COUNT)
                chunk.forEachIndexed { sampleIndex, sample -> pcm[sampleIndex] = sample }
                processor.process(frame(2, index.toLong(), pcm))
            }.flatten()
        processor.close()

        assertTrue("Expected at least one VAD window", observations.isNotEmpty())
        assertTrue("Generated speech did not cross the start threshold",
            observations.any { it.speechStarted })
        assertTrue("VAD did not emit an end edge after trailing silence",
            observations.any { it.speechEnded })
        val inference = observations.map { it.inferenceMillis }.sorted()
        val firstStart = observations.first { it.speechStarted }
        val onsetAfterSpeechInputMillis = (firstStart.frameSequence + 1) * AudioFrame.FRAME_MILLIS -
            500 - firstAudibleSample * 1000 / AudioFrame.SAMPLE_RATE
        assertTrue("Unexpected speech onset delay: $onsetAfterSpeechInputMillis ms",
            onsetAfterSpeechInputMillis in 0..250)
        println("VAD_SPEECH windows=${observations.size} starts=${observations.count { it.speechStarted }} " +
            "ends=${observations.count { it.speechEnded }} max_probability=${observations.maxOf { it.probability }} " +
            "high_probability_windows=${observations.count { it.probability >= VadFrameProcessor.START_THRESHOLD }} " +
            "first_start_frame=${firstStart.frameSequence} onset_after_audible_boundary_ms=$onsetAfterSpeechInputMillis " +
            "inference_p50_ms=${inference[inference.size / 2]} inference_p95_ms=${inference[(inference.size * 95 / 100).coerceAtMost(inference.lastIndex)]}")
    }

    @Test
    fun compactSherpaRecognizerProducesTextFromTheExistingSpeechFixture() {
        val fixture = context().getExternalFilesDir(null)?.let { File(it, "vad-reference.wav") }
        assumeTrue("Stage a 16 kHz mono PCM16 speech WAV in the app external-files directory", fixture?.isFile == true)
        requireNotNull(fixture)
        val samples = readPcm16Wave(fixture)
        val silenceSamples = AudioFrame.SAMPLE_RATE * 2
        val paddedSamples = ShortArray(samples.size + silenceSamples)
        samples.copyInto(paddedSamples)
        val engine = SherpaStreamingSttEngine(context().assets)
        var text = ""
        var firstTextFrame = -1L
        var maxInferenceMillis = 0.0
        var sawFinal = false
        try {
            for (start in paddedSamples.indices step AudioFrame.SAMPLE_COUNT) {
                val pcm = ShortArray(AudioFrame.SAMPLE_COUNT)
                val end = minOf(start + pcm.size, paddedSamples.size)
                paddedSamples.copyInto(pcm, endIndex = end, startIndex = start)
                val sequence = (start / AudioFrame.SAMPLE_COUNT).toLong()
                val update = engine.accept(frame(4, sequence, pcm))
                maxInferenceMillis = maxOf(maxInferenceMillis, update.inferenceMillis)
                sawFinal = sawFinal || update.isFinal
                val transcript = update.transcript?.text.orEmpty()
                if (transcript.isNotBlank()) {
                    if (firstTextFrame < 0) firstTextFrame = sequence
                    text = transcript
                }
            }
        } finally {
            engine.close()
        }

        assertTrue("Recognizer produced no word-like text: $text", Regex("[A-Za-z]{3,}").containsMatchIn(text))
        assertTrue("Recognizer did not emit a final endpoint", sawFinal)
        assertTrue(firstTextFrame >= 0)
        println("STT_STREAMING frames=${paddedSamples.size / AudioFrame.SAMPLE_COUNT} " +
            "first_text_ms=${firstTextFrame * AudioFrame.FRAME_MILLIS} " +
            "max_frame_decode_ms=$maxInferenceMillis text=$text")
    }

    @Test
    fun physicalMicrophoneFramesRunThroughVadWithoutPersistingPcm() = runBlocking<Unit> {
        val context = context()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.RECORD_AUDIO)
        val audio = AndroidAudioInput.get(context)
        val processor = VadFrameProcessor(SherpaSileroVadEngine(context.assets))
        val observations = CopyOnWriteArrayList<VadObservation>()
        var frameCount = 0

        ActivityScenario.launch<VoiceLabActivity>(Intent(context, VoiceLabActivity::class.java)).use { scenario ->
            val collector: Job = CoroutineScope(Dispatchers.Default).launch {
                audio.frames.collect { frame ->
                    frameCount++
                    observations.addAll(processor.process(frame))
                }
            }
            try {
                withTimeout(8000) {
                    var ready = false
                    while (!ready) {
                        scenario.onActivity { activity ->
                            ready = activity.findViewById<Button>(dev.edgeai.prototype.R.id.audio_start).isEnabled
                        }
                        if (!ready) delay(20)
                    }
                }
                scenario.onActivity {
                    it.findViewById<Button>(dev.edgeai.prototype.R.id.audio_start).performClick()
                }
                val capture = withTimeout(8000) {
                    audio.state.first { it.status == CaptureStatus.CAPTURING && it.frames >= 100 }
                }
                audio.stop()
                collector.cancelAndJoin()
                val audioManager = context.getSystemService(AudioManager::class.java)
                withTimeout(5000) {
                    while (audioManager.activeRecordingConfigurations.isNotEmpty()) delay(20)
                }
                assertTrue(audioManager.activeRecordingConfigurations.isEmpty())

                assertTrue(frameCount >= 100)
                assertTrue(observations.isNotEmpty())
                assertTrue(observations.all { it.probability.isFinite() && it.inferenceMillis >= 0.0 })
                var renderedVadStats = ""
                var renderedSttStats = ""
                scenario.onActivity {
                    renderedVadStats = it.findViewById<TextView>(dev.edgeai.prototype.R.id.vad_stats).text.toString()
                    renderedSttStats = it.findViewById<TextView>(dev.edgeai.prototype.R.id.stt_stats).text.toString()
                }
                assertTrue(renderedVadStats.contains("Windows: "))
                assertFalse(renderedVadStats.contains("Windows: 0"))
                assertTrue(renderedSttStats.contains("Frames processed: "))
                assertFalse(renderedSttStats.contains("Frames processed: 0"))
                val inference = observations.map { it.inferenceMillis }.sorted()
                println("VAD_MIC sample_rate=${capture.sampleRate} frames=$frameCount " +
                    "windows=${observations.size} starts=${observations.count { it.speechStarted }} " +
                    "max_probability=${observations.maxOf { it.probability }} " +
                    "inference_p50_ms=${inference[inference.size / 2]} " +
                    "inference_p95_ms=${inference[(inference.size * 95 / 100).coerceAtMost(inference.lastIndex)]} " +
                    "dropped_frames=${capture.droppedFrames}")
            } finally {
                audio.stop()
                collector.cancelAndJoin()
                processor.close()
            }
        }
    }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun frame(sessionId: Long, sequence: Long, samples: ShortArray) =
        AudioFrame(sessionId, sequence, sequence * AudioFrame.FRAME_MILLIS * 1_000_000L, samples)

    private fun firstSampleAboveRms(samples: ShortArray, threshold: Double): Int {
        val blockSamples = AudioFrame.SAMPLE_RATE / 100
        for (start in samples.indices step blockSamples) {
            val end = minOf(start + blockSamples, samples.size)
            var squares = 0.0
            for (index in start until end) {
                val normalized = samples[index] / 32768.0
                squares += normalized * normalized
            }
            if (sqrt(squares / (end - start)) > threshold) return start
        }
        return -1
    }

    private fun readPcm16Wave(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var sampleRate: Int? = null
        var channels: Int? = null
        var dataOffset = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val chunkName = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkLength = buffer.getInt(offset + 4)
            require(chunkLength >= 0 && offset + 8L + chunkLength <= bytes.size)
            when (chunkName) {
                "fmt " -> {
                    require(buffer.getShort(offset + 8).toInt() == 1)
                    channels = buffer.getShort(offset + 10).toInt()
                    sampleRate = buffer.getInt(offset + 12)
                    require(buffer.getShort(offset + 22).toInt() == 16)
                }
                "data" -> {
                    dataOffset = offset + 8
                    dataLength = chunkLength
                    break
                }
            }
            offset += 8 + chunkLength + (chunkLength and 1)
        }
        require(sampleRate == AudioFrame.SAMPLE_RATE && channels == 1 && dataOffset >= 0 && dataLength % 2 == 0)
        return ShortArray(dataLength / 2) { buffer.getShort(dataOffset + it * 2) }
    }
}