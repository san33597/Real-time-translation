package com.localfirst.realtimetranslator.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.localfirst.realtimetranslator.model.AsrEngine
import com.localfirst.realtimetranslator.model.AsrRuntimeStats
import com.localfirst.realtimetranslator.model.AsrUpdate
import com.localfirst.realtimetranslator.model.ParakeetAudioChunker
import com.localfirst.realtimetranslator.model.ParakeetOverlapStitcher
import com.localfirst.realtimetranslator.model.ParakeetWindowPreset
import com.localfirst.realtimetranslator.model.PcmFrame
import com.localfirst.realtimetranslator.model.SessionIdentity
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Offline, NOT streaming. A short PCM-window builder runs on the capture consumer;
 * heavyweight JNI decode runs on its own coroutine and never blocks AudioRecord.
 * At most three windows can be queued (bounded RAM and bounded inference lag).
 * Native recognizer is released on the decode worker's finally block.
 */
class ParakeetOfflineEnglishEngine(
    private val identity: SessionIdentity,
    modelDirectory: File,
    private val nowMs: () -> Long,
    private val onUpdate: (AsrUpdate) -> Unit,
    private val onStats: (AsrRuntimeStats) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val preset: ParakeetWindowPreset = ParakeetWindowPreset.LEGACY,
) : AsrEngine {
    private data class PendingWindow(
        val window: ParakeetAudioChunker.Window,
        val generation: Long,
        val queuedAtMs: Long,
    )

    private val model: OfflineRecognizer
    private val chunks = Channel<PendingWindow>(capacity = 3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val chunker = if (preset == ParakeetWindowPreset.LEGACY) {
        // Baseline uses the identical default chunker initialization as R3.5.
        ParakeetAudioChunker()
    } else {
        ParakeetAudioChunker(windowMs = preset.windowMs, overlapMs = preset.overlapMs)
    }
    @Volatile private var generation = 0L
    private val decoded = AtomicLong()
    private val dropped = AtomicLong()
    private val queued = AtomicLong()
    private val inputFrames = AtomicLong()
    private val emptyResults = AtomicLong()
    private val overlapOnlyResults = AtomicLong()
    @Volatile private var inputPeakPermille = 0
    @Volatile private var inputRmsPermille = 0
    @Volatile private var lastDecodeMs = 0L
    @Volatile private var lastQueueWaitMs = 0L
    @Volatile private var lastWindowTurnaroundMs = 0L
    @Volatile private var closed = false
    private var finished = false

    init {
        fun file(name: String): String = File(modelDirectory, name).absolutePath
        model = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = file(ParakeetModelInstaller.ENCODER),
                        decoder = file(ParakeetModelInstaller.DECODER),
                        joiner = file(ParakeetModelInstaller.JOINER),
                    ),
                    tokens = file(ParakeetModelInstaller.TOKENS),
                    numThreads = 4,
                    provider = "cpu",
                    modelType = "nemo_transducer",
                ),
            ),
        )
    }

    // UNDISTPATCHED enters the channel receive loop before returning, ensuring
    // that cancellation still runs the worker finally/release block.
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val stitcher = ParakeetOverlapStitcher()
        var workerGeneration = generation
        var utterance = 0L
        try {
            for (pending in chunks) {
                val wave = pending.window.samples
                try {
                    if (pending.generation != generation) continue
                    if (workerGeneration != pending.generation) {
                        stitcher.reset()
                        workerGeneration = pending.generation
                    }
                    val begin = nowMs()
                    lastQueueWaitMs = (begin - pending.queuedAtMs).coerceAtLeast(0)
                    val stream = model.createStream()
                    val text = try {
                        stream.acceptWaveform(wave, 16000)
                        model.decode(stream)
                        model.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                    val elapsed = (nowMs() - begin).coerceAtLeast(0)
                    // A reset/gap during native decode invalidates that result.
                    if (pending.generation != generation) continue
                    lastDecodeMs = elapsed
                    lastWindowTurnaroundMs = (nowMs() - pending.queuedAtMs).coerceAtLeast(0)
                    decoded.incrementAndGet()
                    if (text.isBlank()) emptyResults.incrementAndGet()
                    val merged = stitcher.append(text)
                    if (text.isNotBlank() && merged.isBlank()) overlapOnlyResults.incrementAndGet()
                    if (merged.isNotBlank()) {
                        onUpdate(AsrUpdate(identity.sessionId, identity.audioEpoch,
                            utterance++, 0L, merged, true, nowMs()))
                    }
                    publishStats()
                } finally {
                    wave.fill(0f)
                }
            }
        } catch (t: Throwable) {
            if (!closed && t !is kotlinx.coroutines.CancellationException) onFailure(t)
        } finally {
            while (true) {
                val next = chunks.tryReceive().getOrNull() ?: break
                next.window.samples.fill(0f)
            }
            model.release()
        }
    }

    override fun accept(frame: PcmFrame) {
        check(!closed && !finished)
        require(frame.sessionId == identity.sessionId && frame.audioEpoch == identity.audioEpoch)
        require(frame.sampleRateHz == 16000 && frame.channelCount == 1)
        val samples = frame.samples
        var squared = 0.0
        var peak = 0
        for (sample in samples) {
            val value = sample.toInt()
            peak = maxOf(peak, kotlin.math.abs(value))
            squared += value.toDouble() * value
        }
        inputPeakPermille = (peak * 1000L / 32768L).toInt().coerceIn(0, 1000)
        inputRmsPermille = (sqrt(squared / samples.size) * 1000.0 / 32768.0)
            .toInt().coerceIn(0, 1000)
        val count = inputFrames.incrementAndGet()
        chunker.append(samples) { offer(it) }
        if (count % 25L == 0L) publishStats()
    }

    private fun offer(window: ParakeetAudioChunker.Window) {
        if (!chunks.trySend(PendingWindow(window, generation, nowMs())).isSuccess) {
            window.samples.fill(0f)
            dropped.incrementAndGet()
            publishStats()
        } else {
            queued.incrementAndGet()
            publishStats()
        }
    }

    private fun publishStats() {
        onStats(AsrRuntimeStats(identity.sessionId, identity.audioEpoch,
            decodedWindows = decoded.get(), droppedWindows = dropped.get(),
            lastDecodeMs = lastDecodeMs,
            queuedWindows = queued.get(),
            inputFrames = inputFrames.get(),
            inputPeakPermille = inputPeakPermille,
            inputRmsPermille = inputRmsPermille,
            emptyResults = emptyResults.get(),
            overlapOnlyResults = overlapOnlyResults.get(),
            lastQueueWaitMs = lastQueueWaitMs,
            lastWindowTurnaroundMs = lastWindowTurnaroundMs,
            windowMs = preset.windowMs,
            overlapMs = preset.overlapMs))
    }

    override fun resetAfterGap() {
        if (closed || finished) return
        generation++
        chunker.reset()
        // Never splice audio samples across a discontinuity.
    }

    override fun finish() {
        if (closed || finished) return
        finished = true
        chunker.finish { offer(it) }
        chunks.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++ // invalidate any decode still in flight
        chunker.reset()
        chunks.cancel()
        worker.cancel() // JNI finishes before worker's finally releases model
        scope.cancel()
    }
}
