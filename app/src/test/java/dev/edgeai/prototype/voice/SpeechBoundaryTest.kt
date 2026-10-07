package dev.edgeai.prototype.voice

import dev.edgeai.prototype.voice.conversation.AssistantResponse
import dev.edgeai.prototype.voice.conversation.UserTranscript
import dev.edgeai.prototype.voice.session.VoiceConversationSession
import dev.edgeai.prototype.voice.stt.transcriptFromStt
import dev.edgeai.prototype.voice.tts.SpeechSynthesisState
import dev.edgeai.prototype.voice.tts.SpeechSynthesizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechBoundaryTest {
    @Test fun sttTextBecomesUserTranscriptAndBlankResultsAreIgnored() {
        assertEquals(UserTranscript("Recognized words"), transcriptFromStt("Recognized words"))
        assertNull(transcriptFromStt("  "))
    }

    @Test fun finalSttTranscriptEntersConversationAsUserText() {
        val conversation = VoiceConversationSession("voice-lab")
        conversation.acceptTranscript(UserTranscript("What does GLiNER mean?"), isFinal = true)

        assertEquals("What does GLiNER mean?", conversation.context.committedTranscript)
        assertEquals("What does GLiNER mean?", conversation.context.partialTranscript)
    }

    @Test fun synthesizerReceivesAssistantResponse() {
        val synthesizer = RecordingSynthesizer()
        val response = AssistantResponse("voice-lab", "response-1", 0, "Hello there.")

        synthesizer.speak(response)

        assertSame(response, synthesizer.lastResponse)
        synthesizer.close()
    }

    @Test fun sherpaSttHasNoDirectTtsDependency() {
        val project = java.io.File(requireNotNull(System.getProperty("gliner.moduleRoot")))
        val source = java.io.File(project,
            "app/src/main/java/dev/edgeai/prototype/voice/stt/SherpaStreamingSttEngine.kt")
        val imports = source.readLines().filter { it.startsWith("import ") }

        assertFalse(imports.any { it.contains("voice.tts") })
        assertTrue(imports.any { it.endsWith("voice.conversation.UserTranscript") })
    }

    private class RecordingSynthesizer : SpeechSynthesizer {
        private val mutableState = MutableStateFlow(SpeechSynthesisState())
        override val state: StateFlow<SpeechSynthesisState> = mutableState
        var lastResponse: AssistantResponse? = null
            private set

        override fun speak(response: AssistantResponse): Boolean {
            lastResponse = response
            return true
        }

        override fun stop(): Double = 0.0
        override fun close() = Unit
    }
}
