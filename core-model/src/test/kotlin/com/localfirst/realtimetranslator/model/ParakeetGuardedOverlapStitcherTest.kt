package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class ParakeetGuardedOverlapStitcherTest {
    @Test fun preservesCompleteOneAndTwoWordRepliesInsteadOfSilentlyDropping() {
        val stitcher = ParakeetGuardedOverlapStitcher()
        assertEquals("Can I come down? No.", stitcher.append("Can I come down? No.", 0))
        assertEquals("No.", stitcher.append("No.", 1))
        assertEquals(0, stitcher.lastDuplicateWords)
        assertEquals("short-complete-match-preserved", stitcher.lastReason)
        assertEquals("Firefly people?", stitcher.append("Firefly people?", 2))
        assertEquals("Firefly people?", stitcher.append("Firefly people?", 3))
        assertEquals(0, stitcher.lastDuplicateWords)
    }

    @Test fun stillRemovesMultiWordSuffixPrefixOnConsecutiveWindows() {
        val stitcher = ParakeetGuardedOverlapStitcher()
        assertEquals("I need to tell you where", stitcher.append("I need to tell you where", 12))
        assertEquals("we are", stitcher.append("you where we are", 13))
        assertEquals(2, stitcher.lastDuplicateWords)
        assertTrue(stitcher.lastReason.startsWith("adjacent-suffix-prefix"))
    }

    @Test fun supportsConservativeKnownHonorificNormalization() {
        val stitcher = ParakeetGuardedOverlapStitcher()
        assertEquals("I met Dr. Newman", stitcher.append("I met Dr. Newman", 0))
        assertEquals("today", stitcher.append("Doctor Newman today", 1))
        assertEquals(2, stitcher.lastDuplicateWords)
    }

    @Test fun blankWindowsAndMissingIndicesDoNotDeduplicateStaleSpeech() {
        val stitcher = ParakeetGuardedOverlapStitcher()
        assertEquals("AND THAT'S YOUR BIGGEST", stitcher.append("AND THAT'S YOUR BIGGEST", 3))
        assertEquals("", stitcher.append("", 4))
        assertEquals("blank-reset", stitcher.lastReason)
        assertEquals("AND THAT'S YOUR BIGGEST", stitcher.append("AND THAT'S YOUR BIGGEST", 5))
        assertEquals(0, stitcher.lastDuplicateWords)
        assertEquals("A LONG PHRASE", stitcher.append("A LONG PHRASE", 10))
        assertEquals(0, stitcher.lastDuplicateWords)
        assertEquals("disconnected-or-first-window", stitcher.lastReason)
    }

    @Test fun punctuationOnlyCannotActAsDuplicateWord() {
        val stitcher = ParakeetGuardedOverlapStitcher()
        assertEquals("...", stitcher.append("...", 0))
        assertEquals("... next", stitcher.append("... next", 1))
        assertEquals(0, stitcher.lastDuplicateWords)
    }

    @Test fun bothModesHaveExplicitWireIdsAndLegacySafeFallback() {
        assertEquals(ParakeetStitchMode.LEGACY, ParakeetStitchMode.fromWire(null))
        assertEquals(ParakeetStitchMode.LEGACY, ParakeetStitchMode.fromWire("???"))
        assertEquals(ParakeetStitchMode.LEGACY, ParakeetStitchMode.fromWire("legacy-exact"))
        assertEquals(ParakeetStitchMode.GUARDED, ParakeetStitchMode.fromWire("guarded-r39"))
        val legacy = ParakeetOverlapStitcher()
        assertEquals("No.", legacy.append("No."))
        assertEquals("", legacy.append("No."))
    }

    @Test fun resetClearsHistoryBetweenSessions() {
        val stitcher = ParakeetGuardedOverlapStitcher()
        stitcher.append("HELLO YOU AND ME", 2)
        stitcher.reset()
        assertEquals("AND ME THERE", stitcher.append("AND ME THERE", 3))
        assertEquals(0, stitcher.lastDuplicateWords)
    }
}
