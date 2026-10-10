package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class OrderedCaptionQueueTest {
    private fun event(text: String, revision: Long, isFinal: Boolean,
        ms: Long, utterance: Long = 0L) =
        AsrUpdate("test", 0, utterance, revision, text, isFinal, ms)

    @Test fun responsiveModeDisplaysSecondCaptionEarlierWithoutChangingTextOrOrder() {
        val stable = OrderedCaptionQueue()
        val fast = OrderedCaptionQueue()
        fast.setPacing(CaptionPacing.FAST)
        for (queue in listOf(stable, fast)) {
            queue.ingest(event("FIRST LINE", 0, true, 100, 0))
            queue.ingest(event("SECOND LINE", 0, true, 200, 1))
        }
        assertFalse(stable.advance(620))
        assertTrue(fast.advance(620))
        assertEquals("FIRST LINE", stable.visibleText)
        assertEquals("SECOND LINE", fast.visibleText)
        assertEquals("FIRST LINE", fast.previousText)
        assertTrue(stable.advance(900))
        assertEquals("SECOND LINE", stable.visibleText)
        assertEquals(0, fast.queueOverflows)
        assertEquals(0, stable.queueOverflows)
    }

    @Test fun responsiveModeRetainsOrderedBurstWhenQueueHasSeveralCaptions() {
        val fast = OrderedCaptionQueue()
        fast.setPacing(CaptionPacing.FAST)
        (0L..3L).forEach { i -> fast.ingest(event("ITEM$i", 0, true, 100L, i)) }
        val transcript = mutableListOf(fast.visibleText)
        var time = 100L
        while (fast.queueDepth > 0) {
            time += 250L
            if (fast.advance(time)) transcript.add(fast.visibleText)
        }
        assertEquals(listOf("ITEM0", "ITEM1", "ITEM2", "ITEM3"), transcript)
    }

    @Test fun unknownPacingDefaultsToOriginalStableMode() {
        assertEquals(CaptionPacing.STABLE, CaptionPacing.fromWire(null))
        assertEquals(CaptionPacing.STABLE, CaptionPacing.fromWire("invalid"))
        assertEquals(CaptionPacing.FAST, CaptionPacing.fromWire("responsive-r310"))
    }

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

    @Test fun previousFragmentScrollsUpAsNewFragmentAppears() {
        val queue = OrderedCaptionQueue()
        queue.ingest(event("FIRST", 0, true, 100, 0))
        assertEquals("", queue.previousText)
        queue.ingest(event("SECOND", 0, true, 200, 1))
        assertFalse(queue.advance(500))
        assertTrue(queue.advance(850))
        assertEquals("FIRST", queue.previousText)
        assertEquals("SECOND", queue.visibleText)
        queue.ingest(event("THIRD", 0, true, 900, 2))
        assertTrue(queue.advance(1600))
        assertEquals("SECOND", queue.previousText)
        assertEquals("THIRD", queue.visibleText)
    }

    @Test fun largeBacklogScrollsFasterWithoutDroppingOriginalChunks() {
        val queue = OrderedCaptionQueue()
        (0L..4L).forEach { i ->
            queue.ingest(event("FRAGMENT$i", 0, true, 100 + i * 25, i))
        }
        assertEquals(4, queue.queueDepth)
        assertTrue(queue.advance(285))
        assertEquals("FRAGMENT0", queue.previousText)
        assertEquals("FRAGMENT1", queue.visibleText)
        assertTrue(queue.queueDepth > 0)
        assertEquals(0, queue.queueOverflows)
    }

    @Test fun audioGapEmptiesPendingAndCurrentWithoutKeepingOldWords() {
        val queue = OrderedCaptionQueue()
        queue.ingest(event("FIRST UTTERANCE", 0, true, 100, 0))
        queue.ingest(event("NEXT UTTERANCE", 0, true, 200, 1))
        queue.gap()
        assertEquals(0, queue.queueDepth)
        assertEquals("", queue.visibleText)
        assertEquals("", queue.previousText)
        queue.ingest(event("AFTER GAP", 0, true, 400, 2))
        assertEquals("AFTER GAP", queue.visibleText)
    }
}
