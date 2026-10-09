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
import com.localfirst.realtimetranslator.model.SessionState

@Composable
fun RealtimeTranslatorApp(
    state: SessionState,
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
                Text("R1 工程演示：尚未采集音频、识别英文或生成中文。")
                Text("English → 简体中文（后续 R2–R4）")
                Spacer(Modifier.height(8.dp))
                Text("选择会话权限模式")
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
                    is SessionState.PreparingModels -> "准备模拟会话"
                    is SessionState.RequestingProjection -> "等待系统授权"
                    is SessionState.Starting -> "启动模拟会话"
                    is SessionState.Running -> "模拟运行中（无真实音频）"
                    is SessionState.AwaitingMicrophoneConfirmation -> "需要确认麦克风回退"
                    is SessionState.Stopping -> "停止中"
                    is SessionState.Failed -> "启动失败：" + state.notice.name
                })
                if (state is SessionState.Idle && state.notice != null) {
                    Text("上次状态：" + state.notice.name)
                }
                if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { onStart(selected) }, enabled = idle) {
                    Text("开始模拟会话")
                }
                OutlinedButton(onClick = onStop, enabled = !idle) {
                    Text("停止模拟会话")
                }
                Text("提示：本阶段仅验证授权、通知和安全生命周期，不代表翻译功能已完成。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
