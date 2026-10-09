package com.localfirst.realtimetranslator.audio

import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.PcmFrame
import org.junit.Assert.*
import org.junit.Test

class AudioSourceMonitorTest {
    @Test fun silenceNeverMeansMicFallback() {
        val monitor = AudioSourceMonitor("one", AudioSource.SYSTEM)
        val emptySound = PcmFrame("one", 0, 0, 100, 16000, 1, ShortArray(320))
        val after = monitor.onFrame(emptySound)
        assertEquals(1L, after.frames)
        assertEquals(0, after.peakPermille)
        assertEquals(AudioSource.SYSTEM, after.source)
    }

    @Test fun countsGapsAndClampsPeak() {
        val monitor = AudioSourceMonitor("one", AudioSource.MICROPHONE)
        val frame = PcmFrame("one", 0, 0, 5, 16000, 1, shortArrayOf(Short.MIN_VALUE, 0))
        assertEquals(1000, monitor.onFrame(frame).peakPermille)
        assertEquals(3L, monitor.onGap(3).droppedFrames)
    }
}
