# R3.11 — On-device early Parakeet subtitle preview (experimental)

## Motivation / scope

R3.10 tests show the overlay already paints the FIRST recognized fragment ~15ms
after the ASR emits it. The median speech-to-ASR estimated delay remains
around 2.2–2.4s. The priority is therefore **first subtitle latency**, not
more overlap-deduplication. The user confirmed the recorded replayed dialogue
was caused by seeking the source video, not unwanted repeated overlay captions.

This branch sits on R3.10, whose tests/Android CI passed.
The 3.2s + 640ms, Parakeet INT8 **complete** ASR baseline remains unchanged.
No ML Kit translation, DeepSeek, VAD, internet access or new ASR model.

## Implementation

- Session setting `ParakeetPreviewMode.OFF` (default, existing behavior).
- Opt-in `ParakeetPreviewMode.EARLY` requests a **1.6s prefix snapshot**
  of every unchanged 3.2s PCM window, including its original overlap.
  Inference uses the SAME loaded Parakeet model and the SAME single worker.
- Completed 3.2s windows have decoding priority (biased channel select).
  Only ONE provisional window can be queued; queue overflow drops the preview,
  never the complete window. Probes waiting longer than 800ms or belonging
  to an already-finalized window are discarded.
- An early hypothesis of fewer than two words is suppressed to reduce noisy
  single-word flashes. A best-effort comparison against the previous full
  window reduces overlap echo; it does **not** alter either existing final
  stitcher.
- Provisional text lives separately in EnglishSubtitleState.previewCaption,
  not the ordered confirmed caption queue. The full result for the **same**
  window replaces it. If the full result is blank, it retracts that preview.
  The original complete-word queue, history and A/B caption pacing remain.
- Audio gap increments the PCM generation, resets probe stitch context and
  clears transient subtitles. PCM windows are kept in RAM only.
- With the user's explicit text-logging opt-in, `parakeet_probe` records
  window index, provisional candidate, reason and queue/decode timings.
  `parakeet_window` remains full recognition; `asr_update.isFinal=false`
  signals provisional and `true` full results. Caption state also marks
  provisional text and its timestamp. No microphone audio is written.

## Limits and acceptance criteria

This is **repeated offline prefix decoding, not a true streaming Parakeet model**.
Unlike a native streaming recognizer, it spends ADDITIONAL CPU on every
successful probe and can sometimes delay complete inference. Very short
sentences, music, whispers, and speech crossing window boundaries can produce
wrong or no provisional text. Never infer that early hypotheses are correct
without matching the source video. `1.6s` refers to buffered window length,
not guaranteed screen latency.

A/B on Xiaomi 13 with an English-dialogue clip 3–5 minutes:
1. Parakeet INT8, 3.2s+640ms, identical stitch and caption pacing settings.
2. Enable opt-in logs, choose **完整窗口识别（默认）**, capture clip, stop,
   export `R311-off.jsonl`.
3. Replay same clip without seeking during capture. Choose
   **1.6 秒提前预览（实验）**, capture, stop, export `R311-early.jsonl`.
4. Observe whether **first words appear sooner**, whether provisional
   subtitles mislead/flicker, and if complete text is still reliably visible.
5. Compare `asr_update` and `caption_state` timing, provisional-to-final
   revisions, `parakeet_probe` skip count, full `decodeMs` and
   `queueWaitMs`, `droppedWindows`, `queueOverflows`.
6. FAIL if baseline full recognition is noticeably degraded (more dropped
   windows, excessive full decode waiting, missing dialogue, or stressful
   corrections); revert to mode OFF, do not change the tested baseline.

Unit tests cover PCM snapshot timing and final-window identity, gap resets,
provisional display clock, replacement, blank-final retraction and FIFO safety.
