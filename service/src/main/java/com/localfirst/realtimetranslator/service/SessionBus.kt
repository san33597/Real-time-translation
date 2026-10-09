package com.localfirst.realtimetranslator.service

import com.localfirst.realtimetranslator.model.SessionEvent
import com.localfirst.realtimetranslator.model.SessionState
import com.localfirst.realtimetranslator.model.reduce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-process projection of state owned exclusively by RealtimeTranslationService. */
object SessionBus {
    private val internalState = MutableStateFlow<SessionState>(SessionState.Idle())
    val state: StateFlow<SessionState> = internalState.asStateFlow()

    internal fun dispatch(event: SessionEvent) {
        internalState.update { reduce(it, event) }
    }

    internal fun onServiceInterrupted() {
        val current = internalState.value
        if (current !is SessionState.Idle) {
            internalState.value = SessionState.Idle(com.localfirst.realtimetranslator.model.Notice.SESSION_INTERRUPTED)
        }
    }
}
