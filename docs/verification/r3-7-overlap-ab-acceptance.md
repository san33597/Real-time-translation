# R3.7 — 3.2-second Parakeet sliding-overlap A/B test

## Baseline and scientific scope
R3.6 testing found that shortening Parakeet's input to 2.4s or 2.0s loses more dialogue than the working 3.2s mode. R3.6.1 restored the R3.5 3.2s/640ms baseline and caption scheduler. R3.7 tests **one recognition variable only: overlap between complete windows**.

- Verified baseline (default): 3200ms window, 640ms overlap, 2560ms hop; the original default `ParakeetAudioChunker()` constructor is preserved.
- Experimental sliding mode: **same 3200ms window**, 1280ms overlap, 1920ms hop (one third more complete decodes per minute at constant audio duration).
- Prior short-window profiles have been removed from the UI. Legacy `fast-2000` / `balanced-2400` wire inputs fall back to baseline 3200/640.
- ASR recognizer, model, CPU threads, audio capture, gap handling, bounded decode queue, suffix-prefix overlap deduplication, and complete R3.5 subtitle scheduler are unchanged.
- A/B mode is chosen **before** each capture; switching requires stopping and starting a fresh session. No model reimport is needed.
- More overlap can increase load, repeated output, or accidentally remove genuinely repeated words. **This is an experiment, not a claim of improved recognition**. The offline model still needs 3.2 seconds of initial audio.

## Diagnostics
In addition to existing PCM input peak/RMS, dropped/decoded windows, blank recognizer windows, overlap-only results, decode time and queue wait:
- Show window size, overlap and *hop* in milliseconds.
- Track last and total exact-overlap words removed.
- Show bounded last 160 characters from native decoder **before** the overlap stitcher and after stitcher output. These excerpts are only held in memory and cleared when capture stops; no PCM/transcript is persisted or uploaded.
- The app's prior "ASR raw" display is an upstream `AsrUpdate` after window stitching. Use the new decoder-vs-stitcher excerpts to diagnose overlap filtering, not that label alone.

## CI tests
- The R3.5 baseline and R3.6.1 window-consistency tests remain.
- 1280ms overlap test feeds uneven PCM chunks and checks all 3200ms windows, consecutive 1280ms overlaps, true sample indices and stop tail.
- Both modes' first complete 3200ms window is byte-identical. On 8s input: base outputs 2 complete windows, experimental outputs 3.
- Dedup metric tests check both partial and complete overlap suppression, blanks, and reset, with unchanged output.

## Xiaomi 13 acceptance (before any merge)
1. Install APK 0.3.7 over 0.3.6.1 without uninstalling to retain imported Parakeet model.
2. Select Parakeet and **3.2s + 640ms baseline**. Start system capture. Verify diagnostic `window=3200ms`, `overlap=640ms`, `hop=2560ms`. Confirm *The Last of Us* opening speech matches R3.6.1/R3.5 before proceeding.
3. Stop, select **3.2s + 1280ms experimental** and play exactly the same opening clip from the same point at the same sound volume. Confirm `window=3200ms`, `overlap=1280ms`, `hop=1920ms`.
4. Compare **captured phrases and omissions**, perceived speech-to-subtitle delay, the number of final ASR updates, dropped decode windows, empty recognizer windows, overlap-only results, removed-overlap word count, and both excerpt fields. Do not infer better recognition simply because more windows were decoded.
5. Repeat on clear speech (*Friends*) and low speech over soundtrack (*Love, Death & Robots*) without pausing. If 1280ms misses words, falsely de-duplicates phrases, or falls behind, keep 640ms as default.
6. Test a 10–15min continuous video; confirm zero PCM gaps and decode drops, no growing wait queue, overlay single/dual toggle still works, and notification Stop tears down capture.

## Known limitations
- End-to-end latency is not directly measurable from the available diagnostics; queue waiting and decoder turnaround are components, not the time from original movie speech to user-visible subtitle.
- In the baseline the first full output still needs 3.2s PCM. Increased overlap affects **subsequent** update cadence, not this initial duration.
- No new VAD, cloud calls, translation, audio denoising, subtitle scheduling changes, or stronger native ASR model.
