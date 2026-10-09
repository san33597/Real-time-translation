package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class LiveCaptionSegmenterTest {
    @Test fun startsNewDisplayFragmentWhenNewWordsArriveAfterPause() {
        val projector = LiveCaptionSegmenter()
        assertEquals("SOMEONE WALKED INTO", projector.project(0, "SOMEONE WALKED INTO", 100))
        assertEquals("A QUIET GARDEN", projector.project(0,
            "SOMEONE WALKED INTO A QUIET GARDEN", 1500))
        // Old recognition remains in EnglishSubtitleState; the window only moves.
    }

    @Test fun sameWordCountCorrectionDoesNotLookLikeNextSentence() {
        val projector = LiveCaptionSegmenter()
        projector.project(0, "MY DRAWING SHOW", 0)
        assertEquals("MY DRAWING SHOWS",
            projector.project(0, "MY DRAWING SHOWS", 1700))
    }

    @Test fun continuousSpeechIsBoundedWithoutDiscardingSource() {
        val projector = LiveCaptionSegmenter(maxWords = 9, carryWords = 2, maxCharacters = 72)
        val words = (1..60).map { "WORD$it" }
        var latest = ""
        words.forEachIndexed { i, _ ->
            latest = projector.project(1, words.take(i + 1).joinToString(" "), i * 100L)
            assertTrue(latest.length <= 72)
            assertTrue(latest.split(" ").size <= 9)
        }
        assertTrue(latest.contains("WORD60"))
        assertFalse(latest.contains("WORD1 "))
    }

    @Test fun nativeEndpointStartsFreshVisualFragment() {
        val projector = LiveCaptionSegmenter()
        projector.project(5, "PREVIOUS WORDS", 0)
        assertEquals("NEW WORDS", projector.project(6, "NEW WORDS", 250))
    }

    @Test fun resetAfterAudioGapForgetsPreviousSentence() {
        val projector = LiveCaptionSegmenter()
        projector.project(0, "OLD OLD WORDS", 0)
        projector.reset()
        assertEquals("AFTER GAP", projector.project(1, "AFTER GAP", 50))
    }

    @Test fun textRevisionsAndEmptyHypothesesCannotCrash() {
        val projector = LiveCaptionSegmenter()
        projector.project(1, "HELLO THE LONG WORLD", 0)
        projector.project(1, "HELLO", 100)
        assertEquals("HELLO", projector.project(1, "HELLO", 120))
        assertEquals("", projector.project(1, "", 160))
    }
}
