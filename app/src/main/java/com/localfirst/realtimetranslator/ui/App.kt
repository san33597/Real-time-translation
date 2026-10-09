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
import com.localfirst.realtimetranslator.model.CaptureStatus
import com.localfirst.realtimetranslator.model.EnglishSubtitleState
import com.localfirst.realtimetranslator.model.SessionState

@Composable
fun RealtimeTranslatorApp(
    state: SessionState,
    captureStatus: CaptureStatus?,
    englishSubtitles: EnglishSubtitleState,
    modelReady: Boolean,
    installingModel: Boolean,
    onImportModel: () -> Unit,
    message: String?,
    onStart: (AudioSource) -> Unit,
    onStop: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(AudioSource.SYSTEM) }
    val idle = state is SessionState.Idle
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("R3 · 英文实时字幕", style = MaterialTheme.typography.headlineMedium)
                Text("离线英语识别测试 · 暂不翻译 · 不保存音频和识别内容")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("English captions", style = MaterialTheme.typography.labelLarge)
                        englishSubtitles.committed.forEach {
                            Text(it.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (englishSubtitles.partial.isNotBlank()) {
                            Text(englishSubtitles.partial,
                                style = MaterialTheme.typography.titleMedium)
                        } else if (englishSubtitles.committed.isEmpty()) {
                            Text(
                                if (state is SessionState.Running) "Listening for English speech…"
                                else "Start capture to display English subtitles.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                Text(if (modelReady) "英文离线模型：已校验" else "英文离线模型：尚未安装")
                OutlinedButton(
                    onClick = onImportModel, enabled = idle && !installingModel
                ) { Text(if (installingModel) "导入中…" else "导入英文模型文件夹") }
                if (!modelReady) {
                    Text("先下载并解压指定 sherpa-onnx 英文模型，再选择包含 encoder、decoder、joiner 和 tokens.txt 的文件夹。",
                        style = MaterialTheme.typography.bodySmall)
                }
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
                    "R3 仅检验真实 English partial/final 字幕，ML Kit 与 DeepSeek 均未接入。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
