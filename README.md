# HellFire Ass

Native, local-first Android assistant **HellFire**, package `com.hellfire.ass`.
The [master specification](docs/MASTER_SPEC.md) remains the product source of truth.
Phase 0–1 capture and software slices for Phases 2–6 are implemented. Full phase
acceptance remains **PARTIAL** pending the device, model, account and real-format
gates in [DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).

The owner authorized the remaining phases on 2026-11-13 and kept physical device
testing until last. No phone, live account, personal corpus or model weights were
used to establish host results. No message has been sent and no gateway deployed.

## Current status — 2026-11-16

- **Code:** `main` holds every merged slice: Phases 0–6 software, the UI redesign
  (five tabs, reports, 13 home screen widgets), money entry pickers, fingerprint app
  lock, daily encrypted Google Drive backup, the resource-use changes, the fixes for
  the 2026-11-16 review findings, **multiple email inboxes and WhatsApp accounts**, and
  **Gemma 4 E2B downloaded on first launch with on-device update checks**.
  Debug version **0.6.0** (code 6), Room schema **13**, backup schema **13**.
- **Web access:** an optional read-only web copy (NestJS API, Postgres, Next.js site
  in `web/`) synced from the phone; see [Web access](#web-access-read-only-mirror).
  Not deployed anywhere and not yet run against a real phone.
- **Host checks on this tree:** 232 Android unit tests (228 passed, 4 opt-in live AI
  tests skipped, 0 failures), 23 relay tests, 6 WhatsApp Web worker tests and 11 web
  API tests pass.
  Lint passes with 0 errors and the debug APK assembles; the checksum is in
  [DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).
- **Not done:** nothing has run on the physical OPPO, with a real model file, a live
  mail/WhatsApp/Google account or a real SMS corpus. Every phase stays **PARTIAL**.
- **Review findings:** all fixed in code and covered by host tests; each still needs
  its device or real-data check. DEVICE_FEASIBILITY.md → "Review fixes and multiple
  accounts" lists what changed and what remains to verify.
- **Not added:** a release build type (signing, shrinking) and CI.

## Build and verify

Required: JDK 17/21, Android SDK platform 36, Build Tools 35.0.0, NDK
**28.2.13676358**, CMake **3.22.1**, and platform-tools. Set `ANDROID_HOME`, or
`sdk.dir` in ignored `android/local.properties`. The first build downloads Gradle,
dependencies and the checksummed whisper.cpp source archive, never model weights.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, version **0.6.0**.
It is built for **arm64-v8a only** (since 2026-11-16), Android API 29+, target/compile
API 36. Confirm the phone ABI before installation (`adb shell getprop ro.product.cpu.abilist`);
32-bit-only phones and x86_64 PC emulators cannot run it. Size: about 149 MB
(142 MiB): native libraries ≈94 MB (ONNX Runtime 34 MB, LiteRT-LM 15 MB, ML Kit OCR
22 MB, MediaPipe 8 MB, whisper 5 MB), unshrunk debug code ≈50 MB. Native libraries
stay uncompressed in the APK and are not extracted, so installed size is close to the
APK size plus Android's compiled code and app data. This is a debug artifact, without private release signing.

Pinned stack: Gradle 8.13 (checksummed wrapper), AGP 8.13.2, Kotlin/Compose compiler
2.2.21, KSP 2.2.21-2.0.4, Compose BOM 2025.10.00, Room 2.8.4, coroutines 1.10.2,
WorkManager 2.10.5, LiteRT-LM 0.12.0, bundled ML Kit Latin OCR 16.0.1, SQLCipher
Android 4.20.0, JavaMail Android/activation 1.6.8, Glance 1.1.1, Play services auth
22.0.0, ML Kit image labeling 17.0.9, MediaPipe tasks-text 0.10.26, ONNX Runtime
1.31.1, whisper.cpp v1.9.5. Native source
SHA-256: `ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720`.
The whisper MIT notice is bundled in app assets. LiteRT-LM is pinned to the runtime
that compiles with this Kotlin toolchain; a newer AAR required newer Kotlin metadata.
Model compatibility is established only by running the selected file on the phone.

The app remains one Gradle module with bounded Kotlin packages. Core capture,
ledger calculations, task review and bundled OCR work without account credentials.
Optional network adapters remain disconnected until configured by the owner.

## App controls

Since the 2026-11-14 redesign the app has five tabs (Home, Money, Tasks, Inbox, Ask)
plus Settings. The headings below are feature areas, not tab names: capture controls
are in Settings › Permissions and capture; Finance is the Money tab; Messages is the
Inbox tab with account setup in Settings › Email / WhatsApp; Media, Local AI and
Backup are in Settings › Recordings, On-device models and Encrypted backup; the
assistant is the Ask tab. [DECISIONS.md](DECISIONS.md) records where each control moved.

- **Setup / Capture:** actual assistant, notification, SMS and microphone grants;
  source counters, processing jobs and per-app processing policy. SMS capture starts
  disabled. It imports authorized incoming/sent provider rows in 500-row pages.
  No SEND_SMS permission or third-party call-audio capture is used.
- **Assistant:** `hello`, `habari`, `time`, `spending today`, `income last month`,
  `balances`, `tasks tomorrow`, `morning brief`, `evening brief`, `search <words>`.
  Date ranges use EAT; explicit `2026-11-01 to 2026-11-03` includes both days.
  Finance totals use integer ledger arithmetic; unapproved candidates and own
  transfers are excluded from spending. Source search first uses a full-text word index of
  every non-sensitive saved source (inside the encrypted database, rebuilt after a
  restore), then an exact-phrase scan of the 2,000 most recent events; it returns up
  to 20 matches with source evidence links.
  With an owner-imported MediaPipe text-embedder `.tflite` (Local AI → Meaning search),
  search also adds up to 10 meaning matches (`≈ score`) from an on-device vector index of
  non-sensitive sources. Scores are uncalibrated; it is not a general autonomous agent.
  Automatic meaning indexing runs in batches of at most 500 sources on a best-effort
  15-minute schedule while charging with adequate battery. New sources stay available
  to literal search immediately; meaning matches wait for indexing. **Index now**
  remains available for a manual run on battery. Embedding, speech and assistant
  models share the existing inference lock and release their runtime after use.
- **Image labels:** imported images get bundled on-device ML Kit labels (≥70% confidence)
  saved as a searchable `media_labels` source beside OCR text. Labels are unverified.
- **Assistant commands:** `remind me to <task> <when>` / `nikumbushe …`,
  `remember <subject>: <note>`, `mark <task> done`, `snooze <task>`,
  `reschedule <task> to <when>`, `log cash <item> <amount> na …`,
  `email <address> about <subject>: <body>`, `whatsapp <number>: <body>`.
  Each returns a proposal; nothing changes until you tap Confirm. Reminders become
  active tasks, cash items go to Finance review, drafts still need Messages approval
  to send. `help` lists them.
- **Personal memory:** owner notes, exact source excerpts and uncertain inferences
  remain distinct. Confirm corrections and inspect source IDs. Forgetting an index
  item does not delete its source; the source can still appear in capture search.
  Contact identities are not merged automatically. Typed JSON proposals accept only
  `create_task` and `prepare_reply`; they create review items, never approved sends
  or recording starts. Model text cannot run shell commands.
- **Finance:** add accounts by name with an optional balance (the provider field was
  removed on 2026-11-14); preview English/Kiswahili cash entries
  and historical dates. Review/edit amount, fee, account, kind, category and date
  before posting. Save confirmed Lipa Namba aliases. Own transfers balance debit and
  credit; fees are separate. Corrections append reversal entries and retain source
  evidence. Opening balances need a reviewed entry. Candidate parsers use synthetic
  fixtures; real provider-format accuracy and the 95% target remain unverified.
  Same-reference evidence links without double posting; unfamiliar/refund formats
  require review, including explicit reversal of an existing transaction when needed.
- **Multiple accounts:** Settings › Email and Settings › WhatsApp each hold up to ten
  accounts. Each inbox and each WhatsApp relay syncs on its own and shows its own
  connection state; one failing account does not stop the others. Inbox messages name
  their account when more than one exists. A reply is sent from the account the
  message arrived on; a new message or an edited draft can pick the sending account,
  and the approval dialog names it. Live mail (IMAP IDLE) opens one bounded connection
  per inbox. A new inbox imports its 500 most recent messages per folder; older mail
  stays on the server. Assistant `email …`/`whatsapp …` commands draft from the first
  account and say which one. Settings saved by earlier versions become the first account.
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
  session becomes excluded. New conversation recordings are stored as encrypted
  AAC-LC (ADTS, 32 kbit/s mono, about 8× smaller than the earlier PCM); existing PCM
  recordings still play and export as WAV. Speech intervals use an adaptive noise
  floor (DSP heuristic, not a learned model).
- **Speaker separation (experimental):** import an ONNX speaker-embedding model with
  80-bin filterbank input (WeSpeaker/3D-Speaker style; runs on ONNX Runtime 1.31.1) and
  enroll your voice from the latest 60-second test sample. "Label speakers automatically"
  groups transcribed segments by voice (`AUTO SPEAKER n`), marks a group matching your
  enrolled voice `AUTO OWNER? 0.72`, and adds names heard in the conversation as
  placeholders (`AUTO SPEAKER 2 · Juma?`) when a self-introduction or repeated direct
  address supports them. Manual labels are never replaced; thresholds are uncalibrated.
  Monitoring is visible, capped at 30 minutes, retains
  the complete compressed original, and never restarts automatically. Experimental energy
  intervals include up to 30 seconds of preceding audio; they are not reliable
  diarization or proof of participation. Speaker labels start UNKNOWN and are
  manually correctable. The separate 60-second Phase 0 sample control remains.
- **Gemma 4 E2B on first launch:** the app downloads Google's official
  `gemma-4-E2B-it.litertlm` (2.59 GB, Apache-2.0) from the `litert-community`
  repository on Hugging Face, pinned to a known commit and SHA-256. It waits for
  Wi-Fi, battery and storage, shows a progress notification, resumes after
  interruptions, then test-loads the file before using it; the APK stays small. A
  daily Wi-Fi check compares the published file with the installed one and shows
  **Update available** in Settings › On-device models; the update downloads only when
  you tap **Update**, and the current model stays in use until the new one is verified
  and loads. Removing the model stops automatic downloads. An owner-imported model is
  never replaced by an update. These are the app's only Hugging Face requests; they
  carry no personal data.
- **Local AI:** optionally use your own licensed `.litertlm` model instead, and choose
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
  folder grants and Phase 0 samples are excluded. Restore accepts backup schemas 8,
  10, 11, 12 and 13 and requires empty local data, stopped microphone, disabled notification/SMS capture
  and disconnected integrations. It authenticates/stages before merging, re-encrypts
  payloads/media with the receiving device key, revokes stale send approvals and
  preserves uncertain sends. Reconnect accounts and review exclusions afterward.
  Full native/device round-trip verification is still required before relying on it.

The system assistant session supports the same local queries and confirm-first commands with optional
installed on-device speech input and offline TTS. Gestures, engine/language support,
lock-screen behavior and hotword support require physical verification.

## WhatsApp relay setup

The owner-selected connection is now **whatsapp-web.js capture**. The original
official Meta transport remains available separately below. Web mode captures new
message events exposed by the linked session: incoming/outgoing text, group thread
and author IDs, and media metadata. It does **not** download attachments, import a
complete chat archive, transcribe voice notes or send replies. Notifications remain
a separately labeled source. This is unofficial; upstream changes and account
blocking remain possible. See the [library's own warning](https://wwebjs.dev/guide/).

### Linked WhatsApp Web capture

Required: Docker/Compose on a trusted Linux server, persistent storage, a working
HTTPS reverse proxy, and the public key exported from **Messages** in the Android
app. No Meta credentials are needed. No service has been deployed or account linked
by this implementation run.

1. Place the exported key at ignored `server/device-public.pem`.
2. Create private `server/.env` with only `DEVICE_TOKEN` (at least 32 random
   characters) and `STORE_KEY_BASE64` (base64 of 32 random bytes). Generate these
   locally and save them privately; do not paste actual values into commands,
   documentation or Git. Compose supplies the public-key path and Web mode.
3. When deploying on your selected server, use this entry point from `server/`:

   ```bash
   docker compose -f compose.yaml -f compose.web.yaml up -d --build
   ```

4. On a separate screen, open `https://YOUR-RELAY/pair`, enter the device token,
   and select **Show or refresh pairing**. On your phone select WhatsApp →
   Linked devices → Link a device and scan the QR. Refresh if it expires. The
   token is not put in a URL or browser storage. Clear it when finished. The page
   and authenticated responses are marked `no-store`; configure the reverse
   proxy to avoid caching and logging authentication headers or response bodies.
5. In Android **Messages**, select connection **WhatsApp Web**, enter that same HTTPS URL
   and device token, then **Save and connect gateway** / **Sync connected sources**.
   Inbox entries are labeled `WHATSAPP_WEB`; capture status distinguishes pairing,
   syncing, connected, auth failure, disconnect and a missing/stale worker. A
   mismatched server/app transport fails rather than silently changing channels.

Local worker verification (Node **22.12+**):

```bash
cd server/whatsapp-web
PUPPETEER_SKIP_DOWNLOAD=true npm ci --omit=optional --ignore-scripts
npm test
npm audit --omit=dev --omit=optional
```

The lockfile pins whatsapp-web.js **1.34.8**, qrcode **1.5.4**, and a Puppeteer
**25.17.0** override to avoid advisories in the upstream-pinned browser dependency.
Unused RemoteAuth archive dependencies are omitted; the worker uses `LocalAuth`.
The Docker base pins the matching official Puppeteer image. Live WhatsApp/browser
compatibility still requires account verification. Outside Docker, install a
matching browser through Puppeteer or provide `CHROME_BIN`, provide the two private
environment values above, and run `npm start` under a service manager. Do not run
two workers against the same browser profile. The Compose worker has no exposed
port, runs as UID 10001 and keeps Chromium's sandbox enabled. It uses the official
[Puppeteer Docker setup's `SYS_ADMIN` capability](https://pptr.dev/guides/docker);
run it only on the trusted host. The relay retains its existing dropped capabilities.

Three persistent volumes have distinct purposes: `whatsapp-web-session` holds the
browser credentials/history; `whatsapp-web-spool` holds encrypted pending capture
and private QR/status files; `relay-data` holds the phone-encrypted queue and replay
fingerprints. The browser is a decrypted endpoint, and its profile is **not**
encrypted by our spool/envelope code. Protect its host disk and backups. The worker
gets the spool key but not the device bearer token or Meta credentials. Message
bodies, raw browser objects, QR tokens and provider errors are not printed by our
application code.

Worker events are limited to 128 KiB and 16,000 text characters (truncation is
marked), with up to 10,000 pending events retained for seven days. Quota/storage
errors are surfaced; events arriving during failure can be lost. Relay copies are
also retained seven days, with replay fingerprints retained 30 days. Android uses
manual sync and its existing best-effort 15-minute maintenance; this is not an
instant push connection. Offline/disconnected periods and linked-device history
limits can leave gaps. Edits/deletions/reactions and historical backfill are not
captured in this first slice. No attachment bytes or inferred transcripts are
fabricated.

**Several WhatsApp accounts:** one relay (and one linked browser session) serves one
WhatsApp account. For a second account run a second relay with its own Compose
project, volumes, device token, store key and HTTPS URL, then add it in Android under
Settings › WhatsApp › Add another WhatsApp account. All relays use the same phone
public key. Never point two relays at the same browser profile or relay database.

To revoke access, log out this session from WhatsApp's **Linked devices**, stop
the worker, and disconnect the gateway in Android. Disconnecting Android alone
does not revoke the linked server session. If the linked session disconnects or its
authentication fails, the worker exits and Compose restarts it with the saved session;
if WhatsApp no longer accepts that session, the pairing page shows a new QR code.
Changing the WhatsApp account or transport requires draining the old queue first
and a separate relay database/profile; old queued evidence must not be relabeled
as a new account. Never run Web and Meta capture against the same relay database.

Host relay checks:

```bash
cd server
.venv/bin/python -m pytest -q
docker compose -f compose.yaml -f compose.web.yaml config --no-env-resolution --no-interpolate
```

### Alternative official Meta transport

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
`POST /device/events/ack` (batch of up to 100 IDs; Android uses this),
`POST /device/events/{id}/ack`, `POST /drafts`, `POST /drafts/{id}/approve`, and
`POST /drafts/{id}/send`. Device endpoints require bearer auth; webhooks require
verification/signatures and match configured business/phone IDs. Rate limit is
120 requests/minute per client address, taken from the reverse proxy's
`X-Forwarded-For` (Compose trusts it because the port is bound to 127.0.0.1 only).
Requests carrying the valid device token have a separate allowance of 1,200/minute. Access logs retain requests while redacting queries.
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

## Fingerprint login and daily Google Drive recovery

In Android Settings, enroll a fingerprint and set a device screen lock,
then open **HellFire Settings** and choose **Enable fingerprint app lock…**. Android authenticates both enabling
and disabling the lock. The app locks after leaving the foreground and after
activity/process recreation; authentication is never restored from saved state.
Strong biometrics or the device PIN/pattern/password are accepted on API 30+;
API 29 uses the platform biometric/device-credential prompt. Android chooses which
enrolled sensor to offer. Private assistant-session queries redirect to the
unlocked application while app lock is enabled. Background capture and backup
continue. App windows exclude screenshots/recent-task previews. Removing the
screen lock while app lock is enabled requires restoring a screen lock in Android
Settings before opening private data. This is an application UI lock; background
capture keys remain available to authorized app services.

Google Drive setup requires a Google Cloud project with **Google Drive API enabled**,
an OAuth consent screen (add your account as a test user if applicable), and an
**Android OAuth client** registered for `com.hellfire.ass` and the actual APK's
SHA-1 signing certificate. Register debug and private release certificates separately
as needed; retain the app/Cloud project identity for recovery. No client secret or
service-account key belongs in the APK. `./gradlew :app:signingReport` displays
certificate fingerprints. Devices need Google Play services and a Google account.
Google account policy or incomplete Cloud setup may block authorization; no live
account has been connected by this implementation.

In **Settings → Encrypted backup**, choose **Connect / reconnect Google Drive…**, select your Google
account, and approve `drive.file` access. Enter a strong passphrase (12–1024
characters), keep a separate copy, and choose **Enable daily encrypted backups**.
The first upload is queued immediately; subsequent uploads use a persisted
24-hour WorkManager schedule. Both daily and **Back up now** jobs require an
unmetered network, sufficient battery and free storage. Android/ColorOS, force-stop,
recording or network loss can delay them. Backups use the existing encrypted
portable format, including registered originals, and remain visible as timestamped
`.hfb` files in My Drive. Upload success is recorded only after Drive confirms
archive size and checksum. The last result/time is shown in Backup. Every
archive is complete, so each counts against the account's storage. After each
confirmed upload the app deletes its own older archives beyond the newest 7 (choose 3,
7, 14 or All in Encrypted backup); it never deletes the archive it just uploaded or
files it did not create. The app requests access only to files it creates/opens, not the whole Drive.

The passphrase/account configuration is encrypted with the existing device key in
`noBackupFilesDir`; it is excluded from portable/OS backups. Short-lived access
tokens remain in memory and are reacquired through Google's authorization API.
A worker never opens a consent screen: revoked/expired authorization that needs
interaction reports **Reconnect Google Drive**. **Disable daily backups** cancels
scheduled/manual jobs and removes the saved passphrase, retaining cloud archives.

To recover on a replacement phone or empty app installation:

1. Install the matching HellFire Ass app/version with the registered signing identity.
2. Leave notification/SMS capture off, disconnect mail/gateway accounts, and stop recording.
3. Open **Settings → Encrypted backup**, connect the original Google account and choose **Find Google Drive backups to restore**.
4. Enter the archive's original passphrase, select a dated backup and confirm **Download and restore**.
5. Regrant Android permissions, reconnect accounts, import models and re-enable app lock/daily backup as desired.

The list shows the 100 newest matching app archives. Older visible `.hfb` files
can be selected with the manual restore picker. Restore downloads only ciphertext
into private storage, authenticates the archive and validates it before importing;
wrong passphrases, incomplete/corrupt archives and nonempty local data are rejected.
Accounts/credentials, permissions, app lock and scheduling settings, models and
Phase 0 test samples are excluded. There is no passphrase recovery. A process kill
can leave an encrypted staging file; full native/Google/device round trips remain
NOT TESTED. Jobs use WorkManager's normal execution window (roughly ten minutes);
very large archives or slow connections may require manual export/upload/restore.
The resumable upload protocol is used for a single streamed transfer; this slice
does not persist upload sessions across worker/process restarts.

References: [Android biometric prompt](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.Builder),
[Google authorization](https://developer.android.com/identity/authorization),
[Drive upload protocol](https://developers.google.com/workspace/drive/api/guides/manage-uploads),
[Drive file scope](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

## Web access (read-only mirror)

`web/` is an optional website for reading your data away from the phone. The phone stays
the only place anything is captured, parsed, classified, summarised or approved. It pushes
a copy of selected records to your own server, and the site only displays that copy.
Confirming payments, sending replies and changing tasks still happen on the phone.

- **Copied:** accounts, confirmed ledger entries, account balances and this/last month's
  totals (computed on the phone), payments waiting for review, SMS balances, tasks,
  email/WhatsApp messages, drafts, media names/summaries/transcripts, memory, and SMS or
  notifications classified as messages or payments.
- **Never copied:** security-code (OTP) events and any message, task, memory or payment
  candidate captured from one; recording originals, photos and video; passwords, tokens and
  keys; models and search vectors. Only listed columns are sent, not whole rows.
- **On the server** the copy is plain JSON in Postgres, readable by whoever controls that
  server and its backups. Run it on a machine you trust, behind HTTPS.
- **How sync works:** about every 15 minutes while online with battery not low (Android
  may delay it), the phone sends rows that changed since the last pass and the ids that
  disappeared, up to 300 rows / about 1 MB per request. It remembers what the server holds
  as an encrypted id → hash list tied to the server's dataset id. A new or wiped server,
  or **Copy everything again**, triggers a reset and a full copy.

### Run the server

Runs directly on the host, without Docker: the local PostgreSQL 18 (`postgresql.service`)
and two systemd units in `web/systemd/`. Both Node services listen on 127.0.0.1 only
(API 3911, website 3910). An HTTPS reverse proxy must point your domain at
`127.0.0.1:3910`; the phone accepts only `https://` servers.

1. Database (done on this machine on 2026-11-16): a login role `dev` matching the Linux
   user, owning databases `hellfire` and `hellfire_test`, connecting over the Unix socket
   with peer authentication, so there is no database password:
   `sudo -u postgres psql -c "CREATE ROLE dev LOGIN" -c "CREATE DATABASE hellfire OWNER dev" -c "CREATE DATABASE hellfire_test OWNER dev"`.
   The API creates its two tables on start.
2. Build: `cd web/api && npm ci && npm run build`, and `cd web/frontend && npm ci && npm run build`.
3. Password hash: `cd web/api && npm run hash-password`, type the site password (12+
   characters), press Ctrl-D. The output starts with `scrypt:`.
4. Create private `web/.env` (ignored by Git, mode 600) with
   `DATABASE_URL=postgresql:///hellfire?host=/run/postgresql`, `SYNC_TOKEN` (32+ random
   characters), `SESSION_SECRET` (32+ random characters) and `OWNER_PASSWORD_HASH` (the
   `scrypt:` line). Generate secrets locally, for example with `openssl rand -base64 36`;
   do not paste them into commands, docs or Git.
5. Install and start the units (paths assume this checkout and Node 24.13.0 under nvm):
   `sudo cp web/systemd/*.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now hellfire-web-api hellfire-web-frontend`.
   After code changes rebuild both packages, then `sudo systemctl restart hellfire-web-api hellfire-web-frontend`.
6. On the phone: **Settings › Web access**, enter `https://<your domain>` and the
   `SYNC_TOKEN`, then **Save and turn on**. Open the site and sign in with the password.

**On this machine (2026-11-16):** running at **https://hellfire.local** behind nginx
(`web/nginx/hellfire.local.conf`, installed in `/etc/nginx/sites-available` and enabled), with a
self-signed certificate in `/etc/nginx/ssl/hellfire.local.{crt,key}` (CN and SAN
`hellfire.local`, valid 825 days), `127.0.0.1 hellfire.local` in `/etc/hosts`, both systemd
units enabled, and `web/.env` filled. The generated site password is in
`~/.config/hellfire-web/owner-password` (mode 600); replace it with `npm run hash-password`
and a restart of `hellfire-web-api`. Browsers on this PC must accept or trust the certificate.
**The phone cannot sync to it yet:** `hellfire.local` resolves only on this PC, and the app
trusts only system certificate authorities, so it refuses a self-signed certificate.

The website forwards only `/api/sync/*` to the API, for the phone; the read API is
reachable only from the website's server. Sign-in sessions last 12 hours in an HttpOnly,
Secure, SameSite=Strict cookie; changing `SESSION_SECRET` signs every browser out.
Sign-in is limited to 5 attempts a minute (counted for the whole site, because all
sign-ins reach the API from the website). **Turn off** keeps the server copy; **Turn off
and delete the web copy** empties it first and stays on if the server cannot be reached.

### Develop and test

Node 24. API: `cd web/api && TEST_DATABASE_URL='postgresql:///hellfire_test?host=/run/postgresql' npm test`
(the tests drop and recreate their tables in that database). Website: `cd web/frontend &&
npm run typecheck && npm run build`; `npm run dev` serves it on 127.0.0.1:3910. `API_URL`
(default `http://127.0.0.1:3911`) is fixed into the `/api/sync` forward at build time.
Phone side: `WebSyncTest` in the Android unit tests. Pinned: NestJS 12.3.2,
@nestjs/throttler 6.7.1, pg 8.23.1, Next.js 16.4.3, React 19.2.9, TypeScript 6.0.3.

## Storage, recovery and boundaries

Room schema **13** has non-destructive migrations from schema 1. Production uses
SQLCipher plus Android Keystore-wrapped database credentials; captured payloads
also use Keystore-backed AES-GCM. Legacy plaintext database conversion exports to
an encrypted copy, checks it and atomically replaces the original; errors do not
fall back to plaintext or delete the database. Native conversion must still be
verified on a device. Startup storage failure shows a retry screen and retains files.

Registered media uses independently authenticated 64 KiB chunks for seeking through
in-memory readers, including virtual WAV/PDF descriptors; no plaintext temporary
original is created. A monitor interrupted while the app keeps running (microphone
taken, notice disabled) retains its authenticated complete chunks; the unflushed tail
can be lost. If the process dies mid-recording, the next start recovers the authenticated
chunks of compressed (AAC) and older PCM recordings as "Interrupted conversation
recording", dated from the file's creation time; files with no authenticated audio are
removed. Recovery never runs while the microphone is recording. Phase 0 samples retain their legacy whole-file
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

OTPs/security context are redacted before source persistence. Authentication context
(OTP, password, passcode, verification, login/reset code, 2FA, nenosiri, nywila, msimbo)
withholds the whole item. The everyday words code, PIN, token and verify remove only
the code value that follows them, wherever it repeats, so payment SMS warning
"Usitoe PIN yako", LUKU purchases and "code review" mail keep their content. Unmarked standalone
4–8 digit code candidates are removed conservatively. Preserved when no security
context is present: TZS amounts marked before or after the number (`TZS 5,000`,
`5,000 TZS`, `5000/=`), ISO and day/month/year dates, and, in messages that name a
money provider or currency, amounts after finance words (`umelipa 5000`, `kiasi`,
`salio`) plus till, Lipa Namba and reference numbers. This is not a universal secret detector. Android-protected/missing fields
remain unavailable. External sends require fresh owner approval. Cloud AI is restricted
to explicitly submitted AI chat input under the owner’s privacy/budget settings. There is no automatic capture/media upload, analytics,
stealth microphone, OS bypass, custom hotword engine,
automatic contact merging or unsupported SMS/call sending.

Remaining validation/research gates include actual Gemma 4 E2B/fallback performance,
real provider-template calibration, live Gmail/Meta eligibility and delivery, OEM
alarms/microphone/file visibility, encrypted database/backup device round trips,
on-device AAC encoder behavior, speaker grouping/owner-voice/name-placeholder accuracy, semantic
embedding and image-label quality on a real device, and battery/thermal
measurements. Literal retrieval, OCR and manual speaker correction are implemented;
those are not evidence that the advanced model-dependent gates passed. Optional
Telegram and desktop dashboard have not been added.

## Verification and change log

Exact host results, artifact checksum and the final physical checklist are in
[DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md). Host tests use synthetic data,
ordinary SQLite/Robolectric and generated AES/RSA keys; they do not prove native
SQLCipher/Keystore, actual model execution, live accounts or Android/OEM behavior.

- 2026-11-16, web access: optional read-only web copy (`web/`: NestJS API, Postgres,
  Next.js site) and Settings › Web access with an incremental WebSync worker on the phone;
  no Room change. 226 Android, 11 web API tests.
- 2026-11-16, Gemma 4 E2B (0.6.0): pinned official model downloaded on first launch over
  Wi-Fi with resume, SHA-256 check and load test; daily update check with owner-approved
  updates; arm64-v8a-only build; 232 host tests.
- 2026-11-16, review fixes and multiple accounts (0.5.0, Room v13): stable SMS/mail/relay
  identity, SMS high-water mark, money-preserving redaction, cross-source payment
  duplicates, tighter finance/task rules with Swahili clock times, full-text search,
  AAC recording recovery, recent-window mail sync, whole-folder call import, Drive
  retention, AI output-limit and thinking-block handling, relay rate-limit and batch
  ack, worker restart, internal-only deep links, debug-build diagnostics, and several
  email inboxes and WhatsApp accounts; 221 host tests.
- 2026-11-16, project review: full code and docs review; host suites re-run (205
  Android, 21 relay, 6 worker tests, no failures; lint 0 errors). Findings and next actions are in
  DEVICE_FEASIBILITY.md → "Project review checkpoint". No code changed.
- 2026-11-14, money entry: date/time, category and account pickers for cash entry,
  accounts without a provider field, Room v12; 192 host tests at that checkpoint.
- 2026-11-14, UI redesign: new theme and five-tab navigation, redesigned Home, Money,
  Tasks, Inbox and Ask, settings hub, reports with PDF/CSV export, Room v10–v11 and 13
  Glance home screen widgets. Emulator-checked only; see REDESIGN_SUMMARY.md.
- 2026-11-14, device security/recovery: opt-in platform fingerprint/device-credential
  app lock, encrypted daily Google Drive upload and confirmed backup selection/restore.
  Live Google/OEM verification remains pending.
- 2026-11-13, Phase 0: skeleton, assistant/session, notification capture, encrypted
  prototypes and access/status screens; 34 host tests.
- 2026-11-13, Phase 1: durable processing/recovery, audit/policy, authorized SMS history
  and reconciliation; 58 host tests.
- 2026-11-13, Phases 2–6 software slices: finance/local AI/OCR, encrypted database,
  mail/relay/drafts, tasks/alarms, encrypted multimedia/transcription adapters,
  evidence queries/memory and portable backup/recovery. Device testing stays last.
- 2026-11-13, advanced audio: compressed AAC conversation recordings, adaptive-noise-floor
  speech intervals, and experimental ONNX speaker grouping with owner-voice enrollment
  and conversation-name placeholders.
- 2026-11-13, smarter memory: on-device image labels and owner-imported text-embedding
  meaning search (schema 9 `semantic_vector`, rebuildable and excluded from backup); 141 host tests.
- 2026-11-13, agent workflows: confirm-first voice/chat commands for reminders,
  memory notes, task done/snooze/reschedule, cash review items and email/WhatsApp
  drafts; 137 host tests.
- 2026-11-13, WhatsApp Web: owner-authorized switch to linked-device capture with
  persistent LocalAuth, authenticated QR pairing/status, encrypted spool and relay,
  separate Android provenance and capture-only send guards. No live account paired.
- 2026-11-13, capture quality: narrower whole-item security redaction (above);
  notification removal records stay as evidence without re-creating finance/task
  candidates; a candidate repeating an existing provider reference is kept as
  duplicate evidence outside the review queue and linked on approval; promotions,
  system notices and deadlines over seven days old create no task review items;
  `leo`/`kesho` match whole words and `keshokutwa` means two days ahead; 152 host tests.
- 2026-11-14, resource use: coalesced queued processing drains with a successor for
  events arriving during a running drain; duplicate-only notification/SMS batches
  skip scheduling. Automatic meaning indexing waits for charging and adequate battery,
  skips native model initialization for an empty backlog, respects the requested
  batch limit and shares the heavy-model lock. Actual battery/peak RAM gains remain
  unmeasured pending device profiling.

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
