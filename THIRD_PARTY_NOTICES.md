# Third-party source and license tracking

This R1 repository does not vendor ASR models, native runtime AARs, ML Kit language models or third-party source snippets from the open-source research projects.

## Runtime/build dependencies

- Android Gradle Plugin (9.3.0), Kotlin/Compose toolchain, AndroidX Compose / core / activity / lifecycle, kotlinx.coroutines, JUnit and Robolectric are resolved through declared Gradle repositories, not copied as sources into this repository. Before distribution, produce a license/NOTICE inventory from the resolved dependency graph.
- Gradle Wrapper scripts and JAR are sourced from the official https://github.com/gradle/gradle tag `v9.5.0`; distribution URL points at https://services.gradle.org.
- sherpa-onnx is an unbundled R3 candidate. Upstream project https://github.com/k2-fsa/sherpa-onnx (Apache-2.0), but **individual ASR models and native/transitive components require independent review**.
- Google ML Kit Translate (`com.google.mlkit:translate:17.0.3`) is the **approved future R4 primary EN→ZH translator**, but is **not yet included in R1**. Its on-device translation model is managed by the SDK. The SDK is governed by Google's ML Kit terms and data collection disclosure (including possible metrics/diagnostics transfer); it is not an open-source MIT dependency. See https://developers.google.com/ml-kit/terms and https://developers.google.com/ml-kit/android-data-disclosure.
- DeepSeek is an **optional, future cloud text refinement provider**, disabled by default, not an included R1 runtime dependency. Its APIs are independently governed by DeepSeek terms and provider data-handling policies; user-supplied API keys and cloud text requests must be reviewed before integration.
- InstantVoiceTranslate and live-captions code has **not** been copied. Do not copy code without inspecting the precise license and maintaining copyright/license/modified-files records.

This document is a tracking manifest, not a blanket grant or complete SBOM. Complete the license ledger when real native/model integrations are selected.
