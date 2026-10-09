# Third-party source and license tracking

This R1 repository does not vendor ASR models, native runtime AARs, ML Kit language models or third-party source snippets from the open-source research projects.

## Runtime/build dependencies

- Android Gradle Plugin (9.3.0), Kotlin/Compose toolchain, AndroidX Compose / core / activity / lifecycle, kotlinx.coroutines, JUnit and Robolectric are resolved through declared Gradle repositories, not copied as sources into this repository. Before distribution, produce a license/NOTICE inventory from the resolved dependency graph.
- Gradle Wrapper scripts and JAR are sourced from the official https://github.com/gradle/gradle tag `v9.5.0`; distribution URL points at https://services.gradle.org.
- sherpa-onnx is an unbundled R3 candidate. Upstream project https://github.com/k2-fsa/sherpa-onnx (Apache-2.0), but **individual ASR models and native/transitive components require independent review**.
- Google ML Kit is an unbundled, unapproved R4 candidate governed by SDK terms and disclosures; it is not classified as an open-source MIT dependency.
- InstantVoiceTranslate and live-captions code has **not** been copied. Do not copy code without inspecting the precise license and maintaining copyright/license/modified-files records.

This document is a tracking manifest, not a blanket grant or complete SBOM. Complete the license ledger when real native/model integrations are selected.
