package dev.edgeai.pickleball.camera

interface FrameSource {
    suspend fun consume(consumer: suspend (Yuv420Frame) -> Unit)
}