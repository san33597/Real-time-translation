package com.localfirst.realtimetranslator.service

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit per-session local diagnostics. Records text and timing only, NEVER raw PCM or network.
 * Bounded, nonblocking queue prevents diagnostic IO from affecting real-time capture/ASR.
 */
class AsrDiagnosticLog private constructor(val file: File) {
    private val events = Channel<String>(capacity = 512)
    private val dropped = AtomicLong(0)
    @Volatile private var stopped = false
    private val writerJob: Job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                var bytesWritten = 0L
                var truncated = false
                for (line in events) {
                    if (truncated) continue
                    val bytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1
                    if (bytesWritten + bytes > MAX_FILE_BYTES) {
                        out.write(JSONObject().put("stage", "log_truncated")
                            .put("maxBytes", MAX_FILE_BYTES).toString())
                        out.newLine()
                        truncated = true
                    } else {
                        out.write(line)
                        out.newLine()
                        bytesWritten += bytes
                    }
                }
            }
        } catch (_: Exception) {
            // Logging failures must not terminate the recognizer.
        }
    }

    fun record(stage: String, text: String = "", metadata: Map<String, Any> = emptyMap()) {
        if (stopped) return
        val line = JSONObject()
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("wallTimeMs", System.currentTimeMillis())
            .put("stage", stage)
            .put("text", text)
            .put("metadata", JSONObject(metadata))
            .toString()
        if (!events.trySend(line).isSuccess) dropped.incrementAndGet()
    }

    suspend fun finish() {
        if (!stopped) {
            record("session_end", metadata = mapOf("droppedDiagnosticEvents" to dropped.get()))
            stopped = true
            events.close()
        }
        writerJob.join()
    }

    fun abort() {
        stopped = true
        events.close() // Asynchronously drain without blocking service teardown.
    }

    companion object {
        private const val DIRECTORY = "asr-diagnostics"
        private const val PREFIX = "asr-r3.8-"
        private const val MAX_FILES = 8
        private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
        private fun dir(context: Context): File = File(context.filesDir, DIRECTORY)
        private fun logs(context: Context): List<File> =
            dir(context).listFiles()?.filter { it.isFile &&
                it.name.startsWith(PREFIX) && it.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()

        fun latest(context: Context): File? = logs(context).firstOrNull()
        fun clear(context: Context): Int = logs(context).count { it.delete() }

        fun start(context: Context): AsrDiagnosticLog? = try {
            val directory = dir(context)
            check(directory.exists() || directory.mkdirs())
            val file = File(directory, PREFIX + System.currentTimeMillis() + ".jsonl")
            check(file.createNewFile())
            logs(context).drop(MAX_FILES).forEach { it.delete() }
            AsrDiagnosticLog(file)
        } catch (_: Exception) {
            null
        }
    }
}
