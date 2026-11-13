# AGENTS.md — HellFire Ass

## Source of truth
Read `docs/MASTER_SPEC.md` (the provided `HellFire_Ass_Codex_Master_Spec.md`) fully before starting. The project name is exactly **HellFire Ass**; use assistant invocation name **HellFire** and proposed package `com.hellfire.ass`.

## Execution rules
- **Implement Phase 0 first; do not start later phases automatically.**
- Inspect the existing repo, then propose a concise implementation plan and implement a compiling, testable vertical slice.
- Android: Kotlin, Jetpack Compose, Room, coroutines, testable integration adapters. Consult installed SDK/tooling and official docs before choosing versions.
- Do not invent API methods, fake demo integrations, successful device tests, or claim to have used a device that is not connected.
- Work offline for core capture/database/assistant demonstrations. Large models and external credentials are not needed in Phase 0.
- Notifications: ingest every notification available to the permitted `NotificationListenerService`, not just bank/chat apps. Redact OTPs and honor Android-protected fields.
- Assistant: implement real `VoiceInteractionService` and session metadata/role integration; OEM gesture and hotword support require physical verification.
- Mic: create a visibly active, user-authorized recording prototype. Do not use stealth capture or OS-bypass tricks.
- WhatsApp later: official Business Platform Coexistence and self-hosted webhook gateway. Do not rely on notification-only integration for eligible Business account.
- Gmail later: IMAP/SMTP with user-provided Gmail app password where allowed. Do not use Gmail API.
- Calls later: import the OPPO's existing call recordings; never assume third-party call audio capture is possible.
- In-person conversation audio later: retain encrypted original audio **and** transcript/summary until user chooses retention/deletion.
- Finances later: deterministic integer-based ledger, template parsers and provenance; LLM only for candidate extraction.
- External sends/side effects must be user-approved by default.
- Never commit secrets, credentials, personal notifications, raw recordings, real SMS corpora or private data.
- Don't upgrade OS/global packages or unrelated dependencies.

## Definition of Done — each phase
1. Build works with available tooling, or exact missing prerequisites documented.
2. Automated tests for new logic pass; command/results reported.
3. No missing permissions falsely represented as granted.
4. `docs/DEVICE_FEASIBILITY.md` updated with PASS/PARTIAL/BLOCKED/NOT TESTED and evidence.
5. Code changes summarized with next steps and blockers.

## Phase 0 focus
Create the Android app skeleton, default-assistant integration, notification ingest into Room, permission/status screen, local assistant test response, a microphone foreground-service proof of concept, tests, and device-specific verification guide. Do not attempt a whole app in one Codex run.
