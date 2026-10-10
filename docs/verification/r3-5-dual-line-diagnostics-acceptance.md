# R3.5 — Dual-row caption readability + complex-audio diagnostics

## Scope

- Based on `feat/r3-4-parakeet-int8-asr`, preserves R2 and R3.4 as separate baselines.
- Real dual-row overlay: previous displayed fragment (muted) above current fragment (white).
- Defaults to two rows; app switch returns to one row without restarting capture. Each row occupies one visual line, with font auto-sizing (16sp down to 12sp) and ellipsis as a last resort; scheduler fragments are capped at 44 characters to limit clipping.
- Reduces scheduler hold time to 750ms normally, 320ms with 2–3 queued fragments, 180ms with 4+ queued fragments. This helps catch up instead of introducing unbounded caption delay; content is **not** retranslated or edited.
- ASR transcripts and in-app traces remain independent of subtitle presentation. No model upgrade, translation, cloud calls or forced noise suppression.
- Parakeet diagnostics (in memory): input frames, PCM peak/RMS, queued/decoded/dropped windows, empty model results, results fully removed by exact-overlap deduplication, JNI decode time, and queue wait. No voice-activity detection or energy-gated audio dropping occurs in the R3.4/R3.5 Parakeet path.
- Prefer installed Parakeet at app startup; Zipformer remains selectable and is used if Parakeet is absent.
- No audio saved and no transcripts written to disk.

## Build
The Android CI workflow runs:

```sh
./gradlew :core-model:test :core-audio:testDebugUnitTest :core-asr:testDebugUnitTest :service:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
```

Android Gradle packaging requires the pinned `sherpa-onnx` AAR (installed by `scripts/install-sherpa.ps1` in CI).

## Xiaomi 13 device checks (required)

1. Install Android 0.3.5 **over** 0.3.4 without uninstalling, so existing imported model files remain.
2. Turn on floating subtitles (permission required); verify dual-line is the default. Leave app for a video, speak two or more separated fragments, and verify first line scrolls up before new fragment appears below it.
3. Toggle single-line mode and ensure only newest text appears. Switch back to two lines without losing ASR input. Return to the app; the overlay must hide as before.
4. **Friends**: verify no regression with clear conversations, long lines, stopping and resuming.
5. **The Last of Us**: play continuous rapid opening dialogue without pausing. Compare in-app raw ASR final updates with what appears in the overlay. Watch queue depth, queue overflows, and how quickly the previous caption scrolls. If the text exists in ASR but not on screen, record approximate times and screenshots.
6. **Love, Death & Robots**: repeat scenes with music and effects. Compare PCM input peak/RMS, submitted/decoded windows, `emptyResults` and `overlapOnlyResults`. Empty ASR can mean model difficulty with mixing; there is no VAD here to blame without other evidence.
7. Pause playback: peak/RMS should fall if source is silent; a muted/restricted playback capture may return zero energy despite video speech. Check capture dropped frames independently.
8. For 10–15 minutes continuously, monitor dropped PCM frames, dropped Parakeet windows, inference time versus 2.56s window hop, caption queue overflows and perceptual subtitle lag.
9. Verify the old notification actions, permission revocation, AudioRecord/projection stop, and switching back to Zipformer next session.

## Limits and next steps

- R3.5 does **not** make Parakeet streaming: a 3.2s window is still required before most outputs, so real-time latency has a fixed front-end component.
- Two rows improve legibility of captions already returned by ASR; they cannot restore words never decoded.
- Overlap deduplication deliberately uses exact words, but repeated short phrases can be ambiguous: investigate if `overlapOnlyResults` rises during real dialogue.
- If decoder emits blanks while PCM input remains non-zero, only then test alternative window sizing or optional speech enhancement on the same labeled scenes.
