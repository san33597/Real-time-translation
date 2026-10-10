# R3.9-A — Guarded Parakeet overlap stitching, isolated A/B

## Scope and rollback contract

Based on `feat/r3-8-asr-diagnostic-log`; R3.5/R3.7/R3.8 stay unchanged.
Parakeet INT8, 3.2s inference window, 640/1280ms overlap options, audio
capture, and `OrderedCaptionQueue`/overlay behavior are unchanged.

Two **independent** per-capture switches:
- Overlap: `legacy-3200` 640ms (default) or `sliding-3200-1280`.
- Stitcher: `legacy-exact` (default, original unchanged
  `ParakeetOverlapStitcher`) or `guarded-r39` (experiment).

Switch only when capture is stopped; no reimport needed. Legacy remains the
rollback path. Missing/unknown stitch wire values fall back to legacy.

## R3.9 guarded policy

- Only compare exact suffix/prefix on **immediately adjacent nonblank windows**;
  blank decodes and missing window indices invalidate the previous comparison.
- For a complete 1- or 2-word repeated recognizer result, **keep** the fragment
  rather than erase it. This protects ambiguous quick replies like "No.", but
  may visibly duplicate words if the ASR genuinely repeated the same audio.
- Retain 12-word maximum comparison. Normalize punctuation/case and a few common
  titles (`Dr` / `Doctor`, `Mr` / `Mister`, `Mrs` / `Missus`).
  Do **not** use arbitrary fuzzy/Levenshtein matching; word timestamps are absent.
- Each decoded `parakeet_window` JSONL event includes `stitchMode`,
  `stitchReason`, removed-word count, full original recognizer text, and
  full post-stitch text.

## Existing R3.8 exported log replay (2026-10-10)

Reproduced existing R3.8 legacy output exactly: **408/408 windows**
(201 at 640ms and 207 at 1280ms). A source-independent replay helper is
`python3 tools/replay_r38_stitch.py PATH_TO_EXPORTED.jsonl`.
The original logs are deliberately **not committed**.

| Logged input | Windows | Replay differs from legacy | Extra emitted words |
| --- | ---: | ---: | ---: |
| 3200ms / 640ms | 201 | 1 | 4 |
| 3200ms / 1280ms | 207 | 8 | 12 |

These are replay counts, **not WER or verified recovered movie dialogue**.
Some extra words are genuine candidate replies; others may simply be
duplicate recognition. R3.8 recordings have different lengths and start points.

## Android CI

Run JVM unit tests / Android unit tests / debug APK:
`./gradlew :core-model:testDebugUnitTest :core-asr:testDebugUnitTest :service:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`.

Core tests check legacy wire default, full short repetition protection, partial
overlap trimming, blank and discontinuous window resets, punctuation-only
tokens, and title normalization.

## Xiaomi 13 real-film A/B

Keep one overlap preset fixed to isolate the stitch change:
1. Install R3.9 APK **over** R3.8 (do not uninstall the existing model).
2. Enable local **ASR text logging**; choose Parakeet / 3200+640 / **legacy-exact**.
3. Play identical 3-5min of *The Last of Us* and export a JSONL after stopping.
4. Repeat same clip/volume/source at 3200+640 / **guarded-r39**, export separately.
5. Compare raw `parakeet_window.text` (should be similar), emitted text,
   `stitchReason`, `asr_update`, and clipped `caption_state`. Review on
   video any short words preserved vs repeated. Check zero audio-gap and drop
   counts; run 10-15min without growth in decode waiting time.
6. Only then repeat the same A/B at 3200+1280; do not change overlap and
   stitch policy simultaneously when claiming causality.
7. Repeat rapid *Friends* replies and whispery *Love, Death & Robots* scenes.

**Acceptance:** fewer *verified* missing words/short replies without
unacceptable repeated captions or increased latency. If not, retain legacy.
No VAD, dual-model ASR, partial streaming, translation, or PCM persistence
is introduced.
