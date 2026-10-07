package dev.edgeai.prototype.voice.conversation

@JvmInline
value class UserTranscript(
    val text: String
) {
    init {
        require(text.isNotBlank())
    }
}