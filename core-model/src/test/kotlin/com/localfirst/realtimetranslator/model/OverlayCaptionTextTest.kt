package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class OverlayCaptionTextTest {
    @Test fun usesLivePartialBeforePreviousFinal() {
        val state = EnglishSubtitleState(
            committed = listOf(EnglishSubtitle(0, "previously spoken")),
            partial = "currently speaking",
        )
        assertEquals("currently speaking", OverlayCaptionText.latest(state))
    }

    @Test fun longSpeechShowsNewestWords() {
        val source = "some past sentences ".repeat(30) + "the newest important words"
        val result = OverlayCaptionText.latest(EnglishSubtitleState(partial = source), 48)
        assertTrue(result.endsWith("the newest important words"))
        assertTrue(result.length <= 48)
    }

    @Test fun noOverlayWhileAppVisibleOrStoppedOrDisabled() {
        val request = SessionRequest(SessionIdentity("test"), AudioSource.SYSTEM)
        assertTrue(OverlayVisibility.shouldDisplay(SessionState.Running(request), true, false))
        assertFalse(OverlayVisibility.shouldDisplay(SessionState.Running(request), true, true))
        assertFalse(OverlayVisibility.shouldDisplay(SessionState.Running(request), false, false))
        assertFalse(OverlayVisibility.shouldDisplay(SessionState.Idle(), true, false))
        assertEquals("", OverlayCaptionText.latest(EnglishSubtitleState()))
    }
}
