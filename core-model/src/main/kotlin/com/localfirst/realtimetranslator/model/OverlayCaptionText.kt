package com.localfirst.realtimetranslator.model

/** The floating window renders recent speech, not a transcript accumulated across minutes. */
object OverlayCaptionText {
    fun latest(state: EnglishSubtitleState, maxCharacters: Int = 160): String {
        require(maxCharacters >= 16)
        val source = (state.partial.takeIf(String::isNotBlank)
            ?: state.committed.lastOrNull()?.text.orEmpty()).trim()
        if (source.length <= maxCharacters) return source
        val tail = source.takeLast(maxCharacters)
        val space = tail.indexOf(' ')
        return if (space >= 0 && space < tail.lastIndex) tail.substring(space + 1).trimStart()
        else tail.trimStart()
    }
}

/** Never draw over our app or when there is no active running capture session. */
object OverlayVisibility {
    fun shouldDisplay(state: SessionState, userEnabled: Boolean, appVisible: Boolean): Boolean =
        userEnabled && !appVisible && state is SessionState.Running
}
