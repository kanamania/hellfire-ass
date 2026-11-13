# Start HellFire Ass with Codex

Phase 0 source is now implemented. See [README.md](README.md) for build and use
instructions and [docs/DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md) for
actual verification results and physical-device checks. Later phases remain deferred.

The original starter instructions are retained below.

1. Extract this ZIP into the root of an empty project/repository (or copy the `docs` folder and `AGENTS.md` into your existing repository).
2. Open the repository in VS Code or your Codex working directory.
3. Give Codex the prompt below.
4. Have a physical Android OPPO available later for assistant-role, notification, recording, and alarm tests. Codex should document untested outcomes honestly.

## Paste this into Codex

You are the lead Android developer for **HellFire Ass**. Read `AGENTS.md` and `docs/MASTER_SPEC.md` completely. Implement **Phase 0 only**: create the Kotlin/Compose application, assistant-role `VoiceInteractionService` and session implementation, notification listener that writes normalized events to Room, permission/status screen, local test assistant response, and visible user-initiated microphone recording proof of concept. Write unit tests and `docs/DEVICE_FEASIBILITY.md`; inspect installed Android SDK/JDK and use working dependencies. Do not fabricate device results, use real credentials, or implement later phases. Run available builds/tests and report exact pass/fail/blockers with next steps.
