package com.localfirst.realtimetranslator.model

/** Small, metadata-only ASR diagnostics; no audio or transcript storage. */
data class AsrRuntimeStats(
    val sessionId: String,
    val audioEpoch: Long,
    val decodedWindows: Long = 0,
    val droppedWindows: Long = 0,
    val lastDecodeMs: Long = 0,
)
