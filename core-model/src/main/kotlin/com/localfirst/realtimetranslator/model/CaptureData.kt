package com.localfirst.realtimetranslator.model

/** Audio remains transient inside the process. Never serialize, persist or log these samples. */
data class PcmFrame(
    val sessionId: String,
    val audioEpoch: Long,
    val sequence: Long,
    val elapsedRealtimeNanos: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val samples: ShortArray,
) {
    init {
        require(sessionId.isNotBlank())
        require(audioEpoch >= 0 && sequence >= 0 && elapsedRealtimeNanos >= 0)
        require(sampleRateHz > 0 && channelCount > 0 && samples.isNotEmpty())
        require(samples.size % channelCount == 0)
    }
}

sealed interface CaptureEvent {
    data class Frame(val frame: PcmFrame) : CaptureEvent
    data class Gap(val sessionId: String, val audioEpoch: Long, val droppedFrames: Int) : CaptureEvent
    data class ReadFailure(val sessionId: String, val audioEpoch: Long) : CaptureEvent
    data class ProjectionStopped(val sessionId: String, val audioEpoch: Long) : CaptureEvent
}

/** Metadata only: never transport PCM across the UI state flow. */
data class CaptureStatus(
    val sessionId: String,
    val source: AudioSource,
    val frames: Long = 0,
    val droppedFrames: Long = 0,
    val peakPermille: Int = 0,
    val lastElapsedRealtimeNanos: Long = 0,
)
