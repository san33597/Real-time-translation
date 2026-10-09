package com.localfirst.realtimetranslator.model

sealed interface SessionState {
    data class Idle(val notice: Notice? = null) : SessionState
    data class PreparingModels(val request: SessionRequest) : SessionState
    data class RequestingProjection(val request: SessionRequest) : SessionState
    data class Starting(val request: SessionRequest) : SessionState
    data class Running(val request: SessionRequest) : SessionState
    data class AwaitingMicrophoneConfirmation(val request: SessionRequest) : SessionState
    data class Stopping(val request: SessionRequest) : SessionState
    data class Failed(val request: SessionRequest, val notice: Notice) : SessionState
}

fun SessionState.requestOrNull(): SessionRequest? = when (this) {
    is SessionState.Idle -> null
    is SessionState.PreparingModels -> request
    is SessionState.RequestingProjection -> request
    is SessionState.Starting -> request
    is SessionState.Running -> request
    is SessionState.AwaitingMicrophoneConfirmation -> request
    is SessionState.Stopping -> request
    is SessionState.Failed -> request
}
