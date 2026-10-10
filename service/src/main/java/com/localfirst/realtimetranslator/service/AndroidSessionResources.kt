package com.localfirst.realtimetranslator.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.localfirst.realtimetranslator.asr.EnglishModelInstaller
import com.localfirst.realtimetranslator.asr.ParakeetModelInstaller
import com.localfirst.realtimetranslator.asr.ParakeetOfflineEnglishEngine
import com.localfirst.realtimetranslator.asr.SherpaOnnxEnglishEngine
import com.localfirst.realtimetranslator.audio.AndroidAudioCapture
import com.localfirst.realtimetranslator.audio.AudioCapture
import com.localfirst.realtimetranslator.model.AsrEngine
import com.localfirst.realtimetranslator.model.AsrUpdate
import com.localfirst.realtimetranslator.model.AsrModel
import com.localfirst.realtimetranslator.model.AsrRuntimeStats
import com.localfirst.realtimetranslator.model.CaptureEvent
import com.localfirst.realtimetranslator.model.CaptureStatus
import com.localfirst.realtimetranslator.model.SessionRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Foreground service owns recording + one private, offline English ASR stream. */
class AndroidSessionResources(
    private val context: Context,
    private val resultCode: Int = Activity.RESULT_CANCELED,
    private val consent: Intent? = null,
    private val eventSink: (CaptureEvent) -> Unit,
    private val statusSink: (CaptureStatus) -> Unit,
    private val englishSink: (AsrUpdate) -> Unit,
    private val asrModel: AsrModel = AsrModel.ZIPFORMER,
    private val asrStatsSink: (AsrRuntimeStats) -> Unit = {},
) : SessionResources {
    private var capture: AudioCapture? = null
    private var engine: AsrEngine? = null
    private val engineLock = Any()
    private val gapPending = AtomicBoolean(false)
    private var foregroundReady = false

    fun onForegroundStarted() { foregroundReady = true }

    override suspend fun open(request: SessionRequest) {
        check(foregroundReady) { "Capture requires a foreground service" }
        check(capture == null && engine == null)
        val nextEngine = withContext(Dispatchers.IO) {
            when (asrModel) {
                AsrModel.ZIPFORMER -> {
                    check(EnglishModelInstaller.isReady(context)) { "Zipformer 模型尚未安装" }
                    SherpaOnnxEnglishEngine(request.identity,
                        EnglishModelInstaller.directory(context),
                        SystemClock::elapsedRealtime, englishSink)
                }
                AsrModel.PARAKEET -> {
                    check(ParakeetModelInstaller.isReady(context)) { "Parakeet 模型尚未安装" }
                    ParakeetOfflineEnglishEngine(request.identity,
                        ParakeetModelInstaller.directory(context),
                        SystemClock::elapsedRealtime, englishSink, asrStatsSink,
                        onFailure = { eventSink(CaptureEvent.ReadFailure(
                            request.identity.sessionId, request.identity.audioEpoch)) })
                }
            }
        }
        engine = nextEngine
        val next = AndroidAudioCapture(
            context, request, resultCode, consent, eventSink, statusSink,
            onPcmFrame = { frame ->
                synchronized(engineLock) {
                    if (gapPending.getAndSet(false)) nextEngine.resetAfterGap()
                    nextEngine.accept(frame)
                }
            },
            onPcmGap = { gapPending.set(true) },
        )
        capture = next
        next.foregroundStarted()
        next.start()
    }

    override suspend fun close() {
        val current = capture
        capture = null
        try { current?.close() } finally {
            withContext(Dispatchers.IO) {
                synchronized(engineLock) {
                    val active = engine
                    engine = null
                    try { active?.finish() } finally { active?.close() }
                }
            }
        }
    }

    fun abort() {
        val current = capture
        capture = null
        current?.abort()
        // Serialize with any in-flight JNI decode before releasing the native stream.
        synchronized(engineLock) {
            val active = engine
            engine = null
            active?.close()
        }
    }
}
