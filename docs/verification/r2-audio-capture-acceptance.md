# R2 Android audio capture verification

**Implementation date:** 2026-10-09.
**Status:** R2 automated CI **PASS** on commit `c32e1b4` (GitHub Actions run `37904209856`); user supplied target-phone screenshots showing real system and microphone PCM frame activity. **Core capture smoke PASS based on screenshots; full R2 device acceptance remains OPEN** for pause/mute correlation, stop paths, revocation and longer runs. R1's 6/6 simulation checks are distinct evidence.

## Scope

- User-initiated SYSTEM playback capture: fresh Android MediaProjection consent -> typed mediaProjection foreground service -> getMediaProjection once -> register onStop callback -> AudioPlaybackCaptureConfiguration -> 16 kHz mono PCM16 AudioRecord.
- User-initiated MICROPHONE capture: RECORD_AUDIO permission -> typed microphone foreground service -> AudioRecord.
- A single `TranslationSession` owns recorder/projection/reader and safely stops on the UI button, notification Stop, read error, projection revocation or Service destruction. New SYSTEM sessions must request new MediaProjection approval; never reuse captured Intent.
- R2 retains no WAV or raw samples. PCM exists briefly as 20 ms frames in a bounded (32-frame max) queue and is immediately consumed and zeroed. UI receives **only** total frames, dropped-frame count, and approximate peak energy, not PCM. R3 will replace the discard consumer with ASR processing.
- 16 kHz input is attempted directly, without a resampler. Some OEM devices/audio routes might not support it; test on the target device before R3.
- `AudioPlaybackCapture` only receives usages and apps explicitly permitted by Android playback capture policies. Silence does **not** prove the source is uncapturable. It must **never** trigger automatic mic fallback.

Android reference:
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/reference/android/media/AudioRecord

## Automated checks (CI is the evidence, do not assume PASS)

```bash
./gradlew :core-model:test :core-audio:testDebugUnitTest :service:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Unit/contract coverage:
- `BoundedPcmBufferTest`: finite capacity, dropping oldest, sample zeroization on overflow/close.
- `AudioSourceMonitorTest`: silence is a valid frame; peak and gap counts.
- `CaptureStartGuardTest`: typed foreground gate, single-use projection consent.
- `AudioCaptureLifecycleTest`: reject mic before foreground, reject system without new consent, idempotent abort of partial starts.
- `SessionReducerTest` and `TranslationSessionTest`: cancellation, read/projection event identity, stop and resource ownership.
- `PrivacyManifestTest`: permissions and backup restrictions.

**Limit:** Robolectric contracts do not verify actual system playback eligibility, hardware PCM delivery, permissions on Xiaomi/other OEMs, or termination behavior when the OS kills the service.

## User-provided Android screenshots: preliminary real-device evidence (2026-10-09)

Five screenshots of the installed R2 app and Android system capture consent were provided by the user:

| Observation | Evidence | Scope |
| --- | --- | --- |
| System audio mode active | UI says SYSTEM, real capture RUNNING | User screenshot |
| System audio frame count advancing | UI displays 190 frames and 550 frames in two separate captures | Frames were read; no raw PCM was shared |
| System audio nonzero sample peak | 31.5% and 33.9% peak shown | Confirms nonzero amplitude; capture from the intended playback app should be corroborated by pause/resume behavior |
| System audio dropped frames | Both screenshots show 0 | No observed drops over the shown period, not a long-run guarantee |
| Microphone mode active | UI shows 2,580 frames and 2.0% peak, 0 drops | Nonzero mic capture data; speaking/peak dynamics not independently verified |
| Android media projection consent | System offers whole-screen vs single-app sharing | Consent UI shown; screenshots alone do not prove both modes work or notification Stop |
| End-to-end ASR and translation | Not part of R2, absent in UI | Not implemented |

**Preliminary conclusion:** the device is delivering frames with nonzero samples to both audio paths. Do not treat metadata-only screenshots as proof of 10–15 minute stability, capture from all other apps, lack of protected-media restrictions, stop/revocation safety or audio quality.

**Automated evidence:** [successful CI run 37904209856](https://github.com/san33597/Real-time-translation/actions/runs/37904209856) compiled/tested/assembled the same R2 source; [APK artifact 11603907699](https://github.com/san33597/Real-time-translation/actions/runs/37904209856/artifacts/11603907699).

## Remaining target-device checklist (only initial capture is evidenced)


1. Install latest R2 debug APK; launch. Both sources should clearly say **R2**, not simulation or finished translation.
2. Select **system audio**, grant RECORD_AUDIO and the fresh screen-audio projection prompt. Confirm ongoing R2 notification. Play known unprotected English audio/video **from another allowed app**, and verify `已读取音频帧` rises and `瞬时峰值` becomes nonzero during audible playback. Keep a note of which app and whether its capture policy permits this.
3. Pause or mute playback for 30 seconds. The session must **stay in system mode**, with no hidden microphone start. A zero peak can be normal.
4. Tap the **app Stop** button; confirm the audio/microphone indicator and notification disappear. Start again: new projection consent required.
5. Start again, then tap **notification Stop**; confirm the app returns Idle and there is no background capture.
6. Start SYSTEM, then stop sharing using Android's privacy/share controls or lock screen. Confirm projection revocation stops capture with a recoverable notice.
7. Switch to **microphone** only by stopping first and deliberately selecting that source. Speak aloud; verify rising frames/peak. Deny/revoke microphone and verify graceful error/cleanup.
8. Rapid start/stop, rotate Activity, switch apps and run a continuous 10–15 minute test. Check no duplicate notifications, frozen stop, unbounded frame backlog, overheating or unexplained restart. On termination, launch again and verify no silent capture.
9. Airplane mode after setup: audio capture must not require network. Confirm no PCM WAV/file or user transcript output. Inspect Android permission dashboard / `adb logcat` if failures occur.
10. Record Android release/API/ABI and manufacturer build so R3 native libraries can be selected correctly.

## R2 pass criteria

- Real nonzero PCM activity observed on at least one Android-eligible playback source, and on microphone, with no sample logs or files.
- Both stop paths work and release camera/projection/mic indicators; projection revocation shuts capture down.
- No automatic fallback from silence or unsupported playback to the microphone.
- CI is green AND target-device checks are recorded. **Do not call R2 fully accepted on CI evidence alone.**
