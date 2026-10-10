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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.localfirst.realtimetranslator.asr.EnglishModelInstaller
import com.localfirst.realtimetranslator.asr.ParakeetModelInstaller
import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.AsrModel
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** R3 performs on-device English ASR. Translation, cloud calls and disk transcripts are absent. */
class RealtimeTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: TranslationSession? = null
    private var captureResources: AndroidSessionResources? = null
    private var stopping = false
    private lateinit var floatingCaptions: EnglishCaptionOverlay

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "实时音频采集", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        floatingCaptions = EnglishCaptionOverlay(this)
        scope.launch {
            while (isActive) {
                delay(100L)
                SessionBus.tickCaptions(SystemClock.elapsedRealtime())
            }
        }
        scope.launch {
            combine(SessionBus.state, SessionBus.englishSubtitles,
                SessionBus.overlayEnabled, SessionBus.appVisible, SessionBus.overlayTwoLines
            ) { state, subtitle, enabled, appVisible, twoLines ->
                OverlayFrame(state, subtitle, enabled, appVisible, twoLines)
            }.collect { frame ->
                floatingCaptions.render(frame)
            }
        }
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
        val asrModel = AsrModel.fromWire(intent.getStringExtra(EXTRA_ASR_MODEL))
        SessionBus.dispatch(SessionEvent.StartRequested(request))
        val modelReady = when (asrModel) {
            AsrModel.ZIPFORMER -> EnglishModelInstaller.isReady(this)
            AsrModel.PARAKEET -> ParakeetModelInstaller.isReady(this)
        }
        if (!modelReady) {
            SessionBus.dispatch(SessionEvent.ModelsMissing(id))
            SessionBus.dispatch(SessionEvent.StopCompleted(id))
            stopSelf()
            return
        }
        SessionBus.dispatch(SessionEvent.ModelsReady(id))
        if (source == AudioSource.SYSTEM) SessionBus.dispatch(SessionEvent.ProjectionGranted(id))

        val serviceType = if (source == AudioSource.SYSTEM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildCaptureNotification(id, source), serviceType)
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
            englishSink = { update -> scope.launch { SessionBus.updateEnglish(update) } },
            asrModel = asrModel,
            asrStatsSink = { stats -> scope.launch { SessionBus.updateAsrStats(stats) } },
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
            is CaptureEvent.Gap -> SessionBus.onAudioGap(
                event.sessionId, event.audioEpoch)
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

    /**
     * R2 phone fix: the notification body and its Stop action are two separate user controls.
     * Tapping the body opens the existing Activity; only the Stop action ends capture.
     * Do not confuse Android's own "屏幕共享中" privacy indicator with this notification.
     */
    fun buildCaptureNotification(id: String, source: AudioSource): Notification {
        val openIntent = (packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "$packageName.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openAction = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Distinct immutable, session-scoped PendingIntent: a stale old notification cannot
        // accidentally stop a newer session. A notification press is a user-initiated action.
        val stopIntent = Intent(this, RealtimeTranslationService::class.java)
            .setAction(ACTION_STOP)
            .setData(android.net.Uri.parse("realtimetranslator://capture/stop/$id"))
            .putExtra(EXTRA_SESSION_ID, id)
        val stopAction = PendingIntent.getForegroundService(
            this, id.hashCode(), stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mode = if (source == AudioSource.SYSTEM) "系统音频" else "麦克风"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("实时字幕 · R3 英文识别")
            .setContentText("$mode；点击返回应用，展开可停止采集")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$mode 正在进行本地英文语音识别。点击返回应用，或展开点击「停止采集」。不进行翻译。"
            ))
            .setContentIntent(openAction)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止采集", stopAction)
            .build()
    }

    override fun onDestroy() {
        // onDestroy cannot suspend: immediately release AudioRecord / MediaProjection.
        captureResources?.abort()
        captureResources = null
        session = null
        if (::floatingCaptions.isInitialized) floatingCaptions.close()
        scope.cancel()
        SessionBus.onServiceInterrupted()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SYSTEM = "com.localfirst.realtimetranslator.START_SYSTEM"
        const val ACTION_START_MICROPHONE = "com.localfirst.realtimetranslator.START_MICROPHONE"
        const val ACTION_STOP = "com.localfirst.realtimetranslator.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_ASR_MODEL = "asr_model"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "audio_capture_session"
        private const val NOTIFICATION_ID = 1001
    }
}
