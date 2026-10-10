# R3.8 — opt-in ASR text-stage diagnostics

R3.8 deliberately preserves R3.7 3200/640ms and 3200/1280ms recognition modes,
Parakeet, caption queues and playback capture. This is a *diagnostic-only* experiment.

## Log format
An explicit per-session switch enables UTF-8 JSONL files in app-private
`filesDir/asr-diagnostics/`. Each event includes monotonic `elapsedRealtimeMs`,
wall-clock `wallTimeMs`, `stage`, full `text`, and `metadata`.

- `session_start`: ASR engine, selected overlap, and capture type.
- `parakeet_window`: native decoded text before stitcher and full
  `metadata.stitchedText`, window index, removed duplicate count, decode/queue times.
- `asr_update`: every final or partial passed into subtitle coordinator.
- `caption_state`: current and previous coordinator lines, queue state and overlay eligibility.
- `audio_gap`, `session_end`, and `log_truncated`.

Diagnostics never save or transmit audio, do not make cloud calls, and have no telemetry.
Files are limited to eight sessions at ~4MiB each; opt-in starts OFF at new app launch.
Export of latest completed session is user-initiated through Android CreateDocument;
there is also a local clear action. Android backup is already disabled.

## Compare
1. Upgrade over R3.7; retain the imported Parakeet model.
2. Enable saving, capture the same *The Last of Us* clip at 3200/640 and stop.
3. Export to e.g. `last-of-us-640.jsonl`.
4. Capture the same clip at 3200/1280, stop and export to a different filename.
5. Compare native `parakeet_window.text`, `metadata.stitchedText`,
   `asr_update.text`, and `caption_state.text`. Check blank windows, overlap removal,
   queue depth, lag, and `audio_gap` events.
6. Repeat with quiet speech in *Love, Death & Robots* and fast dialogue in *Friends*.
7. Verify the switch OFF does not create files and clear deletes all local traces.

The timestamps represent ASR and display processing events, not exact movie speech onset.
Do not treat their difference as exact subtitle-latency measurement.

No VAD, cloud translation, extra ASR models, or window/scheduler changes in this revision.
