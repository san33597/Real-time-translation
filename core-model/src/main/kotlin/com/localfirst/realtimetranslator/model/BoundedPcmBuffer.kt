package com.localfirst.realtimetranslator.model

import java.util.ArrayDeque

/** Bounded memory only. Oldest PCM is dropped and wiped if the consumer falls behind. */
class BoundedPcmBuffer(private val capacity: Int = 32) {
    init { require(capacity > 0) }
    private val frames = ArrayDeque<PcmFrame>()
    private var closed = false

    /** Number of dropped frames. Producer must emit CaptureEvent.Gap when nonzero. */
    @Synchronized fun offer(frame: PcmFrame): Int {
        if (closed) {
            frame.samples.fill(0)
            return 0
        }
        var dropped = 0
        while (frames.size >= capacity) {
            frames.removeFirst().samples.fill(0)
            dropped++
        }
        frames.addLast(frame)
        return dropped
    }

    @Synchronized fun poll(): PcmFrame? = if (frames.isEmpty()) null else frames.removeFirst()

    @Synchronized fun size(): Int = frames.size

    @Synchronized fun close() {
        closed = true
        while (frames.isNotEmpty()) frames.removeFirst().samples.fill(0)
    }
}
