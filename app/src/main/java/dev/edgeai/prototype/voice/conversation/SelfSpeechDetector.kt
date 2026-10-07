package dev.edgeai.prototype.voice.conversation


data class SelfSpeechResult(
    val similarity: Float,
    val likelyPlaybackLeakage: Boolean
)

interface SelfSpeechDetector {
    fun evaluate(assistant: AssistantResponse?, candidate: UserTranscript): SelfSpeechResult
}

class LexicalSelfSpeechDetector(
    private val suspicionThreshold: Float = DEFAULT_SUSPICION_THRESHOLD
) : SelfSpeechDetector {
    init {
        require(suspicionThreshold in 0f..1f)
    }

    override fun evaluate(
        assistant: AssistantResponse?,
        candidate: UserTranscript
    ): SelfSpeechResult {
        val assistantTokens = assistant?.text?.let(::tokens).orEmpty()
        val candidateTokens = tokens(candidate.text)
        if (assistantTokens.isEmpty() || candidateTokens.isEmpty()) return SelfSpeechResult(0f, false)

        val remaining = assistantTokens.groupingBy { it }.eachCount().toMutableMap()
        val overlap = candidateTokens.count { token ->
            val count = remaining[token] ?: 0
            if (count == 0) false else {
                remaining[token] = count - 1
                true
            }
        }
        val similarity = overlap.toFloat() / candidateTokens.size
        return SelfSpeechResult(similarity, similarity >= suspicionThreshold)
    }

    private fun tokens(text: String): List<String> = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)

    companion object {
        const val DEFAULT_SUSPICION_THRESHOLD = 0.95f
    }
}
