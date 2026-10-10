# R3.6.1 — R3.5 baseline recovery before low-latency experiments

## Why this revision exists
User tests found that R3.6 was worse than R3.5 even when selecting the same 3.2s window:
both raw ASR Final results and floating subtitles were missing lines in *The Last of Us*. 
That is a regression observation, **not** proof of a specific cause in the model.
R3.6 simultaneously changed recognition window scheduling and caption hold times, preventing a clean A/B comparison.

## Safety-first recovery
- **3.2s R3.5 mode is the default again** (640ms overlap, 2560ms hop).
- The default Parakeet audio chunker is constructed with the **same no-argument constructor** as R3.5.
- The complete `OrderedCaptionQueue.kt` and `EnglishSubtitle.kt` implementations and caption queue tests were restored **byte-for-byte** from the R3.5 branch: 750ms ordinary hold, 320ms at 2–3 pending fragments, 180ms at 4+.
- 2.4s (480ms overlap) and 2.0s (400ms overlap) are strictly *manual experiments* only, never the default.
- Diagnostic-only window turnaround telemetry is retained and raw recognized text remains independent of display.
- App version 0.3.6.1 (versionCode 11). Existing models stay installed when the APK is installed over R3.5/R3.6.

## Tests and comparison plan
1. In Android CI run JVM and Android module unit tests, Lint and Debug APK assembly.
2. A PCM regression test feeds a deterministic 120,000-sample sequence into the R3.5 default chunker and explicit 3200/640 chunker and asserts that every output window is exactly identical. This validates **window construction**, not neural-model accuracy.
3. Install 0.3.6.1 without uninstalling the old version; ensure 3.2s selected as the default.
4. Play **the identical *The Last of Us* opening scene** for 3–5min under identical volume, audio source and capture permissions. Repeat on **actual R3.5 APK** if comparison is inconclusive.
5. Compare raw Final count and exact phrases, overlay misses, dropped PCM frames, Parakeet dropped windows, `emptyResults` and `overlapOnlyResults`. Take screenshots of each diagnostics panel after the same scene; the fact that subtitles seem faster alone is not evidence of ASR accuracy.
6. **Do not test 2.4s/2.0s for acceptance until the recovered 3.2s profile matches R3.5.**
7. If recovered 3.2s still produces fewer raw Final outputs under the same conditions, investigate PCM capture discontinuities, model load/state, source restrictions and per-window text/timing. Do **not** presume caption queue timing caused the ASR misses.
8. Once baseline parity is verified, compare one change at a time on the same scene. Never combine shortened windows with altered caption hold times.

## Limitations
The offline Parakeet decoder still needs a full audio window. No VAD, model swap, speech enhancement, translation or transcript persistence is introduced. CI cannot validate TV speech quality.
