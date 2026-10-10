package com.localfirst.realtimetranslator.model

/**
 * Post-recognition display pacing only; no effect on ASR audio or native decoding.
 * FAST is opt-in for A/B: shorter holds can scroll too quickly in dense dialogue.
 */
enum class CaptionPacing(val wireId: String, val label: String) {
    STABLE("stable-r33", "稳定字幕（原版显示节奏）"),
    FAST("responsive-r310", "低延迟字幕（实验：更快滚动）");

    companion object {
        fun fromWire(value: String?): CaptionPacing =
            entries.firstOrNull { it.wireId == value } ?: STABLE
    }
}
