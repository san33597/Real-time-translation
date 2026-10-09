package com.localfirst.realtimetranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import com.localfirst.realtimetranslator.model.SessionState

@Composable
fun RealtimeTranslatorApp(
    state: SessionState,
    captureStatus: CaptureStatus?,
    message: String?,
    onStart: (AudioSource) -> Unit,
    onStop: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(AudioSource.SYSTEM) }
    val idle = state is SessionState.Idle
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("实时翻译 V0.1", style = MaterialTheme.typography.headlineMedium)
                Text("R2 音频采集验证：真实采集系统音频或麦克风，但尚不识别或翻译。")
                Text("English → 简体中文（语音识别和翻译将在 R3/R4 实现）")
                Spacer(Modifier.height(8.dp))
                Text("选择音频来源（静音不代表采集失败，也不会自动切麦）")
                Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected == AudioSource.SYSTEM,
                        onClick = { selected = AudioSource.SYSTEM }, enabled = idle)
                    Text("系统声音", modifier = Modifier.padding(top = 12.dp, end = 16.dp))
                    RadioButton(selected == AudioSource.MICROPHONE,
                        onClick = { selected = AudioSource.MICROPHONE }, enabled = idle)
                    Text("麦克风", modifier = Modifier.padding(top = 12.dp))
                }
                Text("当前状态：" + when (state) {
                    is SessionState.Idle -> "空闲"
                    is SessionState.PreparingModels -> "准备音频采集"
                    is SessionState.RequestingProjection -> "等待系统授权"
                    is SessionState.Starting -> "启动采集设备"
                    is SessionState.Running -> "正在采集音频（不存储）"
                    is SessionState.AwaitingMicrophoneConfirmation -> "需要确认麦克风回退"
                    is SessionState.Stopping -> "停止中"
                    is SessionState.Failed -> "启动失败：" + state.notice.name
                })
                if (state is SessionState.Running && captureStatus != null) {
                    Text("已读取音频帧：${captureStatus.frames}；丢失：${captureStatus.droppedFrames}")
                    Text("瞬时峰值：${captureStatus.peakPermille / 10.0}%（仅用于采集验证）")
                    if (captureStatus.peakPermille == 0) Text("检测到静音；可能是视频静音或该应用限制系统音频采集。")
                }
                val previousNotice = (state as? SessionState.Idle)?.notice
                if (previousNotice != null) {
                    Text("上次状态：" + previousNotice.name)
                }
                if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { onStart(selected) }, enabled = idle) {
                    Text("开始采集")
                }
                OutlinedButton(onClick = onStop, enabled = !idle) {
                    Text("停止采集")
                }
                Text("R2：只在内存中短暂处理 PCM 并丢弃；不写音频文件，不产生字幕。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
