package com.localfirst.realtimetranslator.model

/**
 * R3.3 display-only scheduler. Never uses takeLast() to silently skip recognized
 * stable words. A word is stable after it occurs at the same position in two
 * successive partial hypotheses; Final flushes the rest of that utterance.
 *
 * The native ASR and full raw transcripts are not modified. Partials can still
 * be revised by the recognizer, so "stable" is not the same as linguistically
 * guaranteed correct. Revisions to already queued words are reported.
 */
class OrderedCaptionQueue(
    private val maxChunkCharacters: Int = 60,
    private val maxChunkWords: Int = 9,
    private val maxQueuedChunks: Int = 48,
    private val standardHoldMs: Long = 950,
) {
    init {
        require(maxChunkCharacters >= 16 && maxChunkWords >= 2)
        require(maxQueuedChunks >= 2 && standardHoldMs > 0)
    }

    private val pending = ArrayDeque<String>()
    private var lastUtteranceId: Long? = null
    private var previousWords = emptyList<String>()
    private var committedWords = emptyList<String>()
    private var current: String = ""
    private var shownAtMs: Long = -1
    private var lastChangedAtMs: Long = -1

    var displayedChunks: Int = 0
        private set
    var queueOverflows: Int = 0
        private set
    var revisedStablePrefixes: Int = 0
        private set
    val queueDepth: Int get() = pending.size
    val visibleText: String get() = current
    val visibleAtMs: Long get() = lastChangedAtMs

    fun reset() {
        pending.clear()
        lastUtteranceId = null
        previousWords = emptyList()
        committedWords = emptyList()
        current = ""
        shownAtMs = -1
        lastChangedAtMs = -1
        displayedChunks = 0
        queueOverflows = 0
        revisedStablePrefixes = 0
    }

    /** Flush queued captions on audio gaps: hypotheses spanning missing PCM are unreliable. */
    fun gap() {
        pending.clear()
        previousWords = emptyList()
        committedWords = emptyList()
        lastUtteranceId = null
        current = ""
        shownAtMs = -1
        lastChangedAtMs = -1
    }

    fun ingest(update: AsrUpdate) {
        if (lastUtteranceId != update.utteranceId) {
            lastUtteranceId = update.utteranceId
            previousWords = emptyList()
            committedWords = emptyList()
        }

        val currentWords = update.text.trim().split(Regex("\\s+"))
            .filter(String::isNotBlank)
        val shared = commonPrefix(previousWords, currentWords)
        val safeCount = if (update.isFinal) currentWords.size else shared

        // An ASR revision can retroactively change an already confirmed prefix.
        // Never pretend that it is the same set of words or silently rewrite
        // already visible historical captions.
        val confirmedPrefix = commonPrefix(committedWords, currentWords)
        if (confirmedPrefix < committedWords.size) revisedStablePrefixes++

        if (safeCount > committedWords.size) {
            appendWords(currentWords.subList(committedWords.size, safeCount), update.elapsedRealtimeMs)
        }
        // Only the aligned shared prefix is considered newly verified.
        if (safeCount >= committedWords.size) {
            committedWords = currentWords.take(safeCount)
        }
        previousWords = currentWords
        advance(update.elapsedRealtimeMs)
    }

    /** Must be called from the foreground service, even while ASR emits no text. */
    fun advance(nowMs: Long): Boolean {
        if (pending.isEmpty()) return false
        val waiting = if (pending.size >= 8) 400L
            else if (pending.size >= 4) 650L
            else standardHoldMs
        if (current.isNotBlank() && shownAtMs >= 0 &&
            nowMs >= shownAtMs && nowMs - shownAtMs < waiting) return false

        current = pending.removeFirst()
        shownAtMs = nowMs
        lastChangedAtMs = nowMs
        displayedChunks++
        return true
    }

    private fun appendWords(words: List<String>, nowMs: Long) {
        if (words.isEmpty()) return
        for (word in words) {
            // Merge fresh words into an undisplayed chunk to avoid a queue of
            // single-word captions when ASR confirms one word at a time.
            val last = pending.lastOrNull()
            if (last != null && canAppend(last, word)) {
                pending.removeLast()
                pending.addLast("$last $word")
                continue
            }
            // First words may be shown promptly, without freezing the next
            // few words into separate 950ms screens.
            if (pending.isEmpty() && current.isNotBlank() && shownAtMs >= 0 &&
                nowMs >= shownAtMs && nowMs - shownAtMs < 320 &&
                canAppend(current, word)) {
                current = "$current $word"
                lastChangedAtMs = nowMs
                continue
            }
            if (pending.size >= maxQueuedChunks) queueOverflows++
            else pending.addLast(word)
        }
    }

    private fun canAppend(existing: String, word: String): Boolean =
        existing.length + 1 + word.length <= maxChunkCharacters &&
            existing.split(' ').size < maxChunkWords

    private fun commonPrefix(a: List<String>, b: List<String>): Int {
        var count = 0
        while (count < a.size && count < b.size &&
            a[count].equals(b[count], ignoreCase = true)) count++
        return count
    }
}
