package com.localfirst.realtimetranslator.model

import org.junit.Assert.*
import org.junit.Test

class BoundedPcmBufferTest {
    private fun frame(sequence: Long) = PcmFrame("one", 0, sequence, 100L, 16_000, 1, shortArrayOf(10, 20))

    @Test fun boundedDropOldestAndWipeSamples() {
        val buffer = BoundedPcmBuffer(2)
        val a = frame(1)
        val b = frame(2)
        val c = frame(3)
        assertEquals(0, buffer.offer(a))
        assertEquals(0, buffer.offer(b))
        assertEquals(1, buffer.offer(c))
        assertArrayEquals(shortArrayOf(0, 0), a.samples)
        assertEquals(2L, buffer.poll()?.sequence)
        assertEquals(3L, buffer.poll()?.sequence)
        assertNull(buffer.poll())
    }

    @Test fun clearingAndClosingWipesUnconsumedAudio() {
        val buffer = BoundedPcmBuffer(1)
        val a = frame(1)
        buffer.offer(a)
        buffer.close()
        assertArrayEquals(shortArrayOf(0, 0), a.samples)
        val afterClose = frame(2)
        buffer.offer(afterClose)
        assertArrayEquals(shortArrayOf(0, 0), afterClose.samples)
        assertEquals(0, buffer.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotHaveUnboundedCapacity() { BoundedPcmBuffer(0) }
}
