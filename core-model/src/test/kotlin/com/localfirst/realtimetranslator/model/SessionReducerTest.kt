package com.localfirst.realtimetranslator.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    private val system = SessionRequest(SessionIdentity("one"), AudioSource.SYSTEM)
    private val mic = SessionRequest(SessionIdentity("two"), AudioSource.MICROPHONE)

    @Test fun systemRequiresProjectionBeforeRunning() {
        val preparing = reduce(SessionState.Idle(), SessionEvent.StartRequested(system))
        assertEquals(SessionState.PreparingModels(system), preparing)
        val consent = reduce(preparing, SessionEvent.ModelsReady("one"))
        assertEquals(SessionState.RequestingProjection(system), consent)
        val starting = reduce(consent, SessionEvent.ProjectionGranted("one"))
        assertEquals(SessionState.Starting(system), starting)
        assertEquals(SessionState.Running(system), reduce(starting, SessionEvent.CaptureStarted("one")))
    }

    @Test fun micRequiresExplicitStartButNotProjection() {
        val preparing = reduce(SessionState.Idle(), SessionEvent.StartRequested(mic))
        assertEquals(SessionState.Starting(mic), reduce(preparing, SessionEvent.ModelsReady("two")))
    }

    @Test fun consentDeniedIsRecoverable() {
        val awaiting = SessionState.RequestingProjection(system)
        assertEquals(SessionState.Idle(Notice.PROJECTION_DENIED), reduce(awaiting, SessionEvent.ProjectionDenied("one")))
    }

    @Test fun cannotStartTwiceOrWhileStopping() {
        assertEquals(SessionState.Running(system),
            reduce(SessionState.Running(system), SessionEvent.StartRequested(mic)))
        assertEquals(SessionState.Stopping(system),
            reduce(SessionState.Stopping(system), SessionEvent.StartRequested(mic)))
    }

    @Test fun stopIsIdempotentAndStaleStopsAreIgnored() {
        val running = SessionState.Running(system)
        assertEquals(running, reduce(running, SessionEvent.StopRequested("two")))
        val stopping = reduce(running, SessionEvent.StopRequested("one"))
        assertEquals(stopping, reduce(stopping, SessionEvent.StopRequested("one")))
        assertEquals(SessionState.Idle(), reduce(stopping, SessionEvent.StopCompleted("one")))
        assertEquals(SessionState.Idle(), reduce(SessionState.Idle(), SessionEvent.StopRequested()))
    }

    @Test fun oldSessionCallbacksNeverMutateNewSession() {
        val starting = SessionState.Starting(mic)
        assertEquals(starting, reduce(starting, SessionEvent.CaptureStarted("one")))
        assertEquals(starting, reduce(starting, SessionEvent.ProjectionRevoked("one")))
    }

    @Test fun projectionRevocationRequiresCleanup() {
        assertEquals(SessionState.Stopping(system),
            reduce(SessionState.Running(system), SessionEvent.ProjectionRevoked("one")))
    }

    @Test fun internalAudioFailureNeedsExplicitUserChoice() {
        val awaiting = reduce(SessionState.Running(system), SessionEvent.InternalAudioUnavailable("one"))
        assertEquals(SessionState.AwaitingMicrophoneConfirmation(system), awaiting)
        assertEquals(SessionState.Idle(Notice.NEEDS_NEW_PERMISSION),
            reduce(awaiting, SessionEvent.MicrophoneConfirmed("one")))
    }

    @Test fun failedStartCanBeCleanedUp() {
        val failed = reduce(SessionState.Starting(system), SessionEvent.CaptureFailed("one"))
        assertTrue(failed is SessionState.Failed)
        assertEquals(SessionState.Idle(Notice.CAPTURE_FAILED),
            reduce(failed, SessionEvent.StopCompleted("one")))
    }
}
