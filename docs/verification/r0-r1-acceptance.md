# R0 + R1 acceptance and test record

Date: 2026-10-09. The branch implements R1 with **fake** audio/ASR/translation resources. No audio capture or translation output is present.

## Automated gates

Run locally or inspect CI at `https://github.com/san33597/Real-time-translation/actions`:

```bash
./gradlew :core-model:test
./gradlew :service:testDebugUnitTest
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

- `SessionReducerTest`: system-vs-mic model transition, projection permission denial, stale callbacks, duplicate starts, stop idempotency, projection revocation, explicit mic fallback.
- `ProjectionFlowTest`: valid/cancelled/missing projection intent.
- `TranslationSessionTest`: duplicate start, stale stop, concurrent stop, failed-open cleanup and retry.
- `PrivacyManifestTest`: forbidden broad storage/location permissions, disabled backup, private service.

**Do not claim PASS until logs provide evidence**. Absence of a CI result is `NOT VERIFIED`, not a pass.

## Device walkthrough (still requires target Android phone)

1. Install debug APK, verify visible launcher and descriptive **模拟** UI.
2. Tap Start in system mode; RECORD_AUDIO consent, then system MediaProjection prompt, followed by an ongoing demo notification. R1 intentionally does not create AudioRecord or decode audio.
3. Stop from UI. Verify notification disappears and state returns to Idle. Repeat rapidly.
4. Repeat in microphone mode from a visible Activity; never start the microphone automatically.
5. On Android 13+ deny notification permission, and verify the app reports a recoverable result rather than crashing.
6. Cancel projection; verify no active session or recording.
7. Stop from the notification and repeat after Activity recreation and process termination; there must be no silent automatic restart.
8. Inspect merged manifest, logcat and app backup/transfer behavior; no real PCM, subtitles or history should exist at R1.

## Known R1 limitations

- R1 permission and FGS flow is NOT a proof that eligible third-party app audio can be captured. That belongs to R2.
- No actual models or translator are installed, and the MediaProjection token is intentionally not consumed. Re-request consent on every future R2 session.
- Android foreground-service rules vary by API version; only device runs can validate the ordering/notification behavior.
- The R0 ML Kit SDK metrics decision and R3 artifact hashes remain blocked as documented in the dependency matrix.
