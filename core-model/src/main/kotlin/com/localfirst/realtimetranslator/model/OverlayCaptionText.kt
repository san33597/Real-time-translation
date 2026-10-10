package com.localfirst.realtimetranslator.model

/** A small subtitle-window projection; never scroll the whole ASR transcript in the overlay. */
object OverlayCaptionText {
    /** Characters are an additional bound; the Android view itself is limited to two lines. */
    fun latest(state: EnglishSubtitleState, maxCharacters: Int = 64): String {
        require(maxCharacters >= 16)
        val source = if (state.previewCaption.isNotBlank()) {
            state.previewCaption.trim()
        } else if (state.sessionId != null || state.captionUpdatedAtMs >= 0L) {
            // After an audio gap this is intentionally blank; never revive an older final.
            state.displayCaption.trim()
        } else {
            // Backward compatibility for callers constructing a state without live projection.
            (state.partial.takeIf(String::isNotBlank)
                ?: state.committed.lastOrNull()?.text.orEmpty()).trim()
        }
        if (source.length <= maxCharacters) return source
        val tail = source.takeLast(maxCharacters)
        val space = tail.indexOf(' ')
        return if (space in 0 until tail.lastIndex) tail.substring(space + 1).trimStart()
        else tail.trimStart()
    }

    /** Two bounded display fragments in spoken order; raw ASR remains untouched. */
    fun rows(state: EnglishSubtitleState, twoLines: Boolean): Pair<String, String> {
        val newest = latest(state)
        return if (twoLines && newest.isNotBlank()) {
            // A provisional hypothesis replaces the current row temporarily;
            // the last CONFIRMED caption remains above it.
            (if (state.previewCaption.isNotBlank()) state.displayCaption.trim()
                else state.previousCaption.trim()) to newest
        } else {
            "" to newest
        }
    }

    /** Uses Android's monotonic elapsedRealtime clock, not wall-clock timestamps. */
    fun isFresh(state: EnglishSubtitleState, nowMs: Long, holdMs: Long = 3_300L): Boolean {
        require(holdMs >= 0L)
        val lastUpdate = if (state.previewCaption.isNotBlank())
            state.previewUpdatedAtMs else state.captionUpdatedAtMs
        return lastUpdate >= 0 && nowMs >= lastUpdate && nowMs - lastUpdate <= holdMs
    }
}

/** Never draw over our app or when there is no active running capture session. */
object OverlayVisibility {
    fun shouldDisplay(state: SessionState, userEnabled: Boolean, appVisible: Boolean): Boolean =
        userEnabled && !appVisible && state is SessionState.Running
}
