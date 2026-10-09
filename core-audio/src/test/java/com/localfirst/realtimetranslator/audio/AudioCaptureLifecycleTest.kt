package com.localfirst.realtimetranslator.audio

import android.app.Activity
import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.SessionIdentity
import com.localfirst.realtimetranslator.model.SessionRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Contract tests avoid touching device microphones or consuming real projection permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioCaptureLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun microphoneCannotStartBeforeForegroundAndAbortIsIdempotent() {
        val capture = AndroidAudioCapture(
            context, SessionRequest(SessionIdentity("mic"), AudioSource.MICROPHONE),
            onEvent = {}, onStatus = {})
        var rejected = false
        try { capture.start() } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        capture.abort()
        capture.abort()
    }

    @Test fun systemSessionMustHaveFreshConsentAndCanCleanupFailedStart() {
        val capture = AndroidAudioCapture(
            context, SessionRequest(SessionIdentity("projection"), AudioSource.SYSTEM),
            resultCode = Activity.RESULT_CANCELED, consent = null,
            onEvent = {}, onStatus = {})
        capture.foregroundStarted()
        var rejected = false
        try { capture.start() } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        capture.abort()
    }
}
