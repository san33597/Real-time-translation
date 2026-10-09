package com.localfirst.realtimetranslator.audio

import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.CaptureStatus
import com.localfirst.realtimetranslator.model.PcmFrame
import kotlin.math.abs

/** Silence is valid audio, never evidence to switch to the microphone automatically. */
class AudioSourceMonitor(sessionId: String, source: AudioSource) {
    private var status = CaptureStatus(sessionId, source)

    @Synchronized fun onFrame(frame: PcmFrame): CaptureStatus {
        val level = if (frame.samples.isEmpty()) 0 else {
            val max = frame.samples.maxOf { abs(it.toInt()) }
            (max * 1000L / Short.MAX_VALUE).toInt().coerceIn(0, 1000)
        }
        status = status.copy(
            frames = status.frames + 1,
            peakPermille = level,
            lastElapsedRealtimeNanos = frame.elapsedRealtimeNanos
        )
        return status
    }

    @Synchronized fun onGap(count: Int): CaptureStatus {
        require(count >= 0)
        status = status.copy(droppedFrames = status.droppedFrames + count)
        return status
    }

    @Synchronized fun snapshot(): CaptureStatus = status
}
