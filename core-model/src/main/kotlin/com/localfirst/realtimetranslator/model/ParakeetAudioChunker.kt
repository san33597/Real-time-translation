package com.localfirst.realtimetranslator.model

/**
 * Short overlapping inference windows. All PCM is transient RAM; the caller
 * must deliver completed windows to a DIFFERENT decoder worker, not decode on
 * the AudioRecord consumption thread. Fixed windows work with music/noise,
 * unlike energy-only VAD, and 640ms overlap protects spoken window edges.
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

    data class Window(val index: Long, val samples: FloatArray)

    fun append(frame: ShortArray, ready: (Window) -> Unit) {
        var offset = 0
        while (offset < frame.size) {
            val amount = minOf(length - count, frame.size - offset)
            System.arraycopy(frame, offset, buffer, count, amount)
            count += amount
            offset += amount
            if (count == length) {
                ready(Window(seq++, toFloatArray(count)))
                if (overlap > 0) System.arraycopy(buffer, length - overlap, buffer, 0, overlap)
                count = overlap
            }
        }
    }

    /** Flush the final meaningful voice fragment only when stopping naturally. */
    fun finish(ready: (Window) -> Unit) {
        if (count >= minimumTail) ready(Window(seq++, toFloatArray(count)))
        reset()
    }

    /** Missing input invalidates current window; do not decode a discontinuous sample. */
    fun reset() {
        buffer.fill(0)
        count = 0
    }

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

    fun reset() { previous = emptyList() }

    fun append(result: String): String {
        val tokens = result.trim().split(Regex("\\s+")).filter(String::isNotBlank)
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
        return tokens.drop(duplicate).joinToString(" ")
    }

    private fun normalize(token: String): String =
        token.lowercase().filter(Char::isLetterOrDigit)
}
