package com.localfirst.realtimetranslator.model

/**
 * R3.9 A/B changes only post-decode window text stitching.
 * LEGACY must remain the default to preserve R3.5/R3.8 recognition output.
 */
enum class ParakeetStitchMode(val wireId: String, val label: String) {
    LEGACY("legacy-exact", "旧版：精确重叠去重（默认）"),
    GUARDED("guarded-r39", "R3.9 实验：保护短句 / 识别窗口连续性");

    companion object {
        fun fromWire(value: String?): ParakeetStitchMode =
            entries.firstOrNull { it.wireId == value } ?: LEGACY
    }
}
