package com.localfirst.realtimetranslator.service

import com.localfirst.realtimetranslator.model.AsrUpdate
import com.localfirst.realtimetranslator.model.AsrRuntimeStats
import com.localfirst.realtimetranslator.model.CaptureStatus
import com.localfirst.realtimetranslator.model.CaptionPacing
import com.localfirst.realtimetranslator.model.EnglishSubtitleCoordinator
import com.localfirst.realtimetranslator.model.EnglishSubtitleState
import com.localfirst.realtimetranslator.model.requestOrNull
import com.localfirst.realtimetranslator.model.SessionEvent
import com.localfirst.realtimetranslator.model.SessionState
import com.localfirst.realtimetranslator.model.reduce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory foreground-service state only; no persisted transcripts or network. */
object SessionBus {
    private val internalState = MutableStateFlow<SessionState>(SessionState.Idle())
    val state: StateFlow<SessionState> = internalState.asStateFlow()
    private val internalCapture = MutableStateFlow<CaptureStatus?>(null)
    val captureStatus: StateFlow<CaptureStatus?> = internalCapture.asStateFlow()
    private val internalAsrStats = MutableStateFlow<AsrRuntimeStats?>(null)
    val asrStats: StateFlow<AsrRuntimeStats?> = internalAsrStats.asStateFlow()
    private val coordinator = EnglishSubtitleCoordinator()
    private val internalEnglish = MutableStateFlow(EnglishSubtitleState())
    val englishSubtitles: StateFlow<EnglishSubtitleState> = internalEnglish.asStateFlow()
    private val overlaySetting = MutableStateFlow(false)
    val overlayEnabled: StateFlow<Boolean> = overlaySetting.asStateFlow()
    private val overlayTwoLineSetting = MutableStateFlow(true)
    val overlayTwoLines: StateFlow<Boolean> = overlayTwoLineSetting.asStateFlow()
    private val activityVisibility = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = activityVisibility.asStateFlow()

    /** Called by the service before StartRequested; does not modify ASR input/output. */
    internal fun setCaptionPacing(mode: CaptionPacing) {
        coordinator.setCaptionPacing(mode)
    }

    // Explicit opt-in, not written to disk; no overlay is enabled on a fresh process.
    fun setOverlayEnabled(enabled: Boolean) { overlaySetting.value = enabled }
    fun setOverlayTwoLines(enabled: Boolean) { overlayTwoLineSetting.value = enabled }
    fun setAppVisible(visible: Boolean) { activityVisibility.value = visible }

    internal fun dispatch(event: SessionEvent) {
        if (event is SessionEvent.StartRequested) {
            internalCapture.value = null
            internalAsrStats.value = null
            coordinator.begin(event.request.identity.sessionId, event.request.identity.audioEpoch)
            internalEnglish.value = coordinator.state
        }
        internalState.update { reduce(it, event) }
        if (internalState.value is SessionState.Idle) {
            internalCapture.value = null
            internalAsrStats.value = null
            coordinator.clear()
            internalEnglish.value = coordinator.state
        }
    }

    internal fun updateCapture(status: CaptureStatus) {
        val active = internalState.value.requestOrNull()
        if (active?.identity?.sessionId == status.sessionId) internalCapture.value = status
    }

    internal fun updateAsrStats(stats: AsrRuntimeStats) {
        val active = internalState.value.requestOrNull()
        if (active?.identity?.sessionId == stats.sessionId &&
            active.identity.audioEpoch == stats.audioEpoch) internalAsrStats.value = stats
    }

    internal fun updateEnglish(update: AsrUpdate) {
        if (internalState.value !is SessionState.Running &&
            internalState.value !is SessionState.Starting) return
        if (coordinator.accept(update)) internalEnglish.value = coordinator.state
    }

    /** Advance confirmed captions independently of when the ASR emits its next word. */
    internal fun tickCaptions(nowMs: Long) {
        if (internalState.value !is SessionState.Running &&
            internalState.value !is SessionState.Starting) return
        if (coordinator.tick(nowMs)) internalEnglish.value = coordinator.state
    }

    internal fun onAudioGap(sessionId: String, epoch: Long) {
        val active = internalState.value.requestOrNull() ?: return
        if (active.identity.sessionId != sessionId || active.identity.audioEpoch != epoch) return
        coordinator.gap()
        internalEnglish.value = coordinator.state
    }

    internal fun onServiceInterrupted() {
        if (internalState.value !is SessionState.Idle) {
            internalState.value =
                SessionState.Idle(com.localfirst.realtimetranslator.model.Notice.SESSION_INTERRUPTED)
        }
        internalCapture.value = null
        internalAsrStats.value = null
        coordinator.clear()
        internalEnglish.value = coordinator.state
    }
}
