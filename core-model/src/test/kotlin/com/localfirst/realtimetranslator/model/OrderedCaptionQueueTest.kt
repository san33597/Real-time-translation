package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class OrderedCaptionQueueTest {
    private fun event(text: String, revision: Long, isFinal: Boolean,
        ms: Long, utterance: Long = 0L) =
        AsrUpdate("test", 0, utterance, revision, text, isFinal, ms)

    @Test fun fastTwoPartDialogueIsNotSkippedWhenFinalContainsBoth() {
        val queue = OrderedCaptionQueue()
        queue.ingest(event("HERE YOU ARE", 0, false, 100))
        assertEquals("", queue.visibleText) // hypothesis still provisional
        queue.ingest(event("HERE YOU ARE THANK", 1, false, 220))
        assertEquals("HERE YOU ARE", queue.visibleText)
        queue.ingest(event("HERE YOU ARE THANK YOU", 2, true, 320))
        assertEquals("HERE YOU ARE", queue.visibleText)
        assertEquals(1, queue.queueDepth)
        assertTrue(queue.advance(1180))
        assertEquals("THANK YOU", queue.visibleText)
        assertEquals(0, queue.queueOverflows)
    }

    @Test fun rapidFinalOnlyUtterancesKeepTheirOrder() {
        val queue = OrderedCaptionQueue()
        queue.ingest(event("HERE YOU ARE", 0, true, 100, 0))
        queue.ingest(event("THANK YOU", 0, true, 200, 1))
        assertEquals("HERE YOU ARE", queue.visibleText)
        assertEquals(1, queue.queueDepth)
        queue.advance(1100)
        assertEquals("THANK YOU", queue.visibleText)
    }

    @Test fun separateFinalUtterancesDoNotMergeIntoSameQueuedChunk() {
        val queue = OrderedCaptionQueue()
        queue.ingest(event("FIRST LINE", 0, true, 100, 0))
        queue.ingest(event("SECOND LINE", 0, true, 200, 1))
        queue.ingest(event("THIRD LINE", 0, true, 300, 2))
        assertEquals("FIRST LINE", queue.visibleText)
        assertEquals(2, queue.queueDepth)
        queue.advance(1200)
        assertEquals("SECOND LINE", queue.visibleText)
        queue.advance(2200)
        assertEquals("THIRD LINE", queue.visibleText)
    }

    @Test fun continuousSpeechNeverJumpsToLastWords() {
        val queue = OrderedCaptionQueue()
        val words = (1..38).map { "WORD$it" }
        val full = words.joinToString(" ")
        queue.ingest(event(full, 0, true, 100))
        val shown = mutableListOf(queue.visibleText)
        var clock = 100L
        while (queue.queueDepth > 0) {
            clock += 1000L
            assertTrue(queue.advance(clock))
            shown.add(queue.visibleText)
        }
        assertEquals(full, shown.joinToString(" "))
        assertTrue(shown.all { it.length <= 60 })
        assertEquals(0, queue.queueOverflows)
    }

    @Test fun recognizesStablePrefixFromRepeatedPartialAndFinalFlushesTail() {
        val queue = OrderedCaptionQueue()
        queue.ingest(event("THE PARCEL", 0, false, 100))
        queue.ingest(event("THE PARCEL IS", 1, false, 200))
        queue.ingest(event("THE PARCEL IS HERE", 2, false, 250))
        queue.ingest(event("THE PARCEL IS HERE", 3, true, 300))
        val shown = mutableListOf(queue.visibleText)
        queue.advance(1300)
        if (queue.visibleText != shown.last()) shown.add(queue.visibleText)
        assertEquals("THE PARCEL IS HERE", shown.joinToString(" "))
    }

    @Test fun revisionMetricsDetectChangedConfirmedPrefix() {
        val queue = OrderedCaptionQueue()
        queue.ingest(event("WHAT THE", 0, false, 100))
        queue.ingest(event("WHAT THE WEATHER", 1, false, 200))
        queue.ingest(event("WHERE THE WEATHER", 2, true, 300))
        assertTrue(queue.revisedStablePrefixes >= 1)
    }

    @Test fun backlogOverloadMustBeReported() {
        val queue = OrderedCaptionQueue(maxQueuedChunks = 2, maxChunkWords = 2)
        queue.ingest(event((1..30).joinToString(" ") { "LONGWORD$it" },
            0, true, 100))
        assertTrue(queue.queueOverflows > 0)
    }

    @Test fun audioGapEmptiesPendingAndCurrentWithoutKeepingOldWords() {
        val queue = OrderedCaptionQueue()
        queue.ingest(event("FIRST UTTERANCE", 0, true, 100, 0))
        queue.ingest(event("NEXT UTTERANCE", 0, true, 200, 1))
        queue.gap()
        assertEquals(0, queue.queueDepth)
        assertEquals("", queue.visibleText)
        queue.ingest(event("AFTER GAP", 0, true, 400, 2))
        assertEquals("AFTER GAP", queue.visibleText)
    }
}
