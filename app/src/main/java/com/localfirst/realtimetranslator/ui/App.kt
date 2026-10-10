package com.localfirst.realtimetranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.AsrModel
import com.localfirst.realtimetranslator.model.ParakeetWindowPreset
import com.localfirst.realtimetranslator.model.AsrRuntimeStats
import com.localfirst.realtimetranslator.model.CaptureStatus
import com.localfirst.realtimetranslator.model.EnglishSubtitleState
import com.localfirst.realtimetranslator.model.OverlayCaptionText
import com.localfirst.realtimetranslator.model.SessionState

@Composable
fun RealtimeTranslatorApp(
    state: SessionState,
    captureStatus: CaptureStatus?,
    asrStats: AsrRuntimeStats?,
    asrModel: AsrModel,
    parakeetPreset: ParakeetWindowPreset,
    onChooseParakeetPreset: (ParakeetWindowPreset) -> Unit,
    zipformerReady: Boolean,
    parakeetReady: Boolean,
    onChooseAsr: (AsrModel) -> Unit,
    onImportParakeet: () -> Unit,
    englishSubtitles: EnglishSubtitleState,
    modelReady: Boolean,
    installingModel: Boolean,
    onImportModel: () -> Unit,
    overlayEnabled: Boolean,
    overlayAllowed: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    overlayTwoLines: Boolean,
    onToggleOverlayTwoLines: (Boolean) -> Unit,
    message: String?,
    onStart: (AudioSource) -> Unit,
    onStop: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(AudioSource.SYSTEM) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val idle = state is SessionState.Idle
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("R3.6.1 · 英文实时字幕", style = MaterialTheme.typography.headlineMedium)
                Text("离线英语识别测试 · 暂不翻译 · 不保存音频和识别内容")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("English captions", style = MaterialTheme.typography.labelLarge)
                        val (previous, current) = OverlayCaptionText.rows(englishSubtitles, overlayTwoLines)
                        val caption = listOf(previous, current).filter(String::isNotBlank).joinToString("\n")
                        if (caption.isNotBlank()) {
                            Text(caption, style = MaterialTheme.typography.titleMedium,
                                maxLines = 3)
                        } else {
                            Text(
                                if (state is SessionState.Running) "Listening for English speech…"
                                else "Start capture to display English subtitles.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }) {
                    Text(if (showDiagnostics) "收起识别诊断（仅内存）" else "展开识别诊断：ASR 原文 vs 字幕")
                }
                if (showDiagnostics) {
                    val debug = englishSubtitles.diagnostics
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("ASR 原始最近输出（与字幕独立）",
                                style = MaterialTheme.typography.labelLarge)
                            Text(debug.latestRaw.takeLast(450).ifBlank { "尚无识别结果" },
                                style = MaterialTheme.typography.bodySmall)
                            Text("当前实际显示的字幕",
                                style = MaterialTheme.typography.labelLarge)
                            Text(englishSubtitles.displayCaption.ifBlank { "暂无已确认字幕" })
                            Text(
                                "Partial：${debug.partialUpdates} / Final：${debug.finalUpdates}" +
                                    " · 已显示片段：${debug.displayedChunks}" +
                                    " · 待显示片段：${debug.queuedChunks}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "字幕队列溢出：${debug.queueOverflows} · ASR 稳定前缀改写：${debug.correctedPrefixes}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (debug.queueOverflows > 0)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            asrStats?.let { stats ->
                                Text("Parakeet 窗口：已解码 ${stats.decodedWindows} · 跳过 ${stats.droppedWindows}" +
                                    " · 最近推理 ${stats.lastDecodeMs}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (stats.droppedWindows > 0) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface)
                            }
                            asrStats?.let { stats ->
                                Text(
                                    "输入帧：${stats.inputFrames} · 当前峰值 ${stats.inputPeakPermille / 10.0}%" +
                                        " · RMS ${stats.inputRmsPermille / 10.0}%",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "已提交窗口：${stats.queuedWindows} · 空识别：${stats.emptyResults}" +
                                        " · 重叠去重后为空：${stats.overlapOnlyResults}" +
                                        " · 最近排队等待：${stats.lastQueueWaitMs}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (stats.droppedWindows > 0)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Parakeet 当前为固定重叠窗口，无 VAD / 能量阈值过滤。" +
                                        "空识别不等于音频未被采集。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            asrStats?.let { stats ->
                                Text(
                                    "窗口长度：${stats.windowMs}ms · 重叠：${stats.overlapMs}ms" +
                                        " · 解码周转：${stats.lastWindowTurnaroundMs}ms",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text("最近 ASR 更新（仅内存，停止后清空）",
                                style = MaterialTheme.typography.labelLarge)
                            debug.recent.takeLast(6).forEach { entry ->
                                Text(
                                    "${if (entry.isFinal) "Final" else "Partial"}" +
                                        " #${entry.utteranceId} r${entry.revision}：" +
                                        entry.rawExcerpt.takeLast(130),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                Text("当前识别引擎（停止采集后可切换）",
                    style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = asrModel == AsrModel.ZIPFORMER,
                        onClick = { onChooseAsr(AsrModel.ZIPFORMER) }, enabled = idle && !installingModel)
                    Text("Zipformer", modifier = Modifier.padding(top = 12.dp, end = 12.dp))
                    RadioButton(selected = asrModel == AsrModel.PARAKEET,
                        onClick = { onChooseAsr(AsrModel.PARAKEET) }, enabled = idle && !installingModel)
                    Text("Parakeet v3 INT8", modifier = Modifier.padding(top = 12.dp))
                }
                if (asrModel == AsrModel.PARAKEET) {
                    Text("Parakeet 识别窗口（默认 R3.5 基线）",
                        style = MaterialTheme.typography.titleMedium)
                    ParakeetWindowPreset.entries.forEach { preset ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            RadioButton(
                                selected = parakeetPreset == preset,
                                onClick = { onChooseParakeetPreset(preset) },
                                enabled = idle && !installingModel,
                            )
                            Text(preset.label, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    Text("3.2 秒保留 R3.5 原识别与字幕调度；2.4 秒和 2.0 秒仅用于实验，可能漏词。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Zipformer：${if (zipformerReady) "已安装" else "未安装"}；" +
                    "Parakeet：${if (parakeetReady) "已安装" else "未安装"}",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (modelReady) "当前模型文件：已就绪" else "当前模型未安装，请先导入")
                OutlinedButton(
                    onClick = onImportModel, enabled = idle && !installingModel
                ) { Text("导入 Zipformer 文件夹") }
                OutlinedButton(
                    onClick = onImportParakeet, enabled = idle && !installingModel
                ) { Text(if (installingModel) "模型导入中…" else "导入 Parakeet v3 INT8 文件夹") }
                if (!modelReady) {
                    Text("Parakeet 使用 encoder.int8.onnx / decoder.int8.onnx / joiner.int8.onnx / tokens.txt；导入可能需要 1GB 左右临时空间。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("在其他应用上显示英文字幕",
                        modifier = Modifier.weight(1f).padding(top = 12.dp))
                    Switch(checked = overlayEnabled, onCheckedChange = onToggleOverlay)
                }
                Text(
                    when {
                        !overlayAllowed -> "需要授权「显示在其他应用上层」；拒绝后仍能看应用内字幕。"
                        overlayEnabled -> "开启中：切到视频应用后会在屏幕下方显示字幕。"
                        else -> "已有悬浮窗权限，开启开关后可在其他应用上显示。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("双行滚动字幕（上一句 + 最新句）",
                        modifier = Modifier.weight(1f).padding(top = 12.dp))
                    Switch(checked = overlayTwoLines, onCheckedChange = onToggleOverlayTwoLines)
                }
                Text("默认双行；关闭后显示最新片段。切换不影响 ASR 原文。",
                    style = MaterialTheme.typography.bodySmall)
                Text("选择声音来源（静音不会自动切换到麦克风）")
                Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = selected == AudioSource.SYSTEM,
                        onClick = { selected = AudioSource.SYSTEM }, enabled = idle
                    )
                    Text("系统声音", modifier = Modifier.padding(top = 12.dp, end = 16.dp))
                    RadioButton(
                        selected = selected == AudioSource.MICROPHONE,
                        onClick = { selected = AudioSource.MICROPHONE }, enabled = idle
                    )
                    Text("麦克风", modifier = Modifier.padding(top = 12.dp))
                }
                Text("当前状态：" + when (state) {
                    is SessionState.Idle -> "空闲"
                    is SessionState.PreparingModels -> "检查模型"
                    is SessionState.RequestingProjection -> "等待系统授权"
                    is SessionState.Starting -> "加载模型并开始录音"
                    is SessionState.Running -> "正在识别英文"
                    is SessionState.AwaitingMicrophoneConfirmation -> "需要手动确认麦克风"
                    is SessionState.Stopping -> "停止中"
                    is SessionState.Failed -> "识别或采集失败：" + state.notice.name
                })
                if (state is SessionState.Running && captureStatus != null) {
                    Text("音频帧：${captureStatus.frames}；丢帧：${captureStatus.droppedFrames}")
                    Text("瞬时峰值：${captureStatus.peakPermille / 10.0}%")
                    if (captureStatus.peakPermille == 0) {
                        Text("当前静音；系统音频可能受到播放应用的采集政策限制。")
                    }
                }
                (state as? SessionState.Idle)?.notice?.let {
                    Text("上次状态：" + it.name)
                }
                if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { onStart(selected) }, enabled = idle && modelReady && !installingModel) {
                    Text("开始英文识别")
                }
                OutlinedButton(onClick = onStop, enabled = !idle) { Text("停止采集") }
                Text(
                    "R3.6.1 默认恢复 R3.5 的 3.2s 识别与字幕等待机制，短窗口需手动选择。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
