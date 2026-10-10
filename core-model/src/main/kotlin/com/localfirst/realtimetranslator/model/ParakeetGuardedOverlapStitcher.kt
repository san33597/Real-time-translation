package com.localfirst.realtimetranslator.model

/**
 * Conservative R3.9 alternative to the unchanged ParakeetOverlapStitcher.
 *
 * Only compare immediate consecutive, nonblank decoder windows. Never deduplicate
 * across missing windows or a blank window. Treat a fully repeated 1-2 word
 * hypothesis as ambiguous: preserve it rather than discard a possible real
 * repeated reply ("No.", "Yeah.", "Firefly people?"). That deliberately trades
 * possible duplicate text for fewer irreversible short-utterance omissions.
 *
 * Without word timestamps these are heuristics, not a claim that any repeated
 * phrase is or is not genuinely spoken. Always retain the legacy A/B option.
 */
class ParakeetGuardedOverlapStitcher(private val maxCompareWords: Int = 12) {
    init { require(maxCompareWords >= 2) }

    private var previous = emptyList<String>()
    private var previousIndex: Long? = null

    var lastDuplicateWords: Int = 0
        private set
    var lastReason: String = "initial"
        private set

    fun reset() {
        previous = emptyList()
        previousIndex = null
        lastDuplicateWords = 0
        lastReason = "reset"
    }

    fun append(result: String, windowIndex: Long): String {
        val tokens = result.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        lastDuplicateWords = 0
        if (tokens.isEmpty()) {
            previous = emptyList()
            previousIndex = null
            lastReason = "blank-reset"
            return ""
        }

        val adjacent = previousIndex?.let { windowIndex == it + 1L } ?: false
        var duplicate = 0
        lastReason = if (adjacent) "no-match" else "disconnected-or-first-window"

        if (adjacent) {
            val possible = minOf(previous.size, tokens.size, maxCompareWords)
            for (size in possible downTo 1) {
                val suffix = previous.takeLast(size)
                val prefix = tokens.take(size)
                if (suffix.indices.all { i ->
                        val a = normalize(suffix[i])
                        val b = normalize(prefix[i])
                        a.isNotEmpty() && a == b
                    }) {
                    duplicate = size
                    break
                }
            }
            if (duplicate == tokens.size && tokens.size <= 2) {
                duplicate = 0
                lastReason = "short-complete-match-preserved"
            } else if (duplicate > 0) {
                lastReason = "adjacent-suffix-prefix-${duplicate}-words"
            }
        }

        previous = tokens
        previousIndex = windowIndex
        lastDuplicateWords = duplicate
        return tokens.drop(duplicate).joinToString(" ")
    }

    private fun normalize(token: String): String {
        val clean = token.lowercase().filter(Char::isLetterOrDigit)
        return when (clean) {
            "dr" -> "doctor"
            "mr" -> "mister"
            "mrs" -> "missus"
            else -> clean
        }
    }
}
