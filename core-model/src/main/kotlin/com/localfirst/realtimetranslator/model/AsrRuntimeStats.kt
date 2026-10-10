package com.localfirst.realtimetranslator.model

/** Ephemeral window/energy diagnostics only. Never store waveforms or ASR text. */
data class AsrRuntimeStats(
    val sessionId: String,
    val audioEpoch: Long,
    val decodedWindows: Long = 0,
    /** Windows rejected by the bounded inference queue; not empty ASR decodes. */
    val droppedWindows: Long = 0,
    val lastDecodeMs: Long = 0,
    val queuedWindows: Long = 0,
    val inputFrames: Long = 0,
    val inputPeakPermille: Int = 0,
    val inputRmsPermille: Int = 0,
    /** Decoder returned blank for a delivered window. */
    val emptyResults: Long = 0,
    /** Decoder returned text, but exact overlap de-duplication removed all words. */
    val overlapOnlyResults: Long = 0,
    /** Wall time between offering a window and beginning to decode it. */
    val lastQueueWaitMs: Long = 0,
)
