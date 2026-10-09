# Android 实时翻译器 V0.1 设计规格

**状态：** 原设计基线已确认；2026-10-09 授权先实施 R0 + R1（模拟骨架），R2–R6 实现尚未授权；已选择方案 B（本地零遥测优先，可选手动云服务，2026-10-09），具体本地模型待验证

**目标用户：** 单一私人用户  
**目标平台：** 用户自己的 Android 10（API 29）及以上手机  
**核心原则：** Local-first；音频、识别文本、字幕、术语表和设置不上传到任何服务器

> 本文第 1–16 节保留原基线以便比较。第 17 节记录源码复核发现的纠错及建议，尚未获用户批准，不得把旧计划或本次文档修改视为继续实现的授权。尤其第 3 节的“零遥测/仅模型下载联网”与 ML Kit 官方披露存在待决冲突。

## 1. 目标

构建一个可侧载安装的 Android APK，在其他应用播放英语视频时捕获系统内部音频，在设备上完成流式英语语音识别、字幕稳定、术语保护和英译中，并同时在应用内和系统悬浮窗显示字幕。

V0.1 优先服务用户自己的手机和使用方式，不追求应用商店发布或大范围机型兼容。

## 2. 明确范围

### 2.1 V0.1 包含

- Android 10+ 的 `AudioPlaybackCapture` 系统播放音频捕获。
- 当目标应用禁止内部音频捕获时，在明确提示并经用户确认后切换到麦克风。
- sherpa-onnx 英文 Streaming Zipformer INT8 本地流式 ASR。
- C 方案字幕稳定器：英文 partial 快速变化，中文只在文本稳定后更新，final 句锁定。
- 翻译前本地术语保护，避免 GLB、Three.js 等术语被错误改写。
- ML Kit 设备端 English → 简体中文翻译。
- 应用内双语/仅中文字幕和系统悬浮字幕。
- 本地模型管理、术语表、字幕设置、性能模式和本地字幕历史。
- 前台服务、持续通知、主动停止入口和权限失败降级。
- 模型下载后进行 SHA-256 校验并保存到应用私有目录。

### 2.2 V0.1 不包含

- 登录、注册、账号、多用户或跨设备同步。
- 自建后端、云端 ASR、云端翻译、OpenAI 或 DeepSeek Provider。
- Analytics、Crashlytics、广告、遥测或远程日志。
- 音频录音文件持久化或上传。
- 自动识别多语言、日语/韩语翻译、中文转英文。
- Google Play 发布流程和面向所有厂商 ROM 的兼容承诺。
- QNN/NPU 加速；V0.1 先使用 sherpa-onnx 的 CPU/NNAPI 可用路径，后续再评估 Snapdragon 专项优化。

## 3. 隐私与数据边界

1. PCM 音频只存在于有限容量内存缓冲区；消费或丢弃后不可恢复，不写入文件、数据库或日志。
2. ASR partial、stable segment 和翻译结果只在本机进程中流转。
3. 字幕历史、设置与术语表只保存到应用私有存储；V0.1 不提供同步或分享。
4. sherpa-onnx 模型下载请求只包含静态模型 URL，不包含音频、字幕、设备中的用户内容或术语表。
5. ML Kit 翻译在设备上执行；其语言模型下载是唯一允许的网络用途之一，不向翻译服务提交字幕文本。
6. 不引入任何统计、广告、崩溃收集或远程日志 SDK。
7. 日志不得包含 PCM、完整识别文本、完整翻译文本或术语表内容；debug 日志只记录状态、耗时和匿名错误类型。
8. 音频捕获、麦克风与悬浮窗都由用户在可见界面主动启动或授权，不在后台偷偷请求或启动。

## 4. 用户流程

### 4.1 首次准备

1. 用户侧载并打开 APK。
2. 应用检查 sherpa-onnx 英文模型和 ML Kit 英中语言模型。
3. 缺少 ASR 模型时展示大小、来源和用途；用户主动下载。
4. 下载到临时文件，校验 SHA-256，原子移动到应用私有模型目录；校验失败则删除临时文件并允许重试。
5. 缺少翻译模型时由用户主动下载，下载完成后才允许进入就绪状态。
6. 悬浮字幕首次启用时请求系统悬浮窗权限。

### 4.2 开始实时翻译

1. 用户在首页选择“系统声音”或“麦克风”、字幕模式和性能模式。
2. 系统声音模式下，点击“开始实时翻译”后请求一次 MediaProjection 授权。
3. 授权成功后立即启动 `mediaProjection` 类型前台服务，再创建捕获会话。
4. PCM 进入有限容量 channel，经重采样变为 16 kHz mono，再由单独的 ASR worker 消费。
5. ASR partial 立即更新英文字幕；稳定器产生 stable segment 后才触发术语保护和本地翻译。
6. 同一个 `SubtitleState` 驱动应用内字幕与悬浮字幕。
7. 用户从应用、悬浮控制器或前台通知停止；停止后释放 AudioRecord、MediaProjection、识别器、翻译器和悬浮窗。

### 4.3 捕获失败回退

若内部音频持续无有效 PCM、目标应用禁止捕获或 AudioRecord 初始化失败：

- 停止当前捕获，保持应用稳定。
- 明确说明“该应用可能不允许捕获系统声音”。
- 提供“切换到麦克风”操作，不自动启用麦克风。
- 用户确认后请求 `RECORD_AUDIO`，并从可见 Activity 启动 `microphone` 类型前台服务。

## 5. 系统架构

采用单 Activity、Compose UI、前台 Service 和独立功能模块：

```text
app/
  MainActivity + navigation + screens
core-audio/
  InternalAudioCapture + MicrophoneCapture + AudioSourceMonitor + AudioResampler
core-asr/
  AsrEngine + SherpaOnnxEngine + AsrResult
core-subtitle/
  SentenceStabilizer + SubtitlePipeline + SubtitleState
core-translation/
  TranslationProvider + MlKitTranslationProvider + GlossaryProcessor
service/
  RealtimeTranslationService + session coordinator
overlay/
  SubtitleOverlayService + FloatingController
data/
  SettingsRepository + ModelRepository + GlossaryRepository + SubtitleHistoryRepository
```

模块间只通过 Kotlin 接口与不可变数据类型通信。UI 不直接操作 AudioRecord、sherpa-onnx 或 ML Kit；Service 不直接拼装 UI。

## 6. 数据流与并发

```text
AudioCapture thread
  → bounded PCM channel
Audio resample/ASR worker
  → AsrResult.Partial / AsrResult.Final
SentenceStabilizer
  → live English + optional StableSegment
translation worker
  → glossary protect → ML Kit → glossary restore
StateFlow<SubtitleState>
  → Compose screen + overlay
```

- `AudioRecord.read()` 只采集 PCM，不执行推理、翻译或 UI 更新。
- ASR、翻译和 UI 分属独立调度器/线程域。
- PCM channel 为有限容量；背压时优先丢弃过旧 partial 工作，绝不形成十几秒积压。
- 翻译请求使用 `collectLatest`/代次编号取消过期结果，旧翻译不得覆盖新字幕。
- 所有资源均归单一 session 所有，`stop()` 必须幂等。

## 7. C 方案字幕稳定器

### 7.1 状态

- `liveEnglish`：当前 ASR partial，可约每 200 ms 更新。
- `stableEnglish`：已达到稳定条件、正在或已经翻译的英文片段。
- `translatedChinese`：stable segment 的最新有效翻译。
- `isFinal`：ASR endpoint/final 到达后锁定该句。
- `updatedAt`：用于静音淡出与延迟测量的单调时钟时间。

### 7.2 稳定规则

- 相同规范化文本连续出现，或新 partial 仅在末尾增长，均累计稳定时间。
- 平衡模式下，文本保持约 900 ms、遇到 ASR final、或检测到强句末标点时发出 stable segment。
- final 永远立即发出并锁定；已锁定句不会被后续 partial 回写。
- 完全相同的 stable segment 不重复翻译。
- 只包含空白或低置信噪声的片段不触发翻译。
- 长句达到安全字符/时长阈值时在最近的词边界分段，避免无限增长。
- 静音 30 秒期间不提交空翻译；可见字幕在配置的空闲时间后淡出，但历史中的 final 句不被删除。

性能模式只改变 partial 节流、稳定窗口和 worker 并发参数，不改变语义：

| 模式 | partial UI 最小间隔 | 稳定窗口 | 目标 |
|---|---:|---:|---|
| 省电 | 400 ms | 1,200 ms | 更低 CPU/发热 |
| 平衡（默认） | 200 ms | 900 ms | 可读性与延迟平衡 |
| 高性能 | 100 ms | 600 ms | 更低延迟、允许更高功耗 |

## 8. 术语保护

- 术语表是本地的 `source → target/display` 映射。
- 翻译前按最长匹配优先、忽略英文大小写边界，将术语替换为不可冲突的占位符。
- ML Kit 返回后恢复占位符；若占位符缺失或损坏，则保留原英文术语并记录匿名错误类型。
- 默认术语仅提供少量技术词示例，用户可以新增、修改、禁用和删除本地条目。

## 9. 模型管理

- V0.1 只管理英文 Streaming Zipformer INT8 ASR 模型。
- ASR 模型不打入 APK；manifest 描述模型版本、各文件 URL、字节数和 SHA-256。
- 下载支持取消和失败重试；只在完整校验成功后标记可用。
- 模型位于应用私有目录，删除操作要求用户确认且运行中的模型不可删除。
- `AsrEngine` 和 `TranslationProvider` 均保留可替换接口，但 V0.1 只有 `SherpaOnnxEngine` 和 `MlKitTranslationProvider` 两个实现。

## 10. Android 生命周期与权限

- 最低 API 29；编译 SDK 37，目标 SDK 36。
- 系统声音：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`，Service 声明 `mediaProjection` 类型。
- 麦克风：`RECORD_AUDIO`、`FOREGROUND_SERVICE_MICROPHONE`，仅从可见 Activity 经用户操作启动。
- Android 13+：运行前请求通知权限；拒绝时给出明确说明，但不崩溃。
- 悬浮窗：`SYSTEM_ALERT_WINDOW`，拒绝后应用内字幕仍可用。
- 每次 MediaProjection 会话都重新获取用户同意，不复用失效 token。
- 前台通知持续显示当前音源、运行状态和“停止”动作。

## 11. UI

首页保持单屏、低操作成本：

```text
实时翻译                                      设置

音源        ● 系统声音   ○ 麦克风
翻译          English → 中文
字幕        ● 中英双语   ○ 仅中文

┌──────────────────────────────────┐
│ We need to export the GLB file.  │
│ 我们需要导出 GLB 文件。           │
└──────────────────────────────────┘

             [ 开始实时翻译 ]
```

设置页 V0.1 只包含字幕大小、字幕位置、字幕模式、性能模式、术语表和模型管理。运行中首页显示准备、请求权限、加载模型、监听、降级提示、停止中和错误状态；不得用无限 loading 隐藏错误。

## 12. 延迟与性能目标

正常英语视频、平衡模式、用户目标手机上：

- ASR partial 产生：目标 < 500 ms。
- 英文字幕可见：目标 0.3–0.8 秒。
- 第一条稳定中文字幕：目标 1–2 秒。
- 完整句 final 翻译：目标 1.5–3 秒。

`PerformanceMonitor` 只在本机内存中监控 ASR 推理耗时、PCM 队列长度、字幕端到端延迟和系统 Thermal Status。严重热状态时降低 partial 频率并延长稳定窗口，不上传监控数据。

## 13. 错误处理

- 权限拒绝、取消投屏、投屏 token 撤销、AudioRecord 失败、模型缺失/校验失败、ASR 初始化失败、翻译模型缺失和悬浮窗拒绝都映射为明确的可恢复 UI 状态。
- session 内任何致命错误都触发一次幂等清理，禁止留下录音、投屏或悬浮窗资源。
- 翻译暂时失败时保留英文字幕并显示本地错误提示；不得停止 ASR。
- Service 被系统重建时不自动恢复捕获，回到“需要用户重新开始”状态。

## 14. 测试策略

- JVM 单元测试：SentenceStabilizer、GlossaryProcessor、session reducer、积压丢弃策略、过期翻译抑制、模型 SHA-256 校验。
- Android instrumentation：权限结果映射、前台服务启动/停止、Compose 首页状态、overlay 状态渲染。
- fake adapters：单元和 UI 测试使用内存 AudioSource、AsrEngine、TranslationProvider 和单调 FakeClock，不访问网络或真实麦克风。
- 真机验收：用户自己的 Android 10+ 手机。

## 15. V0.1 验收标准

1. **YouTube 普通英语视频 10 分钟：** 不崩溃、不断流、英文与中文持续更新、无明显字幕积压。
2. **快速科技/编程视频：** GLB、Three.js 等术语保持正确；长句自动分段；不存在无限增长句。
3. **静音 30 秒：** 不反复提交空翻译、不产生大量 ASR 幻觉、字幕按设置淡出。
4. **后台 20–30 分钟：** 切到 YouTube 后前台 Service 存活，悬浮字幕持续可用，通知可停止。
5. **禁止内部音频捕获的应用：** 不崩溃，识别捕获不可用，提示麦克风回退，并只在用户确认后切换成功。
6. **权限拒绝/撤销：** 悬浮窗拒绝、麦克风拒绝、取消 MediaProjection、运行中撤销权限均优雅失败并完整释放资源。
7. **隐私检查：** APK 无统计/崩溃 SDK；抓包与日志检查未发现音频、字幕、术语表或历史离开设备。

## 16. 完成定义

V0.1 只有在自动化测试通过、debug APK 可构建、上述真机验收记录完成、且隐私检查通过后才算完成。“能编译”不等于完成。

## 17. 2026-09-04 开源复核：待确认修订

依据：[源码研究报告](../../research/2026-09-04-open-source-android-review.md)。本节区分事实纠错和产品/实现建议；批准后再将相关内容合并到正文，不默默改写已确认基线。

### 17.1 必须纠正的事实与承诺

- 第 4/10 节：系统播放捕获与麦克风都需要 `RECORD_AUDIO`。请求该系统权限不意味着自动启用麦克风；用户对音源选择的同意仍单独处理。
- 第 4.3/15 节：无 PCM 或持续静音不能确定目标应用禁止捕获。状态改为“未检测到可用系统音频/原因未确定”；明确 read 错误或 projection 撤销另外映射，绝不静默开麦。
- 第 3 节：ML Kit 保证翻译输入/输出留在设备，但官方披露 API 指标网络传输。因此不能声称该 SDK 仅下载模型时联网，也不能在未验证前承诺零遥测。用户未确认前，不放宽隐私目标、不接入实际翻译 SDK。
- 第 3 节“消费或丢弃后不可恢复”过强：应表述为不落盘、不日志记录、缓冲有界且不保留引用；不承诺托管内存中即时安全擦除。
- 第 7 节：ASR 不保证输出标点或可用的 calibrated confidence，不能把强标点/低置信阈值作为基线成立前提。
- 第 8 节：占位符缺失后不能保证恢复到正确位置。恢复验证失败要明确降级到英文/已有有效译文，不展示损坏 token。

### 17.2 建议采用的架构细化

- 保持私人、Android 10+、Local-first、英→中、系统音频优先/确认后麦克风、Streaming ASR、C 方案、悬浮字幕与术语表。不新增账号、服务器、统计 SDK、TTS、云 Provider。
- 采用 implementation plan 已列出的 `core-model` 共享契约模块；`GlossaryProcessor` 归 `core-subtitle`。翻译适配器只依赖契约，不反向依赖字幕 pipeline。
- 只保留一个前台 `RealtimeTranslationService` 作为 session owner；`overlay` 模块改由 `SubtitleOverlayController` 管理 WindowManager，不再另起 `SubtitleOverlayService`。
- 音源切换为停止旧会话并等待清理后，由可见 Activity 发起新会话。纯系统捕获仅启用相应 mediaProjection FGS 类型；麦克风会话按 API 支持启用 microphone 类型。`START_NOT_STICKY`，重复/空启动不创建新捕获。
- 先直接请求 16 kHz mono PCM16，约 100 ms 音频块；约 200 ms 是 UI partial 节流，不是采集周期。只有目标设备需要时才加经过验证的重采样适配。
- ASR 先 CPU 路径，删除 CPU/NNAPI 的双路径承诺。优先验证 2023-06-26 英文 streaming Zipformer 模型作为候选，未验证前不锁定为最终模型。AAR、模型文件组合、校验及许可单独固定。
- 模型先使用必需文件直下载、逐文件 SHA + 整套 staging 原子提交；ML Kit 模型由 SDK 单独管理。禁止因引用上游而退化为“文件存在即 ready”。

### 17.3 C 方案及并发契约

- 增加 `sessionId/audioEpoch/utteranceId/revision` 与术语/设置版本。不同句说相同文本不应全局去重。
- 稳定计时针对未变化的词前缀/文本，包含无新 ASR 回调时的 timer tick；追加的新后缀不能继承前缀的稳定年龄。
- endpoint 立即锁英文并优先请求最终中文；最终中文只补到所属句子。下一句 partial 不取消前一句 final 待办，也不接受旧句结果覆盖当前句。
- 同句旧 stable 请求可合并；final 待办有界且过载显式处理。`collectLatest` 不能无差别应用到所有句子。
- PCM 溢出需发出可观察的 discontinuity/gap，重置 ASR stream/epoch 并撤销旧候选；不直接把丢帧后音频接到旧流。中断不伪装成完整 final。
- 停止先解除音频 read 阻塞，等待 worker 不再访问 native handles，再释放。不得主线程等待或超时后无条件释放仍被使用的对象。
- 900 ms/200 ms 仍作为平衡模式起点；其他模式与热降级具体参数待真机测量，不声称降低 UI 更新频率必然解决 ASR 算力不足。

### 17.4 增补验收与许可门槛

- 七类原验收保持；补充重复 start/stop、切源、队列过载、timer 无输入、同文不同句、final 晚返回、模型损坏及占位符损坏测试。
- 历史仅保存 final，明确容量、清空及备份排除。禁用云备份并检查 Android 12+ 提取规则；不复制上游 `allowBackup=true`、WAV 调试录音或完整字幕日志。
- 原生 AAR 检查 ABI、16 KB 兼容性、打包依赖冲突；不复制屏蔽对齐 lint 或随意选取同名 ONNX Runtime 的配置。
- InstantVoiceTranslate MIT 代码如实际移植，保留作者版权、完整许可及来源/修改台账；live-captions 该快照没有明确应用代码许可，不移植。具体模型和 AAR 内第三方依赖逐项核查 LICENSE/NOTICE。
- ML Kit 输入/输出隐私、SDK 指标传输、翻译署名要求分别验收；“断网能用”和依赖关键词扫描不能替代隐私审计。

**批准门槛：** 用户确认修订路线，并明确是否接受 ML Kit 官方披露的 API 指标传输。若要求连 SDK 指标也不发送，现有 ML Kit 选型仍待解决，不视为已满足。

## 18. 2026-10-09 用户确认：方案 B 与可选云翻译（覆盖冲突的历史基线）

用户选择自主管理的本地翻译模型作为默认模式，**不采用 ML Kit SDK**。保留 `TranslationProvider` 抽象并允许未来自行配置 DeepSeek 等云端翻译服务，但云端翻译**绝不能**以“零遥测、完全离线”宣传。旧文的 ML Kit 选型、ML Kit 模型准备、只允许本地 Provider / 排除云端 Provider 等陈述是历史基线，本节与 [决策记录](../../decisions/2026-10-09-provider-architecture.md) 优先于其冲突内容。该决定不是授权直接实施云 Provider。

**本地默认模式：** 模型/推理引擎仍待技术选型、许可审核、设备内存/性能和联网审计；禁止统计/遥测、联网内容上传及自动云回退。模型可在用户主动操作时下载，运行时应能全离线。

**可选云模式：** 用户明确开启、选择提供商和输入 API key 后，只允许发送本地 ASR 产出的稳定英文句子（及用户明确允许的少量上下文）到其选择的 API；永不上传 PCM/音频，显示正在使用云端翻译及相应隐私/费用提醒。云端请求失败不会静默改为其他云服务。API key 使用安全存储，不在日志、代码库或 APK 资源中出现。

**一致性与测试：** 两种 Provider 共用 `sessionId/audioEpoch/utteranceId/revision` 的结果身份及 final 优先级，断网、取消、切换 Provider、旧译文晚返回、欠费和限流不得把旧字幕覆盖到新句。隐私审计区分本地零出站与用户授权的云端文本出站。
