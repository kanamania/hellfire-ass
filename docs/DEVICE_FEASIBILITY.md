# HellFire Ass — implementation and feasibility

Evidence date: 2026-11-13, Africa/Dar_es_Salaam. The owner authorized remaining
phases and explicitly kept physical device testing until last. No physical device,
emulator, live account, personal message corpus, recording or model weights were used.
No device-dependent phase is fully accepted solely because its host checks pass.

## Current phase gates

| Phase | Software evidence | Status | Remaining gate |
|---|---|---|---|
| 0 — feasibility | Android skeleton, real assistant/session metadata, capture, visible recording controls, access/status UI | PARTIAL | OEM role/gesture/microphone/access behavior NOT TESTED |
| 1 — capture | Transactional immutable sources, processing jobs, audits/policy, read-only paged SMS reconciliation | PASS (host) / PARTIAL (device) | Real delivery, SMS grants, screen-off/process/boot behavior NOT TESTED |
| 2 — AI/finance | Integer ledger, separate fees/transfers/reversals/evidence, review edits, provider-scoped merchant aliases, cash/date review, strict candidate schemas, LiteRT-LM adapter and bundled OCR | PARTIAL | Gemma 4 E2B/fallback benchmark BLOCKED without owner-selected compatible model/device; representative provider corpus unavailable; 95% accuracy NOT ESTABLISHED |
| 3 — communications | IMAPS/SMTP, UIDVALIDITY/MIME handling, bounded visible IDLE, drafts/one-use approval/recovery, encrypted signed-webhook relay and delivery/echo adapters | PARTIAL | Live Gmail app-password access and Meta eligibility/Coexistence onboarding NOT TESTED; no real sends or deployment |
| 4 — tasks/alarms | Evidence-linked candidates, owner activation, escalation, Done/Snooze/Reschedule, durable restoration, nightly review and morning/evening query briefs | PARTIAL | Physical alarm/Doze/DND/permission/reboot tests NOT TESTED; overdue repeat interval owner-configurable (5–1440 minutes, default 10) |
| 5 — media | Encrypted originals, SAF import/folder scanning, VAD intervals during visible owner recording, compiled whisper JNI adapter, chunked processing, OCR/PDF/video sampling, playback/export/delete/manual speakers/summary review | PARTIAL | Actual decoder/STT/OCR/model accuracy and OEM file visibility NOT TESTED. Opus encoding, reliable learned VAD/diarization/owner-voice matching and model-based vision/embedding index remain research gates; no simulated outputs |
| 6 — assistant/stability/backup | Source queries/links, deterministic finance/briefs, typed review-only proposals, memory provenance/correction/forget, encrypted portable backup/staging/recovery, startup storage and send recovery | PARTIAL | Full native/device backup round trip, SQLCipher conversion/Keystore, battery/peak RAM/thermal and usability tests NOT TESTED. Literal retrieval is bounded; autonomous/semantic capability is not claimed |

## Host verification

From `android/`:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Final result: **93 tests; 0 failures/errors/skips. Lint: 0 errors,
44 warnings. APK assembly passed.** Native whisper JNI compiled for
arm64-v8a and x86_64. Robolectric uses API 34 and ordinary SQLite/generated AES keys;
it does not execute phone Keystore, native SQLCipher, MediaCodec, ML Kit or models.
Warnings remain enabled: dependency/update notices, English resources, native API
annotations/KTX suggestions and the existing deprecated Material tab API.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, version **0.4.0**, code 4,
package `com.hellfire.ass`, min/target API 29/36, arm64-v8a and x86_64 only.
SHA-256: `98d21f570eb586aee7ea943d5ce0ae170a70bceb6176a311f01e213538c97134`.
HTML/XML reports are preserved under `android/app/build/reports/` and
`android/app/build/test-results/testDebugUnitTest/` after integration.

| Check | Result | Evidence / limits |
|---|---|---|
| Tooling | PASS | JDK 21.0.13, SDK 36/build tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1; no global package upgrades |
| Normalization/capture/SMS regression | PASS (host) | Immutable identities, malformed/missing/protected content, OTP precedence, marked money/reference/date preservation, queue/policy/rollback/persistence and SMS paging fixtures |
| Room migrations | PASS (host) | Construct schema 1, migrate non-destructively through schema 8; source ciphertext retained and Room validates tables/indices/FKs |
| Finance | PASS (host) | Integer limits, synthetic provider candidates, duplicate-reference evidence, transfer balancing, separate fees, edited approval, refunds/expense totals and append-only reversals |
| Candidate/action contracts | PASS (host) | Strict integer/schema fields, invalid dates/parties/channels, unsupported execution and injected approval/header data rejected |
| Communication persistence/MIME | PASS (host) | Approval freshness/single claim, interrupted-process recovery, monotonic delivery states, UID cursor isolation, Unicode/plain-text alternatives and bounded deep MIME traversal |
| Tasks | PASS (host) | Source-relative EAT dates, invalid dates, escalation math, review-only until activation, snooze/done, source FKs and explicit nightly review completion |
| Media container | PASS (host) | Authenticated seeking across chunks, corruption/wrong identity rejection, interrupted recording prefix recovery and energy interval/exclusion logic; no real microphone/codec |
| Backup components | PASS (host) | Table allowlists, Unicode rows/evidence, different payload key after restore, wrong passphrase and stale approval revocation. Does NOT establish full SAF/SQLCipher/Keystore/device restore |
| APK/native alignment | PASS (host) | `zipalign -c -P 16 4` passes; all 14 packaged ELF libraries have LOAD alignment >=16 KiB. Physical installation remains NOT TESTED |
| Relay tests | PASS (host) | Seven pytest tests: signature/verification/auth, encrypted replay/ACK, business filtering/statuses, approval/replay/expiry/unknown delivery, bounds/rate limiting and log query redaction; mocked Graph only |
| Relay image / Compose | PASS (host) | `docker build -t hellfire-relay:local server`; non-root image `b02eda1eb4f8`; Compose parses with no env resolution. Ephemeral ASGI smoke confirms UID 10001, honest unconfigured health and protected endpoint 503; no listener/deployment/live network |
| Physical/model/live-account tests | NOT TESTED | Explicitly deferred; neither grants, Coexistence eligibility, actual inference nor successful delivery is asserted |

Relay verification from `server/`:

```bash
.venv/bin/python -m pytest -q
docker compose config --no-env-resolution --no-interpolate
```

Result: **7 passed**. One Starlette TestClient/httpx deprecation warning remains;
no runtime/security check was disabled to hide it. Docker's legacy-builder notice
also remains visible. A separate isolated container smoke test imports the app and
checks ASGI routes without opening a port or contacting Meta.

`aapt` confirms INTERNET, SCHEDULE_EXACT_ALARM, data-sync/microphone foreground
permissions, SMS read/receive, microphone/posting, and normal WorkManager permissions.
It declares no SEND_SMS or broad media-reading grant. Declarations are not grants.
The app acquires files/folders through owner-controlled SAF selection.

Historical host checkpoints: Phase 0 — 34 tests; Phase 1 — 58; combined initial
finance/comms slice — 66; media slice — 80; initial assistant/backup slice — 87.
Current artifact/results above supersede prior debug builds, not their untested
physical outcomes.

## Final-stage device verification

Run these when physical testing begins, from the checkout root. Do not assume the
model, firmware, ABI, feature support, storage paths or access from the owner's
hardware description. With multiple devices use `adb -s SERIAL` locally. Do not
commit serials, private dumps, messages, databases or recordings.

```bash
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.security_patch
adb shell getprop ro.build.version.opporom
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hellfire.ass/.MainActivity
```

In Setup, select HellFire Ass. Record role held separately from voice service active;
neither proves the session works until it opens. Use the settings fallback if needed.

```bash
adb shell cmd role get-role-holders android.app.role.ASSISTANT
adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction
```

In airplane mode ask `hello`, `habari`, and `time` in the activity, then in the real
session. Test the supported gesture/button from another app and after backgrounding.
Try optional offline speech and TTS only if system engines are available. Record
engine/language/error and firmware. No custom hotword or lock-screen parity is claimed.

Grant notification access manually; use Android's owner-controlled restricted-settings
flow only if offered. Insert the synthetic fixture twice, then post controlled input:

```bash
adb shell "cmd notification post -t 'HellFire fixture' hellfire-phase1 'Synthetic delivery test'"
adb shell "cmd notification post -t 'HellFire fixture edited' hellfire-phase1 'Synthetic updated text'"
```

Verify actual source package, posted/updated/removed evidence, redaction flags and
processing labels. Dismiss manually for removal. Test silent, ongoing, grouped,
image-only and malformed/empty inputs from authorized test apps. Toggle access,
background the app and reconnect. Identical snapshots must deduplicate; changed
rank/post time can retain new evidence. Protected content stays unavailable.

Turn an app's processing switch off: its next notification must still be captured,
with CAPTURE_ONLY status. Turn it back on: new/pending events can be classified;
existing capture-only/completed records are not rewritten. Retry failed jobs only
through the visible Resume control. Check historical failures versus live access.

For SMS, use only a test SIM/provider corpus you control. Tap **Enable SMS capture
and history import**; verify READ_SMS and RECEIVE_SMS separately. If Android or the
installer refuses them, record BLOCKED/PARTIAL and leave other sources functional.
Do not bypass restrictions or claim default-assistant selection guarantees SMS access.
Check incoming and sent provider rows, multipart messages as stored by the default
SMS app, imports over 500 rows, replayed imports and outbox-to-sent transitions.
Drafts/failed/outbox rows must not appear as sent. Some messages can be omitted by
the provider; broadcast receipt alone is not stored-body evidence.

Test new incoming broadcasts, outgoing provider observation, app/process restart and
best-effort periodic reconciliation. WorkManager may defer work under Doze/OEM rules;
15 minutes is a requested maintenance interval, not a delivery deadline. Disabling
SMS capture must stop future imports, cancel its pending chain and retain old evidence.
Permission revocation must be reported honestly. No action in this app sends an SMS.

For microphone tests keep the app visible, grant mic and posting permissions, and
Start. Verify Android's mic indicator, persistent notification and Stop action.
Record a non-sensitive sample, stop/play/delete, then repeat to automatic 60-second
stop. Test permission denial/revocation, privacy mute, blocked notification channel,
competing mic, call interruption and screen off. No restart or hidden capture should
occur. Process/power loss can leave incomplete ciphertext, which playback rejects.

For controlled lifecycle tests, terminate the process only after known events have
committed, then reopen and verify pending processing resumes without duplicates.
Separately test before-commit termination and queue overflow: losses can occur there
and must not be claimed recoverable. Test reboot and force-stop separately; Android
can prevent work until user relaunch. Perform battery measurements later.

Optional debug inspection after stopping capture and recording:

```bash
adb shell run-as com.hellfire.ass ls databases
adb shell run-as com.hellfire.ass ls files/recordings
```

Inspect only synthetic evidence if exporting a debug database. The production database, payloads and audio must be ciphertext. Keystore/native
SQLCipher behavior and legacy plaintext conversion need explicit physical verification. Clear controlled test events and samples in the app. Keep all actual
device artifacts out of Git.

## Final checks for Phases 2–6

Run only after the owner starts physical testing, using controlled synthetic material.
Keep messages, credentials, recordings, serials and exported backups out of Git.

1. **Encryption and migration:** install over the previous debug app after a safe
   backup of synthetic data. Confirm legacy events migrate and the database file
   no longer exposes SQLite headers/plaintext. Exercise denied/unavailable Keystore,
   storage-full and retry paths; original files must remain, with no plaintext
   fallback. Host Room schema validation does not establish native conversion.
2. **Finance:** create test accounts and approve expenses, income, own transfers,
   separate fees, refunds and reversals. Replay SMS/notification evidence with the
   same provider reference; it must not double post. Test edited dates/amounts,
   merchant reuse and nightly skipped-day review. Obtain a representative,
   owner-approved anonymized provider corpus before measuring the >=95% parser
   target; synthetic fixtures are not that corpus. Review unfamiliar/reversal
   templates explicitly.
3. **Models/OCR:** owner imports licensed compatible model files. In airplane mode,
   test Gemma 4 E2B and an appropriate smaller fallback separately on CPU/GPU.
   Record cold-start/request latency, actual output correctness, peak memory,
   thermal state and battery deltas, including failed runs. UI PSS is a process
   snapshot after the request, not peak/model-only RAM. No NPU support is inferred
   from the phone marketing description. Test bundled OCR with non-sensitive images,
   receipts, multi-page PDFs, video samples and unsupported files; OCR never proves
   settlement. Schema-invalid output must stay rejected.
4. **Accounts:** only with actual owner permission, configure eligible Gmail app
   password / IMAP and the owner-provisioned HTTPS Meta relay. Verify Coexistence
   eligibility, number/account linkage, webhook subscriptions, encryption pairing,
   incoming/echo/status delivery and unsupported envelopes. Drop network mid-sync,
   change UIDVALIDITY and replay batches. Stop IDLE via UI/notification/timeout and
   disconnect. Inspect source IDs and ensure no message is marked sent merely from
   preparation or SMTP acceptance.
5. **Approval/failure:** approve one controlled external reply; compare actual
   recipient/body/reply headers. Test stale/replayed approval, app death before and
   during transport, credential changes and delayed/out-of-order confirmations.
   Recovery must require fresh approval or keep UNKNOWN; it must not resend. Check
   the actual channel before manually creating another send after uncertainty.
6. **Alarms:** review candidate versus active task, 24h/2h/due/overdue escalation,
   configurable repeat interval, Done/Snooze/Reschedule, revoked posting/exact-alarm
   access, blocked channel, reboot/update/time changes, Doze/DND and force-stop.
   Record real delay, sound and restoration separately; best effort does not promise
   DND bypass or force-stop survival. Check morning/evening brief totals and sources.
7. **Recordings/media:** test owner notice/exclusions before visible monitoring;
   verify Android indicator, ongoing notice, Stop and 30-minute bound. Revoke
   permissions/notice and take a call; retain authenticated recording prefix with
   visible interruption status. No boot/process interruption may restart the mic.
   Test noise/speech/music and VAD false positives, manual speaker correction and
   playback from timestamps. Measure actual PCM storage/battery cost; Opus and
   learned speaker identity are not claimed. Import/replay accessible OPPO recordings
   and selected-folder changes. If files are private/inaccessible, record BLOCKED
   and use dialer sharing. Test rename, unknown/known original dates, video without
   audio, charging/thermal constraints, analysis cancellation/recovery, decrypted
   export and media deletion versus retained linked source snapshots.
8. **Assistant/memory:** compare deterministic finance answers against the ledger;
   inspect source links, query limits, facts versus inferences, owner corrections
   and index-only forgetting. Verify both typed and installed offline speech/TTS
   queries. Incoming instruction-like source text cannot execute tools. Typed
   proposals only retain Tasks/Messages review items; separate approval is required.
9. **Backup:** export synthetic registered originals and relational data with a
   strong test passphrase. Authenticate/restore into an empty separate installation
   or test profile with capture/accounts off. Compare ciphertext decryption, media
   hashes, ledger evidence, tasks, transcripts, Unicode memory and source links.
   Wrong password, truncation, tampering and unsupported schema must fail without
   replacing current data. Test process loss during staging/commit and verify
   journal cleanup. Restored approvals must not send; accounts, model files,
   grants and exclusions need reconfiguration. Do not rely on backups until this
   full native/device round trip passes.
10. **Stability/energy:** compare baseline and enabled-source overnight runs, model
    jobs on/off charger, screen off, low storage/battery, severe thermal status,
    interrupted connectivity and process death. Record actual losses and delays.
    Tune only from measurements; no overnight survival or energy claim is made.

## References and limits

- [Assistant service](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
  and [notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService).
- [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work)
  and [work chaining policies](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/chain-work).
- [SMS provider](https://developer.android.com/reference/android/provider/Telephony.Sms)
  and [protected SMS broadcast](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents).
- [Microphone foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).
- Notifications before a durable commit or while disconnected can be lost; committed
  jobs persist. OTP filtering is conservative, not a universal secret detector.
- Host tests establish software contracts, not OEM permissions or device success.
