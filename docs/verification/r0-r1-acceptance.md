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

## Device feedback: six checks passed (user-reported, 2026-10-09)

The user installed the R1 APK on an Android device and reported **PASS** on all six requested primary checks. These are first-hand user observations recorded here; they are not independent automated evidence.

| Check | Reported result |
| --- | --- |
| APK can install and launch | PASS |
| Selecting system audio and Start displays Android recording / MediaProjection consent | PASS |
| After approval, demo running state is shown and an ongoing foreground-service notification appears | PASS |
| Tapping Stop returns the app to Idle and removes its notification | PASS |
| Microphone mode requests permission and starts the demo session | PASS |
| Denying permission or cancelling authorization does not crash the app | PASS |

Primary functional smoke check: **6 / 6 PASS**.

## Additional device / privacy acceptance not yet evidenced

These scenarios were in the expanded acceptance checklist and should not be inferred from the six checks above:

- Stop from the notification action (as distinct from the app UI Stop button), verify notification removal.
- Rapid repeated Start/Stop and Activity recreation; no duplicate session or Window/Service leak.
- Terminate the process/relaunch; there must be no silent automatic restart or stale projection token reuse.
- Android 13+ specifically deny `POST_NOTIFICATIONS` and verify a recoverable state; general permission-denial PASS does not necessarily cover this path.
- Inspect merged manifest, logcat, and Android backup/device-transfer behavior for data/privacy boundaries.
- Record device Android version/API and ABI for native ASR compatibility planning.

These checks do not invalidate the six successful reports but remain open before declaring the whole R1 device acceptance complete.

## Known R1 limitations

- R1 permission and FGS flow is NOT a proof that eligible third-party app audio can be captured. That belongs to R2.
- No actual models or translator are installed, and the MediaProjection token is intentionally not consumed. Re-request consent on every future R2 session.
- Android foreground-service rules vary by API version; only device runs can validate the ordering/notification behavior.
- The R0 ML Kit SDK metrics decision and R3 artifact hashes remain blocked as documented in the dependency matrix.
