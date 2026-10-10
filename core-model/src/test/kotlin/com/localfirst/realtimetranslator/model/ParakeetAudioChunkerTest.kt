package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class ParakeetAudioChunkerTest {
    @Test fun finiteOverlappingWindowsKeepBoundarySamples() {
        val chunks = mutableListOf<ParakeetAudioChunker.Window>()
        val assembler = ParakeetAudioChunker(
            sampleRate = 1000, windowMs = 1200, overlapMs = 200, minimumTailMs = 100)
        val pcm = ShortArray(2500) { (it % 1000).toShort() }
        assembler.append(pcm) { chunks.add(it) }
        assertEquals(2, chunks.size)
        assertEquals(1200, chunks[0].samples.size)
        assertEquals(1200, chunks[1].samples.size)
        // First 200 samples of window 2 are the last 200 of window 1.
        assertArrayEquals(chunks[0].samples.copyOfRange(1000, 1200),
            chunks[1].samples.copyOfRange(0, 200), 0.0001f)
        assembler.finish { chunks.add(it) }
        assertEquals(3, chunks.size)
        assertEquals(500, chunks[2].samples.size)
    }

    @Test fun gapDiscardsUnfinishedAudio() {
        val chunks = mutableListOf<ParakeetAudioChunker.Window>()
        val a = ParakeetAudioChunker(
            sampleRate = 1000, windowMs = 1000, overlapMs = 200, minimumTailMs = 100)
        a.append(ShortArray(600) { 100 }) { chunks.add(it) }
        a.reset()
        a.append(ShortArray(1000) { 400 }) { chunks.add(it) }
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].samples.all { it > 0.01f })
    }

    @Test fun overlapDedupUsesExactOrderedSuffixAndPrefix() {
        val s = ParakeetOverlapStitcher()
        assertEquals("HERE YOU ARE THANK YOU", s.append("HERE YOU ARE THANK YOU"))
        assertEquals("LET US GO", s.append("THANK YOU LET US GO"))
        assertEquals("HOME NOW", s.append("GO HOME NOW"))
        s.reset()
        assertEquals("THANK YOU", s.append("THANK YOU"))
    }

    @Test fun partialWordOverlapDoesNotRemoveUnrelatedWords() {
        val s = ParakeetOverlapStitcher()
        assertEquals("SHE CAN SEE", s.append("SHE CAN SEE"))
        assertEquals("WHO IS HERE", s.append("WHO IS HERE"))
        assertEquals("", s.append("   "))
    }

    @Test fun legacyWireFallbackIsZipformer() {
        assertEquals(AsrModel.ZIPFORMER, AsrModel.fromWire("unknown"))
        assertEquals(AsrModel.PARAKEET, AsrModel.fromWire("parakeet-v3-int8"))
    }
}
