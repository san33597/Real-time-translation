package com.localfirst.realtimetranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.Settings
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
import com.localfirst.realtimetranslator.asr.ParakeetModelInstaller
import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.AsrModel
import com.localfirst.realtimetranslator.model.ParakeetWindowPreset
import com.localfirst.realtimetranslator.model.ParakeetStitchMode
import com.localfirst.realtimetranslator.model.ProjectionDecision
import com.localfirst.realtimetranslator.model.SessionIdentity
import com.localfirst.realtimetranslator.model.SessionState
import com.localfirst.realtimetranslator.model.projectionDecision
import com.localfirst.realtimetranslator.model.requestOrNull
import com.localfirst.realtimetranslator.service.RealtimeTranslationService
import com.localfirst.realtimetranslator.service.SessionBus
import com.localfirst.realtimetranslator.service.AsrDiagnosticLog
import com.localfirst.realtimetranslator.ui.RealtimeTranslatorApp

/** R3: English live subtitles from the R2 capture service; model import is explicit. */
class MainActivity : ComponentActivity() {
    private var pendingSource = AudioSource.SYSTEM
    private var pendingId = ""
    private var message by mutableStateOf<String?>(null)
    private var modelReady by mutableStateOf(false)
    private var zipformerReady by mutableStateOf(false)
    private var parakeetReady by mutableStateOf(false)
    private var selectedAsr by mutableStateOf(AsrModel.ZIPFORMER)
    private var selectedParakeetPreset by mutableStateOf(ParakeetWindowPreset.LEGACY)
    private var selectedStitchMode by mutableStateOf(ParakeetStitchMode.LEGACY)
    private var pendingStitchMode = ParakeetStitchMode.LEGACY
    private var pendingParakeetPreset = ParakeetWindowPreset.LEGACY
    private var pendingAsr = AsrModel.ZIPFORMER
    private var installingModel by mutableStateOf(false)
    private var overlayAllowed by mutableStateOf(false)
    private var requestedOverlayGrant = false
    private var saveAsrLog by mutableStateOf(false)
    private var pendingSaveAsrLog = false
    private var exportSourceFile: java.io.File? = null

    private val logExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        val source = exportSourceFile
        exportSourceFile = null
        if (uri != null && source != null) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            source.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("无法打开导出位置")
                    }
                    message = "ASR 日志已导出，可用于对比识别结果。"
                } catch (e: Exception) {
                    message = "日志导出失败：" + (e.message ?: "未知错误")
                }
            }
        }
    }

    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        overlayAllowed = Settings.canDrawOverlays(this)
        if (requestedOverlayGrant) {
            SessionBus.setOverlayEnabled(overlayAllowed)
            message = if (overlayAllowed) "已开启悬浮字幕，切到视频应用即可看到英文。" else
                "未获得悬浮窗权限；仍可继续使用应用内字幕。"
        }
        requestedOverlayGrant = false
    }

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
                    zipformerReady = EnglishModelInstaller.isReady(this@MainActivity)
                    modelReady = if (selectedAsr == AsrModel.ZIPFORMER) zipformerReady else parakeetReady
                    message = "英文模型安装完成，可开始 R3 识别。"
                } catch (e: Exception) {
                    message = "模型导入失败：" + (e.message ?: "请检查模型文件")
                } finally {
                    installingModel = false
                }
            }
        }
    }

    private val parakeetFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && !installingModel) {
            installingModel = true
            message = "正在导入 Parakeet 模型（约 640 MiB），请等待文件复制完成…"
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ParakeetModelInstaller.install(this@MainActivity, uri)
                    }
                    parakeetReady = ParakeetModelInstaller.isReady(this@MainActivity)
                    selectedAsr = AsrModel.PARAKEET
                    modelReady = parakeetReady
                    message = "Parakeet 模型已导入，已切换为 Parakeet，启动时将校验原生加载。"
                } catch (e: Exception) {
                    parakeetReady = ParakeetModelInstaller.isReady(this@MainActivity)
                    modelReady = if (selectedAsr == AsrModel.PARAKEET) parakeetReady else zipformerReady
                    message = "Parakeet 导入失败：" + (e.message ?: "请核对模型四个文件")
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
        zipformerReady = EnglishModelInstaller.isReady(this)
        parakeetReady = ParakeetModelInstaller.isReady(this)
        selectedAsr = if (parakeetReady) AsrModel.PARAKEET else AsrModel.ZIPFORMER
        modelReady = if (selectedAsr == AsrModel.PARAKEET) parakeetReady else zipformerReady
        overlayAllowed = Settings.canDrawOverlays(this)
        setContent {
            val session by SessionBus.state.collectAsState()
            val capture by SessionBus.captureStatus.collectAsState()
            val english by SessionBus.englishSubtitles.collectAsState()
            val overlayEnabled by SessionBus.overlayEnabled.collectAsState()
            val overlayTwoLines by SessionBus.overlayTwoLines.collectAsState()
            val asrStats by SessionBus.asrStats.collectAsState()
            RealtimeTranslatorApp(
                state = session,
                captureStatus = capture,
                asrStats = asrStats,
                asrModel = selectedAsr,
                parakeetPreset = selectedParakeetPreset,
                stitchMode = selectedStitchMode,
                onChooseStitchMode = { selectedStitchMode = it },
                onChooseParakeetPreset = { selectedParakeetPreset = it },
                zipformerReady = zipformerReady,
                parakeetReady = parakeetReady,
                onChooseAsr = { next ->
                    selectedAsr = next
                    modelReady = if (next == AsrModel.ZIPFORMER) zipformerReady else parakeetReady
                },
                onImportParakeet = { parakeetFolder.launch(null) },
                englishSubtitles = english,
                modelReady = modelReady,
                installingModel = installingModel,
                onImportModel = { modelFolder.launch(null) },
                overlayEnabled = overlayEnabled && overlayAllowed,
                overlayAllowed = overlayAllowed,
                onToggleOverlay = ::toggleOverlay,
                overlayTwoLines = overlayTwoLines,
                saveAsrLog = saveAsrLog,
                onToggleAsrLog = { saveAsrLog = it },
                onExportAsrLog = ::exportLastAsrLog,
                onClearAsrLogs = ::clearAsrLogs,
                onToggleOverlayTwoLines = SessionBus::setOverlayTwoLines,
                message = message,
                onStart = ::beginCapture,
                onStop = ::stopCapture
            )
        }
    }

    override fun onStart() {
        super.onStart()
        SessionBus.setAppVisible(true)
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        if (!overlayAllowed) SessionBus.setOverlayEnabled(false)
    }

    override fun onStop() {
        SessionBus.setAppVisible(false)
        super.onStop()
    }

    private fun toggleOverlay(enabled: Boolean) {
        if (!enabled) {
            requestedOverlayGrant = false
            SessionBus.setOverlayEnabled(false)
            return
        }
        overlayAllowed = Settings.canDrawOverlays(this)
        if (overlayAllowed) {
            SessionBus.setOverlayEnabled(true)
            return
        }
        requestedOverlayGrant = true
        try {
            overlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            requestedOverlayGrant = false
            SessionBus.setOverlayEnabled(false)
            message = "无法进入悬浮窗权限设置，请手动从应用权限中开启。"
        }
    }

    private fun beginCapture(source: AudioSource) {
        if (SessionBus.state.value !is SessionState.Idle) return
        if (!modelReady || installingModel) {
            message = "请先导入并校验英文识别模型。"
            return
        }
        pendingSource = source
        pendingAsr = selectedAsr
        pendingParakeetPreset = selectedParakeetPreset
        pendingStitchMode = selectedStitchMode
        pendingSaveAsrLog = saveAsrLog
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
            ContextCompat.startForegroundService(this,
                intent.putExtra(RealtimeTranslationService.EXTRA_ASR_MODEL, pendingAsr.wireId)
                    .putExtra(RealtimeTranslationService.EXTRA_PARAKEET_WINDOW,
                        pendingParakeetPreset.wireId)
                    .putExtra(RealtimeTranslationService.EXTRA_PARAKEET_STITCH_MODE,
                        pendingStitchMode.wireId)
                    .putExtra(RealtimeTranslationService.EXTRA_SAVE_ASR_LOG, pendingSaveAsrLog))
        } catch (_: Exception) {
            message = "前台服务无法启动，请检查系统权限后重试。"
        }
    }

    private fun exportLastAsrLog() {
        if (SessionBus.state.value !is SessionState.Idle) {
            message = "请先停止采集，等待日志写入完成后再导出。"
            return
        }
        val latest = AsrDiagnosticLog.latest(this)
        if (latest == null) {
            message = "还没有本地 ASR 日志，请先开启保存开关并完成一次测试。"
            return
        }
        exportSourceFile = latest
        logExport.launch(latest.name)
    }

    private fun clearAsrLogs() {
        if (SessionBus.state.value !is SessionState.Idle) {
            message = "请先停止采集再清空日志。"
            return
        }
        val count = AsrDiagnosticLog.clear(this)
        message = "已删除 " + count + " 份本地 ASR 文字日志。"
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
