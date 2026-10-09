# Real-time-translation

An Android 10+ local-first English-to-Chinese live subtitle project.

## R3.1: optional English floating subtitles over other apps

Development branch: `feat/r3-1-floating-english-captions` (R2 stable and original R3 branches untouched).
Turn on **在其他应用上显示英文字幕** and grant Android's **Display over other apps** special permission. While the original foreground service continues English ASR, a non-touchable, small bottom-screen overlay shows latest English speech when the app is backgrounded; opening the app hides the extra overlay. Stopping capture removes it. It needs no ML Kit, DeepSeek, network transcription, audio storage or additional audio capture path.

[Android/HyperOS acceptance checklist](docs/verification/r3-1-floating-caption-acceptance.md).

## R3 development: real-time offline English captions

Development: `feat/r3-english-subtitles`. Preserved R2 baseline: `stable/r2-v0.2.1`.

R3 retains R2's system playback/microphone capture and notification controls, and connects the bounded PCM consumer to an offline sherpa-onnx English streaming model. Only English partial/final subtitles are displayed. **No ML Kit, DeepSeek, cloud ASR or translated captions are included in R3.** Models are user-imported through Android's folder picker and verified before use.

See [R3 model installation, build and phone acceptance](docs/verification/r3-english-asr-acceptance.md).

## Previous milestone: R2 audio capture

The R2 build **captures real transient PCM** through Android AudioPlaybackCapture or the user-approved microphone. It immediately consumes and zeroes frames after updating metadata (frame count, peak energy); **no audio file, ASR, translation or subtitles** are produced yet. Android playback may be silent for apps that disallow capture; silence never automatically enables the microphone. Requires CI + real-device acceptance.

Implemented on the `feat/r0-r1-android-foundation` branch:
- Gradle 9.5.0 wrapper (official Gradle v9.5.0 wrapper scripts and JAR).
- Android Compose launcher, explicit audio-source selection, user-initiated microphone permission / MediaProjection consent flow, and notification permission request on Android 13+.
- One non-exported foreground service with notification Stop action and `START_NOT_STICKY`.
- A pure Kotlin session state machine with session identity, stale-event filtering, and idempotent cleanup.
- R2 `core-audio` module: 16 kHz mono PCM16 `AudioRecord`, fresh MediaProjection consent, bounded frame buffer + short-lived frames, metadata-only UI, stop/revocation/reader error cleanup. No retained audio or cloud transport.
- Robolectric privacy tests and JVM service/reducer tests, plus CI.

## Build and test

Prerequisites: JDK 17; Android SDK Platform 37 and Build Tools 36.0.0; network access for the pinned Gradle and Android dependencies.

On Windows:
```powershell
.\gradlew.bat :core-model:test :core-audio:testDebugUnitTest :service:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Linux/macOS:
```bash
./gradlew :core-model:test :core-audio:testDebugUnitTest :service:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Tap **开始采集** to exercise real playback/microphone `AudioRecord` on a phone. R2 consumes system projection consent exactly once after `startForeground`. Confirm frame totals/peak energy and notification stop; no spoken text appears until R3/R4. See [R2 phone tests](docs/verification/r2-audio-capture-acceptance.md).

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

**Status:** ML Kit and DeepSeek are still only product/design choices. R2 has real capture but **no speech recognition or translation**. Architecture record: [ML Kit + optional DeepSeek refinement](docs/decisions/2026-10-09-provider-architecture.md).

Design decision: [Translation provider and privacy policy](docs/decisions/2026-10-09-provider-architecture.md).

Technical records: [R0 dependency and decision matrix](docs/verification/v0.1-dependency-matrix.md), [R1 acceptance](docs/verification/r0-r1-acceptance.md), [original specification](docs/superpowers/specs/2026-09-03-android-realtime-translator-design.md).

Next phases: R2 device verification; R3 model verification and streaming ASR, R4 ML Kit subtitle/translation, optional DeepSeek refinement, R5 overlay/settings/history, R6 privacy/performance and device acceptance.
