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
import com.localfirst.realtimetranslator.model.ParakeetGuardedOverlapStitcher
import com.localfirst.realtimetranslator.model.ParakeetStitchMode
import com.localfirst.realtimetranslator.model.ParakeetWindowPreset
import com.localfirst.realtimetranslator.model.ParakeetPreviewMode
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
import kotlinx.coroutines.selects.select

/**
 * Offline, NOT streaming. A short PCM-window builder runs on the capture consumer;
 * heavyweight JNI decode runs on its own coroutine and never blocks AudioRecord.
 * At most three windows can be queued (bounded RAM and bounded inference lag).
 * Native recognizer is released on the decode worker's finally block.
 */
/** Per-token emission timing reported by sherpa-onnx (relative seconds converted to ms). */
data class ParakeetTokenTiming(
    val token: String,
    val relativeMs: Long,
    val segmentMs: Long,
    // Raw native TDT duration value, deliberately not converted without calibration.
    val nativeDuration: Float?,
)

/** Full native Parakeet output, before overlap stitching, for opt-in diagnostic recording. */
data class ParakeetWindowDiagnostic(
    val index: Long,
    val rawText: String,
    val emittedText: String,
    val removedWords: Int,
    val queueWaitMs: Long,
    val decodeMs: Long,
    val stitchMode: String,
    val stitchReason: String,
    val segmentId: Long,
    val windowStartSample: Long,
    val windowEndSample: Long,
    val windowReadyAtMs: Long,
    val decodeStartAtMs: Long,
    val decodeFinishAtMs: Long,
    val tokenCount: Int,
    val timestampCount: Int,
    val tokenTimings: List<ParakeetTokenTiming>,
)

data class ParakeetProbeDiagnostic(
    val index: Long,
    val rawText: String,
    val provisionalText: String,
    val queueWaitMs: Long,
    val decodeMs: Long,
    val reason: String,
    val segmentId: Long,
    val windowStartSample: Long,
    val windowEndSample: Long,
)

/** Same recognizer instance; normal 3.2s decodes always take priority. */
private enum class DecodeKind { FINAL, PROBE }

class ParakeetOfflineEnglishEngine(
    private val identity: SessionIdentity,
    modelDirectory: File,
    private val nowMs: () -> Long,
    private val onUpdate: (AsrUpdate) -> Unit,
    private val onStats: (AsrRuntimeStats) -> Unit,
    private val onDecode: (ParakeetWindowDiagnostic) -> Unit = {},
    private val onFailure: (Throwable) -> Unit,
    private val preset: ParakeetWindowPreset = ParakeetWindowPreset.LEGACY,
    private val stitchMode: ParakeetStitchMode = ParakeetStitchMode.LEGACY,
    private val recordTokenTimings: Boolean = false,
    private val previewMode: ParakeetPreviewMode = ParakeetPreviewMode.OFF,
    private val onProbe: (ParakeetProbeDiagnostic) -> Unit = {},
) : AsrEngine {
    private data class PendingWindow(
        val window: ParakeetAudioChunker.Window,
        val generation: Long,
        val queuedAtMs: Long,
    )

    private val model: OfflineRecognizer
    private val chunks = Channel<PendingWindow>(capacity = 3)
    private val probes = Channel<PendingWindow>(capacity = 1)
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
    private val removedOverlapWords = AtomicLong()
    @Volatile private var inputPeakPermille = 0
    @Volatile private var inputRmsPermille = 0
    @Volatile private var lastDecodeMs = 0L
    @Volatile private var lastQueueWaitMs = 0L
    @Volatile private var lastWindowTurnaroundMs = 0L
    @Volatile private var lastDecoderExcerpt = ""
    @Volatile private var lastEmittedExcerpt = ""
    @Volatile private var lastRemovedOverlapWords = 0
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
        val legacyStitcher = ParakeetOverlapStitcher()
        val guardedStitcher = ParakeetGuardedOverlapStitcher()
        var workerGeneration = generation
        var utterance = 0L
        var lastFullRaw = ""
        var lastFinalWindow = -1L
        try {
            while (true) {
                val work = select<Pair<DecodeKind, PendingWindow>?> {
                    // Biased select: when both are ready, the complete window wins.
                    chunks.onReceiveCatching { it.getOrNull()?.let { item ->
                        DecodeKind.FINAL to item
                    } }
                    probes.onReceiveCatching { it.getOrNull()?.let { item ->
                        DecodeKind.PROBE to item
                    } }
                } ?: break
                val (kind, pending) = work
                val wave = pending.window.samples
                try {
                    if (pending.generation != generation) continue
                    if (workerGeneration != pending.generation) {
                        legacyStitcher.reset()
                        guardedStitcher.reset()
                        lastFullRaw = ""
                        lastFinalWindow = -1L
                        workerGeneration = pending.generation
                    }
                    if (kind == DecodeKind.PROBE) {
                        val queueWait = (nowMs() - pending.queuedAtMs).coerceAtLeast(0)
                        if (previewMode == ParakeetPreviewMode.OFF ||
                            pending.window.index <= lastFinalWindow ||
                            queueWait > 800L) {
                            onProbe(ParakeetProbeDiagnostic(
                                pending.window.index, "", "", queueWait, 0L,
                                "skipped-stale-or-busy",
                                pending.window.segmentId,
                                pending.window.startSample, pending.window.endSample))
                            continue
                        }
                        val start = nowMs()
                        val stream = model.createStream()
                        val raw = try {
                            stream.acceptWaveform(wave, 16000)
                            model.decode(stream)
                            model.getResult(stream).text.trim()
                        } finally {
                            stream.release()
                        }
                        val decodeMs = (nowMs() - start).coerceAtLeast(0)
                        if (pending.generation != generation ||
                            pending.window.index <= lastFinalWindow) continue
                        // Never mutate the full-window stitcher with provisional text.
                        val candidate = if (lastFullRaw.isBlank()) raw else
                            ParakeetOverlapStitcher().apply { append(lastFullRaw) }.append(raw)
                        val wordCount = candidate.split(Regex("\\s+"))
                            .count(String::isNotBlank)
                        val safe = candidate.takeIf { wordCount >= 2 }.orEmpty()
                        onProbe(ParakeetProbeDiagnostic(
                            pending.window.index, raw, safe, queueWait, decodeMs,
                            if (safe.isNotBlank()) "provisional" else "short-or-overlap",
                            pending.window.segmentId,
                            pending.window.startSample, pending.window.endSample))
                        if (safe.isNotBlank()) {
                            onUpdate(AsrUpdate(identity.sessionId, identity.audioEpoch,
                                pending.window.index, 0L, safe, false, nowMs()))
                        }
                        continue
                    }
                    val begin = nowMs()
                    lastQueueWaitMs = (begin - pending.queuedAtMs).coerceAtLeast(0)
                    val stream = model.createStream()
                    val result = try {
                        stream.acceptWaveform(wave, 16000)
                        model.decode(stream)
                        model.getResult(stream)
                    } finally {
                        stream.release()
                    }
                    val text = result.text.trim()
                    val tokenTimings = if (recordTokenTimings) {
                        val count = minOf(result.tokens.size, result.timestamps.size)
                        buildList {
                            for (i in 0 until count) {
                                val seconds = result.timestamps[i]
                                if (!seconds.isFinite() || seconds < 0f ||
                                    seconds > wave.size / 16000f + 1f) continue
                                val localMs = (seconds.toDouble() * 1000).toLong()
                                val duration = result.durations.getOrNull(i)
                                    ?.takeIf { it.isFinite() && it >= 0f }
                                add(ParakeetTokenTiming(
                                    token = result.tokens[i],
                                    relativeMs = localMs,
                                    segmentMs = pending.window.startSample * 1000L / 16000L + localMs,
                                    nativeDuration = duration,
                                ))
                            }
                        }
                    } else emptyList()
                    val decodeFinishAtMs = nowMs()
                    val elapsed = (decodeFinishAtMs - begin).coerceAtLeast(0)
                    // A reset/gap during native decode invalidates that result.
                    if (pending.generation != generation) continue
                    lastFullRaw = text
                    lastFinalWindow = pending.window.index
                    lastDecodeMs = elapsed
                    lastWindowTurnaroundMs = (nowMs() - pending.queuedAtMs).coerceAtLeast(0)
                    decoded.incrementAndGet()
                    if (text.isBlank()) emptyResults.incrementAndGet()
                    val merged = when (stitchMode) {
                        ParakeetStitchMode.LEGACY -> legacyStitcher.append(text)
                        ParakeetStitchMode.GUARDED ->
                            guardedStitcher.append(text, pending.window.index)
                    }
                    val removedWords = when (stitchMode) {
                        ParakeetStitchMode.LEGACY -> legacyStitcher.lastDuplicateWords
                        ParakeetStitchMode.GUARDED -> guardedStitcher.lastDuplicateWords
                    }
                    val stitchReason = when (stitchMode) {
                        ParakeetStitchMode.LEGACY -> "legacy-exact-suffix-prefix"
                        ParakeetStitchMode.GUARDED -> guardedStitcher.lastReason
                    }
                    lastRemovedOverlapWords = removedWords
                    removedOverlapWords.addAndGet(removedWords.toLong())
                    lastDecoderExcerpt = text.takeLast(160)
                    lastEmittedExcerpt = merged.takeLast(160)
                    onDecode(ParakeetWindowDiagnostic(
                        index = pending.window.index,
                        rawText = text,
                        emittedText = merged,
                        removedWords = removedWords,
                        queueWaitMs = lastQueueWaitMs,
                        decodeMs = elapsed,
                        stitchMode = stitchMode.wireId,
                        stitchReason = stitchReason,
                        segmentId = pending.window.segmentId,
                        windowStartSample = pending.window.startSample,
                        windowEndSample = pending.window.endSample,
                        windowReadyAtMs = pending.queuedAtMs,
                        decodeStartAtMs = begin,
                        decodeFinishAtMs = decodeFinishAtMs,
                        tokenCount = if (recordTokenTimings) result.tokens.size else 0,
                        timestampCount = if (recordTokenTimings) result.timestamps.size else 0,
                        tokenTimings = tokenTimings,
                    ))
                    if (text.isNotBlank() && merged.isBlank()) overlapOnlyResults.incrementAndGet()
                    if (merged.isNotBlank() || previewMode == ParakeetPreviewMode.EARLY) {
                        // In preview mode each window has a stable utterance id so
                        // the final can replace/cancel its own early hypothesis.
                        val id = if (previewMode == ParakeetPreviewMode.EARLY)
                            pending.window.index else utterance++
                        onUpdate(AsrUpdate(identity.sessionId, identity.audioEpoch,
                            id, 1L, merged, true, nowMs()))
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
            while (true) {
                val next = probes.tryReceive().getOrNull() ?: break
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
        if (previewMode == ParakeetPreviewMode.EARLY) {
            chunker.appendWithPreview(samples, 1600,
                previewReady = { offerProbe(it) },
                ready = { offer(it) })
        } else {
            chunker.append(samples) { offer(it) }
        }
        if (count % 25L == 0L) publishStats()
    }

    private fun offerProbe(window: ParakeetAudioChunker.Window) {
        if (!probes.trySend(PendingWindow(window, generation, nowMs())).isSuccess) {
            window.samples.fill(0f) // Best-effort probe must never pressure full decoder.
        }
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
            overlapMs = preset.overlapMs,
            lastDecoderExcerpt = lastDecoderExcerpt,
            lastEmittedExcerpt = lastEmittedExcerpt,
            lastRemovedOverlapWords = lastRemovedOverlapWords,
            totalRemovedOverlapWords = removedOverlapWords.get()))
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
        probes.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++ // invalidate any decode still in flight
        chunker.reset()
        chunks.cancel()
        probes.cancel()
        worker.cancel() // JNI finishes before worker's finally releases model
        scope.cancel()
    }
}
