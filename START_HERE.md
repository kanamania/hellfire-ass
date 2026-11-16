# Start HellFire Ass with Codex

Phase 0–1 and software slices across Phases 2–6 are implemented. See
[README.md](README.md) for setup/use and [docs/DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md)
for exact host results, remaining model/account gates and the final device checklist.
The owner authorized remaining phases and deferred physical device testing until last.
No physical, model or live-account success is claimed. The debug version is 0.7.0.

Status on 2026-11-16: the UI redesign, home screen widgets, money entry pickers,
fingerprint app lock, daily Google Drive backup, the review fixes and multiple email
inboxes and WhatsApp accounts, the Gemma 4 E2B first-launch download, and the daily
brief, waiting-on, people, debts, bills, expiry, capture-alert and template features
are merged into `main` (version 0.7.0, Room schema 14).
An optional read-only web copy synced from the phone (`web/`, NestJS + Postgres +
Next.js; README → "Web access") is added but not deployed. Host suites pass (247
Android, 23 relay, 6 worker and 11 web API tests). Next work is the physical
OPPO test pass; DEVICE_FEASIBILITY.md → "Review fixes and multiple accounts" lists the
device checks each change still needs. [REDESIGN_SUMMARY.md](REDESIGN_SUMMARY.md) has the hand-test
checklist and [DECISIONS.md](DECISIONS.md) the design decisions.

The original starter instructions are retained below.

1. Extract this ZIP into the root of an empty project/repository (or copy the `docs` folder and `AGENTS.md` into your existing repository).
2. Open the repository in VS Code or your Codex working directory.
3. Give Codex the prompt below.
4. Have a physical Android OPPO available later for assistant-role, notification, recording, and alarm tests. Codex should document untested outcomes honestly.

## Paste this into Codex

You are the lead Android developer for **HellFire Ass**. Read `AGENTS.md` and `docs/MASTER_SPEC.md` completely. Implement **Phase 0 only**: create the Kotlin/Compose application, assistant-role `VoiceInteractionService` and session implementation, notification listener that writes normalized events to Room, permission/status screen, local test assistant response, and visible user-initiated microphone recording proof of concept. Write unit tests and `docs/DEVICE_FEASIBILITY.md`; inspect installed Android SDK/JDK and use working dependencies. Do not fabricate device results, use real credentials, or implement later phases. Run available builds/tests and report exact pass/fail/blockers with next steps.
