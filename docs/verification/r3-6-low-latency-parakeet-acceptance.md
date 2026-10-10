# R3.6 — Low-latency Parakeet subtitle trial

## Intent
Keep the offline Parakeet TDT v3 INT8 + Zipformer architecture and the R3.5 two-row overlay. Reduce avoidable subtitle latency without changing raw transcripts or adding cloud/translation, VAD, or enhancement models.

## Changes
- Three **session-frozen** Parakeet decoding profiles, using the same imported model:
  - **2.4s default** with 480ms overlap (1.92s hop).
  - **2.0s experimental** with 400ms overlap (1.60s hop).
  - **3.2s R3.5 comparison** with 640ms overlap (2.56s hop).
- In-app selector is available while capture is stopped. Selected profile travels to the foreground service and is frozen when capture is requested. Invalid/missing wire mode safely selects 2.4s.
- Display scheduler ordinary hold 750ms → 260ms; 2+ pending chunks 180ms; 4+ pending chunks 120ms. Previous line remains in the overlay, and all queued words retain their order. No transcript rewriter, forced per-line 2-second hold, or silent take-last queue skipping.
- RAM-only metadata diagnostics: window length/overlap, decoder window turnaround time (audio window queued to decode completion), recognition queue wait, caption queue display wait. These are **component delays, not a measured source-voice-to-screen end-to-end latency**.
- Model files remain private on-device and are not re-imported between modes. Audio PCM and ASR transcripts are not saved or transmitted. R2 and R3.5 branches untouched.

## Local/CI verification
The existing Android CI runs JVM tests, Android unit tests, Android Lint, and assembles debug APK. The model remains a 640 MiB externally imported artifact, so CI does not prove speech accuracy.

## Xiaomi 13 acceptance (required)
1. Install 0.3.6 over 0.3.5; **do not uninstall** (keeps imported ASR models). Check Parakeet v3 remains installed and two-row caption mode retained when enabled in the same process.
2. Use SYSTEM audio, select Parakeet and start 2.4s default mode; check diagnostics **window length 2400ms, overlap 480ms**, no dropped PCM frames/window backlog/recognizer exceptions.
3. Play a **Friends** episode with sustained conversational English: inspect caption readability and compare raw recognized words to speech. Ensure no regression from 3.2s.
4. Play the exact **The Last of Us** opening dialogue continuously for 3–5 minutes. Without pausing, note subjective delay and whether recognized words reach the overlay in spoken order. Check `lastCaptionWaitMs`, `lastWindowTurnaroundMs`, queue and overflow.
5. Stop recording, switch to **3.2s R3.5 comparison** (do not change source/video volume/scene), replay same segment. Capture a screenshot with the selected window size, decode counts, empty windows, and caption wait. 2.4s should improve latency **without unacceptable missing words**.
6. Only if 2.4s remains noticeably late, stop and try **2.0s experimental** on the identical scene. This preset is not claimed to have equivalent accuracy.
7. For **Love, Death & Robots** music + low voice scenes, compare empty recognizer windows, unchanged source PCM RMS, and raw text for the same scene at 2.4s and 3.2s. **Do not interpret music-only blanks as speech recognition failures.** Shorter windows can make music-masked whispers worse.
8. Run 10–15 minutes; ensure decoded+queued counters advance, capture and decoder dropped windows stay zero, no growing backlog, and overlay closes on Stop or foreground app return.
9. Verify notifications, permission revocation, model import retention, single-row switching, and Zipformer fallback.

## Limits
This is still a **windowed offline** recognizer; first full result requires an entire selected PCM window. A shorter window may lower latency but not solve missed soft speech under loud music. Compare real dialogue against audible audio, not just the subtitle file supplied by the TV service.
