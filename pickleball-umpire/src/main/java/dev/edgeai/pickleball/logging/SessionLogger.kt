package dev.edgeai.pickleball.logging

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SessionLogger(context: Context) {
    private val sessionDirectory = File(context.filesDir, "sessions")
    private val queue = ArrayBlockingQueue<String>(256)
    private val accepting = AtomicBoolean(false)
    private val droppedRecords = AtomicInteger(0)
    @Volatile
    private var writerThread: Thread? = null
    @Volatile
    var currentFile: File? = null
        private set

    val isActive: Boolean
        get() = accepting.get()

    @Synchronized
    fun startSession(): File {
        check(!accepting.get()) { "A session is already active" }
        check(sessionDirectory.exists() || sessionDirectory.mkdirs()) {
            "Unable to create session log directory"
        }
        queue.clear()
        droppedRecords.set(0)

        val sessionFile = File(sessionDirectory, "session_${System.currentTimeMillis()}.jsonl")
        currentFile = sessionFile
        accepting.set(true)
        writerThread = Thread({ writeSession(sessionFile) }, "pickleball-session-log").apply {
            isDaemon = true
            start()
        }
        logEvent("SESSION_START", mapOf("format" to "jsonl"))
        return sessionFile
    }

    fun logEvent(event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!accepting.get()) return
        val record = JSONObject().put("event", event).put("ts", System.nanoTime())
        fields.forEach { (key, value) -> record.put(key, value) }
        if (!queue.offer(record.toString())) droppedRecords.incrementAndGet()
    }

    @Synchronized
    fun stopSession(reason: String = "USER") {
        if (!accepting.getAndSet(false)) return
        val endRecord = JSONObject()
            .put("event", "SESSION_END")
            .put("ts", System.nanoTime())
            .put("reason", reason)
        queue.offer(endRecord.toString())
        val dropped = droppedRecords.get()
        if (dropped > 0) {
            queue.offer(JSONObject()
                .put("event", "LOG_RECORDS_DROPPED")
                .put("ts", System.nanoTime())
                .put("count", dropped)
                .toString())
        }
        if (!queue.offer(STOP_MARKER)) {
            Thread({
                try {
                    queue.put(STOP_MARKER)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, "pickleball-log-close").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun awaitStopped(timeoutMs: Long) {
        writerThread?.join(timeoutMs)
    }

    private fun writeSession(file: File) {
        try {
            BufferedWriter(FileWriter(file, false)).use { writer ->
                while (true) {
                    val line = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (line == STOP_MARKER) break
                    writer.append(line)
                    writer.newLine()
                    writer.flush()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Session log write failed", error)
        }
    }

    private companion object {
        const val TAG = "PickleballLogger"
        const val STOP_MARKER = "__PICKLEBALL_STOP__"
    }
}