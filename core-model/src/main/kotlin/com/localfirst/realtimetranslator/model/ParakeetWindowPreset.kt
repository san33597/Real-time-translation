package com.localfirst.realtimetranslator.model

/**
 * R3.7 isolated overlap A/B test. Both profiles always feed 3200ms PCM into
 * the same local Parakeet decoder. Neither caption scheduling nor the deduper
 * changes between modes. Higher overlap means more inferences per minute.
 */
enum class ParakeetWindowPreset(
    val wireId: String,
    val windowMs: Int,
    val overlapMs: Int,
    val label: String,
) {
    LEGACY("legacy-3200", 3200, 640, "3.2 秒 + 640ms 重叠 · 已验证（默认）"),
    SLIDING("sliding-3200-1280", 3200, 1280, "3.2 秒 + 1280ms 重叠 · 实验");

    val hopMs: Int get() = windowMs - overlapMs

    companion object {
        /** Old 2.0/2.4s R3.6 values deliberately fall back to R3.5. */
        fun fromWire(value: String?): ParakeetWindowPreset =
            entries.firstOrNull { it.wireId == value } ?: LEGACY
    }
}
