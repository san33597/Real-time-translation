package com.localfirst.realtimetranslator.model

/**
 * Short overlapping inference windows. All PCM is transient RAM; the caller
 * must deliver completed windows to a DIFFERENT decoder worker, not decode on
 * the AudioRecord consumption thread. Fixed windows work with music/noise,
 * unlike energy-only VAD. A per-session overlap preset protects spoken window edges.
 */
class ParakeetAudioChunker(
    private val sampleRate: Int = 16_000,
    private val windowMs: Int = 3_200,
    private val overlapMs: Int = 640,
    private val minimumTailMs: Int = 260,
) {
    init {
        require(sampleRate > 0 && windowMs >= 1000)
        require(overlapMs in 0 until windowMs / 2)
        require(minimumTailMs > 0 && minimumTailMs < windowMs)
    }

    private val length = sampleRate * windowMs / 1000
    private val overlap = sampleRate * overlapMs / 1000
    private val minimumTail = sampleRate * minimumTailMs / 1000
    private val buffer = ShortArray(length)
    private var count = 0
    private var seq = 0L
    // Sample position is scoped to an uninterrupted PCM segment. A gap increments
    // the segment id, so two different playback/capture segments never align.
    private var segmentId = 0L
    private var totalSegmentSamples = 0L
    private var previewIssued = false

    data class Window(
        val index: Long,
        val samples: FloatArray,
        val segmentId: Long = 0L,
        val startSample: Long = 0L,
        val endSample: Long = 0L,
    )

    fun append(frame: ShortArray, ready: (Window) -> Unit) {
        appendInternal(frame, null, 0, ready)
    }

    /**
     * Optional 1.6s probe before the full 3.2s window.
     * There is no extra PCM capture, and the full-window boundaries are unchanged.
     */
    fun appendWithPreview(
        frame: ShortArray,
        previewMs: Int = 1600,
        previewReady: (Window) -> Unit,
        ready: (Window) -> Unit,
    ) {
        require(previewMs > overlapMs && previewMs < windowMs)
        appendInternal(frame, previewReady, sampleRate * previewMs / 1000, ready)
    }

    private fun appendInternal(
        frame: ShortArray,
        previewReady: ((Window) -> Unit)?,
        previewSamples: Int,
        ready: (Window) -> Unit,
    ) {
        var offset = 0
        while (offset < frame.size) {
            val nextBoundary = if (!previewIssued && previewReady != null)
                previewSamples else length
            val amount = minOf(length - count, frame.size - offset,
                (nextBoundary - count).coerceAtLeast(0).takeIf { it > 0 } ?: (length - count))
            System.arraycopy(frame, offset, buffer, count, amount)
            count += amount
            offset += amount
            totalSegmentSamples += amount.toLong()
            if (!previewIssued && previewReady != null && count >= previewSamples) {
                previewIssued = true
                previewReady(makePreview(count))
            }
            if (count == length) {
                ready(makeWindow(count))
                if (overlap > 0) System.arraycopy(buffer, length - overlap, buffer, 0, overlap)
                count = overlap
                previewIssued = false
            }
        }
    }

    /** Flush the final meaningful voice fragment only when stopping naturally. */
    fun finish(ready: (Window) -> Unit) {
        if (count >= minimumTail) ready(makeWindow(count))
        reset()
    }

    /** Missing input invalidates current window; do not decode a discontinuous sample. */
    fun reset() {
        buffer.fill(0)
        count = 0
        previewIssued = false
        totalSegmentSamples = 0L
        segmentId++
    }

    private fun makePreview(size: Int): Window = Window(
        index = seq,
        samples = toFloatArray(size),
        segmentId = segmentId,
        startSample = totalSegmentSamples - size,
        endSample = totalSegmentSamples,
    )

    private fun makeWindow(size: Int): Window = Window(
        index = seq++,
        samples = toFloatArray(size),
        segmentId = segmentId,
        startSample = totalSegmentSamples - size,
        endSample = totalSegmentSamples,
    )

    private fun toFloatArray(size: Int): FloatArray =
        FloatArray(size) { i -> buffer[i] / 32768f }
}

/**
 * Overlapping windows may transcribe the same boundary twice.
 * Remove only an exact, same-order word overlap; never use tail-only display
 * truncation or guess words absent from the model output.
 */
class ParakeetOverlapStitcher(private val maxCompareWords: Int = 12) {
    init { require(maxCompareWords >= 2) }
    private var previous = emptyList<String>()
    /** Observability only; has no effect on deduplication decisions. */
    var lastDuplicateWords: Int = 0
        private set

    fun reset() {
        previous = emptyList()
        lastDuplicateWords = 0
    }

    fun append(result: String): String {
        val tokens = result.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        lastDuplicateWords = 0
        if (tokens.isEmpty()) return ""
        var duplicate = 0
        val possible = minOf(previous.size, tokens.size, maxCompareWords)
        for (size in possible downTo 1) {
            val suffix = previous.takeLast(size)
            val prefix = tokens.take(size)
            if (suffix.indices.all { normalize(suffix[it]) == normalize(prefix[it]) }) {
                duplicate = size
                break
            }
        }
        previous = tokens
        lastDuplicateWords = duplicate
        return tokens.drop(duplicate).joinToString(" ")
    }

    private fun normalize(token: String): String =
        token.lowercase().filter(Char::isLetterOrDigit)
}
