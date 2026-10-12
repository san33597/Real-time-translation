package com.localfirst.realtimetranslator.caption.preview

import org.json.JSONObject

/**
 * R3.12 ASR transition logger.
 * Stores comparable preview states for later accuracy analysis.
 */
class AsrPreviewLogger {

    fun createRecord(segment: PreviewSegment): JSONObject {
        return JSONObject().apply {
            put("time", segment.createdAt)
            put("partial", segment.partialText)
            put("stable", segment.stablePrefix)
            put("confidence", segment.confidence)
            put("speechRate", segment.speechRate)
            put("final", segment.isFinal)
        }
    }
}
