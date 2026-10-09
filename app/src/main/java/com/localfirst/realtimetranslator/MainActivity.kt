package com.localfirst.realtimetranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.ProjectionDecision
import com.localfirst.realtimetranslator.model.SessionIdentity
import com.localfirst.realtimetranslator.model.SessionState
import com.localfirst.realtimetranslator.model.projectionDecision
import com.localfirst.realtimetranslator.model.requestOrNull
import com.localfirst.realtimetranslator.service.RealtimeTranslationService
import com.localfirst.realtimetranslator.service.SessionBus
import com.localfirst.realtimetranslator.ui.RealtimeTranslatorApp

/** R1 demo Activity: requests user consent but never records or translates any audio. */
class MainActivity : ComponentActivity() {
    private var pendingSource = AudioSource.SYSTEM
    private var pendingId = ""
    private var message by mutableStateOf<String?>(null)

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Denial only limits notification visibility; it is not fatal to the foreground service.
        requestAudioPermission()
    }

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) continueWithSource() else message = "录音权限被拒绝，请在设置中授权后重试。"
    }

    private val projectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (projectionDecision(result.resultCode == Activity.RESULT_OK, result.data != null)) {
            ProjectionDecision.GRANTED -> startServiceForSource(
                Intent(this, RealtimeTranslationService::class.java)
                    .setAction(RealtimeTranslationService.ACTION_START_SYSTEM)
                    .putExtra(RealtimeTranslationService.EXTRA_SESSION_ID, pendingId)
                    .putExtra(RealtimeTranslationService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(RealtimeTranslationService.EXTRA_PROJECTION_DATA, result.data)
            )
            ProjectionDecision.DENIED -> message = "系统声音授权已取消；未启动任何捕获。"
            ProjectionDecision.INVALID -> message = "系统未返回有效的媒体授权，请重新尝试。"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val session by SessionBus.state.collectAsState()
            RealtimeTranslatorApp(
                state = session,
                message = message,
                onStart = ::beginDemo,
                onStop = ::stopDemo
            )
        }
    }

    private fun beginDemo(source: AudioSource) {
        if (SessionBus.state.value !is SessionState.Idle) return
        pendingSource = source
        pendingId = SessionIdentity.new().sessionId
        message = null
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestAudioPermission()
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) continueWithSource()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun continueWithSource() {
        if (pendingSource == AudioSource.SYSTEM) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionPermission.launch(projectionManager.createScreenCaptureIntent())
        } else {
            startServiceForSource(
                Intent(this, RealtimeTranslationService::class.java)
                    .setAction(RealtimeTranslationService.ACTION_START_MICROPHONE)
                    .putExtra(RealtimeTranslationService.EXTRA_SESSION_ID, pendingId)
            )
        }
    }

    private fun startServiceForSource(intent: Intent) {
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {
            message = "前台服务无法启动，请检查系统权限后重试。"
        }
    }

    private fun stopDemo() {
        val sessionId = SessionBus.state.value.requestOrNull()?.identity?.sessionId
        startService(
            Intent(this, RealtimeTranslationService::class.java)
                .setAction(RealtimeTranslationService.ACTION_STOP)
                .putExtra(RealtimeTranslationService.EXTRA_SESSION_ID, sessionId)
        )
    }
}
