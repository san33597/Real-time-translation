package com.localfirst.realtimetranslator.model

/**
 * R3.11 experimental early hypothesis before the ordinary 3.2s final decode.
 * Disabled by default: adds native inference work and a provisional subtitle
 * which can disagree with the full-window result.
 */
enum class ParakeetPreviewMode(val wireId: String, val label: String) {
    OFF("off-r311", "完整窗口识别（原版，默认）"),
    EARLY("early-1600-r311", "1.6 秒提前预览（实验）");

    companion object {
        fun fromWire(value: String?): ParakeetPreviewMode =
            entries.firstOrNull { it.wireId == value } ?: OFF
    }
}
