package com.localfirst.realtimetranslator.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.CaptureEvent
import com.localfirst.realtimetranslator.model.SessionEvent
import com.localfirst.realtimetranslator.model.SessionIdentity
import com.localfirst.realtimetranslator.model.SessionRequest
import com.localfirst.realtimetranslator.model.SessionState
import com.localfirst.realtimetranslator.model.requestOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** R2 owns real audio capture; ASR, translation and recording-to-disk remain absent. */
class RealtimeTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: TranslationSession? = null
    private var captureResources: AndroidSessionResources? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "实时音频采集", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRequested(intent)
            ACTION_START_SYSTEM, ACTION_START_MICROPHONE -> startRequested(intent)
            else -> if (session == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startRequested(intent: Intent) {
        if (SessionBus.state.value !is SessionState.Idle || stopping || session != null) return
        val id = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
        if (id == null) {
            stopSelf()
            return
        }
        val source = if (intent.action == ACTION_START_SYSTEM) AudioSource.SYSTEM else AudioSource.MICROPHONE
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val consent = if (source == AudioSource.SYSTEM) intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA) else null
        if (source == AudioSource.SYSTEM &&
            (resultCode != Activity.RESULT_OK || consent == null)) {
            stopSelf()
            return
        }
        val request = SessionRequest(SessionIdentity(id), source)
        SessionBus.dispatch(SessionEvent.StartRequested(request))
        // R2 reuses the lifecycle reducer but still has no ASR model or translation engine.
        SessionBus.dispatch(SessionEvent.ModelsReady(id))
        if (source == AudioSource.SYSTEM) SessionBus.dispatch(SessionEvent.ProjectionGranted(id))

        val serviceType = if (source == AudioSource.SYSTEM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(id, source), serviceType)
        } catch (_: Exception) {
            SessionBus.dispatch(SessionEvent.CaptureFailed(id))
            SessionBus.dispatch(SessionEvent.StopCompleted(id))
            stopSelf()
            return
        }
        val resources = AndroidSessionResources(
            context = this,
            resultCode = resultCode,
            consent = consent,
            eventSink = { event -> scope.launch { onCaptureEvent(event) } },
            statusSink = { status -> scope.launch { SessionBus.updateCapture(status) } },
        )
        resources.onForegroundStarted()
        captureResources = resources
        val owner = TranslationSession(resources)
        session = owner
        scope.launch {
            try {
                if (owner.start(request) && !stopping) SessionBus.dispatch(SessionEvent.CaptureStarted(id))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                onCaptureEvent(CaptureEvent.ReadFailure(id, request.identity.audioEpoch))
            }
        }
    }

    private fun onCaptureEvent(event: CaptureEvent) {
        val request = SessionBus.state.value.requestOrNull() ?: return
        val id = request.identity.sessionId
        val epoch = request.identity.audioEpoch
        when (event) {
            is CaptureEvent.Frame -> Unit // PCM never travels on the app state bus
            is CaptureEvent.Gap -> Unit // dropped frames only; silence does not imply a bad source
            is CaptureEvent.ReadFailure -> {
                if (event.sessionId != id || event.audioEpoch != epoch) return
                SessionBus.dispatch(SessionEvent.CaptureFailed(id, epoch))
                stopRequested(Intent(this, javaClass).setAction(ACTION_STOP).putExtra(EXTRA_SESSION_ID, id))
            }
            is CaptureEvent.ProjectionStopped -> {
                if (event.sessionId != id || event.audioEpoch != epoch) return
                SessionBus.dispatch(SessionEvent.ProjectionRevoked(id, epoch))
                stopRequested(Intent(this, javaClass).setAction(ACTION_STOP).putExtra(EXTRA_SESSION_ID, id))
            }
        }
    }

    private fun stopRequested(intent: Intent) {
        val current = SessionBus.state.value.requestOrNull() ?: run {
            stopSelf()
            return
        }
        val expectedId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (expectedId != null && expectedId != current.identity.sessionId) return
        if (stopping) return
        stopping = true
        val id = current.identity.sessionId
        SessionBus.dispatch(SessionEvent.StopRequested(id))
        val owner = session
        scope.launch {
            try {
                owner?.stop(id)
            } finally {
                captureResources?.abort() // cover failed / partially opened capture
                captureResources = null
                session = null
                SessionBus.dispatch(SessionEvent.StopCompleted(id))
                ServiceCompat.stopForeground(this@RealtimeTranslationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun notification(id: String, source: AudioSource): Notification {
        val stopIntent = Intent(this, RealtimeTranslationService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_SESSION_ID, id)
        val stopAction = PendingIntent.getService(
            this, id.hashCode(), stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mode = if (source == AudioSource.SYSTEM) "系统音频" else "麦克风"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("实时翻译 · R2 正在采集音频")
            .setContentText("$mode；仅采集验证，尚无语音识别和翻译")
            .setOngoing(true)
            .addAction(0, "停止", stopAction)
            .build()
    }

    override fun onDestroy() {
        // onDestroy cannot suspend: immediately release AudioRecord / MediaProjection.
        captureResources?.abort()
        captureResources = null
        session = null
        scope.cancel()
        SessionBus.onServiceInterrupted()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SYSTEM = "com.localfirst.realtimetranslator.START_SYSTEM"
        const val ACTION_START_MICROPHONE = "com.localfirst.realtimetranslator.START_MICROPHONE"
        const val ACTION_STOP = "com.localfirst.realtimetranslator.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "audio_capture_session"
        private const val NOTIFICATION_ID = 1001
    }
}
