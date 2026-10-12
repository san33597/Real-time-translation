package com.localfirst.realtimetranslator.caption.preview

/**
 * R3.12 Adaptive Preview Engine.
 *
 * Converts unstable ASR partial results into a safer early caption.
 * This first version intentionally avoids model changes and only improves
 * the caption timing layer.
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
            stablePrefix = stablePrefix(partialText),
            confidence = confidence,
            speechRate = speechRate,
            createdAt = now
        )
    }

    /**
     * Keeps a conservative prefix that is less likely to be rewritten.
     * Later versions can replace this with ASR token timestamps.
     */
    private fun stablePrefix(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 2) return text.trim()

        return words.dropLast(1).joinToString(" ")
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
