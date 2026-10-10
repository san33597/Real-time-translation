# R3.4 — Parakeet TDT v3 INT8 offline ASR trial

## Scope
- Start from R3.3 A+B branch; R2/R3/R3.1/R3.2/R3.3 remain unchanged.
- Add second optional on-device speech recognizer: `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` via sherpa-onnx 1.13.8 `OfflineRecognizer` with `modelType = "nemo_transducer"`.
- Retain Zipformer as selectable fallback. The selected model is frozen per capture session.
- Independent SAF folder import to `filesDir/asr/parakeet-v3-int8`. The import checks exact filenames and plausible minimum sizes, uses transactional staging and preserves prior model. **No Parakeet SHA-256 fingerprint has been verified/pinned yet; structural checks are not a signature guarantee.**
- Separate recording consumer and Parakeet JNI decode. Bounded channel of three audio windows; native recognition never executes in AudioRecord frame callback.
- Non-streaming 3200ms audio windows with 640ms overlap; overlap dedupe uses exact word-boundary suffix/prefix comparison. This is a first-pass fixed-window baseline, **not speech/VAD-based semantic segmentation**. Quick dialogue near window boundaries must be evaluated on-device.
- Each recognized Parakeet chunk emits one final `AsrUpdate`; no synthetic token-level partial. Existing R3.3 caption diagnostics and two-line overlay handle both engines.
- ASR stats display decoded-window count, dropped-window count, and last decode time in the diagnostics panel. Dropped windows mean inference cannot keep up, not just an ASR accuracy issue.
- No audio or recognized text is written to disk or transmitted to a server. PCM is kept in a few transient memory windows and cleared on consumption/close.
- No ML Kit translation / DeepSeek / network calls.

## Required model files
Download and unpack the official:
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2

Select the extracted folder **containing these four files at its root**:
- `encoder.int8.onnx`
- `decoder.int8.onnx`
- `joiner.int8.onnx`
- `tokens.txt`

The official sample config is in sherpa-onnx's versioned Kotlin API (1.13.8), model type `nemo_transducer`. Example upstream:
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html

## Build / CI
```powershell
./scripts/install-sherpa.ps1
./gradlew.bat :core-model:test :core-audio:testDebugUnitTest :core-asr:testDebugUnitTest :service:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Xiaomi 13 device acceptance (REQUIRED; CI cannot load a 640 MiB user model)
1. Upgrade v0.3.3 to v0.3.4 in place. DO NOT uninstall, or private Zipformer model will be deleted.
2. Under ASR select Parakeet v3 and import the above extracted folder from Android file picker. Expect ~640MiB copy and an extra temporary disk footprint. Confirm success/ready.
3. Start SYSTEM audio capture. Confirm no launch crash, OOM or immediate foreground-service failure. Check model startup time.
4. Play the exact same English dialogue/video scene used with Zipformer. Allow at least 3–5 seconds initial chunk latency, plus CPU decode time.
5. In app diagnostics check raw Parakeet final texts vs actual speech, decoded count, dropped count, newest decoder latency and subtitle queue overflow.
6. Repeat rapid exchanges (“Here you are / Thank you”), loud backgrounds, pauses, long dialogue, and a 10–15 minute continuous playback.
7. Check that overlay appears above video, dismisses on return to app, and notification Stop stops capture. Confirm permission revocation and switching back to Zipformer on the NEXT fresh session.
8. Important: count errors against audible dialogue, not exclusively edited film captions which may omit/reword speech.
9. If `droppedWindows > 0` or sustained decoder latency exceeds audio window duration, this is **not** an acceptable real-time configuration on this phone without further optimization.

## Known limitations
- Non-streaming output after complete windows: perceptible latency is expected. The model has not yet been run on the user's device.
- Fixed windows and exact token overlap are a baseline; overlapping dialogue, music and repetitions may still cause mistakes. This release does not claim superior measured WER.
- The model may require much more RAM/CPU than Zipformer. Native memory use requires an actual Android runtime test.
