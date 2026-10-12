package com.localfirst.realtimetranslator.caption.preview

/**
 * R3.12 Adaptive Preview Engine.
 *
 * Converts unstable ASR partial results into early captions while reducing
 * subtitle flicker caused by partial rewrites.
 */
class AdaptivePreviewEngine {

    private val merger = PreviewMerger()

    fun createPreview(
        id: Long,
        partialText: String,
        confidence: Float,
        speechRate: Float,
        now: Long = System.currentTimeMillis()
    ): PreviewSegment {
        val merged = merger.merge(partialText, confidence)

        return PreviewSegment(
            id = id,
            partialText = partialText,
            stablePrefix = stablePrefix(merged, confidence, speechRate),
            confidence = confidence,
            speechRate = speechRate,
            createdAt = now
        )
    }

    fun reset() {
        merger.reset()
    }

    private fun stablePrefix(
        text: String,
        confidence: Float,
        speechRate: Float
    ): String {
        val words = text.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return ""
        if (confidence >= 0.85f) return words.joinToString(" ")
        if (words.size <= 2) return text.trim()

        val keepCount = when {
            confidence < 0.45f -> maxOf(1, words.size - 2)
            speechRate >= 2.0f -> words.size - 1
            else -> words.size - 1
        }

        return words.take(keepCount).joinToString(" ")
    }

    fun previewLeadMs(speechRate: Float): Long {
        return when {
            speechRate >= 3.0f -> 900L
            speechRate >= 2.0f -> 650L
            speechRate >= 1.0f -> 450L
            else -> 300L
        }
    }
}
