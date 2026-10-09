# Android 实时翻译器 V0.1 Implementation Plan

**执行状态（2026-09-04）：暂停。** 用户要求先完成开源复核并确认新路线。下列原 Task 1–10 保留作基线，不得继续执行；本文件末尾的 R0–R6 是待批准的修订路线，不是已完成步骤。当前只有部分骨架，不能把文件存在视为测试通过。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建可侧载的 Android 10+ APK，在设备端把英语系统音频或经用户确认的麦克风音频实时转换为应用内和悬浮中文字幕。

**Architecture:** Kotlin/Compose 单 Activity 负责配置和可见状态；前台服务拥有捕获与推理 session。音频、ASR、稳定器、术语保护、翻译和渲染通过有限容量 channel 与 `StateFlow` 串联，外部能力均位于可替换接口后面。

**Tech Stack:** JDK 17、Gradle 9.5、Android Gradle Plugin 9.3.0、compileSdk 37、targetSdk 36、minSdk 29、AGP 内置 Kotlin 2.2.10、Compose BOM 2026.08.00、Coroutines、sherpa-onnx Android 1.13.7、ML Kit Translate 17.0.3、JUnit 4、Robolectric、AndroidX Test。

**Spec:** `docs/superpowers/specs/2026-09-03-android-realtime-translator-design.md`

## Global Constraints

- 私人自用、Local-first、无账号、无后台、无数据采集。
- 音频、识别文本、字幕、术语表和历史不得上传到任何服务器。
- V0.1 仅支持 English → 简体中文，最低 Android 10（API 29）。
- 内部音频不可用时只提示回退；必须由用户确认后才启用麦克风。
- ASR 模型不打进 APK，下载后校验 SHA-256 并存入应用私有目录。
- 不引入 Analytics、Crashlytics、广告、遥测、远程日志或云端 Provider。
- 所有生产行为遵循 RED–GREEN–REFACTOR；配置/生成文件不承载业务行为。
- 每个任务结束时运行其测试与 lint，再提交。

---

## File Map

| 路径 | 职责 |
|---|---|
| `app/` | Activity、Compose 导航、依赖装配和 manifest |
| `core-model/` | 不可变领域类型、session 状态与 reducer |
| `core-subtitle/` | C 方案稳定器、字幕 pipeline 和术语保护 |
| `core-audio/` | 系统/麦克风采集、重采样、音源健康监测 |
| `core-asr/` | ASR 接口、模型校验和 sherpa-onnx 适配器 |
| `core-translation/` | 翻译接口和 ML Kit 本地实现 |
| `data/` | 本地设置、术语、历史和模型元数据 |
| `service/` | 前台翻译 session 生命周期与通知 |
| `overlay/` | 悬浮字幕视图和控制器 |

### Task 1: Buildable Android skeleton and privacy guard

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/localfirst/realtimetranslator/MainActivity.kt`
- Create: `app/src/main/java/com/localfirst/realtimetranslator/ui/App.kt`
- Test: `app/src/test/java/com/localfirst/realtimetranslator/PrivacyManifestTest.kt`

**Interfaces:**
- Consumes: none.
- Produces: application id `com.localfirst.realtimetranslator`; `RealtimeTranslatorApp()`; no exported component or telemetry metadata.

- [ ] **Step 1: Create Gradle configuration and failing privacy test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class PrivacyManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `installed app requests no broad storage or location access`() {
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.MANAGE_EXTERNAL_STORAGE in permissions)
    }
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*PrivacyManifestTest'`  
Expected: FAIL because the RED fixture manifest temporarily requests `ACCESS_FINE_LOCATION`.

- [ ] **Step 3: Add minimal manifest and Compose shell**

Declare only foreground service, media projection, microphone, notification, overlay and model-download Internet permissions. `MainActivity` calls `setContent { RealtimeTranslatorApp() }`; initial action is disabled as “请先准备模型”.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests '*PrivacyManifestTest' :app:assembleDebug`  
Expected: PASS and debug APK exists.

- [ ] **Step 5: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle app
git commit -m "build: scaffold private Android app"
```

### Task 2: Session state machine

**Files:**
- Create: `core-model/src/main/kotlin/com/localfirst/realtimetranslator/model/SessionState.kt`
- Create: `core-model/src/main/kotlin/com/localfirst/realtimetranslator/model/SessionEvent.kt`
- Create: `core-model/src/main/kotlin/com/localfirst/realtimetranslator/model/SessionReducer.kt`
- Test: `core-model/src/test/kotlin/com/localfirst/realtimetranslator/model/SessionReducerTest.kt`

**Interfaces:**
- Produces: `sealed interface SessionState`, `sealed interface SessionEvent`, `fun reduce(state, event): SessionState`.

- [ ] **Step 1: Write reducer tests RED**

```kotlin
@Test fun `projection denial returns to recoverable idle`() {
    assertEquals(SessionState.Idle(Notice.ProjectionDenied),
        reduce(SessionState.RequestingProjection, SessionEvent.ProjectionDenied))
}

@Test fun `capture failure asks before microphone fallback`() {
    assertEquals(SessionState.AwaitingMicrophoneConfirmation,
        reduce(SessionState.Running(AudioSource.System), SessionEvent.InternalAudioUnavailable))
}

@Test fun `stop is idempotent`() {
    assertEquals(SessionState.Idle(), reduce(SessionState.Idle(), SessionEvent.StopRequested))
}
```

- [ ] **Step 2: Run `./gradlew :core-model:test`; expect unresolved types.**
- [ ] **Step 3: Implement exhaustive pure reducer with Idle, PreparingModels, RequestingProjection, Starting, Running, AwaitingMicrophoneConfirmation, Stopping and Failed.**
- [ ] **Step 4: Run `./gradlew :core-model:test`; expect PASS.**
- [ ] **Step 5: Commit `feat: add translation session state machine`.**

### Task 3: C-scheme stabilizer and glossary

**Files:**
- Create: `core-subtitle/src/main/kotlin/com/localfirst/realtimetranslator/subtitle/SentenceStabilizer.kt`
- Create: `core-subtitle/src/main/kotlin/com/localfirst/realtimetranslator/subtitle/SubtitleModels.kt`
- Create: `core-subtitle/src/main/kotlin/com/localfirst/realtimetranslator/subtitle/GlossaryProcessor.kt`
- Test: `core-subtitle/src/test/kotlin/com/localfirst/realtimetranslator/subtitle/SentenceStabilizerTest.kt`
- Test: `core-subtitle/src/test/kotlin/com/localfirst/realtimetranslator/subtitle/GlossaryProcessorTest.kt`

**Interfaces:**
- Consumes: `AsrUpdate(text: String, isFinal: Boolean, receivedAtMs: Long)`.
- Produces: `SentenceStabilizer.accept(update): StabilizerOutput`; `GlossaryProcessor.protect/restore`.

- [ ] **Step 1: Write stabilizer tests RED**

```kotlin
@Test fun `partial is live immediately but stable after 900ms`() {
    val sut = SentenceStabilizer(900)
    assertNull(sut.accept(AsrUpdate("we need", false, 0)).stableSegment)
    assertNull(sut.accept(AsrUpdate("we need", false, 899)).stableSegment)
    assertEquals("we need", sut.accept(AsrUpdate("we need", false, 900)).stableSegment?.text)
}

@Test fun `final emits once and blank never emits`() {
    val sut = SentenceStabilizer(900)
    assertEquals("export GLB", sut.accept(AsrUpdate("export GLB", true, 10)).stableSegment?.text)
    assertNull(sut.accept(AsrUpdate("export GLB", true, 20)).stableSegment)
    assertNull(sut.accept(AsrUpdate(" ", true, 30)).stableSegment)
}
```

- [ ] **Step 2: Run the test; expect missing stabilizer failure.**
- [ ] **Step 3: Implement whitespace normalization, monotonic timing, final lock and duplicate suppression; rerun PASS.**
- [ ] **Step 4: Write glossary test RED**

```kotlin
@Test fun `protects and restores technical terms`() {
    val sut = GlossaryProcessor(listOf(Term("Three.js", "Three.js"), Term("GLB", "GLB")))
    val p = sut.protect("Export GLB with Three.js")
    assertFalse(p.text.contains("GLB"))
    val translated = "使用 " + p.tokens[1] + " 导出 " + p.tokens[0]
    assertEquals("使用 Three.js 导出 GLB", sut.restore(translated, p))
}
```

- [ ] **Step 5: Implement deterministic placeholders, run `:core-subtitle:test`, commit `feat: stabilize subtitles and protect glossary terms`.**

### Task 4: Local repositories and home UI

**Files:**
- Create: `data/src/main/kotlin/com/localfirst/realtimetranslator/data/SettingsRepository.kt`
- Create: `data/src/main/kotlin/com/localfirst/realtimetranslator/data/GlossaryRepository.kt`
- Create: `data/src/main/kotlin/com/localfirst/realtimetranslator/data/ModelRepository.kt`
- Create: `data/src/main/kotlin/com/localfirst/realtimetranslator/data/DataStoreSettingsRepository.kt`
- Create: `app/src/main/java/com/localfirst/realtimetranslator/ui/HomeScreen.kt`
- Create: `app/src/main/java/com/localfirst/realtimetranslator/ui/HomeViewModel.kt`
- Create: `app/src/main/java/com/localfirst/realtimetranslator/ui/SettingsScreen.kt`
- Create: `app/src/main/java/com/localfirst/realtimetranslator/ui/ModelManagerScreen.kt`
- Test: `data/src/test/kotlin/com/localfirst/realtimetranslator/data/InMemoryRepositoriesTest.kt`
- Test: `app/src/androidTest/java/com/localfirst/realtimetranslator/ui/HomeScreenTest.kt`

**Interfaces:**
- Produces: `SettingsRepository.settings: Flow<UserSettings>`, `GlossaryRepository.terms`, `ModelRepository.observeStatus()`.

- [ ] **Step 1: Test defaults RED:** Balanced, Bilingual, System audio.
- [ ] **Step 2: Implement contracts/in-memory fakes and DataStore keys; run `:data:test` PASS.**
- [ ] **Step 3: Test that “请先准备模型” is disabled for `modelsReady=false`, and Running shows Stop.**
- [ ] **Step 4: Implement confirmed home layout and limited settings; run connected UI tests PASS.**
- [ ] **Step 5: Commit `feat: add local settings and translator home`.**

### Task 5: Audio capture and bounded PCM

**Files:**
- Create: `core-audio/src/main/kotlin/com/localfirst/realtimetranslator/audio/AudioCapture.kt`
- Create: `core-audio/src/main/kotlin/com/localfirst/realtimetranslator/audio/PcmFrame.kt`
- Create: `core-audio/src/main/kotlin/com/localfirst/realtimetranslator/audio/LatestFrameBuffer.kt`
- Create: `core-audio/src/main/kotlin/com/localfirst/realtimetranslator/audio/InternalAudioCapture.kt`
- Create: `core-audio/src/main/kotlin/com/localfirst/realtimetranslator/audio/MicrophoneCapture.kt`
- Create: `core-audio/src/main/kotlin/com/localfirst/realtimetranslator/audio/AudioSourceMonitor.kt`
- Test: `core-audio/src/test/kotlin/com/localfirst/realtimetranslator/audio/LatestFrameBufferTest.kt`
- Test: `core-audio/src/test/kotlin/com/localfirst/realtimetranslator/audio/AudioSourceMonitorTest.kt`

**Interfaces:**
- Produces: `AudioCapture.frames: Flow<PcmFrame>`, `suspend start()`, `suspend stop()`; PCM16 includes sample rate/channel count.

- [ ] **Step 1: Test DROP_OLDEST RED**

```kotlin
@Test fun `drops oldest at capacity`() = runTest {
    val b = LatestFrameBuffer(capacity = 2)
    b.offer(frame(1)); b.offer(frame(2)); b.offer(frame(3))
    assertEquals(listOf(2, 3), listOf(b.receive().id, b.receive().id))
}
```

- [ ] **Step 2: Implement bounded Channel; rerun PASS.**
- [ ] **Step 3: Test source monitor emits internal-unavailable once but does not mistake established true silence for capture failure.**
- [ ] **Step 4: Implement AudioPlaybackCapture and VOICE_RECOGNITION capture on IO dispatcher, idempotent stop and 16 kHz mono resampling.**
- [ ] **Step 5: Run `:core-audio:test :core-audio:lintDebug`, commit `feat: capture local audio with bounded buffering`.**

### Task 6: Verified sherpa-onnx streaming ASR

**Files:**
- Create: `core-asr/src/main/kotlin/com/localfirst/realtimetranslator/asr/AsrEngine.kt`
- Create: `core-asr/src/main/kotlin/com/localfirst/realtimetranslator/asr/AsrResult.kt`
- Create: `core-asr/src/main/kotlin/com/localfirst/realtimetranslator/asr/ModelManifest.kt`
- Create: `core-asr/src/main/kotlin/com/localfirst/realtimetranslator/asr/Sha256Verifier.kt`
- Create: `core-asr/src/main/kotlin/com/localfirst/realtimetranslator/asr/SherpaOnnxEngine.kt`
- Test: `core-asr/src/test/kotlin/com/localfirst/realtimetranslator/asr/Sha256VerifierTest.kt`
- Test: `core-asr/src/test/kotlin/com/localfirst/realtimetranslator/asr/FakeAsrEngine.kt`

**Interfaces:**
- Consumes: 16 kHz mono `PcmFrame`.
- Produces: `AsrEngine.results: Flow<AsrResult>`, `load(modelDir)`, `accept(frame)`, `close()`.

- [ ] **Step 1: Test SHA-256 exact match and changed-byte rejection RED.**

```kotlin
@Test fun `matches exact digest and rejects changed bytes`() {
    val bytes = "local model".encodeToByteArray()
    assertTrue(Sha256Verifier.matches(bytes, "ffa838f6509a5a99bd5d39d5b9bd4243a9c586f359a2830c93603c1908d13a6e"))
    assertFalse(Sha256Verifier.matches(bytes + 0, "ffa838f6509a5a99bd5d39d5b9bd4243a9c586f359a2830c93603c1908d13a6e"))
}
```
- [ ] **Step 2: Implement streaming wrapper:** normalize floats, decode while ready, emit changed partial, emit/reset endpoint final, release native handles once.
- [ ] **Step 3: Implement installation:** download to `.part`, verify byte count/SHA-256, atomic rename, preserve prior verified model on failure.
- [ ] **Step 4: Run `:core-asr:test :core-asr:lintDebug`, commit `feat: add verified local streaming ASR`.**

### Task 7: ML Kit provider and stale-safe pipeline

**Files:**
- Create: `core-translation/src/main/kotlin/com/localfirst/realtimetranslator/translation/TranslationProvider.kt`
- Create: `core-translation/src/main/kotlin/com/localfirst/realtimetranslator/translation/MlKitTranslationProvider.kt`
- Create: `core-subtitle/src/main/kotlin/com/localfirst/realtimetranslator/subtitle/SubtitlePipeline.kt`
- Test: `core-subtitle/src/test/kotlin/com/localfirst/realtimetranslator/subtitle/SubtitlePipelineTest.kt`

**Interfaces:**
- Produces: `prepare(): Result<Unit>`, `translate(text): Result<String>`, `SubtitlePipeline.state: StateFlow<SubtitleState>`.

- [ ] **Step 1: Write stale result test RED**

```kotlin
@Test fun `late old translation cannot replace newer subtitle`() = runTest {
    val translator = ControllableTranslator()
    val pipeline = SubtitlePipeline(translator, identityGlossary, backgroundScope)
    pipeline.submit(stable(1, "first")); pipeline.submit(stable(2, "second"))
    translator.complete(2, "第二"); translator.complete(1, "第一")
    assertEquals("第二", pipeline.state.value.translatedChinese)
}
```

- [ ] **Step 2: Configure ML Kit ENGLISH→CHINESE, bridge Task with `await()`, close per session, and accept only newest sequence; rerun PASS.**
- [ ] **Step 3: Run both module tests, commit `feat: translate stable subtitles on device`.**

### Task 8: Foreground service and permission orchestration

**Files:**
- Create: `service/src/main/kotlin/com/localfirst/realtimetranslator/service/RealtimeTranslationService.kt`
- Create: `service/src/main/kotlin/com/localfirst/realtimetranslator/service/TranslationSession.kt`
- Create: `service/src/main/kotlin/com/localfirst/realtimetranslator/service/ServiceNotificationFactory.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `MainActivity.kt`
- Test: `service/src/test/kotlin/com/localfirst/realtimetranslator/service/TranslationSessionTest.kt`
- Test: `app/src/androidTest/java/com/localfirst/realtimetranslator/ProjectionFlowTest.kt`

**Interfaces:**
- Consumes: `StartCommand.SystemAudio(resultCode, resultData)` or `StartCommand.Microphone`.
- Produces: process-local `StateFlow<SessionState>`; notification `ACTION_STOP`.

- [ ] **Step 1: Test fatal error closes capture, ASR, translator and projection exactly once RED.**
- [ ] **Step 2: Implement one supervisor job and mutex-protected idempotent cleanup GREEN.**
- [ ] **Step 3: Implement order:** visible Activity consent → start FGS → `startForeground` → `getMediaProjection`. Microphone requires explicit confirmation and runtime permission.
- [ ] **Step 4: Run service/unit/instrumentation tests; commit `feat: run translation in a foreground session`.**

### Task 9: Overlay and performance safeguards

**Files:**
- Create: `overlay/src/main/kotlin/com/localfirst/realtimetranslator/overlay/SubtitleOverlayService.kt`
- Create: `overlay/src/main/kotlin/com/localfirst/realtimetranslator/overlay/OverlayRenderer.kt`
- Create: `service/src/main/kotlin/com/localfirst/realtimetranslator/service/PerformanceMonitor.kt`
- Create: `service/src/main/kotlin/com/localfirst/realtimetranslator/service/AdaptivePerformancePolicy.kt`
- Test: `overlay/src/test/kotlin/com/localfirst/realtimetranslator/overlay/OverlayRendererTest.kt`
- Test: `service/src/test/kotlin/com/localfirst/realtimetranslator/service/AdaptivePerformancePolicyTest.kt`

**Interfaces:**
- Consumes: SubtitleState, UserSettings, `PerformanceSample(asrMs, queueDepth, subtitleLatencyMs, thermalStatus)`.
- Produces: `OverlayFrame`; `RuntimeTuning(partialIntervalMs, stableWindowMs, asrThreadCount)`.

- [ ] **Step 1: Test severe thermal status yields partial interval ≥400 ms and stable window ≥1200 ms RED.**
- [ ] **Step 2: Implement bounded in-memory metrics and hysteresis; never persist/transmit samples.**
- [ ] **Step 3: Implement non-focusable TYPE_APPLICATION_OVERLAY; permission denial leaves app subtitles active.**
- [ ] **Step 4: Run module tests/lint; commit `feat: add subtitle overlay and thermal safeguards`.**

### Task 10: Release gate and device acceptance

**Files:**
- Create: `scripts/privacy-audit.ps1`
- Create: `docs/verification/v0.1-device-acceptance.md`

- [ ] **Step 1: Add audit that fails on Firebase/Crashlytics/analytics/ad dependencies, broad storage/location permissions, and non-model network endpoints.**
- [ ] **Step 2: Run automated gate**

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
powershell -ExecutionPolicy Bypass -File scripts/privacy-audit.ps1
```

Expected: tests/lint/audit PASS and debug APK exists.

- [ ] **Step 3: Record six device scenarios:** 10-minute YouTube, fast technical speech/terms, 30-second silence, 20–30-minute background, capture-disabled fallback, permission denial/revocation.
- [ ] **Step 4: Run package/packet/log review; verify no user audio/text leaves device.**
- [ ] **Step 5: Commit `test: add V0.1 privacy and device acceptance gate`.**

---

## 2026-09-04 开源复核修订路线（待批准）

**依据：** [研究报告](../../research/2026-09-04-open-source-android-review.md) 与 [spec 第 17 节](../specs/2026-09-03-android-realtime-translator-design.md#17-2026-09-04-开源复核待确认修订)。使用 Superpowers writing-plans 整理；本轮仅文档，不执行下列步骤。

### Global constraints 修订提案

- 原“无后台”应为“无后端服务器”：明确允许用户主动启动的前台服务持续工作。
- no account / backend / cloud ASR / cloud translation / analytics SDK / TTS；不上传音频、文本、字幕、术语与历史。
- ML Kit SDK 指标传输单独列为 R0 待决项，不能靠放宽审计或排除依赖掩盖。用户未批准例外时，零遥测仍是未解决要求。
- 保留现有九个模块的职责；共享请求/结果身份、PCM 元数据和接口放 `core-model`，避免 `core-subtitle` 与 `core-translation` 双向引用。各新增模块的 `build.gradle.kts`、settings include 和依赖声明必须加入详细执行清单，原计划漏列的部分不能跳过。
- ASR AAR 由官方固定版本获取并验证，不执行上游 `preBuild` 自动下载任务；不盲目升级/降级当前工具链。现列版本是待兼容性验证的候选，不等于已验证组合。
- 采集/ASR/翻译分离；PCM gap 与 UI partial 合并是不同策略；final 不能被下一句取消。
- 实际移植前保存 License/NOTICE 和来源台账。live-captions 未许可应用代码不移植。

### R0：决策、来源与依赖门槛（先于正式代码）

**文档目标文件：** `docs/verification/v0.1-dependency-matrix.md`、`THIRD_PARTY_NOTICES.md`；实际引用时再创建 `third_party/licenses/` 的对应许可文件。

- [ ] 用户批准本路线，明确 ML Kit SDK 指标边界；如不接受，翻译选型单独研究，不暗自改用其他模型。
- [ ] 确认目标手机 Android/API、ABI；没有设备信息时不照搬 Pixel 9 Pro 的 arm64-only 假设。
- [ ] 固定 sherpa AAR 版本/URL/SHA、模型 revision/四文件组合/精确 bytes/SHA/许可，区分 Kotlin runtime 与模型版本。
- [ ] 记录 Gradle/AGP/Kotlin/Compose/SDK 的候选与可用性；此前 Gradle loopback 失败仍未解决，不将失败诊断假设写成结论。
- [ ] 合并已批准的 spec 修订；将下述阶段展开为实际模块插件、完整类型和可运行测试命令，再进入 R1。

**退出证据：** 决策记录、来源矩阵；不以 README 或一次源码下载代替 ABI/性能测试。

### R1：最小骨架 + 单一 session owner

**对应原任务：** Task 1、2，以及 Task 8 的最小服务部分。

**文件范围：** 原 Task 1/2/8 的文件；补每个此时需要的模块 `build.gradle.kts`；`core-model/.../SessionIdentity.kt`；`service/.../TranslationSession.kt`；`app/.../MainActivity.kt` 与 manifest。

- [ ] 保留已有骨架和 RED fixture，先得到真实可运行环境，然后执行 privacy RED→GREEN；不在未运行测试时记录 PASS。
- [ ] 明确 launcher Activity exported=true、业务 Service exported=false；无 location/broad storage 权限，关闭备份并加相应版本的数据提取规则。
- [ ] session reducer 增加 session/epoch 身份、重复/空 start、停止中 start、projection 撤销和用户确认切麦事件。
- [ ] 使用唯一前台服务、最小开始/停止界面、持续通知。空 intent 不重启捕获；start/stop 与错误入口有统一所有权。
- [ ] 测试先使用 fake capture/asr/translator 验证释放一次、迟到事件拒绝及取消时序。

**测试目标：** `PrivacyManifestTest`、`SessionReducerTest`、`TranslationSessionTest`、`ProjectionFlowTest`。JVM 模块使用 `:core-model:test`；Android 模块使用各自 `testDebugUnitTest/lintDebug`，不要混用原计划中的泛化 `:data:test`。

### R2：系统捕获及显式麦克风回退

**对应原任务：** Task 5 + Task 8 权限/捕获部分。

**文件范围：** `core-audio/.../AudioCapture.kt`、`InternalAudioCapture.kt`、`MicrophoneCapture.kt`、`AudioSourceMonitor.kt`；原 `LatestFrameBuffer.kt` 替换为 `BoundedPcmBuffer.kt`；共享 `PcmFrame`/`CaptureEvent` 放 `core-model`。

**边界：** frame 带采样率、声道、序号、单调时间和 epoch；capture event 明确区分 frame/gap/read error。音频只进有界内存；UI 只看能量/状态，不记录 WAV。

- [ ] 系统及麦克风都先确认 `RECORD_AUDIO`；系统 consent → FGS/startForeground → getMediaProjection → callback → AudioRecord；仅捕获音频。
- [ ] 首先验证系统音频 16 kHz mono 采集、后台持续、撤销及通知停止；不预先创建通用重采样器。
- [ ] 麦克风采用同一接口；切源先停止旧 session，再从可见 Activity 主动启动新 session，不自动开麦。
- [ ] 增加有界缓冲溢出/gap 事件；健康监测不能从静音断言应用禁止捕获。
- [ ] 将采集线程 read 错误、创建失败及取消映射回 session；清理需解除 read 阻塞并安全等待 worker。

**测试目标：** `BoundedPcmBufferTest`、`AudioSourceMonitorTest`、`AudioCaptureLifecycleTest`、`ProjectionFlowTest`。真机观察不保存音频；静音30秒不得触发偷偷切麦；不通过则先处理捕获风险，不铺完整 UI。

### R3：校验模型 + Streaming ASR 英文链路

**对应原任务：** Task 6 + Task 4 的最小模型准备页。

**文件范围：** 原 Task 6 文件；补 `ModelInstaller.kt` / `ModelInstallerTest.kt`；下载/安装实现归模型管理层，AsrEngine 只接受已验证目录。`AsrEngine` 契约放 `core-model`，`SherpaOnnxEngine` 放 `core-asr`。

- [ ] 模型准备必须在用户主动操作后完成；单模型四文件直下载，staging → 验 bytes/SHA → 整套原子提交；中断/磁盘不足保留旧版本。
- [ ] AAR/模型组合确认后，CPU recognizer 执行 accept/ready/decode/endpoint/reset；变更 partial 和 final 带完整身份。
- [ ] 正常停止测试 `inputFinished` 与残余解码，必要的模型尾部填充按所选官方示例验证；撤销/gap 与正常 final 的语义分开。
- [ ] 仅 ASR worker 调用和释放 native recognizer/stream。gap 重置 stream/epoch；无终止前的竞态 release。
- [ ] 在应用内先验证英语 partial/final、静音、长句与目标机实时性，不接中文掩盖 ASR 故障。

**测试目标：** `Sha256VerifierTest`、`ModelInstallerTest`（损坏、取消、rename 失败、旧版本保留）、`SherpaOnnxEngineContractTest`（fake/native 分层）、真机 JNI/ABI/16 KB 及实时性记录。旧计划仅 SHA 测试不足以验收安装器。

### R4：C 稳定器 + 术语 + ML Kit

**对应原任务：** Task 3、7。

**文件范围：** 原 Task 3/7 文件；共享 `AsrUpdate`、`TranslationRequest`、`TranslationResult` 与身份契约放 `core-model`；`core-subtitle` 不让翻译适配器反向依赖。

- [ ] fake clock 验证无新 partial 的 timer tick、前缀增长/回改、同文不同句、空输入、长句与 endpoint；去重限定到句子/版本。
- [ ] 英文 final 立即锁；中文 final 返回后补到对应句子。稳定译文可以刷新但不是 final；后句 partial 不取消前句 final。
- [ ] 翻译单独 worker；同句 stable 合并，final 待办有界、优先并明确过载行为；Task 取消与底层完成不是同一件事。
- [ ] 术语测试涵盖最长匹配、边界、大小写、重复术语、占位符丢失/损坏；降级不能显示伪造译文或残留 token。
- [ ] 仅在 R0 隐私决定通过后，根据官方 API 接入 ML Kit，模型准备与 runtime 翻译拆开；网络失败不触发任何云端回退。

**测试目标：** `SentenceStabilizerTest`、`GlossaryProcessorTest`、`SubtitlePipelineTest`、`MlKitTranslationProviderTest`。除原乱序测试，必须覆盖跨 session、跨 audioEpoch、final 后 stable 晚到、术语版本切换与待办过载。

### R5：悬浮及本地设置/历史

**对应原任务：** Task 4 剩余 + Task 9 overlay。

**文件范围：** 原 settings/home/model/glossary 文件；将 `overlay/.../SubtitleOverlayService.kt` 改为 `SubtitleOverlayController.kt`（仅计划，不在本轮创建）；补原 Task 4 遗漏的 `data/.../SubtitleHistoryRepository.kt` 和对应测试。

- [ ] overlay 模块保留，唯一 service 负责 attach/detach；应用内与 overlay 消费相同 SubtitleState。
- [ ] 拒绝悬浮权限仍可应用内翻译；通知停止一并撤销悬浮窗。
- [ ] 完成 glossary CRUD、字幕样式、模型管理；历史只保存 final，明确本地容量/清空与备份排除。
- [ ] 加入所需 ML Kit 展示署名及第三方许可入口；不引入 TTS、分享/同步功能。

**测试目标：** `HomeScreenTest`、`OverlayRendererTest`、`SubtitleOverlayControllerTest`、`SubtitleHistoryRepositoryTest`。跨 Activity 重建不重复采集或重复挂窗。

### R6：性能、隐私与完成验收

**对应原任务：** Task 9 performance + Task 10。

**文件范围：** 原 PerformanceMonitor/AdaptivePerformancePolicy、`scripts/privacy-audit.ps1`、`docs/verification/v0.1-device-acceptance.md`；更新 R0 来源/许可矩阵。

- [ ] 平衡模式真机测量后才调省电/高性能；热降级降低 UI/翻译负载，不假装它必然解决 ASR 过载；不在线修改 native 线程配置。
- [ ] 根据实际模块插件列出准确的 JVM/Android unit/lint/instrumentation 任务；构建、权限测试、六项设备场景及隐私场景都须实录。
- [ ] 检查 merged manifest、依赖树及 SDK 指标用途，不用泛化 Firebase 字符串禁令替代评审；不得通过删除 SDK 必需依赖让审计“通过”。
- [ ] 验证联网/下载后离线推理、运行中网络、重新联网、日志、备份及许可证；内容外传与 API 指标分别报告。
- [ ] 无真机、无网络可观测证据、未解决隐私冲突或只有 assemble 成功，均不得宣布 V0.1 完成。

**当前停点：** 研究与待确认文档已整理；R0–R6 全部未执行。等待用户确认路线和 ML Kit 指标边界，之后再安排功能实现。
