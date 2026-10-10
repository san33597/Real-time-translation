package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class ParakeetAudioChunkerTest {
    @Test fun earlyProbeDoesNotChangeFullWindowPcmOrCadence() {
        val baseline = ParakeetAudioChunker(sampleRate = 1000,
            windowMs = 3200, overlapMs = 640, minimumTailMs = 260)
        val experimental = ParakeetAudioChunker(sampleRate = 1000,
            windowMs = 3200, overlapMs = 640, minimumTailMs = 260)
        val fullA = mutableListOf<ParakeetAudioChunker.Window>()
        val fullB = mutableListOf<ParakeetAudioChunker.Window>()
        val probes = mutableListOf<ParakeetAudioChunker.Window>()
        val pcm = ShortArray(9000) { (it % 7000 - 3500).toShort() }
        var offset = 0
        while (offset < pcm.size) {
            val size = minOf(191 + offset % 317, pcm.size - offset)
            val piece = pcm.copyOfRange(offset, offset + size)
            baseline.append(piece) { fullA.add(it) }
            experimental.appendWithPreview(piece, 1600,
                previewReady = { probes.add(it) }, ready = { fullB.add(it) })
            offset += size
        }
        assertEquals(fullA.size, fullB.size)
        assertEquals(3, probes.size)
        assertEquals(listOf(0L, 2560L, 5120L), probes.map { it.startSample })
        assertTrue(probes.all { it.samples.size == 1600 })
        fullA.indices.forEach { index ->
            assertEquals(fullA[index].index, fullB[index].index)
            assertEquals(fullA[index].startSample, fullB[index].startSample)
            assertArrayEquals(fullA[index].samples, fullB[index].samples, 0f)
            assertEquals(probes[index].index, fullB[index].index)
            assertArrayEquals(probes[index].samples,
                fullB[index].samples.copyOfRange(0, 1600), 0f)
        }
    }

    @Test fun earlyProbesAreDiscardedAcrossPcmGapAndDoNotJoinAudioSegments() {
        val chunker = ParakeetAudioChunker(sampleRate = 1000,
            windowMs = 3200, overlapMs = 640, minimumTailMs = 260)
        val probes = mutableListOf<ParakeetAudioChunker.Window>()
        val finals = mutableListOf<ParakeetAudioChunker.Window>()
        chunker.appendWithPreview(ShortArray(1700) { 111 }, previewReady = { probes.add(it) },
            ready = { finals.add(it) })
        assertEquals(1, probes.size)
        chunker.reset()
        chunker.appendWithPreview(ShortArray(3200) { 222 }, previewReady = { probes.add(it) },
            ready = { finals.add(it) })
        assertEquals(2, probes.size)
        assertEquals(1L, probes[1].segmentId)
        assertEquals(0L, probes[1].startSample)
        assertEquals(0L, finals.single().startSample)
        assertEquals(1L, finals.single().segmentId)
        assertEquals(0, finals.single().index)
        assertTrue(finals.single().samples.all { it > 0.006f })
    }

    @Test fun previewModeDefaultsOffAndRejectsUnknownWireValues() {
        assertEquals(ParakeetPreviewMode.OFF, ParakeetPreviewMode.fromWire(null))
        assertEquals(ParakeetPreviewMode.OFF, ParakeetPreviewMode.fromWire("bad"))
        assertEquals(ParakeetPreviewMode.EARLY,
            ParakeetPreviewMode.fromWire("early-1600-r311"))
    }

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

    @Test fun tracksExactSampleOffsetsAcrossOverlappingWindows() {
        val chunker = ParakeetAudioChunker(
            sampleRate = 1000, windowMs = 1000, overlapMs = 200, minimumTailMs = 100)
        val windows = mutableListOf<ParakeetAudioChunker.Window>()
        chunker.append(ShortArray(1850) { 99 }) { windows.add(it) }
        assertEquals(2, windows.size)
        assertEquals(0L, windows[0].startSample)
        assertEquals(1000L, windows[0].endSample)
        assertEquals(800L, windows[1].startSample)
        assertEquals(1800L, windows[1].endSample)
        assertEquals(0L, windows[1].segmentId)
        chunker.finish { windows.add(it) }
        assertEquals(3, windows.size)
        assertEquals(1600L, windows[2].startSample)
        assertEquals(1850L, windows[2].endSample)
    }

    @Test fun sampleTimelineRestartsAfterGapWithoutJoiningTwoSegments() {
        val chunker = ParakeetAudioChunker(
            sampleRate = 1000, windowMs = 1000, overlapMs = 200, minimumTailMs = 100)
        val windows = mutableListOf<ParakeetAudioChunker.Window>()
        chunker.append(ShortArray(1000)) { windows.add(it) }
        chunker.append(ShortArray(310)) { windows.add(it) }
        chunker.reset()
        chunker.append(ShortArray(1000)) { windows.add(it) }
        assertEquals(2, windows.size)
        assertEquals(1L, windows.last().segmentId)
        assertEquals(0L, windows.last().startSample)
        assertEquals(1000L, windows.last().endSample)
        assertEquals(1L, windows.last().index)
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

    @Test fun r37PresetsKeepExactThreePointTwoSecondContext() {
        assertEquals(3200, ParakeetWindowPreset.LEGACY.windowMs)
        assertEquals(3200, ParakeetWindowPreset.SLIDING.windowMs)
        assertEquals(640, ParakeetWindowPreset.LEGACY.overlapMs)
        assertEquals(1280, ParakeetWindowPreset.SLIDING.overlapMs)
        assertEquals(2560, ParakeetWindowPreset.LEGACY.hopMs)
        assertEquals(1920, ParakeetWindowPreset.SLIDING.hopMs)
        assertEquals(ParakeetWindowPreset.LEGACY, ParakeetWindowPreset.fromWire(null))
        assertEquals(ParakeetWindowPreset.LEGACY, ParakeetWindowPreset.fromWire("unknown"))
        assertEquals(ParakeetWindowPreset.LEGACY, ParakeetWindowPreset.fromWire("legacy-3200"))
        assertEquals(ParakeetWindowPreset.SLIDING, ParakeetWindowPreset.fromWire("sliding-3200-1280"))
        // Both previously problematic short-window values are disabled.
        assertEquals(ParakeetWindowPreset.LEGACY, ParakeetWindowPreset.fromWire("balanced-2400"))
        assertEquals(ParakeetWindowPreset.LEGACY, ParakeetWindowPreset.fromWire("fast-2000"))
    }

    @Test fun experimentalOverlapKeepsEveryWindowAt3200msAndPreservesSampleBoundaries() {
        val window = ParakeetWindowPreset.SLIDING
        val output = mutableListOf<ParakeetAudioChunker.Window>()
        val assembler = ParakeetAudioChunker(
            sampleRate = 1000, windowMs = window.windowMs,
            overlapMs = window.overlapMs, minimumTailMs = 260)
        val pcm = ShortArray(8000) { it.toShort() }
        var start = 0
        // Deliberately feed uneven frames to exercise copy boundaries.
        while (start < pcm.size) {
            val end = minOf(start + 73 + (start % 997), pcm.size)
            assembler.append(pcm.copyOfRange(start, end)) { output.add(it) }
            start = end
        }
        assertEquals(3, output.size)
        output.forEach { assertEquals(3200, it.samples.size) }
        assertArrayEquals(output[0].samples.copyOfRange(1920, 3200),
            output[1].samples.copyOfRange(0, 1280), 0f)
        assertArrayEquals(output[1].samples.copyOfRange(1920, 3200),
            output[2].samples.copyOfRange(0, 1280), 0f)
        assertEquals(pcm[3840] / 32768f, output[2].samples[0], 0f)
        assertEquals(pcm[7039] / 32768f, output[2].samples.last(), 0f)
        assembler.finish { output.add(it) }
        assertEquals(4, output.size)
        assertEquals(2240, output.last().samples.size)
    }

    @Test fun overlapChangesOnlyCadenceNotFirstWindow() {
        val original = ParakeetAudioChunker(sampleRate = 1000)
        val sliding = ParakeetAudioChunker(sampleRate = 1000,
            windowMs = ParakeetWindowPreset.SLIDING.windowMs,
            overlapMs = ParakeetWindowPreset.SLIDING.overlapMs)
        val baseline = mutableListOf<ParakeetAudioChunker.Window>()
        val experiment = mutableListOf<ParakeetAudioChunker.Window>()
        val pcm = ShortArray(8000) { (it % 5000).toShort() }
        original.append(pcm) { baseline.add(it) }
        sliding.append(pcm) { experiment.add(it) }
        assertEquals(2, baseline.size)
        assertEquals(3, experiment.size)
        assertArrayEquals(baseline.first().samples, experiment.first().samples, 0f)
        // No changes to initial buffering; the second complete sliding window
        // will appear 1920ms after the first, versus 2560ms in baseline.
    }

    @Test fun overlapStatisticsObserveButNeverModifyDedupBehavior() {
        val stitcher = ParakeetOverlapStitcher()
        assertEquals("SHE IS GOING DOWN", stitcher.append("SHE IS GOING DOWN"))
        assertEquals(0, stitcher.lastDuplicateWords)
        assertEquals("AGAIN", stitcher.append("DOWN AGAIN"))
        assertEquals(1, stitcher.lastDuplicateWords)
        assertEquals("", stitcher.append("DOWN AGAIN"))
        assertEquals(2, stitcher.lastDuplicateWords)
        assertEquals("", stitcher.append(" "))
        assertEquals(0, stitcher.lastDuplicateWords)
        stitcher.reset()
        assertEquals(0, stitcher.lastDuplicateWords)
        assertEquals("DOWN AGAIN", stitcher.append("DOWN AGAIN"))
    }

    @Test fun r35LegacyWindowMatchesTheOriginalDefaultChunker() {
        val preset = ParakeetWindowPreset.LEGACY
        val original = ParakeetAudioChunker()
        val restored = ParakeetAudioChunker(windowMs = preset.windowMs,
            overlapMs = preset.overlapMs)
        val a = mutableListOf<ParakeetAudioChunker.Window>()
        val b = mutableListOf<ParakeetAudioChunker.Window>()
        val samples = ShortArray(120_000) { (it % 11003 - 5501).toShort() }
        // Vary PCM delivery boundaries; exactly the same samples must be decoded.
        var offset = 0
        while (offset < samples.size) {
            val amount = minOf(240 + (offset % 761), samples.size - offset)
            val frame = samples.copyOfRange(offset, offset + amount)
            original.append(frame) { a.add(it) }
            restored.append(frame) { b.add(it) }
            offset += amount
        }
        assertEquals(a.size, b.size)
        assertTrue(a.isNotEmpty())
        a.indices.forEach { i ->
            assertEquals(a[i].index, b[i].index)
            assertArrayEquals(a[i].samples, b[i].samples, 0f)
        }
    }

    @Test fun legacyWireFallbackIsZipformer() {
        assertEquals(AsrModel.ZIPFORMER, AsrModel.fromWire("unknown"))
        assertEquals(AsrModel.PARAKEET, AsrModel.fromWire("parakeet-v3-int8"))
    }
}
