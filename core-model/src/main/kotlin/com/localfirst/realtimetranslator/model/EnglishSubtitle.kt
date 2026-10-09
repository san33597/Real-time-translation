package com.localfirst.realtimetranslator.model

/** Only recognized English text is allowed in R3; no translation or cloud provider. */
data class AsrUpdate(
    val sessionId: String,
    val audioEpoch: Long,
    val utteranceId: Long,
    val revision: Long,
    val text: String,
    val isFinal: Boolean,
    val elapsedRealtimeMs: Long,
)

data class EnglishSubtitle(
    val utteranceId: Long,
    val text: String,
)

data class EnglishSubtitleState(
    val sessionId: String? = null,
    val audioEpoch: Long = 0,
    val committed: List<EnglishSubtitle> = emptyList(),
    val partial: String = "",
    val activeUtteranceId: Long = 0,
) {
    val visibleText: String
        get() = (committed.map { it.text } + listOfNotNull(partial.takeIf(String::isNotBlank)))
            .joinToString("\n")
}

/**
 * One bounded, session-guarded subtitle projection. Finals are immutable. Partials
 * are coalesced for readability; a final is never throttled or overwritten by late ASR.
 */
class EnglishSubtitleCoordinator(
    private val maxFinalLines: Int = 3,
    private val partialIntervalMs: Long = 160,
) {
    init { require(maxFinalLines > 0 && partialIntervalMs >= 0) }
    var state = EnglishSubtitleState()
        private set
    private var lastRevision = -1L
    private var lastPartialAt = Long.MIN_VALUE
    private var lastFinalId = -1L

    @Synchronized fun begin(sessionId: String, audioEpoch: Long) {
        require(sessionId.isNotBlank() && audioEpoch >= 0)
        state = EnglishSubtitleState(sessionId, audioEpoch)
        lastRevision = -1
        lastPartialAt = Long.MIN_VALUE
        lastFinalId = -1
    }

    @Synchronized fun accept(update: AsrUpdate): Boolean {
        if (update.sessionId != state.sessionId || update.audioEpoch != state.audioEpoch ||
            update.utteranceId <= lastFinalId || update.utteranceId < state.activeUtteranceId ||
            update.revision < 0) return false

        val newUtterance = update.utteranceId > state.activeUtteranceId
        if (!newUtterance && update.revision <= lastRevision) return false
        val sanitized = update.text.replace(Regex("\\s+"), " ").trim().take(240)
        if (!update.isFinal && !newUtterance &&
            lastPartialAt != Long.MIN_VALUE && update.elapsedRealtimeMs >= lastPartialAt &&
            update.elapsedRealtimeMs - lastPartialAt < partialIntervalMs) return false

        if (newUtterance) {
            lastRevision = -1
            lastPartialAt = Long.MIN_VALUE
        }
        lastRevision = update.revision
        if (update.isFinal) {
            lastFinalId = update.utteranceId
            state = state.copy(
                activeUtteranceId = update.utteranceId + 1,
                committed = if (sanitized.isBlank()) state.committed else
                    (state.committed + EnglishSubtitle(update.utteranceId, sanitized))
                        .takeLast(maxFinalLines),
                partial = "",
            )
            lastRevision = -1
            lastPartialAt = Long.MIN_VALUE
        } else {
            lastPartialAt = update.elapsedRealtimeMs
            state = state.copy(activeUtteranceId = update.utteranceId, partial = sanitized)
        }
        return true
    }

    /** Audio gaps must not preserve a misleading unfinished hypothesis. */
    @Synchronized fun gap() {
        state = state.copy(partial = "")
        lastPartialAt = Long.MIN_VALUE
    }

    @Synchronized fun clear() {
        state = EnglishSubtitleState()
        lastRevision = -1
        lastFinalId = -1
        lastPartialAt = Long.MIN_VALUE
    }
}
