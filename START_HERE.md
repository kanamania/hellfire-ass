# Start HellFire Ass with Codex

Current software baseline: version 0.9.0, Room/backup schema 19 (2026-11-17).
The owner authorized all phases and keeps physical device testing for last.
The owner-selected target is now a OnePlus 16, described as Snapdragon 8 Elite Gen 6;
read actual firmware, ABI and CPU/accelerator capabilities from the connected phone.
No physical-device, model-performance or live-account success is claimed.

A media review found functional bugs missed by the 428-test integration pass.
The four interrupted fix branches are being completed and verified sequentially:
library/intelligence, players/sharing, drawer/backup, then sync/web. Consult the
latest checkpoint in [DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md) for each
branch's actual status. Do not treat an earlier successful APK build as proof that
playback, redaction, restore or sync worked end to end.

The local web copy is installed under systemd at https://hellfire.local. Phone
reachability and certificate trust remain separate device checks. Contacts use
Android's local synced provider; person links are not sent to the web copy.
Backups accept schema 8 and 10–19. QR decoding and a system-wide drawer overlay
are not implemented; the drawer is in-app plus Android Share.

Read [README.md](README.md) for setup, [DECISIONS.md](DECISIONS.md) for implementation
contracts, and [REDESIGN_SUMMARY.md](REDESIGN_SUMMARY.md) for manual UI checks.
The starter text below is historical: later owner updates in MASTER_SPEC.md
supersede its Phase-0-only limit and original OPPO target.

The original starter instructions are retained below.

1. Extract this ZIP into the root of an empty project/repository (or copy the `docs` folder and `AGENTS.md` into your existing repository).
2. Open the repository in VS Code or your Codex working directory.
3. Give Codex the prompt below.
4. Have a physical Android OPPO available later for assistant-role, notification, recording, and alarm tests. Codex should document untested outcomes honestly.

## Paste this into Codex

You are the lead Android developer for **HellFire Ass**. Read `AGENTS.md` and `docs/MASTER_SPEC.md` completely. Implement **Phase 0 only**: create the Kotlin/Compose application, assistant-role `VoiceInteractionService` and session implementation, notification listener that writes normalized events to Room, permission/status screen, local test assistant response, and visible user-initiated microphone recording proof of concept. Write unit tests and `docs/DEVICE_FEASIBILITY.md`; inspect installed Android SDK/JDK and use working dependencies. Do not fabricate device results, use real credentials, or implement later phases. Run available builds/tests and report exact pass/fail/blockers with next steps.
