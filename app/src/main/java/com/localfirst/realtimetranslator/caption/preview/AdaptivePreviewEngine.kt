package com.localfirst.realtimetranslator.caption.preview

/**
 * R3.12 Adaptive Preview Engine.
 *
 * Converts unstable ASR partial results into safer early captions.
 * This layer focuses on reducing subtitle lag while avoiding aggressive
 * display of tokens that are likely to be rewritten by ASR.
 */
class AdaptivePreviewEngine {

    fun createPreview(
        id: Long,
        partialText: String,
        confidence: Float,
        speechRate: Float,
        now: Long = System.currentTimeMillis()
    ): PreviewSegment {
        return PreviewSegment(
            id = id,
            partialText = partialText,
            stablePrefix = stablePrefix(partialText, confidence, speechRate),
            confidence = confidence,
            speechRate = speechRate,
            createdAt = now
        )
    }

    /**
     * Extracts the portion suitable for early rendering.
     *
     * Fast speech needs earlier feedback, while low confidence ASR output
     * needs a more conservative prefix to avoid visible corrections.
     */
    private fun stablePrefix(
        text: String,
        confidence: Float,
        speechRate: Float
    ): String {
        val words = text.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return ""
        if (words.size <= 2) return text.trim()

        val keepCount = when {
            confidence < 0.45f -> maxOf(1, words.size - 2)
            speechRate >= 3.0f -> words.size - 1
            speechRate >= 2.0f -> words.size - 1
            else -> words.size - 1
        }

        return words.take(keepCount).joinToString(" ")
    }

    /**
     * Dynamic preview lead time.
     * Faster speech gets slightly earlier preview.
     */
    fun previewLeadMs(speechRate: Float): Long {
        return when {
            speechRate >= 3.0f -> 900L
            speechRate >= 2.0f -> 650L
            speechRate >= 1.0f -> 450L
            else -> 300L
        }
    }
}
