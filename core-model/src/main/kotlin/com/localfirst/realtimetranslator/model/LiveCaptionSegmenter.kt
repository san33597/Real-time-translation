package com.localfirst.realtimetranslator.model

/**
 * Produces a short DISPLAY window from a growing sherpa-onnx hypothesis.
 *
 * sherpa may return one long, punctuation-free hypothesis even when a person starts
 * another sentence. Never reset the recognizer or discard ASR words for UI purposes.
 * Instead split visually after a meaningful recognition pause or a bounded word count.
 * The entire raw hypothesis/final remains in EnglishSubtitleState for later processing.
 */
class LiveCaptionSegmenter(
    private val pauseMs: Long = 1_100,
    private val maxWords: Int = 13,
    private val carryWords: Int = 3,
    private val maxCharacters: Int = 92,
) {
    init {
        require(pauseMs >= 0)
        require(maxWords >= 4)
        require(carryWords in 1 until maxWords)
        require(maxCharacters >= 24)
    }

    private var lastUtteranceId: Long? = null
    private var lastHypothesis = ""
    private var lastWordCount = 0
    private var lastTextChangeMs: Long? = null
    private var startWord = 0

    fun reset() {
        lastUtteranceId = null
        lastHypothesis = ""
        lastWordCount = 0
        lastTextChangeMs = null
        startWord = 0
    }

    fun project(utteranceId: Long, text: String, timeMs: Long): String {
        if (utteranceId != lastUtteranceId) {
            reset()
            lastUtteranceId = utteranceId
        }
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return ""
        val textChanged = text != lastHypothesis
        // A new word after a pause most often means the speaker has begun the next
        // phrase. This is a visual boundary, NOT an ASR final/linguistic sentence.
        val prevChange = lastTextChangeMs
        if (textChanged && prevChange != null && timeMs >= prevChange &&
            timeMs - prevChange >= pauseMs && words.size > lastWordCount &&
            words.size > startWord) {
            startWord = lastWordCount
        }
        // ASR can revise earlier words, shorten the hypothesis, or re-tokenize.
        // Clamp rather than crashing or duplicating long spans.
        if (words.size <= startWord) startWord = (words.size - 1).coerceAtLeast(0)
        if (words.size - startWord > maxWords) {
            startWord = (words.size - carryWords).coerceAtLeast(0)
        }
        val current = words.drop(startWord).joinToString(" ")
        if (textChanged) {
            lastHypothesis = text
            lastWordCount = words.size
            lastTextChangeMs = timeMs
        }
        return tailAtWordBoundary(current, maxCharacters)
    }

    private fun tailAtWordBoundary(source: String, limit: Int): String {
        if (source.length <= limit) return source
        val tail = source.takeLast(limit)
        val wordEnd = tail.indexOf(' ')
        return if (wordEnd >= 0 && wordEnd < tail.lastIndex)
            tail.substring(wordEnd + 1).trimStart() else tail
    }
}
