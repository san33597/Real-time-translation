# Real-time-translation

An Android 10+ local-first English-to-Chinese live subtitle project.

## Current implementation: R1 foundation only

The R1 app is **a permission and session lifecycle demo, not a real translator**. It never captures system audio or microphone samples, runs ASR, calls ML Kit, or produces live subtitles. Its UI and foreground notification say "模拟" (simulation).

Implemented on the `feat/r0-r1-android-foundation` branch:
- Gradle 9.5.0 wrapper (official Gradle v9.5.0 wrapper scripts and JAR).
- Android Compose launcher, explicit audio-source selection, user-initiated microphone permission / MediaProjection consent flow, and notification permission request on Android 13+.
- One non-exported foreground service with notification Stop action and `START_NOT_STICKY`.
- A pure Kotlin session state machine with session identity, stale-event filtering, and idempotent cleanup.
- Fake session resources behind a replaceable interface and no background content collection.
- Robolectric privacy tests and JVM service/reducer tests, plus CI.

## Build and test

Prerequisites: JDK 17; Android SDK Platform 37 and Build Tools 36.0.0; network access for the pinned Gradle and Android dependencies.

On Windows:
```powershell
.\gradlew.bat :core-model:test :service:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Linux/macOS:
```bash
./gradlew :core-model:test :service:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Tap **开始模拟会话** to verify permissions, notification, simulated running state and stop. The system-audio mode asks Android for MediaProjection consent but R1 deliberately does **not** consume the returned token or record sound. The microphone mode requests RECORD_AUDIO but does **not** open AudioRecord. R2 introduces actual capture.

## Product goal

## Primary product rule: subtitles should feel invisible

**Timely, stable, readable, reasonably accurate captions are the product; AI is an optional enhancement.** Users should be able to enjoy continuous video with AI refinement permanently OFF. Do not spend latency, readability or baseline reliability to squeeze out marginal cloud translation gains.

Priority order:
1. **P0 — responsiveness:** local ASR must surface English promptly; stabilized segments should receive ML Kit Chinese subtitles immediately upon translation completion, with bounded queues and no wait for cloud results.
2. **P0 — readability and continuity:** timestamps/sequence must remain in order, text should not flicker, jump, reflow unnecessarily or be overwritten by stale results; sensible segmentation and overlay layout are more important than rewriting already-read subtitles.
3. **P0 — reliability:** long-running sessions, permission denial, source changes, stop/restart, model-not-ready, backpressure, offline use (after model setup) and thermal pressure must fail safely and preserve the currently useful text.
4. **P1 — translation and terminology:** glossary protection, proper nouns and sentence segmentation should improve local output before requesting cloud refinement.
5. **P2 — optional AI refinement:** OFF by default, independent from the critical display path; a single refinement of the same current subtitle only when on time, useful and visually nondisruptive. Never overwrite old/locked subtitles or cause subtitle backlog.

Acceptance philosophy: first demonstrate a **smooth ML Kit-only experience on a real phone**. Record capture→English partial/stable→Chinese latency distributions, dropped/late segments, queue depth, subtitle churn, CPU/RAM, battery and heat, plus interruption/recovery behavior. Derive concrete pass thresholds from device tests rather than inventing latency guarantees. Only add/enable cloud improvement after the baseline holds; compare ON vs OFF without worsening P0 metrics.

The eventual V0.1 will capture eligible system audio (or user-approved microphone input), recognize English speech locally with sherpa-onnx, stabilize segments, protect technical terms, translate locally into simplified Chinese, and display captions in-app and in an overlay.

**2026-10-09 final translation design:** ML Kit Translate is the always-on first-pass English→Simplified Chinese translator. **DeepSeek AI refinement is strictly optional (OFF by default)**. When enabled and explicitly authorized, each stable/final recognized English sentence is translated locally by ML Kit first. The cloud then may improve the same subtitle using the English source and the ML Kit Chinese draft; results replace only the *same current sentence* while it remains eligible for update. Cloud failure, quota exhaustion or late response never blocks or erases ML Kit subtitles.

Privacy: audio and ASR stay on device. ML Kit runs translation locally but its SDK may transmit diagnostic/usage metrics to Google (see https://developers.google.com/ml-kit/terms and https://developers.google.com/ml-kit/android-data-disclosure), therefore **do not claim zero telemetry**. With AI refinement OFF, there must be no DeepSeek API calls. With it ON, disclose and send only user-authorized stabilized English, corresponding draft Chinese, and any explicitly allowed glossary/context (never audio); cloud requests are subject to provider privacy terms and potential charges. BYOK keys must remain out of source, logs and backups. ML Kit model download requires network initially.

**Status:** This is a product/design decision, not an implemented translation feature: R1 remains a fake session only. Architecture record: [ML Kit + optional DeepSeek refinement](docs/decisions/2026-10-09-provider-architecture.md).

Design decision: [Translation provider and privacy policy](docs/decisions/2026-10-09-provider-architecture.md).

Technical records: [R0 dependency and decision matrix](docs/verification/v0.1-dependency-matrix.md), [R1 acceptance](docs/verification/r0-r1-acceptance.md), [original specification](docs/superpowers/specs/2026-09-03-android-realtime-translator-design.md).

Next phases (not yet implemented): R2 audio capture, R3 model verification and streaming ASR, R4 subtitle/translation, R5 overlay/settings/history, R6 privacy/performance and device acceptance.
