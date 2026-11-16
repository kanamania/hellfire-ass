# HellFire Ass — implementation and feasibility

Latest update: 2026-11-16 (project review checkpoint, directly below). Earlier
checkpoints keep the test counts and APK checksums that were true when they were
written; the review checkpoint has the current ones.

## Project review checkpoint — 2026-11-16

Full read of the Android app, relay, WhatsApp Web worker, tests and docs at `main`
`566a291`, plus a re-run of the host suites. No code was changed. No phone, model
file, live account or real message corpus was used; every "code review" result below
is established by reading the source, not by reproducing it on a device.

Host verification on this tree:

| Check | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'` from `android/` | PASS: 205 tests, 201 passed, 4 opt-in live AI tests skipped, 0 failures or errors. Gradle reported the task up to date, so these are the results of the run made on this tree earlier the same day |
| Relay | `python -m pytest -q` in a fresh Python 3.14.8 virtualenv from `requirements-dev.txt` | PASS: 21 passed, one Starlette/httpx deprecation warning |
| WhatsApp Web worker | `npm ci --omit=optional --ignore-scripts` then `npm test`, Node 24.13.0 | PASS: 6 passed |
| Room migrations | `DatabaseMigrationTest`, `SchemaV10Test`, `WidgetDataTest`, `FinanceTest` | PASS (host): schema 1 migrates through schema 12 |
| Android lint | `./gradlew :app:lintDebug --max-workers=1` with the same heap limit | PASS: 0 errors, 244 warnings, 3 hints (mostly hard-coded English text, unused attributes, KTX suggestions and dependency update notices). This closes the lint run left NOT COMPLETED in the Money entry update below |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` | PASS: `./gradlew :app:assembleDebug` reports it up to date. 249,964,215 bytes, SHA-256 `2c93f66ebe77685d113924f3900fbdd63ecd0c6fc1a890eccf10f1c8c01a7778`, version 0.4.0 (code 4). Not installed on any device |

Findings, in the order they should be handled. Result is the state of the code today.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Is a compressed conversation recording recovered after the process dies mid-recording? | Code review | **DEFECT** | `MicrophoneService` writes monitoring audio with the container's PCM flag off (AAC). `ChunkedMedia.recoverRecording` returns null unless that flag is on, so `MediaVault.recover` skips the file: up to 30 minutes of audio is not registered and the encrypted file stays on disk. `MediaTest` covers recovery for the PCM container only | Accept the AAC container in recovery, add a test, and remove or register orphan `.hfm` files |
| Does each new SMS trigger a bounded import? | Code review | **DEFECT (cost)** | Every trigger (incoming SMS, provider change, app resume, the 15-minute maintenance job) starts `SmsSyncWorker` at row 0 and pages through the whole SMS store, normalizing and hashing each row before the duplicate check. No high-water mark is saved | Save the last imported row ID and rescan only a short look-back window (for outbox to sent changes); measure on the OPPO's real SMS count |
| Is a captured event's identity stable if the redaction rules change? | Code review | **RISK** | The event ID and external ID are hashes of the *redacted* fields. Any later change to `NotificationNormalizer` gives already-imported SMS and mail new IDs, so the next sync re-imports them as new events and creates new review items unless a provider reference matches | Calibrate redaction on a real corpus **before** the first real history import, or derive SMS/mail identity from the provider row ID / IMAP UID instead of content |
| Does redaction keep the money facts in real provider messages? | Code review | **RISK, NOT TESTED on real formats** | Any standalone 4–8 digit number is replaced unless it follows `TZS`/`TSh`, sits in an ISO date, or follows a reference keyword in a message that also has a marked amount. Amounts written without a leading currency mark (`5000`, `120000 TZS`), the year in `13/11/2026`, and till numbers in messages without a marked amount are removed before storage, permanently | Run an owner-approved real corpus through the normalizer and record what is lost per provider |
| Does one payment seen as both an SMS and the SMS app's notification create one review item? | Code review | **RISK** | `FinanceRepository.collect` only detects a duplicate when both sources yield the same parsed reference. The reference pattern needs a `ref`/`kumbukumbu`/`muamala` keyword; a message that leads with a bare transaction ID yields no reference, so both sources go to review and both can be posted | Verify reference parsing per provider on a real corpus; add a fallback match (same provider, amount and time window) that links instead of posting twice |
| Are review queues usable on real traffic? | Code review | **RISK** | `RuleClassifier` labels any text containing `paid`, `payment` or `received` as a finance candidate. `TaskExtractor` proposes a task for any text containing today/tomorrow/a weekday/an ISO date (or `leo`/`kesho`), with no commitment wording required; only one Swahili clock phrase (`saa nne asubuhi`) is understood | Measure candidate precision on real traffic before trusting the queues; tighten rules from the measured errors |
| Does the relay drain a backlog larger than one batch? | Code review | **DEFECT** | The relay limits every caller to 120 requests per minute, counted per peer address and including the phone's own authenticated calls. One sync is 1 fetch plus up to 100 acknowledgments, so the follow-up batch is rejected with 429 and Android records the gateway as FAILED until a retry. Behind the required reverse proxy all callers share one address, so unauthenticated traffic can use up the same allowance | Exempt authenticated device calls or acknowledge a batch in one request; key the limit on the proxy-supplied client address |
| Does the WhatsApp Web worker recover from a disconnect? | Code review | **GAP (documented)** | On `disconnected` the worker only updates status and keeps running, so Compose `restart: unless-stopped` never restarts it | Exit non-zero on disconnect so Compose restarts it with the saved session |
| Does the first mail sync start with recent mail? | Code review | **RISK** | With no cursor the adapter asks for every UID in INBOX and Sent and imports oldest first, 100 per run. A large mailbox takes many runs and recent mail arrives last | Start from a recent window and backfill on request |
| Do the cloud AI adapters accept normal answers? | Code review | **DEFECT (likely), NOT TESTED live** | Every request asks for at most 512 output tokens, and an answer that ends because of that limit is treated as a failed request: the estimated cost stays reserved and the next provider in the route is tried. The Claude adapter also rejects any response that contains a non-text content block, which current Claude models return when thinking is on by default | Accept truncated answers and mark them truncated, raise the limit, skip thinking blocks; then run the opt-in live tests once per provider |
| Is long-term literal search available? | Code review | **GAP vs spec** | Search decrypts and scans only the 2,000 most recent events (a few days once all notifications are captured). The spec asks for SQLite FTS | Add an FTS table inside the already-encrypted database |
| Are newly recorded calls found by the folder scan? | Code review | **RISK** | Each scan reads only the first 300 folder entries in the order the storage provider returns them. A call-recording folder that grows past 300 files may never show its newest files | Sort by last-modified or page through the folder |
| Do daily Drive backups stay within the account's storage? | Code review | **RISK** | Every daily upload is a complete archive including all originals (up to 7 GiB). Nothing prunes old archives | Keep the newest N archives the app created, or back up originals less often |
| Can device problems be diagnosed? | Code review | **GAP** | The app writes no log lines at all; about 95 `catch` blocks turn the exception into a generic status string | Add debug-build-only logging of exception class and stack trace, without message content, before the OPPO test pass |
| Can another app steer the UI? | Code review | **LOW** | `MainActivity` is exported for the launcher and assist intents and accepts any `hellfire://` data URI from any caller. Links only navigate, and the app lock still applies | Accept deep links only from this app's own pending intents |

Repository and build state on 2026-11-16:

- `main` and `origin/main` are both at `566a291`. The merged `feat/ui-redesign`
  worktree and branch have been removed; no feature branches remain.
- Only a debug build type exists. There is no release signing configuration, code
  shrinking or CI. The debug APK is about 238 MiB because it carries arm64-v8a and
  x86_64 native libraries; a phone-only arm64 build would be much smaller.
- `AGENTS.md` still tells agents to implement Phase 0 only and to use official
  WhatsApp Coexistence; `docs/MASTER_SPEC.md` records the owner's later decisions that
  supersede both. An agent that reads only `AGENTS.md` gets the old rules.

## Earlier checkpoints

Evidence date: 2026-11-14, Africa/Dar_es_Salaam. The owner authorized remaining
phases and explicitly kept physical OPPO testing until last. Resource-use validation
used host tests only; the separate UI checkpoint below records earlier emulator
evidence. No live account, personal message corpus, recording or model weights were
used for the resource-use changes.
The existing optional cloud providers/settings were merged from main; their live
tests remain opt-in and unconfigured.
No device-dependent phase is fully accepted solely because its host checks pass.

## Fingerprint and Google Drive checkpoint — 2026-11-14

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Fingerprint login | Enroll fingerprint and device PIN; enable app lock; cancel/wrong/correct fingerprint; use PIN fallback; background/recreate/restart; disable lock after authentication | NOT TESTED (physical) | Platform `BiometricPrompt`; API 30+ strong biometric or device credential, API 29 platform credential fallback; no fingerprint data stored. Only `emulator-5554` was listed by read-only `adb devices -l`; it was not used for authentication testing | Run on the physical OPPO; verify ColorOS sensor/lockout/PIN behavior and revoking enrollment |
| Private UI and assistant access | Open task deep link, assistant role entry, recents, account/document picker; return locked; unlock and consume pending result | NOT TESTED (device) | Main activity gates private Compose content and uses secure-window flag. Assistant session redirects to app when lock is enabled. Saveable UI holder preserves result-registration keys; manual archive passphrase survives only in an in-memory ViewModel | Verify no private content/dialog/voice response is exposed while locked; verify picker return, rotation, screen-off and process death |
| Daily encrypted Google Drive backup | Register Cloud Android OAuth client/certificate, enable Drive API, connect Google account, enable schedule, inspect Drive file and reported success | NOT TESTED (live Google/device) | `drive.file` authorization, existing passphrase archive, unique 24-hour WorkManager job, unmetered/battery/storage constraints, streamed upload, server size/checksum confirmation | Configure actual Cloud project and signing certificates; test first/manual/overnight upload, offline/reboot/Doze/quota/consent revocation; no live Google account used |
| Restore on replacement installation | Connect original account, list/select backup, enter original passphrase, confirm restore into empty database | NOT TESTED (native/live Google/device) | Bounded ciphertext download, existing authenticated archive/schema/table/media validation and empty-database guard; device-key re-encryption, stale send approvals cleared | Perform full SQLCipher/Keystore/media round trip, wrong-password/corrupt-download/insufficient-space tests, regrant access afterward |
| Large archive scheduling | Upload representative original-media archive on actual Wi-Fi | NOT TESTED | Existing 7 GiB originals/1 GiB table limits; 9 GiB transfer ceiling. Normal WorkManager execution window applies; upload sessions are not persisted across process death | Measure timing/disk use; use manual archive upload when transfer cannot fit the worker window |

Host verification for the feature branch (2026-11-14): **PASS** — APK assembly,
lint (**0 errors, 244 warnings**), and **196 tests: 192 passed, four opt-in live AI
tests skipped, zero failures/errors**. All eight `DriveBackupTest` cases passed:
confirmed upload/checksum, token-safe upload destination, checksum mismatch,
app-specific listing/ID validation, short/oversized/cancelled download cleanup,
expired authorization, background/recreated lock session and fail-closed preferences.
Command from `android/`: `./gradlew :app:assembleDebug :app:lintDebug
:app:testDebugUnitTest --console=plain --max-workers=1
'-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8'`.
The native build reused the existing pinned whisper 1.9.5 archive after a repeat
GitHub download stalled; its SHA-256 matched the source's configured hash. No
checksum, lint gate or dependency version was bypassed.

Integrated-main verification (2026-11-14): `./gradlew :app:assembleDebug
:app:testDebugUnitTest --console=plain --max-workers=1
'-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8'` — **PASS**.
Includes the independently merged resource-use changes: **201 tests,
197 passed, 4 opt-in live AI tests skipped, zero failures/errors**.
Current APK: `android/app/build/outputs/apk/debug/app-debug.apk`
(249,353,969 bytes), SHA-256 `b161484f6a3c1155201d52d353d0aa378bf505339292a2cb49d8c61b0bef05b4`.
Feature lint and synthetic test reports are retained under
`android/app/build/reports/device-auth-backup/`; merged-main test reports use
the normal `test-results/testDebugUnitTest/` and `reports/tests/` paths.

No physical fingerprint, live Google authorization, cloud upload or native restore
success is inferred from host tests. Existing OS backup exclusions remain enabled;
Google Drive receives only the owner-approved encrypted portable archive.

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

Final result: **193 tests (merged redesign and resource-use regressions): 189 passed,
4 opt-in live AI tests skipped; 0 failures/errors. Lint: 0 errors, 240 warnings and
3 hints. APK assembly passed.** Native whisper JNI compiled for
arm64-v8a and x86_64. Robolectric uses API 34 and ordinary SQLite/generated AES keys;
it does not execute phone Keystore, native SQLCipher, MediaCodec, ML Kit or models.
Warnings remain enabled, including English widget resources, unused attributes,
KTX suggestions, content descriptions and dependency/update notices.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, version **0.4.0**, code 4,
package `com.hellfire.ass`, min/target API 29/36, arm64-v8a and x86_64 only.
SHA-256: `0183db716798b22363128de5bf8f3d30d052f67f8ab7b96348ffcc41b2154e84` (248,694,596 bytes, ≈237 MiB; uncompressed 16 KiB-aligned native libraries — ONNX Runtime adds ≈75 MB across arm64-v8a and x86_64).
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

## Resource-use checkpoint — 2026-11-14

Processing coalesces repeated requests into one queued drain. If the current drain
is already running, one successor covers events committed after its final queue
read. Duplicate-only notification/SMS batches no longer request processing. Capture,
finance/task rules and alarms do not wait for a charging constraint.

Automatic semantic indexing has a separate, unique 15-minute periodic worker,
requiring charging and adequate battery. It is registered on model import/startup
and cancelled on model removal. New meaning matches can lag until charging and an
OS-scheduled run; literal search still reads captured sources. Manual **Index now**
works on battery. Empty backlogs skip native model creation; batch limits are exact,
thermal status is checked between batches, and embedding runtimes share the existing
speech/assistant model lock. No source/audio retention or permission is changed.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Work queue bounded under bursts and running/failed drains? | Real WorkManager persistence with worker execution disabled; concurrent requests and explicit work states | PASS (host) | 100 concurrent triggers leave one queued drain; 50 triggers during RUNNING leave one BLOCKED successor; FAILED work allows a new drain | Verify actual process/OS scheduling on device |
| Automatic indexing power policy and idle behavior? | Persisted worker constraints; invalid synthetic model with empty Room backlog | PASS (host) | 20 registrations retain one periodic job with charging/battery constraints; empty backlog and zero budget return without loading the invalid model; negative budget rejected. All 5 `ResourceUseTest` checks pass | Verify actual embedding throughput, batch limits, cancellation and model serialization on device |
| Lower battery, CPU and peak RAM on the OPPO? | Compare identical controlled workloads before/after | NOT TESTED | No physical profiling performed | Record baseline and optimized overnight battery, CPU time, PSS/peak memory and index latency; verify unplug/replug, severe thermal status and capture during indexing |

Scheduling references: [unique work and chaining](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work),
[work constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

Final combined verification (after incorporating the separately merged UI redesign):

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' --console=plain
```

PASS: 193 tests (189 passed, 4 opt-in live tests skipped), lint 0 errors / 240
warnings / 3 hints, debug APK assembled, and `zipalign -c -P 16 4` passed. The run
used one worker and a command-line heap limit after concurrent workstation builds
caused memory pressure; no project/global build settings or checks were changed.
The earlier 165-test pre-redesign suite also passed. No device profiling or model
inference was performed. Source capture remains independent of automatic meaning
indexing; battery and peak-memory improvements are still NOT TESTED on the OPPO.

## Capture quality checkpoint — 2026-11-13

Review fixes for silent loss and duplication between capture and review. Synthetic
fixtures only; real provider formats remain uncalibrated.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Do everyday "code/PIN/token" words keep finance and mail content while codes are removed? | `NotificationNormalizerTest` | PASS (host) | "Usitoe PIN yako" payment, LUKU token purchase and "code review" mail keep content; keyed numeric/alphanumeric codes removed in every field, including a PIN repeated as a reference; OTP/password/verification/msimbo still withhold the whole item | Check against real M-Pesa/Airtel/Mixx/bank messages on device |
| Do removal records, promotions and old history stay out of review queues? | `CaptureProcessingTest`, `TaskLogicTest` | PASS (host) | REMOVED events labelled `REMOVAL_RECORD` without finance/task collection; promotion/system labels skip task extraction; deadlines over seven days old are not review items | Observe queue volume with real notification/SMS/mail backfill |
| Does a repeated provider reference avoid a second review item? | `FinanceTest` | PASS (host) | Second source stored as `DUPLICATE`, linked as ledger evidence on approval; one entry posted. Candidates without a parsed reference still need manual review | Calibrate reference parsing on a real corpus |
| Are relative day words parsed as whole words? | `TaskLogicTest`, `AssistantCommandsTest` | PASS (host) | "call Leonard on monday" → Monday; keshokutwa/kesho kutwa/day after tomorrow → +2 days | — |
| Full host suite and lint? | `./gradlew :app:testDebugUnitTest :app:lintDebug` | PASS (host) | Branch: 152 tests, 148 passed, 4 opt-in live AI tests skipped. Merged main with advanced audio: 155 tests, 151 passed, 4 skipped; lint 0 errors, 46 warnings | — |

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

## UI redesign checkpoint — 2026-11-14

Merged branch `feat/ui-redesign`. Redesign per the UI handoff: new theme, five-tab navigation, redesigned tabs, settings,
reports, Room v10/v11 and Glance home screen widgets. Evidence below is host tests and the Android emulator only
(Pixel 6 profile, API 34). **No physical OPPO/ColorOS device was connected; nothing here is a device result.**

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Does the app build and pass the host suite? | `./gradlew assembleDebug :app:testDebugUnitTest :app:lintDebug` | PASS (host) | 188 tests, 184 passed, 4 opt-in live AI tests skipped, 0 failures; lint 0 errors (warnings only) | — |
| Do Room migrations 9→10→11 keep data with no destructive fallback? | `SchemaV10Test`, `WidgetDataTest`, `DatabaseMigrationTest`; emulator upgrade over existing encrypted v9 data | PASS (host + emulator) | Rows kept; completion times backfilled from owner done-audits; promise direction backfilled for message-extracted tasks; SQLCipher DB opened and migrated in place on the emulator | Install over the owner's real database on the OPPO before relying on it |
| Do older encrypted backups still restore? | `SchemaV10Test`, `WidgetDataTest` | PASS (host) | Schema 8 and 10 rows restore into v11 with defaults; unknown or missing core columns still refused | Run a real export → restore on a spare install |
| Do confirm-before-act rules still hold? | Emulator walkthrough | PASS (emulator) | SMS candidates post only after the review sheet's Confirm; drafts send only after "Approve and send" confirmation; task proposals need Add task; balances need Confirm balance; widgets only open the app (promise checkbox marks done, as specified) | — |
| Do reports compute from confirmed entries only, and export? | `ReportDataTest`, `BalanceAndCalendarTest`, emulator | PASS (host + emulator) | Totals, groups, account reconciliation, running balance, calendar totals; PDF exported through the file picker and read back | Compare report totals with real statements |
| Do home screen widgets render, refresh and protect privacy? | Emulator (Pixel launcher) | PARTIAL (emulator) | 13 widgets listed with previews; pinned and rendered; amounts masked by default; a new SMS refreshed Wallets and Nightly review without a timer; widget record → consent → visible notification → stop → "Saved, transcript queued" | ColorOS launcher, resize to large, lock-screen/after-reboot behaviour |
| Widget recording on OPPO/ColorOS | — | NOT TESTED | No device | See REDESIGN_SUMMARY.md checklist |
| Assistant gesture opens Ask | — | NOT TESTED | Intent routing implemented (`ACTION_ASSIST` → Ask) | Test the ColorOS gesture and power-button assistant |
| Background capture overnight with the new UI | — | NOT TESTED | Unchanged services; Setup row shows battery exemption and ColorOS caveat | Overnight test with battery optimisation off and auto-launch on |

## Money entry update — 2026-11-14

Cash entry now has date/time pickers in East Africa Time, a category selector and an
account selector. Each review candidate retains those choices across reloads. Account
creation no longer asks for a provider; Money cards, sheets and report grouping no
longer display it. Any saved account can be chosen when confirming a payment.
Receipt identifiers remain internal to preserve duplicate detection and merchant evidence.
Room v12 adds nullable account/category selections without discarding existing data;
backups retain these selections and schemas 8, 10 and 11 remain readable.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Do selections, migration and ledger approval work? | Host unit tests/build | PASS (host) | 192 tests: 188 passed, 4 live-AI tests skipped; no failures/errors. Debug APK built. Migration v11→v12, selected account/category/EAT timestamp, approval and old/new backup rows covered | Verify real phone interactions |
| Do picker controls behave on ColorOS? | Select and cancel date/time; change category/account; rotate, reopen review, confirm multi-item cash entry; add an account without a provider | NOT TESTED | No phone test performed for this change | Verify timezone, retained selections and balances on the OPPO |

Command: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=1 --console=plain`
from `android/`. Test and assemble tasks passed. Lint was **NOT COMPLETED** at this
checkpoint: its analysis was stopped after prolonged runtime under host memory pressure
(about 600 MiB RAM available, 35 GiB swap in use). It was rerun on 2026-11-16 and
passed with 0 errors; see the project review checkpoint at the top.
APK SHA-256:
`dc852ff89217f8ef380e84c736e87338e42954c5dd583950f99b4aa8d40c2c43`.

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
