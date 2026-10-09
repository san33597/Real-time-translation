# ADR 2026-10-09: Local-first translation and optional BYOK cloud providers

**Status:** Product decision ACCEPTED; implementations remain NOT STARTED (beyond R1 fake session).
**Authority:** User chose **Option B** on 2026-10-09 and explicitly wants the option to connect their own DeepSeek API or similar providers. This is not an instruction to integrate or activate a cloud provider immediately.
**Replaces:** Original V0.1 ML Kit Translate selection and blanket exclusion of cloud Providers. Does not replace the English-only ASR/overlay/permission architecture.

## Chosen operating modes

| Mode | Default | Inference | Network behavior | Privacy claim |
| --- | --- | --- | --- | --- |
| LOCAL | Yes | Self-managed, auditable on-device translation model (to be selected) | User-initiated model download only; no runtime telemetry or content egress (to be verified) | Local/offline after setup, subject to test |
| CLOUD | No; opt-in | User-selected DeepSeek or compatible text translation API (future feature) | Sends selected stable recognized English text (and opt-in context) over TLS to chosen provider; provider may process/retain it under its terms | **Not** zero-egress/offline; raw audio stays on device |

Never silently switch LOCAL → CLOUD if models are unavailable, an API fails, or the device runs out of resources. Cloud mode must carry a persistent visible indicator and allow immediate reversion to LOCAL.

## Provider boundary

- Keep `AudioCapture → local sherpa-onnx ASR → SentenceStabilizer → TranslationProvider → SubtitleState`.
- Interface should support capability `LOCAL|CLOUD`, provider ID, per-request `sessionId`, `audioEpoch`, `utteranceId`, `revision`, glossary version, cancellation, timeout, and validated final/stable ordering.
- Send only stable/final English segments. Prior sentence context may improve quality, but it must be separately disclosed/limited and configurable or disabled. Never upload audio samples, complete history, raw partials or unrelated device IDs.
- Prefer a local translator for the initial R4 acceptance; plan cloud adapters separately so they cannot weaken offline acceptance criteria.
- DeepSeek's documented OpenAI-compatible endpoint is `https://api.deepseek.com/chat/completions`; model names and contracts must be verified again at implementation time. Official reference: https://api-docs.deepseek.com/zh-cn/
- For BYOK personal use, credentials must never be hard-coded or committed. Use Keystore-backed encryption on device, avoid backups/logging, avoid arbitrary endpoint injection, use HTTPS, provide test connection, revoke/delete credentials, usage limits and user-visible cost warnings. On-device secrets are not impervious to extraction on compromised phones; public/multi-user deployments would need a separately planned secure relay.

## Open engineering gates

1. Select a concrete Android EN→ZH local model and runtime, verify performance on the target phone, license, hashes, memory and offline egress.
2. Finalize R2 audio capture and R3 streaming ASR before integrating the real provider.
3. Decide how optional cloud translations are exposed in UI, consent, retries, cost caps, model settings and whether text history is sent (default: not).
4. Verify LOCAL with the network disconnected and packet/SDK inspection; verify CLOUD cannot be invoked without explicit permission and sends only the approved minimum payload.
5. Keep R1 PR #1 in Draft until remaining R1 acceptance items are completed; 6/6 user-reported smoke checks were recorded separately.

## Security and failure contracts

- API key must be provided by the user, not stored in repo, APK constants, exported components, stdout, trace, crash SDK or subtitle history.
- No fallback to cloud when translation is empty/late/fails; preserve English text, display recoverable errors.
- Cloud text can be subject to the provider's privacy and retention rules; local zero-telemetry statement does not cover user-authorized cloud use.
- Before implementation, review DeepSeek or other providers' latest documentation, quota/price terms and data processing statements.
