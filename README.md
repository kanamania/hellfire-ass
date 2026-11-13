# HellFire Ass

Native, local-first Android assistant **HellFire**, package `com.hellfire.ass`.
The [master specification](docs/MASTER_SPEC.md) remains the product source of truth.
Phase 0–1 capture and software slices for Phases 2–6 are implemented. Full phase
acceptance remains **PARTIAL** pending the device, model, account and real-format
gates in [DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).

The owner authorized the remaining phases on 2026-11-13 and kept physical device
testing until last. No phone, live account, personal corpus or model weights were
used to establish host results. No message has been sent and no gateway deployed.

## Build and verify

Required: JDK 17/21, Android SDK platform 36, Build Tools 35.0.0, NDK
**28.2.13676358**, CMake **3.22.1**, and platform-tools. Set `ANDROID_HOME`, or
`sdk.dir` in ignored `android/local.properties`. The first build downloads Gradle,
dependencies and the checksummed whisper.cpp source archive, never model weights.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, version **0.4.0**.
It supports **arm64-v8a and x86_64**, Android API 29+, target/compile API 36.
Confirm the phone ABI before installation; 32-bit-only devices are unsupported by
this build. This is a debug artifact, without private release signing.

Pinned stack: Gradle 8.13 (checksummed wrapper), AGP 8.13.2, Kotlin/Compose compiler
2.2.21, KSP 2.2.21-2.0.4, Compose BOM 2025.10.00, Room 2.8.4, coroutines 1.10.2,
WorkManager 2.10.5, LiteRT-LM 0.12.0, bundled ML Kit Latin OCR 16.0.1, SQLCipher
Android 4.20.0, JavaMail Android/activation 1.6.8, whisper.cpp v1.9.5. Native source
SHA-256: `ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720`.
The whisper MIT notice is bundled in app assets. LiteRT-LM is pinned to the runtime
that compiles with this Kotlin toolchain; a newer AAR required newer Kotlin metadata.
Model compatibility is established only by running the selected file on the phone.

The app remains one Gradle module with bounded Kotlin packages. Core capture,
ledger calculations, task review and bundled OCR work without account credentials.
Optional network adapters remain disconnected until configured by the owner.

## App controls

- **Setup / Capture:** actual assistant, notification, SMS and microphone grants;
  source counters, processing jobs and per-app processing policy. SMS capture starts
  disabled. It imports authorized incoming/sent provider rows in 500-row pages.
  No SEND_SMS permission or third-party call-audio capture is used.
- **Assistant:** `hello`, `habari`, `time`, `spending today`, `income last month`,
  `balances`, `tasks tomorrow`, `morning brief`, `evening brief`, `search <words>`.
  Date ranges use EAT; explicit `2026-11-01 to 2026-11-03` includes both days.
  Finance totals use integer ledger arithmetic; unapproved candidates and own
  transfers are excluded from spending. Source search examines up to 2,000 recent
  events, returns up to 20 literal matches, and offers source evidence links.
  It is not a complete semantic/embedding search or a general autonomous agent.
- **Personal memory:** owner notes, exact source excerpts and uncertain inferences
  remain distinct. Confirm corrections and inspect source IDs. Forgetting an index
  item does not delete its source; the source can still appear in capture search.
  Contact identities are not merged automatically. Typed JSON proposals accept only
  `create_task` and `prepare_reply`; they create review items, never approved sends
  or recording starts. Model text cannot run shell commands.
- **Finance:** add provider-scoped accounts; preview English/Kiswahili cash entries
  and historical dates. Review/edit amount, fee, account, kind, category and date
  before posting. Save confirmed Lipa Namba aliases. Own transfers balance debit and
  credit; fees are separate. Corrections append reversal entries and retain source
  evidence. Opening balances need a reviewed entry. Candidate parsers use synthetic
  fixtures; real provider-format accuracy and the 95% target remain unverified.
  Same-reference evidence links without double posting; unfamiliar/refund formats
  require review, including explicit reversal of an existing transaction when needed.
- **Messages:** encrypted IMAP/SMTP and gateway settings, inbox, local draft editing,
  optional local refinement, and explicit **Approve and send once**. Email replies
  retain a valid Message-ID parent in reply headers. Config changes need fresh
  approval. A claimed send never retries automatically; uncertain delivery remains
  UNKNOWN. SMTP acceptance, actual Sent-folder presence and WhatsApp delivery/read
  confirmations are distinct states. A visible, owner-started IMAP IDLE session is
  capped at ten minutes; durable polling remains available. No Gmail API is used.
- **Tasks:** source-linked review candidates do not schedule alarms. Confirm edited
  deadlines/party before activation. Alerts progress through 24h, 2h, due and
  repeated overdue reminders (owner-configurable 5–1440 minutes); choose Done, Snooze or Reschedule in the app. Exact
  alarms use Android special access or degrade to best effort. Restoration covers
  supported boot/update/time/permission paths. Nightly 21:00 EAT expense review stays
  pending until marked complete; the owner can turn it off.
- **Media:** import a selected call recording, voice note, image, PDF or video.
  Authorize a call-recordings folder for best-effort 15-minute audio scans: direct
  children only, first 300 entries per scan, stable content-hash deduplication.
  Inaccessible OEM storage requires manual sharing/export; access is never bypassed.
  Originals remain encrypted until deleted. Rename, supply a known original date,
  analyze, play from a transcript timestamp, export a decrypted original, or delete
  the media index/original. Linked source snapshots and finance/task evidence remain.
- **Conversation monitoring:** explicitly acknowledge notice and start from the
  visible app. Saved people/place exclusions check the owner-supplied session names
  and place; no automatic identity/location matching is claimed. Stop when the
  session becomes excluded. Monitoring is visible, capped at 30 minutes, retains
  the complete PCM original, and never restarts automatically. Experimental energy
  intervals include up to 30 seconds of preceding audio; they are not reliable
  diarization or proof of participation. Speaker labels start UNKNOWN and are
  manually correctable. The separate 60-second Phase 0 sample control remains.
- **Local AI:** import an owner-selected, licensed `.litertlm` model and choose
  CPU/GPU. No NPU support or specific-model benchmark is asserted. Import a
  multilingual whisper.cpp GGML model for English/Kiswahili transcription. Large
  inference requests are serialized and bounded; media analysis defaults to charging,
  waits for adequate battery, and pauses for severe thermal stress. Transcription
  uses two-minute batches, PDF OCR five-page batches, video OCR up to 20 sampled
  keyframes. Generated summaries/structured notes remain labeled inferences after
  owner review. Imported recordings without a known original date do not generate
  relative-date tasks automatically. OCR is not settlement verification.
- **Backup:** export a portable, passphrase-encrypted backup through Android’s
  document picker, optionally to an owner-configured self-hosted document provider.
  Registered originals, source events, finance, messages/drafts, tasks, memory and
  transcripts are included. Limits: 7 GiB originals plus 1 GiB database rows.
  Passwords/tokens, gateway private key, Android grants, models, recording exclusions,
  folder grants and Phase 0 samples are excluded. Restore requires the same backup
  schema, empty local data, stopped microphone, disabled notification/SMS capture
  and disconnected integrations. It authenticates/stages before merging, re-encrypts
  payloads/media with the receiving device key, revokes stale send approvals and
  preserves uncertain sends. Reconnect accounts and review exclusions afterward.
  Full native/device round-trip verification is still required before relying on it.

The system assistant session supports the same read-only local queries with optional
installed on-device speech input and offline TTS. Gestures, engine/language support,
lock-screen behavior and hotword support require physical verification.

## WhatsApp relay setup

`server/` contains the real single-owner FastAPI relay, not a deployed service.
Its host tests use mock Graph responses and generated synthetic keys/events.

```bash
cd server
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
docker compose config --no-env-resolution --no-interpolate
```

For an owner-authorized deployment, export the device public key in Messages and
place it at ignored `server/device-public.pem`. Create a private, ignored
`server/.env`; never put secrets in Git, commands, screenshots or logs.

| Environment variable | Owner-provided value |
|---|---|
| `META_APP_SECRET` | Meta app webhook signing secret |
| `WEBHOOK_VERIFY_TOKEN` | Random webhook verification secret |
| `DEVICE_TOKEN` | Random device bearer secret, at least 32 characters |
| `STORE_KEY_BASE64` | Base64-encoded 32 random bytes for server draft encryption |
| `META_WABA_ID` | Connected business account ID |
| `META_PHONE_NUMBER_ID` | Connected phone-number ID |
| `META_ACCESS_TOKEN` | Authorized WhatsApp Business access token |
| `GRAPH_API_VERSION` | Explicit supported version, such as the version selected in the owner's Meta app; no guessed default |
| `DEVICE_PUBLIC_KEY_FILE` | Set by Compose to `/app/device-public.pem` |
| `RELAY_DATABASE` | Optional; default `state/relay.db` in the persistent volume |

Compose binds only `127.0.0.1:8080`. A real HTTPS reverse proxy is required for Meta
and the Android client. After provisioning and explicit deployment authorization,
`docker compose up -d --build` is the service entry point. This development run did
not execute that deployment command. Register `/webhook`, verify signatures and
actual account eligibility/Coexistence onboarding with Meta; app configuration alone
cannot prove eligibility. Enter the matching HTTPS URL/device token in Android.

Endpoints: `GET /health`, `GET/POST /webhook`, `GET /device/events`,
`POST /device/events/{id}/ack`, `POST /drafts`, `POST /drafts/{id}/approve`, and
`POST /drafts/{id}/send`. Device endpoints require bearer auth; webhooks require
verification/signatures and match configured business/phone IDs. Rate limit is
120 requests/minute per peer IP. Access logs retain requests while redacting queries.
No raw webhook contents or credentials are logged.

Incoming events use RSA-OAEP-wrapped AES-GCM envelopes for the paired device,
SQLite durability, acknowledgment after Android persistence and deduplication across
replays. Pending envelopes expire after seven days, seen hashes after 30 days,
server drafts after 24 hours. One-use send approval expires after five minutes;
uncertain sends never auto-retry. Unsupported Coexistence/media envelopes remain
source metadata rather than fabricated message bodies. Arbitrary attachment download,
media sending and unverified Business account onboarding are not simulated.

## Optional AI providers

The existing official provider adapters and privacy/budget controls remain available
in **AI settings** and **AI chat**. They are separate from local source queries,
media/finance inference and the imported native model screen. System voice queries
stay local. Cloud use is disabled by default and requires owner configuration.

| Provider | Official API | Authentication |
|---|---|---|
| On-device | Existing deterministic hello / habari / time test | None; no LLM loaded |
| OpenAI | `https://api.openai.com/v1/responses` | API key |
| Anthropic Claude | `https://api.anthropic.com/v1/messages`, version `2026-10-21` | Workspace-scoped API key |
| xAI Grok | `https://api.x.ai/v1/responses` | API key |
| DeepSeek | `https://api.deepseek.com/chat/completions` | API key |

All adapters currently expose plain-text generation only. Streaming, tools, images,
and structured output explicitly fail as unsupported. Model listing supplies current
IDs; it does not prove that a model supports inference through the selected endpoint.
No model versions or price lists are embedded. Models rejected by the endpoint show a
request/model error and advance through the configured fallback list.

In **AI settings**:

1. Enable cloud use for explicitly submitted assistant text; all routes initially
   select the offline test. Configure a monthly estimated spending cap in USD.
2. Select a provider, enable it, enter its official API key, and **Save provider**.
   A blank key keeps the saved key; the delete checkbox removes it. Keys are masked,
   excluded from activity saved state, and encrypted using the existing AES-GCM /
   Android Keystore cipher in `noBackupFilesDir/ai-settings.enc`. Backup/transfer
   exclusions remain enabled. AI-provider calls go directly to the official API; the separate WhatsApp relay is not an AI proxy.
3. **Test saved connection and discover models** checks authenticated model listing.
   Select a discovered model (or enter an official model ID), supply input/output USD
   prices per million tokens, and save. Blank pricing blocks cloud generation. Use
   the highest applicable uncached, context, cache-write and tier prices from your
   provider's price sheet; prices are not fetched automatically.
4. Configure the ordered route separately for general chat, complex reasoning,
   reply drafting, task extraction and summarization. Add/remove entries and use
   **Move up** to set the preferred model and fallback order. Selecting a provider's
   default model makes it available to the route and assistant selectors; it does
   not replace the offline default route automatically.
5. In **AI chat**, choose a task and optionally switch model for that request.
   Manual selection replaces the preferred entry and keeps the saved fallback
   entries. The route is displayed before submission. Each successful response
   identifies its provider, actual model, estimate and audit ID. Generated text is
   a suggestion only; it cannot send messages or change captured records.

The router tries each distinct configured provider/model once, in order, after
network/outage, authorization, rate-limit, quota, model, capability or budget errors.
It does not repeat a failed model, add unconfigured cloud fallbacks, or retry sends /
record changes. Cancel stops transport/waiting and never initiates fallback; work
already accepted by a provider may still be billed. The existing offline test can
serve basic chat only. Other local tasks report unsupported capability honestly.

Cloud use is off by default. The only UI cloud input is text explicitly submitted in
the activity; the capture pipeline has no AI connection. Notification, SMS, finance,
recording and media input categories are restricted to local processing, regardless
of route or manual selection. Cloud text reuses the existing notification security
redactor and additionally masks email addresses and long numbers. Redaction is not
a universal detector: review prose before submitting. Full histories and attachments
are never loaded or uploaded. Provider retention policies still apply; `store:false`
is used for OpenAI/xAI response storage, not claimed as universal zero retention.

Monthly usage is persisted per provider/model in Africa/Dar_es_Salaam months. Requests
reserve a conservative UTF-8 input estimate plus framing and the maximum output
budget before transmission. Known token usage settles the estimate; missing usage,
errors and cancellations keep the reservation. These are local estimates using your
configured prices, not authoritative billing or an account-wide hard cap. Set API
account limits as well. Changing prices does not erase past charges/reservations.
The last 100 audit attempts retain ID/time/task/provider/model/result/token/cost
metadata, without prompts, answers or keys. Existing capture audits are untouched.
Unreadable AI settings block cloud use while the offline test remains available.

No browser cookies, scraped credentials, consumer-app impersonation or subscription
inference has been added. No subscription sign-in existed to preserve. Official
[OpenAI subscription documentation](https://help.openai.com/en/articles/6950777-what-is-chatgpt-plus)
distinguishes API billing from separately authorized participating-tool allowances;
this app has no participating-tool entitlement. Claude, Grok and DeepSeek also use
only their official API credentials here.

Mocked adapters/routing/encrypted-storage tests run with the normal unit suite.
Optional live tests are skipped unless **all three** are set: explicit
`HELLFIRE_RUN_LIVE_AI_TESTS=true`, the provider's `<PREFIX>_API_KEY`, and
`<PREFIX>_TEST_MODEL`. Prefixes are `OPENAI`, `ANTHROPIC`, `XAI`, `DEEPSEEK`.
Use an ignored local environment, never source files or committed credentials;
live generation tests can incur charges. Run the configured tests with:

```bash
cd android
./gradlew :app:testDebugUnitTest --tests 'com.hellfire.ass.AiLiveProviderTest' --rerun-tasks
```

API contracts checked against official documentation on 2026-11-13:
[OpenAI Responses](https://developers.openai.com/api/reference/resources/responses/methods/create),
[Claude authentication/versioning](https://platform.claude.com/docs/en/api/overview),
[Claude Messages](https://platform.claude.com/docs/en/api/messages/create),
[Claude errors/spend limits](https://platform.claude.com/docs/en/api/errors),
[xAI Responses](https://docs.x.ai/developers/rest-api-reference/inference/responses),
[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/),
[DeepSeek errors](https://api-docs.deepseek.com/quick_start/error_codes/).

## Storage, recovery and boundaries

Room schema **8** has non-destructive migrations from schema 1. Production uses
SQLCipher plus Android Keystore-wrapped database credentials; captured payloads
also use Keystore-backed AES-GCM. Legacy plaintext database conversion exports to
an encrypted copy, checks it and atomically replaces the original; errors do not
fall back to plaintext or delete the database. Native conversion must still be
verified on a device. Startup storage failure shows a retry screen and retains files.

Registered media uses independently authenticated 64 KiB chunks for seeking through
in-memory readers, including virtual WAV/PDF descriptors; no plaintext temporary
original is created. An interrupted monitor can recover authenticated complete
chunks; its unflushed tail can be lost. Phase 0 samples retain their legacy whole-file
GCM format and reject incomplete streams. Originals are retained until owner deletion;
automated retention deletion is not enabled. Portable backups use PBKDF2-HMAC-SHA256
(600,000 iterations), a random salt, authenticated chunks and a completion manifest;
staging databases/originals stay encrypted. OS backup remains excluded.

Capture/event-processing commits are atomic and immutable; reference FKs preserve
finance/task/media evidence. Per-app processing off continues capture-only retention.
The notification queue is bounded at 256; disconnection or loss before Room commit
can lose notifications. Active-notification reconnect snapshots cannot restore vanished
history. WorkManager persists jobs and schedules best-effort 15-minute maintenance;
Doze, force-stop and OEM rules can delay work. Nothing restarts microphone capture.

OTPs/security context are redacted before source persistence. Unmarked standalone
4–8 digit code candidates are removed conservatively; explicitly marked TZS amounts,
financial references and valid ISO dates are preserved when no security context is
present. This is not a universal secret detector. Android-protected/missing fields
remain unavailable. External sends require fresh owner approval. Cloud AI is restricted
to explicitly submitted AI chat input under the owner’s privacy/budget settings. There is no automatic capture/media upload, analytics,
stealth microphone, OS bypass, custom hotword engine,
automatic contact merging or unsupported SMS/call sending.

Remaining validation/research gates include actual Gemma 4 E2B/fallback performance,
real provider-template calibration, live Gmail/Meta eligibility and delivery, OEM
alarms/microphone/file visibility, encrypted database/backup device round trips,
compressed audio support, reliable VAD/diarization/owner-voice matching, semantic
embeddings and vision classification with suitable local models, and battery/thermal
measurements. Literal retrieval, OCR and manual speaker correction are implemented;
those are not evidence that the advanced model-dependent gates passed. Optional
Telegram and desktop dashboard have not been added.

## Verification and change log

Exact host results, artifact checksum and the final physical checklist are in
[DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md). Host tests use synthetic data,
ordinary SQLite/Robolectric and generated AES/RSA keys; they do not prove native
SQLCipher/Keystore, actual model execution, live accounts or Android/OEM behavior.

- 2026-11-13, Phase 0: skeleton, assistant/session, notification capture, encrypted
  prototypes and access/status screens; 34 host tests.
- 2026-11-13, Phase 1: durable processing/recovery, audit/policy, authorized SMS history
  and reconciliation; 58 host tests.
- 2026-11-13, Phases 2–6 software slices: finance/local AI/OCR, encrypted database,
  mail/relay/drafts, tasks/alarms, encrypted multimedia/transcription adapters,
  evidence queries/memory and portable backup/recovery. Device testing stays last.

References: [Android assistant](https://developer.android.com/reference/android/service/voice/VoiceInteractionService),
[exact alarms](https://developer.android.com/develop/background-work/services/alarms/schedule),
[foreground timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout),
[LiteRT-LM](https://developers.google.com/edge/litert-lm/overview),
[Gemma 4](https://developers.google.com/edge/litert-lm/models/gemma-4),
[bundled OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android),
[SQLCipher Android](https://github.com/sqlcipher/sqlcipher-android),
[JavaMail IMAP](https://javaee.github.io/javamail/docs/api/com/sun/mail/imap/IMAPFolder.html),
[Gmail app passwords](https://support.google.com/accounts/answer/185833),
[whisper.cpp](https://github.com/ggml-org/whisper.cpp/tree/v1.9.5),
[WhatsApp Business](https://developers.facebook.com/docs/whatsapp/).
