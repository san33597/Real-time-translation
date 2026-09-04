# Android 实时翻译器 V0.1 Implementation Plan

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
class PrivacyManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun `does not declare broad storage or location permissions`() {
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("ACCESS_FINE_LOCATION"))
    }

    @Test fun `contains no telemetry SDK metadata`() {
        assertFalse(manifest.contains("firebase", ignoreCase = true))
        assertFalse(manifest.contains("crashlytics", ignoreCase = true))
    }
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*PrivacyManifestTest'`  
Expected: FAIL because `AndroidManifest.xml` does not exist.

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
