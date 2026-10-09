package com.localfirst.realtimetranslator.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.localfirst.realtimetranslator.model.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Capture never persists sound; R2 deliberately measures and discards PCM rather than running ASR. */
interface AudioCapture {
    fun foregroundStarted()
    fun start()
    suspend fun close()
    fun abort()
}

/** 16 kHz mono PCM16; one fresh MediaProjection grant per system capture. */
class AndroidAudioCapture(
    private val context: Context,
    private val request: SessionRequest,
    private val resultCode: Int = Activity.RESULT_CANCELED,
    private val consent: Intent? = null,
    private val onEvent: (CaptureEvent) -> Unit,
    private val onStatus: (CaptureStatus) -> Unit,
) : AudioCapture {
    private val guard = CaptureStartGuard()
    private val stopped = AtomicBoolean(false)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = BoundedPcmBuffer(32)
    private val monitor = AudioSourceMonitor(request.identity.sessionId, request.source)
    private var record: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var reader: Job? = null
    private var sequence = 0L

    override fun foregroundStarted() = guard.foregroundStarted()

    override fun start() {
        check(!stopped.get() && record == null)
        guard.requireForeground()
        val p = if (request.source == AudioSource.SYSTEM) {
            guard.consumeProjectionGrant()
            check(resultCode == Activity.RESULT_OK && consent != null) { "Fresh projection consent required" }
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            manager.getMediaProjection(resultCode, consent)
                ?: error("MediaProjection unavailable")
        } else null
        projection = p
        if (p != null) {
            val c = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopped.get()) onEvent(CaptureEvent.ProjectionStopped(
                        request.identity.sessionId, request.identity.audioEpoch))
                }
            }
            callback = c
            p.registerCallback(c, Handler(Looper.getMainLooper()))
        }

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz mono recording unavailable" }
        val builder = AudioRecord.Builder().setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum * 2, FRAME_SAMPLES * 8))
        if (p != null) {
            val playback = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(playback)
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        val r = builder.build()
        record = r // own before any initialization failure
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord unavailable" }
        r.startRecording()
        check(r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord start failed" }
        reader = workerScope.launch { readLoop(r) }
    }

    private suspend fun readLoop(r: AudioRecord) {
        val scratch = ShortArray(FRAME_SAMPLES)
        try {
            while (workerScope.isActive && !stopped.get()) {
                val count = r.read(scratch, 0, scratch.size, AudioRecord.READ_NON_BLOCKING)
                when {
                    count > 0 -> {
                        val frame = PcmFrame(
                            request.identity.sessionId, request.identity.audioEpoch,
                            sequence++, SystemClock.elapsedRealtimeNanos(), SAMPLE_RATE, 1,
                            scratch.copyOf(count))
                        val lost = queue.offer(frame)
                        if (lost > 0) {
                            monitor.onGap(lost)
                            onEvent(CaptureEvent.Gap(frame.sessionId, frame.audioEpoch, lost))
                        }
                        // R2 has no ASR worker yet: immediately discard queued audio and zero it.
                        val consumed = queue.poll()
                        if (consumed != null) {
                            val status = monitor.onFrame(consumed)
                            consumed.samples.fill(0)
                            if (status.frames % 10L == 0L) onStatus(status)
                        }
                    }
                    count == 0 -> delay(10)
                    else -> {
                        if (!stopped.get()) onEvent(CaptureEvent.ReadFailure(
                            request.identity.sessionId, request.identity.audioEpoch))
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!stopped.get()) onEvent(CaptureEvent.ReadFailure(
                request.identity.sessionId, request.identity.audioEpoch))
        } finally {
            scratch.fill(0)
        }
    }

    override suspend fun close() {
        if (!stopped.compareAndSet(false, true)) return
        stopRecording()
        reader?.let { withTimeoutOrNull(1200) { it.cancelAndJoin() } }
        release()
    }

    override fun abort() {
        if (!stopped.compareAndSet(false, true)) return
        stopRecording()
        reader?.cancel()
        release()
    }

    private fun stopRecording() {
        try { record?.stop() } catch (_: Exception) { /* best effort */ }
    }

    private fun release() {
        try { record?.release() } catch (_: Exception) { /* best effort */ }
        record = null
        val p = projection
        projection = null
        callback?.let { try { p?.unregisterCallback(it) } catch (_: Exception) {} }
        callback = null
        try { p?.stop() } catch (_: Exception) {}
        queue.close()
        workerScope.cancel()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms
    }
}
