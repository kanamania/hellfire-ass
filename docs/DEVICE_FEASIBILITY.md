# HellFire Ass — implementation and feasibility

Evidence date: 2026-11-13, Africa/Dar_es_Salaam. The owner authorized remaining
phases and explicitly kept physical device testing until last. No physical device,
emulator, live account, personal message corpus, recording or model weights were used.
The existing optional cloud providers/settings were merged from main; their live
tests remain opt-in and unconfigured.
No device-dependent phase is fully accepted solely because its host checks pass.

## Current phase gates

| Phase | Software evidence | Status | Remaining gate |
|---|---|---|---|
| 0 — feasibility | Android skeleton, real assistant/session metadata, capture, visible recording controls, access/status UI | PARTIAL | OEM role/gesture/microphone/access behavior NOT TESTED |
| 1 — capture | Transactional immutable sources, processing jobs, audits/policy, read-only paged SMS reconciliation | PASS (host) / PARTIAL (device) | Real delivery, SMS grants, screen-off/process/boot behavior NOT TESTED |
| 2 — AI/finance | Integer ledger, separate fees/transfers/reversals/evidence, review edits, provider-scoped merchant aliases, cash/date review, strict candidate schemas, LiteRT-LM adapter and bundled OCR | PARTIAL | Gemma 4 E2B/fallback benchmark BLOCKED without owner-selected compatible model/device; representative provider corpus unavailable; 95% accuracy NOT ESTABLISHED |
| 3 — communications | IMAPS/SMTP, UIDVALIDITY/MIME handling, bounded visible IDLE, drafts/one-use approval/recovery, encrypted relay, owner-selected whatsapp-web.js capture and optional Meta adapters | PARTIAL | Live Web pairing/restart/message coverage and Gmail access NOT TESTED; optional Meta eligibility/Coexistence remains unverified; no real sends or deployment |
| 4 — tasks/alarms | Evidence-linked candidates, owner activation, escalation, Done/Snooze/Reschedule, durable restoration, nightly review and morning/evening query briefs | PARTIAL | Physical alarm/Doze/DND/permission/reboot tests NOT TESTED; overdue repeat interval owner-configurable (5–1440 minutes, default 10) |
| 5 — media | Encrypted originals, SAF import/folder scanning, adaptive-noise-floor VAD intervals and AAC-LC/ADTS compressed encrypted recording during visible owner recording, compiled whisper JNI adapter, chunked processing, OCR/PDF/video sampling, bundled ML Kit image labels, playback/export/delete/manual and experimental automatic speaker labels/summary review | PARTIAL | Actual decoder/STT/OCR/label/model accuracy and OEM file visibility NOT TESTED. On-device AAC encoder output/size/battery NOT TESTED. Speaker grouping, owner-voice matching and name placeholders are implemented (owner-imported ONNX embedding model, ONNX Runtime 1.31.1) but their accuracy is NOT TESTED; learned VAD is not implemented; no simulated outputs |
| 6 — assistant/stability/backup | Source queries/links, deterministic finance/briefs, typed review-only proposals, confirm-first English/Kiswahili voice/chat commands (reminders, memory notes, task done/snooze/reschedule, cash review items, email/WhatsApp drafts), memory provenance/correction/forget, encrypted portable backup/staging/recovery, startup storage and send recovery | PARTIAL | Full native/device backup round trip, SQLCipher conversion/Keystore, battery/peak RAM/thermal and usability tests NOT TESTED. Literal retrieval is bounded. Semantic search code is present (owner-imported MediaPipe text embedder, arm64-v8a only) but retrieval quality, indexing speed and battery cost are NOT TESTED; no autonomous capability is claimed |

## Host verification

From `android/`:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Final result: **153 tests: 149 passed, 4 opt-in live AI tests skipped; 0 failures/errors. Lint: 0 errors,
33 warnings. APK assembly passed.** Native whisper JNI compiled for
arm64-v8a and x86_64. Robolectric uses API 34 and ordinary SQLite/generated AES keys;
it does not execute phone Keystore, native SQLCipher, MediaCodec, ML Kit or models.
Warnings remain enabled: dependency/update notices, English resources, native API
annotations/KTX suggestions and the existing deprecated Material tab API.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, version **0.4.0**, code 4,
package `com.hellfire.ass`, min/target API 29/36, arm64-v8a and x86_64 only.
SHA-256: `843ed659ad6dbad6ca9db5306ae41b5b225894680572fe2c92977cbf8a38a62e` (≈242 MB; uncompressed 16 KiB-aligned native libraries — ONNX Runtime adds ≈75 MB across arm64-v8a and x86_64).
HTML/XML reports are preserved under `android/app/build/reports/` and
`android/app/build/test-results/testDebugUnitTest/` after integration.

| Check | Result | Evidence / limits |
|---|---|---|
| Tooling | PASS | JDK 21.0.13, SDK 36/build tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1; no global package upgrades |
| Normalization/capture/SMS regression | PASS (host) | Immutable identities, malformed/missing/protected content, OTP precedence, marked money/reference/date preservation, queue/policy/rollback/persistence and SMS paging fixtures |
| Room migrations | PASS (host) | Construct schema 1, migrate non-destructively through schema 9; source ciphertext retained and Room validates tables/indices/FKs |
| Finance | PASS (host) | Integer limits, synthetic provider candidates, duplicate-reference evidence, transfer balancing, separate fees, edited approval, refunds/expense totals and append-only reversals |
| Candidate/action contracts | PASS (host) | Strict integer/schema fields, invalid dates/parties/channels, unsupported execution and injected approval/header data rejected |
| Speaker features / grouping / names | PASS (host) / NOT TESTED (device) | `SpeakerTest`: Kaldi-style fbank frame counts, mean normalization and tone peaks in the expected mel bin; centroid clustering of synthetic embeddings; English/Kiswahili introduction and direct-address name detection; placeholder assignment needs score ≥2, one group per name, owner group skipped. No ONNX model is run on host; real-voice grouping/owner/name accuracy NOT TESTED |
| Semantic index / image labels | PASS (host) / NOT TESTED (device) | `SemanticIndexTest`: L2 normalization, little-endian vector round trip, cosine ranking, pending excludes sensitive/unavailable/indexed sources, empty-text markers never searched, model switch re-indexes, vectors cascade with sources, confident-label formatting. MediaPipe `libmediapipe_tasks_text_jni.so` 0.10.26 ships arm64-v8a only (16 KiB aligned); no embedding or ML Kit labeling is executed on host |
| Owner voice/chat commands | PASS (host) / NOT TESTED (device) | `AssistantCommandsTest`: questions stay queries; reminder dates/titles (EN/SW, past/missing dates rejected), memory subjects, cash parsing, email/WhatsApp validation and header injection, task action/reschedule parsing, active-only task lookup. `execute` composes existing tested task/draft/finance/memory writes but is not run on host (needs Keystore-backed `HellFireApp`). Spoken commands through the system session NOT TESTED |
| Communication persistence/MIME | PASS (host) | Approval freshness/single claim, interrupted-process recovery, monotonic delivery states, UID cursor isolation, Unicode/plain-text alternatives and bounded deep MIME traversal |
| Tasks | PASS (host) | Source-relative EAT dates, invalid dates, escalation math, review-only until activation, snooze/done, source FKs and explicit nightly review completion |
| Media container | PASS (host) | Authenticated seeking across chunks, corruption/wrong identity rejection, interrupted recording prefix recovery, exclusion logic, ADTS header bits, and adaptive VAD on synthetic signals (steady 0.03 RMS noise rejected where the old fixed threshold fired, 20 ms clicks ignored, odd read boundaries); no real microphone/codec — `MediaCodec` AAC encoding does not run on host |
| Backup components | PASS (host) | Table allowlists, Unicode rows/evidence, different payload key after restore, wrong passphrase and stale approval revocation. Does NOT establish full SAF/SQLCipher/Keystore/device restore |
| APK/native alignment | PASS (host) | `zipalign -c -P 16 4` passes; all 21 packaged ELF libraries have LOAD alignment >=16 KiB. Physical installation remains NOT TESTED |
| Relay tests | PASS (host) | 21 pytest tests (including linked-device capture): signature/verification/auth, encrypted replay/ACK, business filtering/statuses, approval/replay/expiry/unknown delivery, bounds/rate limiting and log query redaction; mocked Graph only |
| Relay image / Compose | PASS (host) | `docker build -t hellfire-relay:whatsapp-web server`; non-root image `4903d7c497e2`; Compose parses with no env resolution. Ephemeral ASGI smoke confirms UID 10001, honest unconfigured health and protected endpoint 503; no listener/deployment/live network |
| Physical/model/live-account tests | NOT TESTED | Explicitly deferred; neither grants, Coexistence eligibility, actual inference nor successful delivery is asserted |

Relay verification from `server/`:

```bash
.venv/bin/python -m pytest -q
docker compose config --no-env-resolution --no-interpolate
```

Result: **21 passed**. One Starlette TestClient/httpx deprecation warning remains;
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

## WhatsApp linked capture checkpoint — 2026-11-13

The owner explicitly selected `whatsapp-web.js` and authorized this capture-only
slice, superseding the former Coexistence-only selection. Meta mode is preserved
as an alternative. No live account, phone or personal message was used.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Worker normalization, encrypted queue and lifecycle? | `cd server/whatsapp-web && npm test` | PASS (host) | 6 tests: incoming/outgoing/group author and media metadata, AES-GCM identity binding, restart/deduplication, quota/expiry, lifecycle/late QR and text bounds | Verify real provider events after owner linking |
| Relay and existing Meta regression? | `cd server && .venv/bin/python -m pytest -q` | PASS (host) | 21 passed; includes actual Node-generated ciphertext imported by Python, phone-envelope decryption, replay/ACK, failed commit/tampering preservation, auth, QR expiry, web send rejection and bounded batches fitting Android's 2 MiB limit. Existing Starlette/httpx deprecation warning remains | Exercise real HTTPS and paired Android persistence |
| Dependency advisories? | `npm audit --omit=dev --omit=optional` | PASS (host) | Zero known vulnerabilities at this checkpoint after Puppeteer 25.17.0 override and omitting unused RemoteAuth dependencies; no global packages changed | Recheck on later updates; live browser/library compatibility remains unverified |
| Library/browser adapter imports? | Construct real `Client` and `LocalAuth`, import Puppeteer launch function without initialization | PASS (host) | whatsapp-web.js 1.34.8 and Puppeteer 25.17.0 import/construct under Node 22.23.3. No browser or WhatsApp connection exercised | Verify full pairing and own-message sync |
| Compose wiring? | `docker compose -f compose.yaml -f compose.web.yaml config --no-env-resolution --no-interpolate` | PASS (host) | Relay remains loopback-only; worker has no port; private profile volume separated from encrypted shared spool | Provision private env/public key and HTTPS before deployment |
| Android capture adapter and setup controls? | Full host unit suite, lint and debug APK; final UI assembly/lint | PASS (host) | 145 tests: 141 passed, 4 opt-in live tests skipped. Four new GatewayEvents tests cover group author/thread, outgoing direction, transport mismatch and OTP/envelope redaction. Lint: 0 errors, 46 warnings; no suppression added | Verify phone capture and background behavior |
| Relay and worker images? | Build both Dockerfiles; offline, network-disabled import/ASGI smoke | PASS (host) | Relay image `4903d7c497e2`; worker image `70cccf1ffad9`. Both run as UID 10001; final worker verifies Puppeteer 25.17.0 and constructs Client/LocalAuth. Pairing shell serves without exposing QR; unconfigured private endpoint rejects access | Browser startup, sandbox and account pairing remain NOT TESTED |
| Live pairing and browser sandbox? | Start provisioned worker, scan QR, check linked-device list | NOT TESTED | No owner account linked. The official Puppeteer image needs SYS_ADMIN for its enabled Chromium sandbox; no `--no-sandbox` bypass added | Verify on selected trusted Linux Docker host |
| Complete incoming/own/group/media coverage? | Controlled messages from phone and another account, disconnect/restart/replay | NOT TESTED | Software normalization and provenance pass host tests only. Attachments are metadata; history/edits/deletions/reactions and Web sending are outside this slice | Record actual gaps; never infer missing content |
| Android grants/background sync? | Connect mode web, Sync, screen-off and periodic maintenance | NOT TESTED | Existing manual/best-effort maintenance reused; no push guarantee | Verify phone envelope decryption, Room persistence, redaction and subsequent ACK |

Combined verification on `main` preserves the separately merged semantic-memory
update: 145 Android tests (141 passed, 4 opt-in live tests skipped), 0 lint errors /
46 warnings, debug APK assembled; 21 relay and 6 worker tests pass. The initial
combined attempt hit a GitHub timeout fetching the existing whisper.cpp source.
The already cached v1.9.5 archive was verified against the pinned SHA-256 and copied
into the ignored CMake download cache; the normal checksummed build then passed.
No build script, source pin, model or global package was changed for that recovery.

Worker spool retention: seven days / 10,000 events, 128 KiB per event and 16,000
body characters with explicit truncation. Queue/storage failures can lose new
events; status reports errors. The relay retains its seven-day inbox / 30-day
replay fingerprints. Session profile/QR status are sensitive, separately persisted
and excluded from Git/build context; browser history/credentials are not encrypted
by the event spool. Disconnecting the Android relay does not revoke the server's
linked session. See README for deployment, pairing and revocation steps.

## Preserved AI provider checkpoint — 2026-11-13

The earlier main-branch update added optional AI provider support. Its adapters,
settings, privacy/budget gates, UI and tests are preserved. The table records that
standalone checkpoint; the combined current suite is reported above. Local queries
and imported native model support have since been added in separate screens.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Existing local/capture workflows preserved? | Full host unit suite | PASS | All 58 previous tests still pass; original entry points preserved at that checkpoint; now extended with local source queries | Verify actual device behavior at final stage |
| Official provider wire contracts? | Mocked discovery and generation | PASS | Claude versioned Messages, OpenAI/xAI Responses, DeepSeek Chat Completions; headers, payloads, text extraction and usage checked | Test accounts with inference permissions later |
| Provider capability boundaries? | Request tools/images/streaming/structured output | PASS | Unsupported capabilities fail before transport; local test cannot claim reasoning, drafting, extraction or summarization | Add further capabilities only with explicit implementation |
| Routing and manual switching? | Preferred model, ordered/deduplicated fallback, per-task routes | PASS | Distinct authorization/quota/network/outage/capability failures advance in saved order; no implicit cloud fallback | Exercise selectors on device |
| Cancel avoids further inference? | Suspend mocked transport; cancel coroutine | PASS | Cancellation reaches transport and no fallback starts; uncertain reservation retained | Verify actual HTTPS disconnect/provider billing behavior later |
| Cloud privacy boundary? | Protected input categories, cloud-disable, redaction tests | PASS | Notification/SMS/finance/recording/media categories never call cloud; submitted security text/email/phone redacted | Review prose manually; redaction is not exhaustive |
| Credential/settings persistence? | Encrypted file round trip/reopen/delete/wrong key/corruption | PASS | Synthetic AES keys; authenticated ciphertext only; failure blocks cloud while basic local chat works | Verify Android Keystore and UI lifecycle on device |
| Cost cap/accounting? | Integer prices, reservation, settlement, missing usage, restart and month tests | PASS | Monthly owner-timezone counters survive router restart; absent prices/cap or exhausted estimate prevents cloud call | Configure accurate highest applicable prices and provider-side account limits |
| Prompt/secret-free debug metadata? | Audit/debug-string assertions; source review | PASS | Last 100 attempt metadata only; no prompt/answer/credential logging calls added | Keep actual credentials and personal input out of Git/test reports |
| Live Claude/Grok/DeepSeek/OpenAI? | Opt-in `AiLiveProviderTest` | NOT TESTED | Four tests skipped; no configured credential-based verification run | Explicit opt-in plus key/model required; tests can incur charges |
| Provider settings UI and actual device encryption? | Manual Android interaction | NOT TESTED | Compose compiles; no emulator or physical phone exercised | Save/delete keys, discover models, switch routes, rotate during responses, cancel, and test offline |
| Subscription/account sign-in? | Existing code and official authentication documentation | PARTIAL | No existing sign-in found; API keys only added; no participating-tool entitlement assumed | Add account auth only with provider-authorized third-party inference scope |

Host validation uses the same command from `android/`:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**Historical provider-only checkpoint: 91 tests passed, 0 failures/errors; 4 live
tests skipped. Lint: 0 errors, 31 warnings.** These are host results with mocked
network responses and synthetic encryption keys, not successful live API or device
tests. That standalone update changed no dependency versions. API references and opt-in live test setup are
in the existing README's **Optional AI providers** section.

The cap covers local estimated/reserved spend using manually supplied prices,
not exact provider invoices or spending by other clients. Errors, cancellations,
missing usage and process loss retain conservative reservations. No automatic
same-model retries or outward actions exist. Local fallback is only the original
basic offline test; imported LiteRT-LM inference now lives separately in Local AI. The system
voice session remains offline; provider/model selection lives in the activity.

For device verification: start with cloud disabled, confirm hello/habari/time,
then configure an API key and cap using non-sensitive text. Check unauthorized,
exhausted credit, airplane-mode fallback, model rejection, cap exhaustion, and
cancel. Verify response provider/model survives activity recreation. Saving keys
must not expose them in logs, saved-instance state or backup/transfer. Test model
listing separately from generation permissions. Do not use personal histories.

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
   playback from timestamps. Confirm new recordings are `audio/aac`, play, seek,
   transcribe and export as `.aac`; measure bytes per minute (expect ≈240 KB/min
   versus ≈1.9 MB/min PCM), battery, and an interrupted recording's playable prefix.
   Compare VAD spans in quiet rooms, fans/traffic and music. Import a speaker model,
   enroll from a test sample, and label a consented multi-person recording: record
   group purity, owner-match rate/false matches, name placeholders versus who was
   addressed, time and memory per minute. Confirm manual labels are never replaced. Import/replay accessible OPPO recordings
   and selected-folder changes. If files are private/inaccessible, record BLOCKED
   and use dialer sharing. Test rename, unknown/known original dates, video without
   audio, charging/thermal constraints, analysis cancellation/recovery, decrypted
   export and media deletion versus retained linked source snapshots.
8. **Assistant/memory:** compare deterministic finance answers against the ledger;
   inspect source links, query limits, facts versus inferences, owner corrections
   and index-only forgetting. Verify both typed and installed offline speech/TTS
   queries. Incoming instruction-like source text cannot execute tools. Typed
   proposals only retain Tasks/Messages review items; separate approval is required.
   Speak and type each owner command (`remind me to … tomorrow 15:00`, `nikumbushe …
   kesho`, `remember Asha: …`, `mark … done`, `snooze …`, `reschedule … to …`,
   `log cash … 3000 na … elfu saba`, `email … about …: …`, `whatsapp +255…: …`):
   confirm nothing changes before Confirm, reminders alarm at the stated EAT time,
   cash items appear only in Finance review, and drafts are never sent without the
   separate Messages approval.
   Import a MediaPipe text-embedder model (e.g. Universal Sentence Encoder), run
   Index now, and record indexed/pending counts, time per 100 sources, battery and
   peak memory. Compare literal versus `≈` meaning matches on synthetic paraphrases;
   confirm OTP/sensitive sources never appear. Import photos and check that
   `media_labels` sources are plausible and marked unverified.
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
