package com.localfirst.realtimetranslator.model

/** Persist neither external audio nor model selections without an explicit request. */
enum class AsrModel(val wireId: String, val displayName: String) {
    ZIPFORMER("zipformer", "Zipformer · 流式 / 低延迟"),
    PARAKEET("parakeet-v3-int8", "Parakeet TDT 0.6B v3 INT8 · 准确率优先");

    companion object {
        fun fromWire(value: String?): AsrModel =
            entries.firstOrNull { it.wireId == value } ?: ZIPFORMER
    }
}
