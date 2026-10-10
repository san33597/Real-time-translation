package com.localfirst.realtimetranslator.model

/**
 * R3.6 reproducible A/B profiles for the same locally installed Parakeet model.
 * Fixed for the entire capture session: do not splice different window lengths.
 * Overlap protects the edges of speech, but an offline decoder still must wait
 * for an entire window before producing its first result.
 */
enum class ParakeetWindowPreset(
    val wireId: String,
    val windowMs: Int,
    val overlapMs: Int,
    val label: String,
) {
    LEGACY("legacy-3200", 3200, 640, "3.2 秒 · R3.5 基线（默认）"),
    BALANCED("balanced-2400", 2400, 480, "2.4 秒 · 实验"),
    FAST("fast-2000", 2000, 400, "2.0 秒 · 实验");

    val hopMs: Int get() = windowMs - overlapMs

    companion object {
        fun fromWire(value: String?): ParakeetWindowPreset =
            entries.firstOrNull { it.wireId == value } ?: LEGACY
    }
}
