# R3.10 — Caption pacing A/B + Parakeet token-timestamp diagnostics

## Priorities after R3.9 testing

The user confirmed the video itself was rewound and replayed; repeated
`No.` entries in the ASR log were not observed as unwanted overlay duplicates.
Real problems: **caption latency and missed quiet/fast dialogue**. Stop
treating duplicates as primary evidence of a faulty stitcher.

This revision is stacked on R3.9-A, leaves legacy behavior as the default,
and isolates two modifications:

1. **Responsive subtitle pacing (optional)** — only shortens the queue hold
   from 750ms to 420ms for one pending chunk, from 320ms to 220ms for 2–3,
   and from 180ms to 120ms for 4+. Maintains FIFO and never drops ASR text.
   Capturing with original `stable-r33` remains the default. This targets
   **post-ASR display delay only**, not 3.2s offline-window inference delay.
2. **Opt-in native token time telemetry** — sherpa-onnx offline recognizer
   `tokens`, `timestamps`, `durations`, plus per-window sample offsets and
   decode-ready/start/end monotonic clocks. Stored only when the user explicitly
   enables local diagnostic text logging. No PCM or cloud traffic.

## Log fields

Each `parakeet_window` event now includes:
- `segmentId`: increments on PCM reset/gap; different segments **must not**
  be aligned to one another
- `windowStartSample` and `windowEndSample`: positions in that 16kHz
  contiguous audio segment (includes window overlap, tail windows)
- `windowReadyAtMs`, `decodeStartAtMs`, `decodeFinishAtMs`: Android
  `elapsedRealtime` (millis), for measuring queue and decode turnaround
- `tokenCount`, `timestampCount`, `tokenTimings` with `token`,
  `relativeMs` (native timestamp converted from seconds),
  `segmentMs` (window start + relative), `nativeDuration` (raw TDT value,
  **not yet calibrated as seconds**; -1 indicates missing)

Token timing validation: only token/timestamp pairs with finite,
non-negative and plausible relative timestamps are recorded; count fields
make partial/missing timestamp arrays apparent. BPE/subword tokens are **not**
implicitly word boundaries. These are offsets into captured PCM, not
source-video wall-clock timestamps; seeking in the player creates an ordinary
new PCM stream that the app cannot automatically identify as a seek.

A warm-start/first-window delay metric would additionally require matching
movie playback time to capture origin; do **not** interpret
`decodeFinishAtMs - token.segmentMs` directly (different clock origins).
A *rough* lag estimate within a window is:
`decodeFinishAtMs - windowReadyAtMs + (windowEndSample / 16 - token.segmentMs)`
in milliseconds, ignoring Android audio buffering and display scheduling.

## A/B tests

1. Install over R3.9 without uninstalling Parakeet.
2. Choose Parakeet / **3.2s + 640ms overlap** / same stitch mode for both.
3. Enable ASR logging; first choose `稳定字幕（原版显示节奏）`.
4. Capture 3–5 minutes of a fixed dialogue clip (new video is fine).
   Stop and export `stable.jsonl`.
5. Replay the identical clip/volume, change *only* to
   `低延迟字幕（实验）`, stop and export `responsive.jsonl`.
6. Compare subtitle readability, dropped/missing text, time between ASR updates
   and caption_state, and median / P95 queue waits. No new spoken words are
   expected: the recognizer was not changed.
7. For timestamp validation, inspect `tokenTimings` and relative window
   positions within a **single** uninterrupted recording; verify reasonable
   monotonic progression and continuity, especially repeated `No. No? No!`,
   with native duration left uncalibrated.
8. Confirm that turning logging off does not store token text or PCM, and
   that session stop/export work normally.

## Boundaries / future decision

- Keeping the original 3.2s offline recognizer means this release **cannot
  eliminate the inherent first-window wait**. Faster display pacing mainly
  removes 0–330ms of avoidable after-decode hold in multi-chunk passages;
  it can be less readable when speech is dense.
- A future major latency improvement likely needs a separate streaming ASR
  model/dual-pass pipeline or cautiously validated partial decoding, both
  outside this PR and subject to device CPU/memory/accuracy A/B checks.
- We will not deploy timestamp-based de-duplication until native timestamps
  are verified against realistic dialogue and subword segmentation.
