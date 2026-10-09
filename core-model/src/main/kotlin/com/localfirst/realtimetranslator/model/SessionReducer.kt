package com.localfirst.realtimetranslator.model

/**
 * Pure state transitions. All session-specific callbacks carry an ID so a late event cannot
 * mutate another session. In R1 the caller is a fake engine; real adapters arrive in R2-R4.
 */
fun reduce(state: SessionState, event: SessionEvent): SessionState {
    if (event is SessionEvent.StartRequested) {
        return if (state is SessionState.Idle) SessionState.PreparingModels(event.request) else state
    }
    val request = state.requestOrNull() ?: return state
    if (event is SessionEvent.StopRequested) {
        if (event.sessionId != null && event.sessionId != request.identity.sessionId) return state
        return if (state is SessionState.Stopping) state else SessionState.Stopping(request)
    }
    if (event.sessionIdOrNull() != request.identity.sessionId) return state
    return when (state) {
        is SessionState.PreparingModels -> when (event) {
            is SessionEvent.ModelsReady ->
                if (request.source == AudioSource.SYSTEM) SessionState.RequestingProjection(request)
                else SessionState.Starting(request)
            is SessionEvent.ModelsMissing -> SessionState.Failed(request, Notice.MODELS_UNAVAILABLE)
            else -> state
        }
        is SessionState.RequestingProjection -> when (event) {
            is SessionEvent.ProjectionGranted -> SessionState.Starting(request)
            is SessionEvent.ProjectionDenied -> SessionState.Idle(Notice.PROJECTION_DENIED)
            else -> state
        }
        is SessionState.Starting -> when (event) {
            is SessionEvent.CaptureStarted -> SessionState.Running(request)
            is SessionEvent.CaptureFailed -> SessionState.Failed(request, Notice.CAPTURE_FAILED)
            is SessionEvent.ProjectionRevoked -> SessionState.Stopping(request)
            else -> state
        }
        is SessionState.Running -> when (event) {
            is SessionEvent.CaptureFailed -> SessionState.Failed(request, Notice.CAPTURE_FAILED)
            is SessionEvent.ProjectionRevoked -> SessionState.Stopping(request)
            is SessionEvent.InternalAudioUnavailable ->
                if (request.source == AudioSource.SYSTEM) SessionState.AwaitingMicrophoneConfirmation(request) else state
            else -> state
        }
        is SessionState.AwaitingMicrophoneConfirmation -> when (event) {
            is SessionEvent.MicrophoneConfirmed -> SessionState.Idle(Notice.NEEDS_NEW_PERMISSION)
            is SessionEvent.MicrophoneDeclined -> SessionState.Idle(Notice.INTERNAL_AUDIO_UNAVAILABLE)
            else -> state
        }
        is SessionState.Stopping -> when (event) {
            is SessionEvent.StopCompleted -> SessionState.Idle()
            else -> state
        }
        is SessionState.Failed -> when (event) {
            is SessionEvent.StopCompleted -> SessionState.Idle(state.notice)
            else -> state
        }
        is SessionState.Idle -> state
    }
}

private fun SessionEvent.sessionIdOrNull(): String? = when (this) {
    is SessionEvent.StartRequested -> request.identity.sessionId
    is SessionEvent.ModelsReady -> sessionId
    is SessionEvent.ModelsMissing -> sessionId
    is SessionEvent.ProjectionGranted -> sessionId
    is SessionEvent.ProjectionDenied -> sessionId
    is SessionEvent.CaptureStarted -> sessionId
    is SessionEvent.CaptureFailed -> sessionId
    is SessionEvent.ProjectionRevoked -> sessionId
    is SessionEvent.InternalAudioUnavailable -> sessionId
    is SessionEvent.MicrophoneConfirmed -> sessionId
    is SessionEvent.MicrophoneDeclined -> sessionId
    is SessionEvent.StopRequested -> sessionId
    is SessionEvent.StopCompleted -> sessionId
}
