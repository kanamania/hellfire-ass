# HellFire Ass — Codex Master Build Specification

**Version:** 1.0  
**Date:** 2026-11-13  
**Owner:** Personal-use project  
**Primary platform:** Android / OPPO ColorOS  
**Project/app name:** **HellFire Ass** (exact spelling and capitalization)  
**Assistant invocation name:** **HellFire**  
**Proposed Android application ID:** `com.hellfire.ass`  
**Default timezone:** `Africa/Dar_es_Salaam`  
**Languages:** English, Kiswahili, and mixed English/Kiswahili  
**Distribution:** Private signed APK / sideload, not initially a Play Store product

> **INSTRUCTION FOR CODEX:** This document is the source of truth for the initial implementation. Build incrementally; do **not** attempt to implement every feature in a single pass. Start with the Phase 0 feasibility implementation and deliver a compiling application plus real tests. Record unsupported or unverified behavior instead of inventing workarounds or falsely claiming completion. Ask for credentials or device interaction only when a specific milestone requires them. Never commit secrets or recordings.

---

**Owner execution update — 2026-11-13:** Phase 0–1 software/host checks are implemented.
The owner explicitly authorized proceeding with the remaining phases.
Continue building incrementally beyond Phase 0; physical device testing is deferred
until the final stage at the owner's request. Keep device-dependent outcomes
explicitly NOT TESTED and do not treat this sequencing change as successful device
verification. This update supersedes the original starter prompt's Phase 0-only limit.

**Owner WhatsApp update — 2026-11-13:** The owner selected `whatsapp-web.js`
and authorized implementation of a linked-device server capture adapter. This
supersedes the earlier Coexistence-only requirement for the selected WhatsApp
connection. The first slice is capture-only: QR linking, persistent session,
incoming/outgoing/group text and media metadata, encrypted relay and truthful
status. Attachment downloads, full-history import and Web-session sending are not
part of this slice. The existing official Meta transport remains an alternative.
Live pairing/account and physical Android tests remain NOT TESTED.

## 0. Requirements locked by the owner

These are explicit decisions, not suggestions to reconsider:

1. This is a **personal AI life assistant**, **not** a Karibu product. All naming and UI branding must say **HellFire Ass**.
2. HellFire Ass should be selectable as the **Android default digital assistant**, replacing Gemini for the system entry points Android/ColorOS makes available.
3. Ingest and process **all notifications made accessible by Android**. Do not limit the listener to chat or bank apps. Notifications can contain finance, messages, tasks, system alerts, delivery status, travel updates, etc.
4. Use the owner-selected **whatsapp-web.js linked-device capture** through the self-hosted relay. Keep it labeled unofficial and capture-only. Preserve official **WhatsApp Business Platform Coexistence** as an alternative for an eligible Business number; notification capture is separately labeled partial coverage.
5. Use **Gmail IMAP for incoming email and SMTP for outgoing email**, authenticated via a user-provided **Gmail app password** when the account permits it. **Do not use Gmail API.** Support standard IMAP/SMTP accounts as an extensible protocol adapter.
6. In-person conversations that the owner **participates in** should be automatically detected during a permitted, active microphone-monitoring session; preserve **original audio**, **transcript**, **summary**, **speaker turns**, and extracted tasks/agreements. Audio must **not** be auto-deleted just because a summary exists.
7. Phone calls are **already recorded by the handset**. Import, index, transcribe, and summarize accessible call-recording files. Do not try to implement undocumented phone-call audio capture.
8. Capture incoming/outgoing finance SMS; recognize Tanzania bank/mobile-money transactions, distinguish income/expense/own-account transfers, learn Lipa Namba merchants, and ask a nightly question about unlogged cash expenses.
9. Detect action items, promises, meetings, deadlines, bills and money owed from all channels. Escalate important alarms until owner explicitly chooses Done, Snooze or Reschedule **inside the application**. Android can still revoke access, force-stop or uninstall the app; do not claim literal OS-level unkillability.
10. Classify and index accessible images, videos, audio, documents and receipts **on-device wherever possible**; avoid routine cloud uploads.
11. Draft contextual replies in English/Kiswahili with a one-tap **user-approved** Send/Edit option where a supported channel permits it.
12. The owner-supplied target hardware is described as **OPPO Find X10 Pro Max, 12 GB RAM, 512 GB storage, Dimensity 9600 Pro, 8,000 mAh**. **Do not assume** OS version, API level, vendor driver features, file paths, or AI acceleration; inspect the connected device using ADB.
13. Default to **local-only AI and encrypted storage**. An optional cloud AI endpoint may handle explicitly allowed difficult tasks; never require it for capture, reminders, basic finance parsing, or local search.

### Non-goals and hard boundaries

- No claim that sideloading grants privileged access to protected app databases, hidden notifications, microphone streams or all call audio.
- No claim that a third-party assistant reproduces every Gemini or OEM-specific gesture, hotword, integration or screen-understanding feature.
- No storing or indexing OTP/authentication codes as general searchable memory; Android may redact them before a listener receives them.
- No root, privileged-system modifications, hidden-surveillance techniques, or Accessibility-based bypass as default engineering dependencies.
- No silent automatic sending of money, messages, files or sensitive commands. External side effects require an owner confirmation/policy gate.
- Do not promise recording if the OS denies the microphone or another app holds it. Show a recoverable status and log the interruption.

---

## 1. Product objective and user scenarios

**Product statement:** HellFire Ass is a native, local-first Android personal assistant that observes the phone's available communications and activity signals, turns them into structured events, connects them to people and financial accounts, maintains searchable long-term memory, and proactively helps the owner act on commitments.

### Critical scenarios

**A. Automatic expense recognition**  
An M-Pesa SMS arrives indicating TZS 15,000 was paid to Lipa Namba `123456`. HellFire Ass captures the SMS, extracts amount/provider/reference/fee/merchant, checks duplicate IDs, matches saved merchant aliases, categorizes as Food, and records the immutable transaction with provenance. A duplicate receipt notification must not create a second expense.

**B. Nightly cash-expense reconciliation**  
At 21:00 East Africa Time, show today's detected transactions and ask, in English/Kiswahili, which cash expenditures were missed. Voice: “Nimelipa bodaboda elfu tatu na chakula elfu saba.” Create two expense candidates, seek confirmation, and post to the cash account.

**C. Task extracted from a chat**  
Message received: “Nitakutumia invoice kesho saa nne asubuhi.” Determine whether **owner or other party** is making the promise, resolve relative dates using the source timestamp/timezone, store a linked task/promise, and remind or flag for review based on confidence.

**D. Cross-channel reply**  
An email or WhatsApp Business message requests a quotation. The assistant uses the conversation and contact memory to draft a reply. The owner taps Send, Edit or Ignore. Sending must use that channel's supported outbound API/action, with audit trail.

**E. In-person discussion**  
When a permitted microphone-monitoring session is active, low-power VAD and owner-speaker detection segment a discussion. Preserve encrypted original audio; after a suitable pause, transcribe, diarize, summarize, and extract agreements/tasks/prices. The owner can play the exact segment supporting a summary statement.

**F. System assistant**  
From Android's default-assistant gesture or shortcut, owner asks “HellFire, how much did I spend on fuel this month?” The assistant queries local deterministic finance data and answers by voice/text with links to transactions. When Android exposes foreground context, use it; when not, respond without pretending to see the screen.

---

## 2. Technical architecture

```text
                       ANDROID DEVICE (LOCAL FIRST)
+------------------------------------------------------------------+
|  Inputs: Assistant / SMS / Notification Listener / IMAP / Media  |
|          Microphone session / Call-recording importer / Telegram |
+-----------------------------+------------------------------------+
                              |
                     Capture Adapters
                              |
                 NORMALIZED EVENT INBOX (Room)
               idempotent + timestamps + provenance
                              |
             Rules / parse / deduplicate / risk filter
                              |
                  LOCAL AI JOB ORCHESTRATOR
             LiteRT-LM / OCR / Whisper / embeddings
                              |
           +------------------+-------------------+
           |                                      |
   Encrypted structured data                  Memory index
   (accounts, transactions,                  (FTS + vectors,
    tasks, contacts, drafts)                  source-linked)
           |                                      |
           +------------------+-------------------+
                              |
                 POLICY-GATED ACTION ENGINE
          alarms / draft / send / log / task / brief
                              |
       Compose UI + VoiceInteractionService + Voice session

                      OPTIONAL HTTPS BACKEND
       +------------------------------------------------+
       | Meta Cloud API webhook -> validated gateway    |
       | short-lived encrypted relay -> authenticated   |
       | phone client; outbound send via Meta API       |
       | optional encrypted backup / optional AI proxy  |
       +------------------------------------------------+
```

### Mandatory design rules

- **Every input becomes a normalized immutable `source_event` first**; derived records reference source event IDs.
- **At-least-once ingest, idempotent processing.** Events arriving twice must not create duplicate payments or reminders.
- **Deterministic core for money and dates.** Let AI extract candidates, but parse/validate/calculate with typed Kotlin code and SQL queries.
- **All LLM output is untrusted.** Validate against versioned JSON schemas, constrain available tool actions, and maintain a manual-approval gate for side effects.
- **Traceable memory.** A summary, task, or merchant classification must be traceable to SMS/email/notification/audio source.
- **Separation of concerns.** Assistant UI must still work with the model unloaded; notification capture must not depend on the model being loaded; alarms must not depend on a network connection.
- **Graceful permissions degradation.** If permission is revoked or access unsupported, show the source as disconnected and provide a supported recovery path.
- **Privacy by default.** No analytics SDK, no embedding credentials in APK, encrypt local media, record explicit cloud-consent setting.

### Proposed stack

| Layer | Choice | Implementation note |
|---|---|---|
| Android app | Kotlin + Jetpack Compose + Material 3 | Single app, multiple Gradle modules |
| Build | Gradle Kotlin DSL + version catalog | Pin tested dependencies; don't guess versions |
| DI | Hilt | Test-friendly bindings |
| Async | Coroutines + Flow | Bounded channels and backpressure |
| Data | Room / SQLite | SQLCipher if current Room/SQLite integration is verified |
| Secrets | Android Keystore + encrypted secrets wrapper | Never plaintext passwords/token logs |
| Jobs | WorkManager | Batch and retry; **not** exact reminder scheduling |
| Alarms | AlarmManager + user-granted permission | Persist + restore schedules |
| Local LLM | LiteRT-LM with Gemma 4 E2B, optional E4B | Measure CPU/GPU/NPU paths on device |
| Speech to text | whisper.cpp or equivalent proven local engine | Test Swahili and code switching |
| Speech detection | Silero VAD or similar small on-device VAD | Lightweight continuous monitor **only while session active** |
| Speaker matching | Optional locally evaluated embedding model | Confidence + error handling; don't assume perfect identification |
| OCR | ML Kit Text Recognition v2 | Run offline on accessible media |
| Video | MediaCodec/MediaExtractor + scene/keyframe logic | Sample frames and speech track |
| Search | SQLite FTS + locally stored embeddings | Avoid server-dependent personal search |
| Mail | IMAP/SMTP Java/Kotlin-compatible client | IMAP IDLE + periodic reconciliation |
| WhatsApp | whatsapp-web.js linked capture; optional official Meta gateway | Web capture-only; Meta Coexistence subject to eligibility |
| Backend | Python FastAPI + PostgreSQL / durable queue | Minimize retained webhook plaintext |
| Transport | HTTPS + authenticated client sync | Durable event delivery and offline replay |
| Optional cloud AI | User-selected provider via backend proxy | Redaction, allowlist, spending cap |

Use interfaces for local model runtime, email provider, message provider, transcript engine and sync so implementations can be replaced without rewriting UI/features.

---

## 3. Android default-assistant integration — **Phase 0 gate**

### Expected components

- A `VoiceInteractionService` registered with the appropriate `android.service.voice.VoiceInteractionService` intent filter, metadata and binding permission.
- A `VoiceInteractionSessionService` and `VoiceInteractionSession` implementation to show an interactive assistant UI.
- Request assistant role with `RoleManager` where available and provide a direct Settings fallback if OEM behavior differs.
- Assistant session UI supports typed and spoken input, concise TTS answer, and deep links to internal records.
- Respect foreground screen context/screenshot permissions and what Android actually provides to the current assistant; optional feature flag if OEM blocks it.
- Do not assume a custom wake word or physical-button shortcut is granted just by holding the role. Treat hotword/power-button integration as device-dependent research spikes.
- Keep always-running voice interaction service **lightweight** and move models/heavy work to a separate process or bounded worker.

### Acceptance tests

1. User can find and select **HellFire Ass** as digital assistant in OPPO settings.
2. Invoking the system assistant opens HellFire Ass's session UI.
3. Assistant can answer a local static query offline without backend or model download.
4. Assistant requests and reports the state of its role and any missing required permission.
5. The app survives being backgrounded and still responds to supported assistant invocation.
6. Record screen/button/hotword behavior on actual firmware as PASS / PARTIAL / BLOCKED, not an assumed feature.

---

## 4. All-notifications ingestion

Implement `NotificationListenerService` with correct manifest registration and an owner-controlled setup flow. The requirement is **all observable app notifications**, not a selected app allowlist.

### Capture fields (when supplied by Android)

`notification_key`, `package_name`, `post_time`, `received_at`, `title`, `text`, `big_text`, `text_lines`, `sub_text`, `category`, `channel_id`, `conversation_id`, `sender_hint`, `is_group_conversation`, `action_metadata`, `remote_input_capability`, `is_ongoing`, `removed_reason`, `ranking`, `content_available`, `content_redacted`.

### Listener pipeline

1. Listen for posted, updated and removed notifications.
2. Normalize extras defensively; do not crash on absent/partial/inconsistent fields.
3. Exclude only our own self-generated notifications from recursive re-ingestion (or mark as internal).
4. Persist an event quickly; do not block system callbacks on inference.
5. Deduplicate updates by notification key + meaningful-content hash + timestamp/version; retain state changes when useful.
6. Do **not** persist exposed OTPs/password-reset codes as general searchable text. Flag security notification class and redact sensitive spans.
7. Rule-classify obvious finance/system/courier/promotion types; send ambiguous relevant notifications to local AI queue.
8. Link notification events to IMAP/WhatsApp/SMS records using source IDs and temporal/content matching; avoid duplicates.
9. Keep notification reply `RemoteInput` actions only while valid. Verify support and execution result; otherwise open source app or offer copy-to-clipboard.
10. Support a user-visible per-app **processing policy**, but default **capture all accessible notifications**.

**Limits:** Android may redact OTPs and other protected data. Notifications do not provide complete historical chat content, inaccessible media, or all cross-device messages. Show `content_unavailable` rather than fabricate it.

### Tests

- Test chat, finance, ongoing, silent, grouped, image-containing and removed notifications.
- Test edited notification, rapid replacement, reboot/reconnect and notification permission toggling.
- Prove no duplicate transaction is created from an SMS plus notification about the same payment.
- Prove OTPs are redacted from the long-term searchable index.

---

## 5. Financial intelligence

### First-class data concepts

- Accounts: bank, M-Pesa, Airtel Money, Mixx by Yas, HaloPesa, cash, savings and other owner-defined wallets.
- Transactions: `INCOME`, `EXPENSE`, `OWN_TRANSFER`, `REFUND`, `REVERSAL`, `FEE`, `ADJUSTMENT`.
- Amounts stored as **integer minor units** according to currency rules. For TZS, whole-shilling amounts are expected; do not use binary floating point for balances.
- Counterparties / merchants keyed by provider + Lipa Namba/merchant ID plus aliases and user-confirmed labels.
- Category taxonomy: Food, Groceries, Transport, Fuel, Utilities, Airtime/Data, Rent, Family, Business, Fees, Savings, Income, Other (editable).

### SMS handling

- Initial authorized SMS import + new SMS receiver, where device policy/permissions permit.
- Provider-specific parsers for real English/Kiswahili formats. Fixtures must be anonymized.
- Extract reference number, direction, timestamp, account, amount, fee, levy, balance after, payee/payer, phone, Lipa Namba, provider.
- Layered parser: trusted templates -> strong validation -> optional local LLM strict-schema fallback -> user review.
- Maintain transaction fingerprint and unique source/provider reference key, including handling missing refs.
- Treat own-account transfers as non-expense cash movements, and fees separately.
- Reconcile statements/SMS against running balances, flag suspicious or missing records.
- Preserve raw source provenance for corrections without silently rewriting ledger entries.

### Merchant registry

- User can name unknown Lipa Namba once and classify it; save provider-scoped mapping.
- Learn aliases and recurring category after confirmation. Never guess that two identical merchant numbers on different providers are identical without evidence.
- Unknown merchants go to nightly review queue with amount/time/provider.

### Nightly check-in

- Default 21:00 `Africa/Dar_es_Salaam`, owner configurable.
- Show day totals, uncertain items, unknown merchants, new cash expenses, budget warnings and unresolved transfers.
- Voice and text input. Parse multiple expenses from a sentence; preview editable rows before save.
- If skipped, carry the unresolved review forward; reminders are persistent but subject to OS controls.

### Accuracy goals

- >=95% correct structured extraction on a representative anonymized sample, with a separate confusion/error report for each provider.
- No duplicate ledger entry in normal SMS + notification + email duplicate cases.
- 100% of accounting calculations derived from validated typed records, **not generated prose**.

---

## 6. WhatsApp linked-device capture and optional Business Coexistence

**Selected connection:** `whatsapp-web.js` on the owner-controlled server, using
QR-authorized Linked devices and `LocalAuth`. Preserve incoming/outgoing direction,
group thread and participant IDs, source provenance and media metadata. Never
represent unavailable attachment bytes or old history as captured. The server is
a decrypted endpoint; its browser profile is sensitive and is not covered by the
phone-envelope encryption. Show auth/disconnect/queue failures. There is no
Web-session sending endpoint in this slice.

The worker writes bounded AES-GCM-encrypted events to a shared spool. The existing
relay commits device-public-key encrypted envelopes before deleting worker files;
Android acknowledges only after local capture. Pairing/status require the device
bearer token. Browser sessions use a separate persistent volume. See README for
setup, limits and the browser sandbox capability required by its Docker image.

**Alternative official connection:** Meta Coexistence remains available for an
eligible Business number, with the original flow and approval controls below.

### Server flow

```text
Meta Cloud API event
   -> HTTPS FastAPI webhook
   -> verify Meta signature / challenge
   -> normalize and deduplicate via message ID
   -> encrypt/queue for authenticated phone delivery
   -> phone ingestion + local AI processing
   -> owner approves response
   -> phone sends requested outbound action to server
   -> server validates policy, calls Meta Graph API
   -> delivery status / echo event returns to phone
```

### Gateway minimum

- HTTPS endpoint for webhook verification (`hub.challenge` flow).
- HMAC validation of incoming webhook signatures with secret held only on server.
- Explicit graph API version config. Never hardcode an old version without verification.
- Authenticated pairing of a single Android device with the server.
- Durable event queue with idempotency, acknowledgments and retry when phone is offline.
- Authorized outgoing messages only; server holds Meta token, phone never stores the Meta app secret.
- Handle message statuses, echoed app messages (`smb_message_echoes` where supported), media metadata, retries and out-of-order deliveries.
- Short retention for any server-side plaintext; encrypt offline backlog to a device-held key where feasible. Note that Meta webhook content is visible transiently to the server during receipt.
- Respect messaging windows, templates, pricing, rate limits and account feature eligibility.

### Questions that cannot be silently assumed

- Does the owner have an **eligible WhatsApp Business app number**, rather than personal WhatsApp?
- Is the owner onboarding directly or through an approved Solution Partner / Tech Provider pathway?
- Which history sync, app echo, companion-device, group and calling capabilities are supported for **that exact onboarding path and API version**? Meta has additional Groups/Calling APIs, so do not declare blanket universal support or assume full WhatsApp app parity.

If selected Meta Coexistence is not eligible, show that transport as blocked. The separately selected Web transport does not require Meta credentials; notification-based coverage remains separately labeled.

---

## 7. Gmail and other email via IMAP/SMTP

**Gmail API is forbidden by project requirement.**

### Gmail default settings

- IMAP: `imap.gmail.com`, port `993`, implicit TLS.
- SMTP: `smtp.gmail.com`, port `465` implicit TLS or `587` STARTTLS.
- Credentials: user email + **Gmail app password** created on an eligible two-step-verified Google account.
- If app password is unavailable (e.g., account/Workspace restrictions), report the blocker. Do not silently switch to Gmail API or ask for the ordinary Google password.

### Behavior

- IMAP folder discovery; incremental message sync with UID/UIDVALIDITY; threaded local conversation model.
- IDLE where available, with reconnect, exponential backoff and periodic reconciliation.
- Parse MIME safely, extract text/HTML with sanitization, store attachments locally when selected/authorized, run OCR and invoice extraction.
- Build drafts locally. SMTP send only after user approval; track Message-ID and synchronize Sent folder rather than assuming every SMTP response equals durable Sent-folder storage.
- Avoid duplicate emails during reconnect or cross-folder movement.
- Store encrypted credentials using an Android Keystore-backed scheme.
- Support multiple standard IMAP/SMTP accounts later; alternative auth methods require a separate decision.

### Required tests

- Connect eligible Gmail account with app password; list INBOX; fetch new message; reconnect after network loss; draft; send; reconcile Sent folder.
- Do not log email content, IMAP password, SMTP password or authentication headers.

---

## 8. In-person conversation recording and call-recording import

The requested output for **each relevant in-person conversation** is:

1. Original audio file, encrypted locally and retained by default until the owner deletes it.
2. Transcription with timestamps and, when reliable, speaker turns.
3. Concise summary + detailed structured notes.
4. Decisions, action items, amounts, promises, questions, topics and named entities.
5. Links from extracted facts to source audio timestamp ranges.
6. Searchable metadata/embeddings for voice queries.

### Ambient conversation pipeline

```text
User activates/authorizes microphone monitoring
   -> visibly running microphone recording service
   -> low-power VAD + bounded rolling audio buffer
   -> detect meaningful speech segment
   -> optional owner-voice matching (confidence threshold)
   -> create conversation file; include available pre-trigger buffer
   -> segment on pauses, retain original compressed audio
   -> queue offline transcription + speaker diarization
   -> local summary + task/finance candidates
   -> encrypt, index, and show conversation timeline
```

**Design target:** automatic start/stop *of conversation clips within an actively authorized microphone session*. Explore whether assistant-role exemptions support background service start on this OPPO firmware, but never presume invisible continuous microphone access or uninterrupted 24/7 recording. Preserve status when recording fails, microphone is taken by another app, permissions change or the OEM terminates service.

### Implementation details

- Provide explicit controls: `Monitoring Off`, `Monitoring Active`, `Recording`, `Paused`, `Error/Unavailable`.
- Use visible persistent recording status and platform microphone indicators; no stealth recording mechanism.
- Audio format preference: Opus in an encrypted file container, with actual encoder/format support verified on device.
- Pre-trigger buffer target 30–60 seconds, bounded RAM/disk usage; measure whether holding buffer costs battery.
- User voice enrollment is optional and local. A voice match is probabilistic; allow unknown-speaker sessions and manual correction. Don't assert perfect identification of participation.
- Use VAD hysteresis to avoid thousands of short clips. Store duration/timestamps and device interruption events.
- Run Whisper and summary in controlled batches, preferentially while charging and on acceptable battery/thermal state.
- Expose playback with linked transcript/timestamps, rename, search, export, delete and retention settings.
- Use locally checked source evidence; a generated summary must not be treated as an actual quote unless supported by transcript/audio.

### Call-recording import

- Do **not** implement phone call recording. Existing OPPO/Phone dialer records calls automatically.
- Probe actual file locations and access restrictions using MediaStore and/or the Android Storage Access Framework.
- Use user-granted folder access if files are not visible in MediaStore.
- Import new accessible recordings by stable content hash; transcribe, summarize, tag call date/direction/contact if metadata is available.
- If the OEM stores recordings in inaccessible app-private storage, show a **device-specific blocker** and support manual sharing/import.

### Consent/retention implementation

Recordings contain other people's speech. Build explicit capture status, per-contact/location exclusions, deletion/export controls, and configurable notice/consent workflow as part of the product. Verify applicable requirements before deployment; do not attempt to bypass Android microphone indicators or access controls.

---

## 9. Photos, video, voice notes and documents

- Use MediaStore observers and user-selected folders via Storage Access Framework; do not assume free filesystem access.
- Images: OCR + local vision classification for receipts, payment screenshots, documents, whiteboards, chat screenshots, places and objects.
- Video: sample keyframes/scene changes, extract permitted audio locally, transcribe and summarize; apply job-size and charging thresholds.
- Audio: speech/music/noise classification; transcribe speech; summarize; tag and index.
- Never treat a screenshot of a payment as verified account settlement without corroboration.
- Store source URI, content hash, extracted text, timestamps, classification confidence, summary and embeddings.
- Require explicit user opt-in before uploading any media to a cloud model.

---

## 10. Commitments, alarms and personal memory

### Extracted commitments

For each candidate, capture `source_event_id`, `owner_or_other_party`, `title`, `due_at`, `timezone`, `confidence`, `evidence_span`, `state`, `associated_contact_id` and `confirmation_status`.

- Detect bidirectional promises: what I promised vs what someone promised me.
- Resolve “kesho”, “Ijumaa”, “next week” using source event time and `Africa/Dar_es_Salaam` unless a specific timezone is provided.
- Use confidence thresholds and owner confirmation for uncertain due dates/amounts.
- Identify bills, subscriptions, rent, internet, LUKU and recurring commitments.

### Escalation policy

- 24h before: standard reminder.
- 2h before: high-priority reminder.
- At due time: allowed alarm UI/sound per Android permissions.
- Re-alert on a configurable interval while unresolved. Actions in app: `Done`, `Snooze`, `Reschedule`.
- Persist pending alarms and re-register on boot, app update, clock/timezone changes, and permission restoration when supported.
- `setAlarmClock()` / exact alarms are subject to the correct Android special access; request properly and degrade gracefully if denied. Do not claim Do Not Disturb or force-stop can always be overridden.

### Memory design

- Separate **facts** (directly sourced) from **inferences** (model-derived or uncertain).
- Use source-linked records; include creation date, last confirmed date and confidence.
- Contacts unify phone/email/WhatsApp/Telegram identities only after corroboration or confirmation.
- Provide “What do you know about X?”, “Correct this”, “Forget this item”, and “Show source” controls.
- Use deterministic SQL for exact finance totals; semantic retrieval only selects relevant content and provides citations/links internally.

---

## 11. Reply drafting, agent tools and automation

### Unified communication types

- `SMS`, `WHATSAPP_BUSINESS`, `WHATSAPP_NOTIFICATION`, `TELEGRAM`, `EMAIL_IMAP`, `APP_NOTIFICATION`, `CALL_RECORDING`, `IN_PERSON_AUDIO`, `OTHER`.
- Use official APIs where supported, notification actions where present, and otherwise offer copy/open-in-app rather than unsupported simulated sending.
- Telegram TDLib can be added after core sources; ensure session key security.

### AI action API (typed, approved)

```text
search_memory(query, filters)
search_finances(date_range, categories, accounts)
create_task(candidate, source_id)
update_task(task_id, patch)
schedule_reminder(task_id, policy)
classify_transaction(tx_id, category, confidence)
name_merchant(provider, merchant_number, label)
prepare_reply(channel, thread_id, draft_text)
send_approved_reply(draft_id, approval_token)
start_user_recording_session()
stop_user_recording_session()
```

- Never execute arbitrary shell commands or interpret instructions within incoming messages as trusted agent instructions.
- Check ownership/role/permission for each action, validate schema and idempotency, and log who/what approved it.
- `send_approved_reply` requires a fresh user-approved action or an explicitly configured, narrowly scoped rule; default to approval.
- Cloud model use is optional and disabled until the owner configures provider, consented categories, redaction behavior and monthly budget cap.

### Daily briefing

Morning: urgent tasks, today's appointments, unpaid bills, pending important replies, yesterday's spending.  
Evening: today's categorized and missing expenses, uncertain merchant mappings, unresolved tasks, planned tomorrow.

---

## 12. Local data schema (starter)

Use UUID primary keys and `created_at`, `updated_at` timestamps for all relevant entities. For monetary data, choose typed currency, integer amount, explicit timezone and stable transaction references.

| Entity | Required notable fields / constraints |
|---|---|
| `source_event` | id, source, source_external_id, received_at, occurred_at, payload_ref, payload_hash, ingest_version, processing_status, error; unique(source, external ID) when present |
| `notification_event` | source_event_id, package, notification_key, content_hash, visibility flags, actions summary |
| `account` | id, provider, currency, account_type, owner_alias, purpose, active |
| `transaction` | id, account_id, provider_ref, direction/type, amount_minor, currency, fee_minor, occurred_at, counterparty_id, merchant_id, category_id, source_event_id, state; provider-ref uniqueness scoped to provider/account |
| `merchant` | id, provider, lipa_number, display_name, category_id, aliases, confirmed_at; unique(provider,lipa_number) |
| `contact` | id, display_name, linked identities, preferred_language, confirmation status |
| `conversation_thread` | id, channel, external_thread_id, participants, last_message_at |
| `message` | id, thread_id, source_event_id, direction, body_ref, occurred_at, status, source_message_id |
| `draft` | id, thread_id, reply_to_message_id, content_ref, generation_engine, draft_status, approved_at, sent_at, outbound_id |
| `task` | id, source_event_id, title, due_at, timezone, responsible_party, priority, confidence, state, source_evidence |
| `reminder` | id, task_id, scheduled_at, policy, attempt_count, last_fired_at, state, last_action |
| `media_item` | id, source_uri, content_hash, media_type, encrypted_file_ref, duration, metadata, classification |
| `transcript_segment` | id, media_item_id, speaker_label, start_ms, end_ms, text, confidence |
| `summary` | id, source_event_id/media_item_id, text, schema_version, model_id, evidence_refs |
| `memory_fact` | id, subject_id, predicate, value, source_event_id, confidence, confirmed, superseded_by |
| `embedding` | entity_ref, model_id, vector_ref, indexed_at |
| `capture_status` | adapter, permission_state, connected, last_seen_at, last_error |
| `action_audit` | id, action_type, source_ref, approval_ref, created_at, result, error |

**Do not use naive deletion to correct a posted transaction.** Use an audit-safe correction/reversal mechanism and retain provenance.

### Example normalized event contract

```json
{
  "schema_version": 1,
  "source": "android_notification",
  "source_external_id": "device:notification:key:version",
  "occurred_at": "2026-11-13T11:30:00+03:00",
  "received_at": "2026-11-13T11:30:01+03:00",
  "channel": "notification",
  "content_ref": "encrypted://events/<uuid>",
  "content_available": true,
  "content_redacted": false,
  "dedupe_key": "sha256:...",
  "processing_state": "pending"
}
```

### Example extracted finance candidate

```json
{
  "schema_version": 1,
  "kind": "EXPENSE",
  "currency": "TZS",
  "amount_minor": 15000,
  "fee_minor": 0,
  "provider": "MPESA",
  "provider_ref": "EXAMPLE-REFERENCE",
  "merchant_number": "123456",
  "merchant_name": null,
  "occurred_at": "2026-11-13T11:30:00+03:00",
  "confidence": 0.94,
  "requires_review": true,
  "evidence_source_id": "<source-event-uuid>"
}
```

The examples above are illustrative; none are actual user records.

---

## 13. Suggested source tree

```text
hellfire-ass/
├── AGENTS.md
├── README.md
├── docs/
│   ├── MASTER_SPEC.md
│   ├── ARCHITECTURE.md
│   ├── DEVICE_FEASIBILITY.md
│   ├── PERMISSIONS_MATRIX.md
│   ├── API_CONTRACTS.md
│   └── ADR/
├── android/
│   ├── app/
│   ├── core/common/
│   ├── core/database/
│   ├── core/security/
│   ├── core/events/
│   ├── core/ai/
│   ├── core/actions/
│   ├── integration/assistant/
│   ├── integration/notifications/
│   ├── integration/sms/
│   ├── integration/mail/
│   ├── integration/whatsapp/
│   ├── integration/media/
│   ├── integration/audio/
│   ├── feature/onboarding/
│   ├── feature/dashboard/
│   ├── feature/finance/
│   ├── feature/inbox/
│   ├── feature/tasks/
│   ├── feature/recordings/
│   └── feature/search/
├── server/
│   ├── app/main.py
│   ├── app/webhooks/
│   ├── app/relay/
│   ├── app/auth/
│   ├── tests/
│   └── README.md
└── fixtures/
    ├── sms/
    ├── notifications/
    ├── email/
    └── events/
```

For initial proof of concept, it is acceptable to start with fewer Gradle modules while preserving clean package boundaries; split later without rewriting contracts. The backend should be skeleton-only until WhatsApp integration phase unless needed for a test.

---

## 14. Delivery phases, gates and order

### Phase 0 — feasibility and skeleton **(IMPLEMENT FIRST)**

**Deliverables**

- Compiling Kotlin/Compose app named HellFire Ass, with `com.hellfire.ass` or documented adjusted package.
- A role-aware `VoiceInteractionService` + session that qualifies for Android assistant selection where supported.
- Notification listener that persists **mock or real** normalized events to Room when authorized.
- Permission/status screen covering assistant role, notification access, SMS, microphone, exact alarms, media access and vendor battery restrictions.
- A small local test query in assistant screen, handled without cloud AI.
- A microphone-recording test screen with a visible foreground service start/stop and a sample local file, conditioned on permission.
- Runtime device-diagnostics page and `docs/DEVICE_FEASIBILITY.md` with ADB commands and **actual test results** clearly distinguished from “not run”.
- At least unit tests for event normalization/dedupe and a README that builds from a clean checkout.

**Exit gate**

1. `./gradlew test` (appropriate Android module command) passes in the development environment.
2. APK builds using installed Android SDK.
3. Assistant selection and invocation are demonstrated **on device** or explicitly documented as `DEVICE TEST REQUIRED`.
4. Notification callback stores a non-sensitive event without triggering AI on callback thread.
5. Foreground microphone service records a test sample if owner grants permissions; if OEM blocks it, status is accurately reported.
6. No hard-coded secrets, no hidden privilege claims.

### Phase 1 — capture engine

- Immutable `source_event`, adapter contracts, durable work queue, dedupe and audit statuses.
- System-wide notification parsing for allowed visible content; SMS ingest; optional boot/reconnect restoration.
- Test notification storms, updates and redaction; source status dashboard.

### Phase 2 — local AI + finance MVP

- Gemma 4 E2B benchmark and fallback; schema validation; local OCR.
- Tanzania SMS parsing fixtures, accounts, ledger, Lipa Namba merchant registry, nightly expense dialog.
- Financial accuracy and reconciliation test suite.

### Phase 3 — communications

- Gmail IMAP/SMTP account + sync + replies.
- Owner-selected whatsapp-web.js capture + optional eligible Meta Coexistence webhook/echo ingestion.
- Unified inbox, draft review, one-tap approved replies and send audit.
- Optional Telegram and generic IMAP expansion.

### Phase 4 — tasks and alarms

- Evidence-linked task extraction; user confirmation; reminders; escalating alarms.
- Restoration after supported reboot/time change/update paths; morning/evening briefs.

### Phase 5 — audio and multimedia memory

- VAD-based real-life conversation segmentation during permitted active monitoring.
- Persist original encrypted audio, locally transcribe/summarize, diarize as feasible, extract tasks.
- Import existing call recordings; OCR photos/receipts; index video/keyframes/voice notes.

### Phase 6 — agent, stability, backup and optimization

- Tool/action policy engine; voice workflows; cross-source natural-language retrieval.
- Encrypted backup to optional self-hosted server, desktop dashboard if desired.
- Battery, thermals, failure recovery, migration, regression and usability testing.

**Planning estimate:** approximately 4–6 months part-time for a solo developer with AI coding assistance, highly dependent on device and Meta integration gates. This is not a delivery promise.

---

## 15. Testing matrix and quality bars

### Automation (host-run)

- Kotlin compilation + unit tests; static analysis/formatting if configured.
- Room migrations, event normalization, idempotency, timezone/date parsing.
- Regex fixtures for every Tanzania provider and failure/reversal format.
- JSON/schema validation for LLM candidates; never accept impossible dates/negative unsigned amounts.
- API simulation for duplicate/out-of-order webhooks, bad signatures, retry, offline replay.
- IMAP MIME parsing fixtures, different encodings, duplicate UIDs/UIDVALIDITY changes.
- Permission-denial and zero-content notification cases.

### Device tests (cannot honestly pass in CI without the phone)

- Assistant role appears and app launches from gesture/button.
- Sideloaded notification access permissions can be granted on ColorOS.
- Notification ingestion after long screen-off periods.
- Mic monitoring while screen is off, incoming call interruption and restart behavior.
- CPU/GPU throughput, latency, peak RAM, battery usage and heat for each local model.
- Native call recording file visibility and new-file detection.
- Alarm behavior with Doze, reboot, revoked exact-alarm permission and DND.
- IMAP sync when network drops or switches mobile ↔ Wi-Fi.

### Initial targets (to measure, not assert)

- >=95% correct finance SMS parsing on representative anonymized test corpus.
- 0 duplicate account transactions in tested multi-source duplicates.
- >=90% precision for automatically accepted task candidates; low-confidence ones reviewed.
- No missed **eligible, authorized** test alarms during repeatable controlled conditions.
- Clear status for every source: active, disconnected, permission missing, unsupported, failed.
- Acceptable overnight battery usage to be determined empirically; track baseline vs HellFire Ass.

---

## 16. Unknowns and discovery tasks for Codex

Create/maintain `docs/DEVICE_FEASIBILITY.md` with columns `Question | Test | Result | Evidence | Next action`.

1. **OPPO firmware details:** model, Android/ColorOS build, API level, CPU ABI and background restrictions. Use `adb shell getprop` with device connected.
2. **Default assistant:** whether app qualifies for role, gesture support, lock-screen behavior, optional hotword facilities.
3. **Microphone:** whether the voice-service exception applies as expected on this firmware, how long monitoring survives, how competing apps interrupt capture.
4. **Notification permission:** whether ColorOS requires additional manual “allow restricted settings” flow for sideloaded app.
5. **Call recordings:** readable via MediaStore, SAF or manual import only.
6. **Local inference:** which LiteRT-LM backend runs and performs well on this actual device; do not assume NPU.
7. **WhatsApp:** Web QR pairing, own/incoming/group messages, session restart, disconnect and replay; no complete-history claim. For optional Meta mode, Business eligibility and exact Coexistence features remain separate gates.
8. **Gmail:** account can create an app password and IMAP access succeeds; fail clearly if not.
9. **Battery / alarm delivery:** exact-alarm authorization and overnight behavior.
10. **Swahili quality:** code-switching speech transcription and informal money/date extraction accuracy.

Do not invent test outcomes. Log `NOT TESTED` explicitly until actual ADB/device/integration evidence exists.

---

## 17. Coding-agent operating instructions

Codex must follow these rules during all phases:

1. Read this entire document and `AGENTS.md` before modifying the repo.
2. At start of each phase, inspect existing code and report a concise implementation plan.
3. Implement the **smallest complete vertical slice** that can be compiled and tested, not decorative mock screens.
4. Prefer official Android/Meta docs and inspect installed SDK/tooling before selecting dependency versions. Avoid fabricated API types or artifact versions.
5. Do not upgrade the machine's OS/global packages or unrelated project dependencies without permission.
6. Do not commit passwords, access tokens, private messages, recordings or source corpora.
7. Label all missing permissions/API features clearly in UI and engineering notes; never fake success.
8. Respect the current owner integration choices: whatsapp-web.js capture with optional Meta Coexistence, Gmail via IMAP/SMTP app password, existing call-recording import, **original in-person audio retained**.
9. Every feature requiring external side effects must be gated by explicit owner approval unless a reviewed automation policy exists.
10. Write tests for every parser, classifier contract, idempotency rule and public backend endpoint. Fail closed on invalid model outputs.
11. Run available build/tests; report actual commands and outputs concisely. If environment lacks Android SDK/device, report precisely what's unverified.
12. Maintain a change log and keep `docs/DEVICE_FEASIBILITY.md` current.
13. Continue to next step only after the current phase meets its acceptance gate or a genuine blocker is documented.
14. Use documented feature flags for experimental device-specific features rather than burying unstable behavior in production paths.
15. Keep context source-linked and distinguish user-confirmed facts from AI guesses.

### FIRST PROMPT TO GIVE CODEX

> You are the lead Android engineer implementing **HellFire Ass**, a personal AI life assistant. Read `docs/MASTER_SPEC.md` and `AGENTS.md` in full. Implement **Phase 0 only**. Start by inspecting the repository and available SDK/JDK/tooling; if empty, create a Kotlin + Jetpack Compose project with package `com.hellfire.ass`, modern build tooling and clean architecture. Build a real selectable Android assistant using `VoiceInteractionService`/`VoiceInteractionSessionService` where supported, a working `NotificationListenerService` that stores normalized events in Room, a permission/status screen, a local assistant test UI, and a user-activated foreground microphone recording proof of concept. Add unit tests and device-feasibility documentation. Do not fake device tests, download large AI models, request real credentials, or start WhatsApp/Gmail/finance implementation in this phase. Run all locally available tests/builds, fix problems, then report what passed, what needs physical OPPO testing, and exact next steps.

---

## 18. Reference documentation checked for this plan

These are engineering references, not an assertion that every feature will work on the owner's firmware:

1. Android `VoiceInteractionService`: https://developer.android.com/reference/android/service/voice/VoiceInteractionService
2. Android `NotificationListenerService`: https://developer.android.com/reference/android/service/notification/NotificationListenerService
3. Android foreground/microphone service restrictions and voice-service exception: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
4. Android exact-alarm permissions: https://developer.android.com/develop/background-work/services/alarms
5. Android 15 notification OTP redaction: https://developer.android.com/about/versions/15/behavior-changes-all
6. Gmail IMAP/SMTP server documentation: https://developers.google.com/workspace/gmail/imap/imap-smtp
7. Google app-password eligibility: https://support.google.com/mail/answer/185833
8. Meta WhatsApp Business Platform documentation and available API products: https://developers.facebook.com/documentation/business-messaging/whatsapp/overview
9. LiteRT-LM overview: https://developers.google.com/edge/litert-lm/overview
10. Gemma 4 LiteRT-LM models: https://developers.google.com/edge/litert-lm/models/gemma-4

---

**Definition of success:** HellFire Ass becomes the owner's reliable Android assistant for *capture → understanding → searchable memory → approved action*. It must preserve the original evidence behind its conclusions, work offline for core functions, and report Android/provider restrictions honestly rather than obscuring them.
