package com.localfirst.realtimetranslator.service

import com.localfirst.realtimetranslator.model.*
import org.junit.Assert.*
import org.junit.Test

class SessionBusEnglishTest {
    @Test fun stopClearsTranscriptAndRejectsLateRecognition() {
        val one = SessionRequest(SessionIdentity("r3-one"), AudioSource.MICROPHONE)
        SessionBus.onServiceInterrupted()
        SessionBus.dispatch(SessionEvent.StartRequested(one))
        SessionBus.dispatch(SessionEvent.ModelsReady("r3-one"))
        SessionBus.dispatch(SessionEvent.CaptureStarted("r3-one"))
        SessionBus.updateEnglish(AsrUpdate("r3-one", 0, 0, 0,
            "test sentence", true, 100L))
        assertEquals("test sentence", SessionBus.englishSubtitles.value.visibleText)
        SessionBus.dispatch(SessionEvent.StopRequested("r3-one"))
        SessionBus.dispatch(SessionEvent.StopCompleted("r3-one"))
        assertTrue(SessionBus.englishSubtitles.value.visibleText.isBlank())
        SessionBus.updateEnglish(AsrUpdate("r3-one", 0, 1, 1,
            "late result", true, 120L))
        assertTrue(SessionBus.englishSubtitles.value.visibleText.isBlank())
    }

    @Test fun differentSessionCannotOverwriteLiveEnglishCaption() {
        val session = SessionRequest(SessionIdentity("r3-two"), AudioSource.MICROPHONE)
        SessionBus.onServiceInterrupted()
        SessionBus.dispatch(SessionEvent.StartRequested(session))
        SessionBus.dispatch(SessionEvent.ModelsReady("r3-two"))
        SessionBus.dispatch(SessionEvent.CaptureStarted("r3-two"))
        SessionBus.updateEnglish(AsrUpdate("old", 0, 0, 0, "wrong", true, 50L))
        SessionBus.updateEnglish(AsrUpdate("r3-two", 0, 0, 0, "correct", true, 55L))
        assertEquals("correct", SessionBus.englishSubtitles.value.visibleText)
        SessionBus.dispatch(SessionEvent.StopRequested("r3-two"))
        SessionBus.dispatch(SessionEvent.StopCompleted("r3-two"))
    }
}
