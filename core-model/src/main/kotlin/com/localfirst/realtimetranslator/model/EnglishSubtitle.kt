package com.localfirst.realtimetranslator.model

/** Recognition output is local-only; no audio/transcript persistence or network calls. */
data class AsrUpdate(
    val sessionId: String,
    val audioEpoch: Long,
    val utteranceId: Long,
    val revision: Long,
    val text: String,
    val isFinal: Boolean,
    val elapsedRealtimeMs: Long,
)

data class EnglishSubtitle(val utteranceId: Long, val text: String)

data class AsrTrace(
    val elapsedRealtimeMs: Long,
    val utteranceId: Long,
    val revision: Long,
    val isFinal: Boolean,
    /** Bounded recent diagnostic excerpt, not a file or retained audio. */
    val rawExcerpt: String,
)

data class CaptionDiagnostics(
    /** Full most recent ASR text, independent of the two-line subtitle window. */
    val latestRaw: String = "",
    val recent: List<AsrTrace> = emptyList(),
    val partialUpdates: Long = 0,
    val finalUpdates: Long = 0,
    val queuedChunks: Int = 0,
    val displayedChunks: Int = 0,
    val queueOverflows: Int = 0,
    val correctedPrefixes: Int = 0,
    val lastCaptionWaitMs: Long = 0,
)

data class EnglishSubtitleState(
    val sessionId: String? = null,
    val audioEpoch: Long = 0,
    val committed: List<EnglishSubtitle> = emptyList(),
    val partial: String = "",
    val activeUtteranceId: Long = 0,
    val displayCaption: String = "",
    /** Prior fragment still visible in two-row overlay mode. */
    val previousCaption: String = "",
    val captionUpdatedAtMs: Long = -1L,
    val diagnostics: CaptionDiagnostics = CaptionDiagnostics(),
) {
    val visibleText: String
        get() = (committed.map { it.text } + listOfNotNull(partial.takeIf(String::isNotBlank)))
            .joinToString("\n")
}

/**
 * Single-session recognition coordinator. Every valid update reaches the scheduler
 * and diagnostic trace, even when the in-app partial view is throttled.
 *
 * Source hypotheses remain independent of the ordered visible caption queue:
 * R3.2's drop-to-latest-window algorithm is no longer called.
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
    private val scheduler = OrderedCaptionQueue()

    @Synchronized fun begin(sessionId: String, audioEpoch: Long) {
        require(sessionId.isNotBlank() && audioEpoch >= 0)
        state = EnglishSubtitleState(sessionId = sessionId, audioEpoch = audioEpoch)
        lastRevision = -1
        lastPartialAt = Long.MIN_VALUE
        lastFinalId = -1
        scheduler.reset()
    }

    @Synchronized fun accept(update: AsrUpdate): Boolean {
        if (update.sessionId != state.sessionId || update.audioEpoch != state.audioEpoch ||
            update.utteranceId <= lastFinalId || update.utteranceId < state.activeUtteranceId ||
            update.revision < 0) return false
        val newUtterance = update.utteranceId > state.activeUtteranceId
        if (!newUtterance && update.revision <= lastRevision) return false
        if (newUtterance) {
            lastRevision = -1L
            lastPartialAt = Long.MIN_VALUE
        }

        val sanitized = update.text.replace(Regex("\\s+"), " ").trim()
        scheduler.ingest(update.copy(text = sanitized))
        lastRevision = update.revision
        val old = state.diagnostics
        val trace = AsrTrace(update.elapsedRealtimeMs, update.utteranceId,
            update.revision, update.isFinal, sanitized.takeLast(320))
        val diagnostics = old.copy(
            latestRaw = sanitized,
            recent = (old.recent + trace).takeLast(12),
            partialUpdates = old.partialUpdates + if (update.isFinal) 0 else 1,
            finalUpdates = old.finalUpdates + if (update.isFinal) 1 else 0,
        )
        val displayState = displayDiagnostics(diagnostics)

        if (update.isFinal) {
            lastFinalId = update.utteranceId
            state = state.copy(
                activeUtteranceId = update.utteranceId + 1,
                committed = if (sanitized.isBlank()) state.committed else
                    (state.committed + EnglishSubtitle(update.utteranceId, sanitized))
                        .takeLast(maxFinalLines),
                partial = "",
                displayCaption = scheduler.visibleText,
                previousCaption = scheduler.previousText,
                captionUpdatedAtMs = scheduler.visibleAtMs,
                diagnostics = displayState,
            )
            lastRevision = -1L
            lastPartialAt = Long.MIN_VALUE
        } else {
            val canUpdatePartial = lastPartialAt == Long.MIN_VALUE ||
                update.elapsedRealtimeMs < lastPartialAt ||
                update.elapsedRealtimeMs - lastPartialAt >= partialIntervalMs
            if (canUpdatePartial) lastPartialAt = update.elapsedRealtimeMs
            state = state.copy(
                activeUtteranceId = update.utteranceId,
                partial = if (canUpdatePartial) sanitized else state.partial,
                displayCaption = scheduler.visibleText,
                previousCaption = scheduler.previousText,
                captionUpdatedAtMs = scheduler.visibleAtMs,
                diagnostics = displayState,
            )
        }
        return true
    }

    /** Called on the service main dispatcher, even between partial/final updates. */
    @Synchronized fun tick(nowMs: Long): Boolean {
        if (!scheduler.advance(nowMs)) return false
        state = state.copy(
            displayCaption = scheduler.visibleText,
            previousCaption = scheduler.previousText,
            captionUpdatedAtMs = scheduler.visibleAtMs,
            diagnostics = displayDiagnostics(state.diagnostics),
        )
        return true
    }

    private fun displayDiagnostics(existing: CaptionDiagnostics) = existing.copy(
        queuedChunks = scheduler.queueDepth,
        displayedChunks = scheduler.displayedChunks,
        queueOverflows = scheduler.queueOverflows,
        correctedPrefixes = scheduler.revisedStablePrefixes,
        lastCaptionWaitMs = scheduler.lastCaptionWaitMs,
    )

    @Synchronized fun gap() {
        scheduler.gap()
        state = state.copy(partial = "", displayCaption = "", previousCaption = "",
            captionUpdatedAtMs = 0L,
            diagnostics = displayDiagnostics(state.diagnostics))
        lastPartialAt = Long.MIN_VALUE
    }

    @Synchronized fun clear() {
        scheduler.reset()
        state = EnglishSubtitleState()
        lastRevision = -1L
        lastFinalId = -1L
        lastPartialAt = Long.MIN_VALUE
    }
}
