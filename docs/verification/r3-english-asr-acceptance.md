# R3 英文实时字幕：真机验证清单

**开发分支：** `feat/r3-english-subtitles`
**R2 稳定基线：** `stable/r2-v0.2.1`（不在该分支修改代码）
**范围：** 真实 16 kHz PCM → sherpa-onnx 英文流式识别 → English partial/final 字幕。**不包含中文翻译、ML Kit、DeepSeek、网络 ASR、音频落盘。**

## 首次准备

1. 使用 PowerShell 显式执行 `./scripts/install-sherpa.ps1`，校验官方 `v1.13.8` AAR 的 SHA-256 后安装到忽略版本管理的本地 Maven 目录。Gradle 不会默默下载该原生 AAR。
2. 下载官方英语流式模型 `sherpa-onnx-streaming-zipformer-en-2023-06-26`，模型页面：
   https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/tree/main
3. **只需要以下四个文件**，放到设备可以使用系统文件选择器访问的**同一文件夹**：
   - `encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx`
   - `decoder-epoch-99-avg-1-chunk-16-left-64.onnx`
   - `joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx`
   - `tokens.txt`
4. 安装 APK，在应用中点击 **导入英文模型文件夹**，选中四文件所在目录。程序按固定 SHA-256 校验三个 ONNX 文件，检查 tokens 头部和大小；经校验后写入应用私有目录。失败不会使旧模型失效。**tokens 的 SHA256 尚未固定，因此不能将其内容验证等同于完整四文件的来源认证。**
5. 选择系统音频或麦克风，并点击 **开始英文识别**。系统音频仍然要求新一次 MediaProjection 授权，不会自动切换到麦克风。

## 构建与检查

```powershell
./scripts/install-sherpa.ps1
./gradlew.bat :core-model:test :core-audio:testDebugUnitTest :core-asr:testDebugUnitTest :service:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## R3 真机门槛（未完成之前不得宣称 R3 稳定）

- 播放支持录制的英文视频，检查英文 partial 能持续出现，final 句子固定且不可被后一句覆盖；不涉及中文译文。
- 视频暂停和持续 30 秒无声时没有凭空字幕，不会弹出麦克风捕获；继续播放可以恢复识别。
- 连续 10–15 分钟：观察字幕出现延迟、final 丢失、错误重写、队列丢帧、CPU/温度、模型加载时间。
- 快语速、长句、技术术语、多次短停顿：记录识别错误和分句表现，区分 ASR 误识别与稳定器造成的丢字。
- 应用内停止、通知栏停止、点击通知返回应用、后台返回及系统停止屏幕分享都必须沿用 R2 的安全退出路径。
- 每次停止后应清空会话内存字幕；新的会话不得收到上一次异步回调或复用投屏授权。
- 离线识别无网络依赖；运行时没有翻译 SDK、DeepSeek 请求、音频保存及日志中 PCM/识别文本。
- 未导入模型时必须明确提示并拒绝启动，而非空运行或悄悄使用其他 ASR。

**状态：** 代码实施进行中；以 Actions 编译测试结果与用户真机观察分别核验。CI 通过也不证明真机识别效果达标。
