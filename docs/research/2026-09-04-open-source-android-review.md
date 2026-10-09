# Android 实时翻译器：开源实现复核

日期：2026-09-04。状态：研究完成，建议待用户确认；不授权继续实现。

## 1. 结论

继续维护自己的模块化架构。InstantVoiceTranslate 适合作为系统捕获及 sherpa 适配的局部参考；live-captions 适合作为麦克风 → streaming ASR → ML Kit 的流程对照，不能作为可直接移植的 MIT 代码库。

两者都没有实现我们的完整 C 方案、系统悬浮字幕、技术术语保护及严格模型校验。这些任务不能用“参考项目已有”替代。

有一项需要先澄清的产品冲突：**ML Kit 本地翻译不上传输入/输出文本，但官方披露 SDK 会发送使用与性能指标。**“不上传音频/字幕”与“绝无遥测、联网仅模型下载”不是同一承诺。当前不擅自放宽后者，也不擅自更换翻译引擎。[官方隐私说明](https://developers.google.com/ml-kit/terms)、[Android SDK 数据披露](https://developers.google.com/ml-kit/android-data-disclosure)

## 2. 研究范围与版本

| 对象 | 固定快照 | 许可证检查 |
|---|---|---|
| InstantVoiceTranslate | `1651c66f5191b092365745273f4d8eb85b5cb9be`，提交时间 2026-07-14 | 根目录 MIT，Copyright (c) 2026 Iliya Brook |
| live-captions | `e1eee62f57d935ab5221bf4221be8a3345fb82f8`，提交时间 2026-06-06 | 该快照未发现覆盖应用代码的 LICENSE、NOTICE 或许可声明；Gradle wrapper 的 Apache 头不覆盖应用代码 |
| 当前项目 | 已提交 spec / plan，加本轮前已有的未提交 plan 调整与 Android 骨架 | 本轮未改功能代码、未构建、未运行上游项目 |

检查方式：下载两个公开仓库快照到系统临时目录，阅读相关 Kotlin、manifest、构建配置及许可证，并与 Android、Google ML Kit、sherpa 官方资料交叉核对。下述缺陷是**静态代码风险判断**，不是已复现的运行故障。没有验证目标手机、性能、识别准确率、网络流量或上游 APK。

固定源码入口：[InstantVoiceTranslate](https://github.com/IliyaBrook/InstantVoiceTranslate/tree/1651c66f5191b092365745273f4d8eb85b5cb9be)、[live-captions](https://github.com/samoylenkodmitry/live-captions/tree/e1eee62f57d935ab5221bf4221be8a3345fb82f8)。以下 I-/L- 来源均固定到这两个提交。

## 3. 七项实现比较

| 主题 | InstantVoiceTranslate | live-captions | 对我们路线的影响 |
|---|---|---|---|
| 系统内部音频 | `AudioPlaybackCaptureConfiguration` + `AudioRecord`；MEDIA/GAME/UNKNOWN；16 kHz mono PCM16 | 未实现；无 MediaProjection 入口/权限 | 参考 I-Audio 的构造方式；不用屏幕帧或 VirtualDisplay；真机验证前置 |
| 麦克风及切换 | 同一管理器按 source 选择 `MIC`；UI 修改 source 设置，启动时固定；不是完整运行中热切换 | `VOICE_RECOGNITION`，独立采集线程；仅麦克风 | 两个适配器、一个接口；切换采用停止旧会话 → 可见 Activity 授权 → 新会话，不做无缝热切换 |
| 前台服务 | 先 `startForeground` 再 `getMediaProjection`，注册 `onStop`；但 `START_STICKY`、资源所有权分散 | `START_NOT_STICKY`，服务拥有采集/解码/翻译；退出等待有超时竞争风险 | 保留自己的 session reducer、单一资源所有者及幂等清理；将 Task 8 前移 |
| sherpa Streaming | `OnlineRecognizer`、转 Float、ready/decode、partial、endpoint/reset；额外 RMS gate、强制切句、标点模型 | 简洁 `OnlineRecognizer`；changed partial、endpoint final/reset；`inputFinished` 排空 | 薄适配器即可；CPU 基线，不引入多引擎、额外 VAD/标点模型或 NNAPI 承诺 |
| ASR 模型 | 单文件下载到 filesDir，临时文件 rename；ready 主要检查存在 | tar.bz2 下载到 cache，按白名单解压，filesDir 保存，最小大小检查 | 单英文模型、固定文件 manifest；直接下载必需文件可免解压依赖；SHA-256/事务安装保留 |
| ML Kit | **没有**；实际为在线翻译及可选 NLLB | 有 `TranslatorOptions`、download、translate、close，但阻塞调用 | 依据官方 SDK 独立实现；下载在准备阶段完成，翻译独立 worker，不阻塞 ASR |
| partial/final | partial 更新 UI，分段后才翻译；并发翻译可乱序覆盖字幕 | partial 更新 UI，final 后翻译再追加历史；阻塞解码 | 都不是 C 方案；稳定前缀/定时器、句子 ID、revision、final 锁及乱序防护必须自建 |

证据：[I-Audio]、[I-Service]、[I-ASR]、[I-Model]、[L-Audio]、[L-Service]、[L-ASR]、[L-Translator]、[L-Model]（文末提供固定文件链接）。

## 4. 值得借鉴的代码边界与必须避开的风险

### 4.1 捕获与音源切换

**可借鉴：** I-Audio 的 AudioRecord builder、usage 过滤、PCM16 → Float；L-Audio 的独立采集线程及 `VOICE_RECOGNITION` 选型。[I-Audio] [L-Audio]

**不能原样照搬：**

- I-Audio 忽略 `trySend()` 失败，无法让下游知道丢帧；`finally`、`awaitClose` 与 `stopCapture` 都可能释放同一 AudioRecord。创建/启动失败也需单独保护。
- I-Audio 的固定 RMS 门限及 I-ASR 的约 300 ms 低能量切断，可能误伤安静语音；应先保留完整流式输入与 sherpa endpoint，再凭真机数据决定是否需要门控。低 RMS 不证明没有语音。
- L-Audio 的工作线程异常不能被 `audio.start()` 外层同步 try/catch 捕获；负 read 返回也没有明确错误映射。需要把读错误和线程失败传回 session。
- 无信号可能是播放器暂停、静音、usage 不匹配、政策限制或设备行为；不能根据零 PCM 宣称“检测到应用禁止捕获”。提示应为“未检测到可用系统音频”，让用户检查播放、重试或主动选择麦克风。

**对现有 spec 的事实纠正：** 系统播放捕获同样需要运行时 `RECORD_AUDIO`，并非只有麦克风才需要。授权录音权限不等于用户同意开启麦克风，二者在 UI 中必须区分。捕获还受同一用户 profile、播放器 usage/capture policy 限制，不能承诺所有应用都可捕获。[Android 音频捕获官方文档](https://developer.android.com/media/platform/av-capture)

**简化建议：** 首先请求 16 kHz mono PCM16，按约 100 ms 块采集；UI 仍按约 200 ms 更新。两者不是同一频率。暂不自写通用重采样库：仅当目标设备实际不支持时，再在既有 AudioCapture 边界内加入经过验证的采样率适配。不可通过错误标记 sampleRate 假装重采样。

### 4.2 Foreground Service 与停止

I-Service 正确示范了授权结果由 UI 传入 Service、前台化后再获取 MediaProjection，以及撤销回调。[I-Service]

不采用它在纯系统音频模式下同时激活 `microphone | mediaProjection` 的做法；manifest 可声明支持两种能力，但运行时按当前音源选择类型并处理 API 29/30+ 差异。麦克风启动须满足可见 Activity 与运行时权限要求。[FGS 类型文档](https://developer.android.com/develop/background-work/services/fgs/service-types)

清理风险：

- I-Service `onDestroy()` 主要取消协程，没有覆盖完整音频/projection/native 资源释放；ASR 独立 scope 的 cancel 没有等待 native 调用退出。
- L-Service `worker.join(3000)` 超时后仍 release engine/translator；若翻译或解码尚未返回，存在并发使用已释放资源的风险。模型初始化在 `onStartCommand` 的调用链中同步执行，也可能卡住主线程。[L-Service]

我们保留 `START_NOT_STICKY`、空/重复 start 拒绝、明确 session ID、一个清理入口；先解除阻塞 read，等待采集/ASR worker 退出，再释放 native handles。等待不能在主线程执行；不能用“等 3 秒然后无条件 release”伪装安全停止。projection 回调与主动停止均进入同一个幂等流程。进程已被杀死时不假定 `onDestroy` 一定执行，重启也不自动恢复授权。

建议取消独立 `SubtitleOverlayService`，保留 `overlay` 模块和 `SubtitleOverlayController`，由唯一的 `RealtimeTranslationService` 管理。减少生命周期组合，不合并音频/识别/翻译模块，也不引入远程服务。

### 4.3 sherpa Streaming 与模型选择

两个项目都使用真正的 OnlineRecognizer streaming 路径，而非把 Whisper 分片当 streaming。核心流程是 accept waveform → decode while ready → changed partial → endpoint final → reset。L-ASR 另有停止时 `inputFinished()` / drain 的清晰边界；可从官方 API 独立实现该模式。[I-ASR] [L-ASR]

模型不是同一个版本：

- I-Model：英文 `2023-06-21`，`modelType=zipformer`，encoder 约 188 MB（源码估值）。
- L-Catalog：英文 `2023-06-26`，`modelType=zipformer2`，chunk-16/left-128 文件组合；源码估计保留文件约 70 MB，但下载整包约 296 MB。
- 这些数字不是本轮测量。小模型不保证技术词更准，较大模型也不保证目标手机更适合。

建议优先验证 `2023-06-26` 英文 streaming INT8 组合：encoder/joiner INT8 + **非 INT8 decoder** + tokens，不能把所有文件名都机械改成 `.int8.onnx`。这一组合可在 [sherpa 官方示例](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html) 独立确认；[模型卡](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/blob/main/README.md) 标注 Apache-2.0。最终下载 revision、SHA、实际大小与 AAR 兼容性尚未锁定，不作为已安装资源。

先仅 CPU，少量固定线程参数在真机测量后确定。不承诺 NNAPI/QNN，不在运行中直接修改已创建 recognizer 的线程参数。不复制 I 项目额外 NLLB ONNX Runtime、`pickFirst libonnxruntime.so` 或屏蔽 16 KB 对齐 lint 的配置；对自己的 AAR/ABI 做真实兼容检查。[I-Build] [Android 16 KB 页说明](https://developer.android.com/guide/practices/page-sizes)

### 4.4 模型下载与本地存储

I-Download 的 `.tmp` + rename 可作为基础思路，但没有 SHA-256，也不检查 rename 返回值。I-Model 的存在性判定和 L-Store 的文件大小下限都不能证明模型完整可信。[I-Download] [I-Model] [L-Store]

L-Model 对 tar.bz2 **多流解压**的处理是有用的经验；不过我们只需四个文件，直接从固定模型 revision 下载可以省去整包、解压库、额外磁盘占用及归档处理风险。若以后不得不用归档，需另加多流、普通文件白名单、重复条目、大小上限与路径安全测试；不直接搬 L 代码。

保留并补强原设计：用户主动下载、HTTPS/重定向限制、精确大小与逐文件 SHA、staging 目录、整套文件验证后原子切换、取消/断网/磁盘不足恢复、失败保留旧版本、运行中不可删除。下载循环需响应取消；单个 `.part` rename 不等于整套模型的事务提交。

ML Kit 模型由 SDK 管理，与我们可校验的 ASR 文件分开；不提取/重打包 ML Kit 私有模型，也不假称由应用验证其 SHA。模型管理 UI 可以统一，存储机制不必强行统一。[ML Kit 模型管理](https://developers.google.com/ml-kit/language/translation/android)

### 4.5 ML Kit 集成及隐私门槛

L-Translator 说明适配层确实很薄，但其 `Tasks.await()` 在 L-Service 的 ASR 解码线程上运行。prepare 下载期间已经开始采集，且队列是无界 `LinkedBlockingQueue`；下载/翻译变慢会导致内存和延迟增长。失败返回原英文却被当作译文追加，也会掩盖状态。[L-Translator] [L-Service]

我们采用官方 `ENGLISH → CHINESE` 配置、显式准备模型、独立翻译 worker、异步 Task 等待、结构化错误，失败保留原文但明确“翻译不可用”。不让 Task 的取消等同于底层 native 工作已经停止；用代次校验阻止过期结果，生命周期关闭与在途任务协调。[官方接入说明](https://developers.google.com/ml-kit/language/translation/android)

**隐私待决：**

- 官方保证处理的输入及输出不发给 Google，但会发送 API 使用/性能等指标。
- Android 数据披露还列出翻译的源/目标语言，以及远程配置相关依赖。不能把“依赖树有 Firebase 字样”直接等同于应用引入 Firebase Analytics，也不能为了通过关键词检查盲目排除 SDK 必需依赖。[SDK 披露](https://developers.google.com/ml-kit/android-data-disclosure)
- 本轮未找到并验证一个足以保证该 SDK 完全零遥测的受支持配置。应用自己的 HTTP 客户端白名单不约束 SDK 内部网络。
- 若用户接受“内容绝不上传，但允许 ML Kit 官方披露的 SDK 指标”，可在明确修订隐私约束后继续既定引擎；若要求零指标发送，ML Kit 适配应暂停，另行研究可验证方案，不能暗自换成 NLLB 或云翻译。
- 飞行模式可验证离线推理，不证明重新联网后没有待发送指标；对联网使用的视频场景尤其不能把飞行模式当最终解决方案。

此外 ML Kit 翻译有署名/展示要求，需加入后续 UI 合规检查，私人用途不直接推导为无需遵守。[翻译使用指南](https://developers.google.com/ml-kit/language/translation/translation-terms)

### 4.6 C 方案、队列与 final 锁

I-ASR 有提前切段和 endpoint，但没有约 900 ms 的中文稳定策略。I-Service 的序号主要保障 TTS 顺序，`uiState.updateTranslated()` 仍可被较老请求晚返回覆盖。L-Service 只在 final 后翻译，两者都不能替代 C 方案。[I-ASR] [I-Service] [L-Service]

建议保留稳定器，但修正原定义：

1. `sessionId + audioEpoch + utteranceId + revision` 标识结果；术语/设置版本也参与翻译请求身份。去重作用域是同一句/同一版本，不是全局文本；两次独立说出相同句子仍须显示。
2. 约 900 ms 稳定依据是**未变化的文本/词前缀**，不是整串只要不断增长就都算稳定。无新 partial 时也要有 monotonic timer 推进，不能依赖 ASR 重发相同文本。
3. 英文 partial 继续变化；稳定的中文候选可更新但不当作不可变 final。endpoint 到来立即锁定该句英文并优先请求最终译文；最终中文完成后锁定，只能补到相应句子，不覆盖下一句。
4. 不对整个 pipeline 机械使用 `collectLatest`：同句旧 stable 可以合并/丢弃，已经 final 的句子不能被下一句 partial 取消。使用有界 final 待办；持续过载时明确提示/保留原文，不能无限排队或静默丢 final。
5. Streaming Zipformer 不保证输出标点。V0.1 不增加标点模型；endpoint、稳定前缀和长句安全界限足以构成基线。“强句末标点”只能是可选辅助条件，不能是正确性的依赖。
6. 长句显示分段不等于随意 reset ASR；明确文本偏移与 revision 处理。若采用模型最大时长 endpoint 切段，标注其为技术边界，不能冒充语言学完整句。

**PCM 与 partial 是不同队列。** 原计划的 `LatestFrameBuffer + DROP_OLDEST` 会把不连续 PCM 拼成假连续语音。保留有界容量，但增加帧序号/时间戳及 gap 事件：溢出后丢过时音频、使旧候选失效、由 ASR worker 重置 stream/epoch，并显示中断状态，不编造完整 final。切音源亦须重置。UI partial 才适合单纯保留最新状态。

### 4.7 悬浮窗、术语、历史

两个源码快照均未发现 `TYPE_APPLICATION_OVERLAY` / `SYSTEM_ALERT_WINDOW` 实现；也没有我们的技术术语保护器。保留独立任务，不估算为可直接复用。

术语占位符必须验证数量及一一对应；ML Kit 不保证原样保留 token。若缺失/损坏，不能承诺恢复原位置，更不能展示残留 token。建议明确降级到英文原句或已有有效中文，并显示失败状态；英文技术词识别错误本身也不由术语翻译器自动解决。

两项目 manifest 都是 `allowBackup=true`，不可移入我们的本地隐私配置。保留禁备份，并检查 Android 12+ 数据提取规则及目标设备传输行为；不能仅凭私有 filesDir 宣称永不进入备份。[I-Manifest] [L-Manifest] [Android application 文档](https://developer.android.com/guide/topics/manifest/application-element)

本地历史保留原范围，但在实施前写清只保存 final、容量/清空策略及备份排除；不保存 PCM、debug WAV 或完整字幕日志。I 项目的 AudioDiagnostics 可把音频写 WAV，ASR 也记录完整文本，均不移植。[I-Diagnostics] [I-ASR]

## 5. 对当前任务清单的调整

| 原任务 | 建议处理 | 降低的风险 |
|---|---|---|
| Task 1 骨架 | 保留已有文件；先解决未验证构建环境；只允许 launcher Activity exported，其他业务组件不导出 | 避免将尚未运行的 RED 当作验证；避免“所有组件都不导出”导致无法启动 |
| Task 2 session | 保留并增加 source epoch、重复启动/撤销/清理测试 | 切源、撤销、停止竞态 |
| Task 3 稳定器/术语 | 保留，新增 timer/前缀回改/同文不同句/损坏 token 测试 | C 方案语义错误 |
| Task 4 UI/仓库 | 最小开始/停止与模型准备页先做，完整设置及历史后做；补 HistoryRepository 任务 | 不在捕获未验证前铺完整 UI；补原 plan 遗漏 |
| Task 5 音频 | 提前与 Task 8 的最小服务配对；按能力延后重采样；替换静默 DROP_OLDEST | 尽早识别真机限制和音频不连续 |
| Task 6 ASR/模型 | 先锁单一模型、AAR 来源/校验/许可，再薄适配；不迁入多引擎 | 模型不匹配、native 版本冲突、供应链风险 |
| Task 7 ML Kit/pipeline | 隐私门槛前置，准备与推理解耦，区分 stable 最新值与 final 待办 | 遥测承诺冲突、积压、过时译文 |
| Task 8 Service | 前移；明确唯一 session owner，不使用 sticky 自动重启 | 背景权限与资源泄漏 |
| Task 9 overlay/performance | overlay controller 取代第二 Service；先平衡参数，再实测其他模式 | 双 Service 生命周期及未经验证的热调参 |
| Task 10 验收 | 内容网络审计与 SDK 指标审计分开；加入许可、AAR/16 KB、离线复测、备份检查 | 关键词扫描“全绿”但真实隐私/兼容性未验 |

模块仍沿用当前 `app/core-model/core-audio/core-asr/core-subtitle/core-translation/data/service/overlay`，不引入 Hilt、远程翻译、多引擎或额外独立 ONNX Runtime。统一 spec/plan 中 GlossaryProcessor 所在位置为 core-subtitle；领域契约进 core-model，禁止 core-translation 反向依赖 core-subtitle 造成循环。

## 6. 建议实施路线（确认后才可执行）

1. **R0 决策和依赖门槛：** 明确 ML Kit 指标边界；记录目标手机 Android/ABI；锁 AAR/model 来源、版本、许可和精确校验；保留已有工具链候选，不为迁就参考项目照抄降级。列出尚未验证的版本组合。
2. **R1 最小可构建壳 + 生命周期：** 恢复 Task 1 的 RED→GREEN；建立 session owner、通知、权限入口、停止与错误状态。暂不做完整设置/历史。
3. **R2 系统捕获优先验证：** 系统 PCM 只进内存能量/队列观察，不录音；验证授权、后台、撤销、停止；随后实现显式麦克风新会话回退和 gap 事件。目标机不通过则先处理此风险。
4. **R3 模型 + 真 streaming ASR：** 校验安装、CPU thin adapter、endpoint/finish/reset；直接在应用内看英语 partial/final。验证采集不被 ASR 阻塞、无积压。
5. **R4 C 方案 + 本地翻译：** 先用 fake 验证稳定器/术语/乱序/final 锁；隐私决策通过后接 ML Kit；模型准备完才开始翻译会话。
6. **R5 悬浮字幕 + 本地设置/历史：** 同一 SubtitleState 驱动两处视图；控制器依附唯一服务；补 glossary CRUD、历史清空及必要署名。
7. **R6 性能、隐私、许可与真机验收：** 测量平衡模式后再调整省电/高性能；执行原六个真机场景和隐私场景，增加过载、重复 start/stop、切源、模型损坏、旧译文晚返回等回归。

这不是“上游跑得通所以本项目通过”的路线。每阶段要有自己的测试和设备证据。当前既没有运行测试，也没有完成 R0；后续执行计划还需根据批准决定细化模块构建文件、完整类型契约与平台测试命令。

## 7. 复用与许可证操作规则

- **InstantVoiceTranslate：** 如实际移植其代码，在 `third_party/licenses/InstantVoiceTranslate-MIT.txt` 保留完整 MIT 和作者版权；在 `THIRD_PARTY_NOTICES.md` 记录 repo、固定 SHA、源文件、目标文件及改动摘要；APK 随附可访问的许可说明。MIT 本身不要求另造 NOTICE，但我们的来源台账应存在。[MIT 原文](https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/LICENSE)
- **live-captions：** 该快照许可未明确，不复制/改写移植其应用代码，也不把 README 的公开可见视为授权。只记录架构观察，依据 Android/sherpa/ML Kit 官方 API 独立实现；需要直接移植时先取得作者明确许可。[GitHub 关于无许可证的说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)
- **sherpa / 模型 / AAR 内依赖：** sherpa 主库 Apache-2.0 不自动代表所有模型和打包二进制依赖许可相同；每个实际选定资源单独记录。保留适用的 LICENSE/NOTICE 和修改标记。[sherpa LICENSE](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE)
- **ML Kit：** SDK 条款、翻译展示署名与开源依赖许可分开处理；不因研究到 API 就把 SDK/模型当成 MIT。
- 本轮只研究和写文档，**没有移植代码**；上述许可交付文件是后续实际引入时的门槛，不伪装成已经引入或完成的材料。

## 8. 固定源码索引

正文的 I-* / L-* 链接分别指向两仓库的固定提交文件；结论适用于本次快照，不推断之后的版本。源码路径已与下载的 Git 快照核对。

[I-Audio]: https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/app/src/main/java/com/example/instantvoicetranslate/audio/AudioCaptureManager.kt
[I-ASR]: https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/app/src/main/java/com/example/instantvoicetranslate/asr/SherpaOnnxRecognizer.kt
[I-Service]: https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/app/src/main/java/com/example/instantvoicetranslate/service/TranslationService.kt
[I-Model]: https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/app/src/main/java/com/example/instantvoicetranslate/data/ModelDownloader.kt
[I-Download]: https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/app/src/main/java/com/example/instantvoicetranslate/data/DownloadUtils.kt
[I-Build]: https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/app/build.gradle.kts
[I-Manifest]: https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/app/src/main/AndroidManifest.xml
[I-Diagnostics]: https://github.com/IliyaBrook/InstantVoiceTranslate/blob/1651c66f5191b092365745273f4d8eb85b5cb9be/app/src/main/java/com/example/instantvoicetranslate/audio/AudioDiagnostics.kt
[L-Audio]: https://github.com/samoylenkodmitry/live-captions/blob/e1eee62f57d935ab5221bf4221be8a3345fb82f8/app/src/main/java/com/asr/live/audio/AudioCapture.kt
[L-ASR]: https://github.com/samoylenkodmitry/live-captions/blob/e1eee62f57d935ab5221bf4221be8a3345fb82f8/app/src/main/java/com/asr/live/asr/StreamingEngine.kt
[L-Service]: https://github.com/samoylenkodmitry/live-captions/blob/e1eee62f57d935ab5221bf4221be8a3345fb82f8/app/src/main/java/com/asr/live/service/CaptionService.kt
[L-Translator]: https://github.com/samoylenkodmitry/live-captions/blob/e1eee62f57d935ab5221bf4221be8a3345fb82f8/app/src/main/java/com/asr/live/i18n/MlKitTranslator.kt
[L-Model]: https://github.com/samoylenkodmitry/live-captions/blob/e1eee62f57d935ab5221bf4221be8a3345fb82f8/app/src/main/java/com/asr/live/model/ModelRepository.kt
[L-Store]: https://github.com/samoylenkodmitry/live-captions/blob/e1eee62f57d935ab5221bf4221be8a3345fb82f8/app/src/main/java/com/asr/live/model/ModelStore.kt
[L-Catalog]: https://github.com/samoylenkodmitry/live-captions/blob/e1eee62f57d935ab5221bf4221be8a3345fb82f8/app/src/main/java/com/asr/live/model/ModelCatalog.kt
[L-Manifest]: https://github.com/samoylenkodmitry/live-captions/blob/e1eee62f57d935ab5221bf4221be8a3345fb82f8/app/src/main/AndroidManifest.xml
