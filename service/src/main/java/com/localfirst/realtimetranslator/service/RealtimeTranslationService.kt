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

/**
 * Single owner of the demo session and notification. Actual playback AudioRecord/ASR are
 * deliberately NOT activated in R1. R2 must consume the projection token after foregrounding.
 */
class RealtimeTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = TranslationSession(NoOpSessionResources())
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "实时翻译模拟会话", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRequested(intent)
            ACTION_START_SYSTEM, ACTION_START_MICROPHONE -> startRequested(intent)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startRequested(intent: Intent) {
        if (SessionBus.state.value !is SessionState.Idle || stopping) return
        val id = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
        if (id == null) {
            stopSelf()
            return
        }
        val source = if (intent.action == ACTION_START_SYSTEM) AudioSource.SYSTEM else AudioSource.MICROPHONE
        if (source == AudioSource.SYSTEM &&
            (intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) != Activity.RESULT_OK ||
                !intent.hasExtra(EXTRA_PROJECTION_DATA))) {
            stopSelf()
            return
        }
        val request = SessionRequest(SessionIdentity(id), source)
        SessionBus.dispatch(SessionEvent.StartRequested(request))
        // R1 uses fake model readiness, never claims a real model is installed.
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
        scope.launch {
            try {
                if (session.start(request)) SessionBus.dispatch(SessionEvent.CaptureStarted(id))
            } catch (_: Exception) {
                SessionBus.dispatch(SessionEvent.CaptureFailed(id))
                stopRequested(Intent(this@RealtimeTranslationService, javaClass)
                    .setAction(ACTION_STOP).putExtra(EXTRA_SESSION_ID, id))
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
        scope.launch {
            try {
                session.stop(id)
            } finally {
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
            .setContentTitle("实时翻译 · R1 模拟运行")
            .setContentText("$mode；未采集或翻译真实音频")
            .setOngoing(true)
            .addAction(0, "停止", stopAction)
            .build()
    }

    override fun onDestroy() {
        // R1 resources are fake and hold no native handles; R2 must join real workers before release.
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
        private const val CHANNEL_ID = "demo_translation_session"
        private const val NOTIFICATION_ID = 1001
    }
}
