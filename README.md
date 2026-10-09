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

The eventual V0.1 will capture eligible system audio (or user-approved microphone input), recognize English speech locally with sherpa-onnx, stabilize segments, protect technical terms, translate locally into simplified Chinese, and display captions in-app and in an overlay.

**2026-10-09 product decision: Option B.** Prefer a self-managed, auditable on-device translation engine with no runtime telemetry; do not bundle ML Kit. The actual local model/runtime is **not yet selected**, and zero-telemetry is a target requiring dependency and network verification, not a verified fact about R1.

An **optional, user-configured cloud provider** (e.g. DeepSeek via its OpenAI-compatible API) may be added in a separate phase. It must be OFF by default, require explicit user action and visible disclosure that **recognized English text** (potentially including context) is sent to that provider; raw audio must never be sent. The app must never silently fall back from local translation to cloud. The cloud mode is **not** offline or zero-egress. No cloud connector or API-key handling has been implemented yet.

Design decision: [Translation provider and privacy policy](docs/decisions/2026-10-09-provider-architecture.md).

Technical records: [R0 dependency and decision matrix](docs/verification/v0.1-dependency-matrix.md), [R1 acceptance](docs/verification/r0-r1-acceptance.md), [original specification](docs/superpowers/specs/2026-09-03-android-realtime-translator-design.md).

Next phases (not yet implemented): R2 audio capture, R3 model verification and streaming ASR, R4 subtitle/translation, R5 overlay/settings/history, R6 privacy/performance and device acceptance.
