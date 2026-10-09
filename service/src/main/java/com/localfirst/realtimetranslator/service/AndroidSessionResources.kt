package com.localfirst.realtimetranslator.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.localfirst.realtimetranslator.audio.AndroidAudioCapture
import com.localfirst.realtimetranslator.audio.AudioCapture
import com.localfirst.realtimetranslator.model.CaptureEvent
import com.localfirst.realtimetranslator.model.CaptureStatus
import com.localfirst.realtimetranslator.model.SessionRequest

/** The Service is the sole owner of capture resources, never the Activity or Compose UI. */
class AndroidSessionResources(
    private val context: Context,
    private val resultCode: Int = Activity.RESULT_CANCELED,
    private val consent: Intent? = null,
    private val eventSink: (CaptureEvent) -> Unit,
    private val statusSink: (CaptureStatus) -> Unit,
) : SessionResources {
    private var capture: AudioCapture? = null
    private var foregroundReady = false

    fun onForegroundStarted() { foregroundReady = true }

    override suspend fun open(request: SessionRequest) {
        check(foregroundReady) { "Capture requires a foreground service" }
        check(capture == null)
        val next = AndroidAudioCapture(context, request, resultCode, consent, eventSink, statusSink)
        capture = next // own partially initialized resources before start()
        next.foregroundStarted()
        next.start()
    }

    override suspend fun close() {
        val current = capture
        capture = null
        current?.close()
    }

    fun abort() {
        val current = capture
        capture = null
        current?.abort()
    }
}
