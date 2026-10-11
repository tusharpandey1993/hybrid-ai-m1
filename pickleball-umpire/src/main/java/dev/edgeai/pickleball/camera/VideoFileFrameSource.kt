package dev.edgeai.pickleball.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class VideoFileFrameSource(
    private val context: Context,
    private val uri: Uri,
) : FrameSource {
    private val stopRequested = AtomicBoolean(false)

    override suspend fun consume(consumer: suspend (Yuv420Frame) -> Unit) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("Selected file has no video track")
            extractor.selectTrack(videoTrack)
            val format = extractor.getTrackFormat(videoTrack)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val rotation = normalizeRotation(
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else {
                    0
                },
            )
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded && !stopRequested.get()) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(decoder.getInputBuffer(inputIndex))
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    try {
                        if (bufferInfo.size > 0) {
                            val image = decoder.getOutputImage(outputIndex)
                            if (image != null) {
                                try {
                                    consumer(
                                        Yuv420Frame(
                                            timestampNs = bufferInfo.presentationTimeUs.coerceAtLeast(0L) * 1_000L,
                                            width = image.width,
                                            height = image.height,
                                            rotationDegrees = rotation,
                                            planes = image.planes.map { plane ->
                                                Yuv420Plane(plane.buffer, plane.rowStride, plane.pixelStride)
                                            },
                                        ),
                                    )
                                } finally {
                                    image.close()
                                }
                            }
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            decoder?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            extractor.release()
        }
    }

    fun stop() {
        stopRequested.set(true)
    }

    private fun normalizeRotation(rotation: Int): Int {
        val normalized = ((rotation % 360) + 360) % 360
        require(normalized in setOf(0, 90, 180, 270)) { "Unsupported video rotation: $rotation" }
        return normalized
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}