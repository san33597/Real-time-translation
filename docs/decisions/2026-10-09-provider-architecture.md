# ADR 2026-10-09: ML Kit primary translator with opt-in DeepSeek refinement

**Status:** APPROVED product architecture on 2026-10-09; real translation/refinement NOT IMPLEMENTED (R1 demo only).
**Supersedes:** Earlier 2026-10-09 Option B self-managed local translator choice. This new user instruction expressly selects **ML Kit first; DeepSeek AI optimization optional**.
**Not authorized:** Automatic cloud transmission, audio upload or activation by default.

## User-visible behavior

**Default (refinement OFF):** local sherpa-onnx English recognition → stabilized English segment → ML Kit on-device EN→ZH translation → immediately show Chinese subtitle.

**AI refinement ON, user opt-in:** same first local ML Kit subtitle → only then asynchronously send a limited, authorized text payload to DeepSeek → if a timely response is valid for the *same still-visible sentence*, update its Chinese translation once; otherwise retain the ML Kit draft.

There is NO automatic cloud fallback, cloud-only primary mode, or background transfer of arbitrary user content in this design. "ML Kit initial translation" always happens before a potential DeepSeek request.

## Provider contract and subtitle coordination

- `LocalTranslationProvider` (`MlKitTranslationProvider`): mandatory first-pass translation of each stabilized English sentence; prepare/download model ahead of time, test readiness and close Translator on cleanup.
- `OptionalTranslationRefiner` (`DeepSeekRefinementProvider`): disabled until user explicitly turns on AI optimization, configures a BYOK API key and acknowledges text disclosure and potential API charges. Separate refinement from baseline `TranslationProvider`; other cloud vendors may be adapted later.
- Input contract: stable/final English source, completed ML Kit Chinese draft, same `sessionId/audioEpoch/utteranceId/revision`, optional approved per-sentence terminology/context, unique request generation, cancellation. Never send audio, ASR partials, full history or unrelated personal metadata.
- Output contract: if successful and still eligible, update the existing live subtitle with a single improved variant. Reject old `sessionId`, `audioEpoch`, `utteranceId`, `revision`, glossary version, invalid placeholders, expired display windows, dismissed/locked entries and provider-generation mismatch.
- The ASR `final` event seals recognition text, *not* indefinite ability for late cloud results to rewrite the displayed subtitle. The brief current-subtitle refinement window ends once the subtitle scrolls, locks or times out. No overwrite of old captions.
- Bounded concurrency/queues, per-request timeout, throttling and cancellation are required; prefer responsiveness and consistent chronological display over accepting every late cloud result.
- If ML Kit unavailable/failed: keep English text, show recoverable model error and offer local model download. DeepSeek is not an automatic substitute.
- If DeepSeek network/key/quota/timeout/validation fails: keep ML Kit draft and continue other segments normally. Closing AI optimization must prevent further cloud requests immediately.

## Privacy disclosure and credentials

- Audio and sherpa-onnx ASR never leave the device. ML Kit runs on-device translation: Google says it does not transmit translation input/output to its servers, but SDK APIs may send diagnostic/usage/device/app metrics and fetch model updates. Thus **zero telemetry is NOT an accurate guarantee**. Official sources: https://developers.google.com/ml-kit/terms and https://developers.google.com/ml-kit/android-data-disclosure .
- A user-enabled DeepSeek refinement sends the selected stabilized English sentence and its ML Kit Chinese draft to the chosen API. Sending term hints or short context requires explicit disclosure/permission and must default to minimal payload. This is **not** offline or no-content-egress while enabled. Provider data retention/fees are subject to its terms.
- DeepSeek's OpenAI-compatible chat completion endpoint is documented at https://api-docs.deepseek.com/api/create-chat-completion/ ; verify model IDs and latest API before writing the adapter.
- Store BYOK secrets using Android Keystore-backed encryption or a vetted equivalent in private no-backup storage. Never ship built-in keys, expose keys in exported components, commit or log them, or upload them to analytics. Provide key deletion, visible AI-enabled label, settings switch and cost warning.
- Strict permission scope: no silent provider enablement after reinstall, app restore, model-not-ready, network return or local translator exception. Distinguish ML Kit SDK diagnostics from the user's explicitly authorized DeepSeek text transfer.

## Acceptance and open gates

1. R1 currently has NO audio capture, ASR, ML Kit or DeepSeek implementation; do not treat this ADR as proof of a working translator.
2. R2: internal audio/MIC capture. R3: local sherpa-onnx streaming ASR. R4: C stabilizer + glossary + ML Kit model readiness/translation. Optional DeepSeek refinement after primary pipeline, then R5/R6 overlays, privacy and performance.
3. Tests: OFF always makes zero DeepSeek requests; ON must show ML Kit draft first. Verify opt-in, encrypted keys, cancellation, no audio egress, minimal payload, timeout/rate-limit/quota errors, content sanitization, glossary restoration and stale-result rejection across sessions/audio epochs/utterance revisions.
4. Review exact ML Kit SDK metrics and app disclosure, device-specific model download/reliability, official DeepSeek API privacy/cost policies and observed outgoing requests. This is **ML Kit + user-authorized cloud refinement**, not strict zero telemetry.
