package com.localfirst.realtimetranslator.model

sealed interface SessionEvent {
    data class StartRequested(val request: SessionRequest) : SessionEvent
    data class ModelsReady(val sessionId: String) : SessionEvent
    data class ModelsMissing(val sessionId: String) : SessionEvent
    data class ProjectionGranted(val sessionId: String) : SessionEvent
    data class ProjectionDenied(val sessionId: String) : SessionEvent
    data class CaptureStarted(val sessionId: String, val audioEpoch: Long = 0) : SessionEvent
    data class CaptureFailed(val sessionId: String, val audioEpoch: Long = 0) : SessionEvent
    data class ProjectionRevoked(val sessionId: String, val audioEpoch: Long = 0) : SessionEvent
    data class InternalAudioUnavailable(val sessionId: String, val audioEpoch: Long = 0) : SessionEvent
    data class MicrophoneConfirmed(val sessionId: String) : SessionEvent
    data class MicrophoneDeclined(val sessionId: String) : SessionEvent
    data class StopRequested(val sessionId: String? = null) : SessionEvent
    data class StopCompleted(val sessionId: String) : SessionEvent
}
