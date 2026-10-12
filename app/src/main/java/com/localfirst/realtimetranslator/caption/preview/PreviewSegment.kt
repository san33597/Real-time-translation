package com.localfirst.realtimetranslator.caption.preview

/**
 * R3.12 Adaptive Preview intermediate caption state.
 * Keeps partial ASR output and the part safe enough to preview.
 */
data class PreviewSegment(
    val id: Long,
    val partialText: String,
    val stablePrefix: String,
    val confidence: Float,
    val speechRate: Float,
    val createdAt: Long,
    val isFinal: Boolean = false
)
