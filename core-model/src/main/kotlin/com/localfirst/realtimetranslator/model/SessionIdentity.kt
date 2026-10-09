package com.localfirst.realtimetranslator.model

import java.util.UUID

data class SessionIdentity(val sessionId: String, val audioEpoch: Long = 0) {
    init {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        require(audioEpoch >= 0) { "Audio epoch cannot be negative" }
    }

    companion object {
        fun new(): SessionIdentity = SessionIdentity(UUID.randomUUID().toString())
    }
}

enum class AudioSource { SYSTEM, MICROPHONE }

data class SessionRequest(val identity: SessionIdentity, val source: AudioSource)

enum class Notice {
    PROJECTION_DENIED,
    PERMISSION_DENIED,
    MODELS_UNAVAILABLE,
    CAPTURE_FAILED,
    PROJECTION_REVOKED,
    INTERNAL_AUDIO_UNAVAILABLE,
    NEEDS_NEW_PERMISSION,
    SESSION_INTERRUPTED,
}
