package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class EnglishSubtitleCoordinatorTest {
    private fun event(id: Long, rev: Long, words: String, final: Boolean, ms: Long,
        session: String = "s", epoch: Long = 0) =
        AsrUpdate(session, epoch, id, rev, words, final, ms)

    @Test fun finalsRemainWhilePartialChanges() {
        val c = EnglishSubtitleCoordinator()
        c.begin("s", 0)
        assertTrue(c.accept(event(0, 0, "good morn", false, 0)))
        assertFalse(c.accept(event(0, 1, "good morning", false, 100)))
        assertTrue(c.accept(event(0, 2, "good morning", true, 110)))
        assertTrue(c.accept(event(1, 0, "everyone", false, 300)))
        assertEquals("good morning\neveryone", c.state.visibleText)
        assertFalse(c.accept(event(0, 3, "obsolete", true, 350)))
        assertEquals("good morning\neveryone", c.state.visibleText)
    }

    @Test fun sessionEpochAndRevisionGuards() {
        val c = EnglishSubtitleCoordinator()
        c.begin("new", 3)
        assertFalse(c.accept(event(0, 0, "old", true, 0)))
        assertFalse(c.accept(event(0, 0, "old epoch", true, 0, "new", 2)))
        assertTrue(c.accept(event(0, 0, "valid", false, 1, "new", 3)))
        assertFalse(c.accept(event(0, 0, "duplicate", false, 200, "new", 3)))
        c.clear()
        assertFalse(c.accept(event(0, 1, "after stop", true, 400, "new", 3)))
    }

    @Test fun boundedHistoryAndGap() {
        val c = EnglishSubtitleCoordinator(maxFinalLines = 2)
        c.begin("s", 0)
        for (i in 0L..2L) assertTrue(c.accept(event(i, 0, "line $i", true, i * 200)))
        assertEquals("line 1\nline 2", c.state.visibleText)
        assertTrue(c.accept(event(3, 0, "unfinished", false, 700)))
        c.gap()
        assertEquals("line 1\nline 2", c.state.visibleText)
    }

    @Test fun longHypothesesRemainCurrentAndUntruncated() {
        val coordinator = EnglishSubtitleCoordinator(partialIntervalMs = 0)
        coordinator.begin("s", 0)
        val longText = "first words " + "more speech ".repeat(28) + "latest words"
        assertTrue(longText.length > 240)
        assertTrue(coordinator.accept(event(0, 0, longText, false, 0)))
        assertEquals(longText, coordinator.state.partial)
        assertEquals("latest words", OverlayCaptionText.latest(coordinator.state, 16))
        assertTrue(coordinator.accept(event(0, 1, longText, true, 1)))
        assertEquals(longText, coordinator.state.committed.last().text)
    }

    @Test fun finalNeverThrottled() {
        val c = EnglishSubtitleCoordinator()
        c.begin("s", 0)
        assertTrue(c.accept(event(0, 0, "partial", false, 10)))
        assertTrue(c.accept(event(0, 1, "final", true, 11)))
        assertEquals("final", c.state.visibleText)
    }
}
