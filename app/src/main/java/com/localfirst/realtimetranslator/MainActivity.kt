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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.localfirst.realtimetranslator.asr.EnglishModelInstaller
import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.ProjectionDecision
import com.localfirst.realtimetranslator.model.SessionIdentity
import com.localfirst.realtimetranslator.model.SessionState
import com.localfirst.realtimetranslator.model.projectionDecision
import com.localfirst.realtimetranslator.model.requestOrNull
import com.localfirst.realtimetranslator.service.RealtimeTranslationService
import com.localfirst.realtimetranslator.service.SessionBus
import com.localfirst.realtimetranslator.ui.RealtimeTranslatorApp

/** R3: English live subtitles from the R2 capture service; model import is explicit. */
class MainActivity : ComponentActivity() {
    private var pendingSource = AudioSource.SYSTEM
    private var pendingId = ""
    private var message by mutableStateOf<String?>(null)
    private var modelReady by mutableStateOf(false)
    private var installingModel by mutableStateOf(false)

    private val modelFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && !installingModel) {
            installingModel = true
            message = "正在校验并导入英文模型…"
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        EnglishModelInstaller.install(this@MainActivity, uri)
                    }
                    modelReady = EnglishModelInstaller.isReady(this@MainActivity)
                    message = "英文模型安装完成，可开始 R3 识别。"
                } catch (e: Exception) {
                    message = "模型导入失败：" + (e.message ?: "请检查模型文件")
                } finally {
                    installingModel = false
                }
            }
        }
    }

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
        modelReady = EnglishModelInstaller.isReady(this)
        setContent {
            val session by SessionBus.state.collectAsState()
            val capture by SessionBus.captureStatus.collectAsState()
            val english by SessionBus.englishSubtitles.collectAsState()
            RealtimeTranslatorApp(
                state = session,
                captureStatus = capture,
                englishSubtitles = english,
                modelReady = modelReady,
                installingModel = installingModel,
                onImportModel = { modelFolder.launch(null) },
                message = message,
                onStart = ::beginCapture,
                onStop = ::stopCapture
            )
        }
    }

    private fun beginCapture(source: AudioSource) {
        if (SessionBus.state.value !is SessionState.Idle) return
        if (!modelReady || installingModel) {
            message = "请先导入并校验英文识别模型。"
            return
        }
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

    private fun stopCapture() {
        val sessionId = SessionBus.state.value.requestOrNull()?.identity?.sessionId
        startService(
            Intent(this, RealtimeTranslationService::class.java)
                .setAction(RealtimeTranslationService.ACTION_STOP)
                .putExtra(RealtimeTranslationService.EXTRA_SESSION_ID, sessionId)
        )
    }
}
