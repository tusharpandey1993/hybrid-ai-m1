package dev.edgeai.prototype.voice.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AudioFrameBus(capacity: Int = 8) {
    private val mutableFrames = MutableSharedFlow<AudioFrame>(
        replay = 0, extraBufferCapacity = capacity, onBufferOverflow = BufferOverflow.SUSPEND
    )
    val frames = mutableFrames.asSharedFlow()

    init { require(capacity > 0) }

    fun offer(frame: AudioFrame): Boolean =
        mutableFrames.subscriptionCount.value > 0 && mutableFrames.tryEmit(frame)
}