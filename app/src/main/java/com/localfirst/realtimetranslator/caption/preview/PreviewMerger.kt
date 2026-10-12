package com.localfirst.realtimetranslator.caption.preview

/**
 * R3.12 Partial ASR merger.
 *
 * Keeps a rolling ASR history and exposes only tokens that are stable enough
 * for adaptive early captions. This prevents flicker when ASR rewrites partials.
 */
class PreviewMerger {

    private var previousWords: List<String> = emptyList()

    fun merge(partialText: String, confidence: Float): String {
        val words = tokenize(partialText)
        if (words.isEmpty()) return ""

        val common = longestCommonPrefix(previousWords, words)
        previousWords = words

        return when {
            confidence >= 0.8f -> words.joinToString(" ")
            common.size >= 2 -> common.joinToString(" ")
            else -> safePrefix(words).joinToString(" ")
        }
    }

    fun reset() {
        previousWords = emptyList()
    }

    private fun tokenize(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun longestCommonPrefix(a: List<String>, b: List<String>): List<String> {
        val size = minOf(a.size, b.size)
        val result = mutableListOf<String>()
        for (i in 0 until size) {
            if (a[i].equals(b[i], ignoreCase = true)) result += a[i]
            else break
        }
        return result
    }

    private fun safePrefix(words: List<String>): List<String> {
        if (words.size <= 2) return words
        return words.dropLast(1)
    }
}
