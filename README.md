# HellFire Ass

Native, local-first Android assistant **HellFire**, package `com.hellfire.ass`.
The [master specification](docs/MASTER_SPEC.md) remains the product source of truth.
Phase 0–1 capture and software slices for Phases 2–6 are implemented. Full phase
acceptance remains **PARTIAL** pending the device, model, account and real-format
gates in [DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).

The owner authorized the remaining phases on 2026-11-13 and kept physical device
testing until last. No phone, live account or personal corpus was used to establish these results.
Downloaded public model weights stay outside Git. No real recipient was contacted
and no gateway was deployed.

## Current status — 2026-11-25

- **Code:** `main` holds every merged slice: Phases 0–6 software, the UI redesign
  (five tabs, reports, 13 home screen widgets), money entry pickers, fingerprint app
  lock, daily encrypted Google Drive backup, the resource-use changes, the fixes for
  the 2026-11-16 review findings, **multiple email inboxes and WhatsApp accounts**, and
  **Gemma 4 E2B downloaded on first launch with on-device update checks**, and the
  **daily brief, waiting-on, people, debts, bills, expiry reminders, capture alerts and
  action templates**, plus **local contact picking and person links**, and **device-calendar context and approved event changes**. Debug/release version **0.9.0** (code 9), Room schema **21**, backup schema **21**.
- **News and media:** scheduled/readable/spoken news, source controls, saved stories and news Ask; device library, media playback, local analysis/search, capture drawer, sanitized sharing, encrypted library sync and backup coverage. See the setup notes below.
- **Web access:** an optional read-only web copy (NestJS API, Postgres, Next.js site
  in `web/`) synced from the phone; see [Web access](#web-access-read-only-mirror).
  Local website: `https://hellfire.local`; not yet connected to a real phone.
- **Current Android host checks (2026-11-25):** 550 tests, 545 passed, 5 existing
  credential/fixture-dependent skips, 0 failures; 22 new calendar tests pass.
  Debug build and lint pass (0 errors). Provider/permissions/sync on the OnePlus
  remain NOT TESTED. See the calendar checkpoint in DEVICE_FEASIBILITY.md.
- **Earlier checks (2026-11-18):** 521 Android JVM tests (517 passed, 4 opt-in live-provider
  tests skipped), 20 emulator tests without skips, relay 23, WhatsApp worker 6,
  API 30 and seven Chromium checks passed. Exact build commands and artifact evidence are in
  [DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).
- **Pre-device checkpoint:** host-native, synthetic-service and isolated x86_64
  Android checks are complete for the implemented slice. The owner subsequently authorized core-depth slices; calendar is complete on the host,
  with background survival, location reminders and Telegram queued. The remaining
  plan is in DEVICE_FEASIBILITY.md. Host and emulator
  evidence remain distinct from actual handset, energy and OEM results.
- **Not done:** nothing has run on the physical OnePlus 16, with a live
  mail/WhatsApp/Google account or a real SMS corpus. Public Whisper and Silero model
  files ran on the host and emulator; GPU/NPU compatibility remains unverified.
  Full phase acceptance stays **PARTIAL**.
- **Earlier project review findings:** covered by host tests; each still needs
  its device or real-data check. DEVICE_FEASIBILITY.md → "Project review implementation" lists what changed
  and what remains to verify.
- **Release and CI:** the owner-signed ARM64 release, debug, emulator/device-test
  and benchmark/profile APKs build and pass signature/alignment inspection.
  Release mappings and native symbols are retained in the local artifact bundle.
  GitHub Actions is configured; hosted execution and physical release behavior
  remain unverified.

The media review on 2026-11-17 found functional defects missed by the integration
checks. Four interrupted fix branches were completed sequentially; see the
latest checkpoint in [DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md). Passing
builds are not evidence of successful playback, sharing, backup or sync on a phone.
The current owner-selected target is a OnePlus 16; device checks remain NOT TESTED.

## Build and verify

Required: JDK 17/21, Android SDK platform 36, Build Tools 35.0.0, NDK
**28.2.13676358**, CMake **3.22.1**, and platform-tools. Set `ANDROID_HOME`, or
`sdk.dir` in ignored `android/local.properties`. The first build downloads Gradle,
dependencies and the checksummed whisper.cpp source archive, never model weights.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
```

**Build naming (owner requirement, 2026-11-18):** Use versioned names for prepared
and delivered build artifacts: `HellFire-Ass-<versionName>-code<versionCode>-<variant>.apk`.
Read the actual version from `android/app/build.gradle.kts`; for the current version,
use `HellFire-Ass-0.9.0-code9-debug.apk` or `HellFire-Ass-0.9.0-code9-release.apk`.
Unsigned releases must end in `-release-unsigned.apk`. Copy the Gradle output to
the versioned name when preparing a build for handover.

Gradle output: `android/app/build/outputs/apk/debug/app-debug.apk`, version **0.9.0**.
It is built for **arm64-v8a only** (since 2026-11-16), Android API 29+, target/compile
API 36. Confirm the phone ABI before installation (`adb shell getprop ro.product.cpu.abilist`);
32-bit-only phones and x86_64 PC emulators cannot run it. Size: about 171 MB
(163 MiB): native libraries ≈99 MB (ONNX Runtime 34 MB, LiteRT-LM 15 MB, ML Kit OCR
22 MB, MediaPipe 8 MB, whisper 5 MB, barcode decoding 5 MB), plus unshrunk
debug code and resources. Native libraries
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
Production ARM64 model compatibility requires running the selected file on the
phone. Host and emulator compatibility results are reported separately.

The app remains one Gradle module with bounded Kotlin packages. Core capture,
ledger calculations, task review and bundled OCR work without account credentials.
Optional network adapters remain disconnected until configured by the owner.

## Everyday improvements and AI skills (0.9.0)

**Settings › AI skills** shows pending, active and disabled personal decision rules.
Corrections during payment review, rejecting false task/payment suggestions, and
saving/correcting source memories can propose skills. Proposals need approval;
choosing **Remember my account and category** is explicit approval for that default.
Existing encrypted review defaults migrate when next used.

- Add a guidance skill with a topic and instruction, or choose a real recent payment
  to set sender/merchant account/category suggestions. Edit, disable, re-enable or
  remove any skill. Removal also removes its usage log; original source records stay.
- Each skill shows why it was created, evidence, revision, status, use count and
  paged history linking to where it was used. Counts reflect guidance supplied to
  local Ask, defaults applied to review, or a repeated false candidate suppressed.
  They do **not** prove a language model followed an instruction or authorize actions.
- New advice about the same trigger is a separate pending update. Enabling it
  disables the previous active rule for that trigger. Past usage keeps its revision.
  Disabled rules are not automatically reactivated by repeated observations.
- Guidance matches all meaningful topic words. False-candidate rules are exact
  normalized-text rules scoped to their original app/sender/conversation/account.
  They suppress derived suggestions, never capture of the underlying source.
- Skill application records are accessible from history but excluded from full-text,
  semantic and assistant fallback retrieval, so old advice is not learned back after
  a skill is removed. Skills/usage are encrypted and included in portable backups;
  restored active skills become pending for review. They are not added to web sync.

**Settings › On-device models › Model catalog** offers compatible published formats,
without typing model names or URLs:

| Model | Purpose | Publisher/source | Download size |
|---|---|---|---|
| Gemma 4 E2B | Language, existing default | Hugging Face `litert-community` | 2,588 MB |
| Qwen3 0.6B | Smaller language model | Hugging Face `litert-community` | 614 MB |
| Gemma 4 E4B | Larger language model | Hugging Face `litert-community` | 3,660 MB |
| Whisper Base multilingual | Transcription | Hugging Face `ggerganov/whisper.cpp` | 148 MB |
| Whisper Small multilingual | Transcription | Hugging Face `ggerganov/whisper.cpp` | 488 MB |
| Universal Sentence Encoder | English semantic search | Google MediaPipe | 6 MB |

The picker shows source, license information, format notes, size, pinned revision
and SHA-256. Downloads wait for Wi-Fi, adequate battery and storage, retain partial
bytes for retry, verify integrity and load the runtime before replacement. Existing
models remain on failure. Gemma's existing daily update checks and explicit update
button remain. Advanced compatible-file imports remain available; there is no free-
text local model identifier or download URL. Speaker models retain their advanced
ONNX file picker because a generic model name cannot establish compatible inputs.
Actual accuracy, speed, memory use and Kiswahili performance need device evaluation;
the English text embedder is not advertised as verified Kiswahili semantic search.

Further daily-use changes:

- Guided setup starts from spending, conversations or commitments, preserves the
  chosen goal, allows finishing later, and distinguishes permissions from real
  delivery tests. Resume in Settings › Guided setup.
- Home/Settings › **Needs attention** combines capture, connection results, queued
  work, models, recordings, backup status and recovery links. Retry processes saved
  sources; it never retries uncertain sends.
- **Privacy and storage** distinguishes encrypted phone records, readable optional
  server copies, encrypted Drive archives and explicitly submitted cloud-AI text.
  Switching a destination off does not claim to erase previously exported copies.
- Memory has search, filters, older pages and earlier correction versions. Private
  drafts for memory, reconciliation, source notes, shares and skill editors are
  encrypted locally and excluded from backups. Capture-grant expiry may require
  sharing files again; a partial import resumes saved per-file checkpoints and
  content hashes prevent duplicate originals. Shared text/derived records commit
  atomically; a shared reminder remains a review proposal until approved.
- Ask accepts `compare spending on Fuel this month vs last month`,
  `why was transport spending higher this month`, and `budget for Fuel this month`.
  Differences and category contributors use integer ledger arithmetic; they do not
  invent causes. Budgets are monthly, category-based, across all accounts.
- English/Kiswahili labels now cover the new daily flows and common errors. Status
  banners announce changes; permission targets and bottom navigation better support
  touch and larger text. Advanced model descriptions/diagnostics remain English.
  Touch, TalkBack, voice and large-font rendering still require phone verification.

## Review improvements (0.8.0)

- Home › **Review** combines payment, task and draft review; **Record** opens recordings.
- Ask supports `spending on Fuel from M-Pesa this month`, `and last month?`,
  `spending at merchant "Cafe" last week`, and `spending for person "Asha" this month`
  (use your own account/category/person names). Unknown filters need clarification.
  Choose **Local model + saved sources** for read-only model explanations. **History**
  offers optional encrypted history and Clear.
- **Share → HellFire Ass** accepts text, screenshots, PDFs and audio/video through
  the existing app unlock gate. Review before saving a note, reminder or expense
  candidate. It never sends or posts a payment automatically.
- Inbox has Conversations / All items, saved-history search and older-page loading.
  Waiting on has per-request clearing and thread snooze. People can have identical
  names; Manage linked identities offers explicit merge/separation.
- Task editing has date/time pickers, recurrence, reminder intensity, per-task
  snooze and **Add to calendar** (you save or cancel in your calendar app).
- Money › **Check account balance** guides reconciliation before an adjustment.
  Review sheets can remember account/category suggestions after your approval.
- Settings › **Language** selects English/Kiswahili main controls and speech locale;
  advanced diagnostics and some dynamic explanations remain English. Captured
  messages, names and source excerpts remain unchanged.
- Backup shows staging-space estimates and **Verify an archive**. Streaming archives
  support more than the previous 7 GiB originals ceiling, subject to free space:
  500 GiB originals / 512 GiB archive; individual originals remain limited to 8 GiB.
  Verification checks authentication and original hashes without replacing data.

### Release APK and automated checks

`cd android && ./gradlew :app:assembleRelease` builds a shrunk release APK. To sign,
put private `android/signing.properties` (mode 600, ignored) beside Gradle settings:
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` is relative to
`android/`. Never commit that file or the keystore. Without it, CI builds an unsigned
release for validation. A release key differs from the debug key: keep using the
debug APK for an in-place upgrade of a debug install; do not uninstall personal data
just to change signing identity. Android/Google authorization must be configured for
whichever signing certificate you use.

`.github/workflows/checks.yml` runs Android tests/lint/debug/release builds, relay and
worker tests, web API tests against disposable Postgres, and frontend typecheck/build.
It needs no personal messages, accounts, model weights or production secrets and
performs no deployment. A hosted successful run has not been claimed.

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
  10, 11, 12, 13 and 14 and requires empty local data, stopped microphone, disabled notification/SMS capture
  and disconnected integrations. It authenticates/stages before merging, re-encrypts
  payloads/media with the receiving device key, revokes stale send approvals and
  preserves uncertain sends. Reconnect accounts and review exclusions afterward.
  Full native/device round-trip verification is still required before relying on it.

- **Daily brief:** Home › Morning brief / Evening wrap-up shows one page built on the
  phone: tasks due and overdue, bills in the next 7 days with a 30-day shortfall
  warning, yesterday's or today's confirmed spending, payments to confirm, replies
  owed, money owed both ways, new task proposals and capture problems. A quiet
  morning notification (default 07:00 EAT, counts only) opens it; switch it off or
  change the hour in Settings › Reminders. `morning brief` / `evening brief` in Ask
  include the same replies, bills, debts and capture lines.
- **People and contacts:** every person/vendor field offers **Choose contact**: loan/repayment,
  a person's name, Lipa namba merchant and payment review, task editing and transaction
  details. Type a name, number or email in the picker to search Android's local
  `ContactsContract` provider on a background thread (up to 30 matching data rows).
  Google contacts already synced to the phone appear there; there is no Google
  People API, new OAuth flow or contact network call. A blank query reads nothing.
  The picker explains `READ_CONTACTS` before its **Allow contacts** button; denying
  access keeps free-text entry usable. To grant it later, use App settings ›
  Permissions › Contacts; Settings › Permissions and capture shows the real state.
  Only an explicitly saved choice is kept: lookup key, display name and normalized
  phone/email aliases. No address-book import runs. Selecting a contact joins all
  its numbers/emails across SMS, WhatsApp and email using the existing last-nine-digit
  phone rule. Names remain the existing shared identity: identical saved names join.
  Tasks and payments have optional person links; clearing the field removes the
  link. Each link edit is audited and never changes money. PersonPage lists linked
  tasks, payments and debt notes, including people without a saved conversation.
  Deleted/unresolved contacts retain their saved name and show **not in contacts**;
  denied access is shown separately. Contact tables, aliases and person keys stay
  out of `WebSync`. Selected links are included in the existing encrypted backup;
  backup schema 15 still restores schemas 8 and 10–14. All OEM firmware contact and
  permission interactions remain **NOT TESTED** on a phone.
- **Waiting on:** Home or the Inbox header. "You owe a reply" lists people whose
  message has been the latest for 4 hours; "Waiting to hear back" lists people the
  owner asked something a day ago. It looks at the latest message per person across
  SMS, email and WhatsApp in the last 14 days and leaves out groups, short codes,
  sender names such as M-PESA and automated mailboxes. **Clear** hides a row until
  that person writes again.
- **People:** one page per person with every saved message across channels, open
  tasks found in their messages, the debt balance and recordings whose file name
  contains their name or number. Naming a number or address joins handles with the
  same name into one person. Calls and recordings are matched by file name only.
- **Debts:** Money › Debts records loans and repayments per person as the owner's own
  notes; they never change the ledger. A confirmed payment can be linked from its
  page (**Record as debt**), and approved payments whose source text names someone
  with an open balance are offered as possible repayments. **Remind** prepares an
  editable Kiswahili or English message for the Android share sheet, or an email
  draft that still needs approval in Inbox. Nothing is sent from this screen.
- **Bills and subscriptions:** Money › Bills totals the bills due in 30 days against
  the account balances and lists payments that look monthly: the same description,
  once or twice a month, three months running. **Track as monthly bill** creates a
  repeating bill reminder. Detection is rule-based on confirmed ledger entries.
- **Document expiry:** scanned images and PDF pages are checked for an expiry date
  after an expiry word (English or Kiswahili). Each future date becomes a task
  proposal ("Renew Passport · expires 12 Mar 2031") due 30 days before, or 7 days
  before when that is closer. Only the date and document type are kept from the
  unredacted text.
- **Capture problem alerts:** every 15 minutes the app checks for revoked access,
  no notification saved for 12 hours, an SMS on the phone that was not saved within
  30 minutes, and an email or WhatsApp account that has not synced for 6 hours. It
  notifies once and again each day the problem remains, shows a banner on Home, and
  asks Android to reconnect a silent notification listener. Switch off in
  Settings › Reminders.
- **Templates:** Home › Templates (also from Tasks and Money). A template is a named
  list of task, expense and income lines. **Use** shows every line with its amount
  and date; untick or adjust lines, then add them all with one tap. Tasks become
  active reminders. Payments with an account are posted to the ledger with the
  template run as their source record; lines without an account wait in To confirm.
  A payment line can be saved without an amount to enter it each time.
  `use template <name>` / `tumia kiolezo <name>` works in Ask and the assistant.
- **Spoken money commands:** `nimelipa bodaboda elfu kumi na tano`, `log income salary
  500,000`, `nimepokea 50000 kutoka kwa Juma`. Compound Kiswahili amounts (elfu, mia,
  laki, milioni) are parsed, and a leading "HellFire," is ignored. Items still go to
  Money › To confirm.

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

Required: a trusted Linux server with systemd, Python 3 and Node 22.12+, persistent storage, a working
HTTPS reverse proxy, and the public key exported from **Messages** in the Android
app. No Meta credentials are needed. No service has been deployed or account linked
by this implementation run.

1. Place the exported key at ignored `server/device-public.pem`.
2. Create private `server/.env` with only `DEVICE_TOKEN` (at least 32 random
   characters) and `STORE_KEY_BASE64` (base64 of 32 random bytes). Generate these
   locally and save them privately; do not paste actual values into commands,
   documentation or Git. The systemd units supply the public-key path and Web mode.
3. When deploying on your selected server, install both services from the repository root
   (paths and `User` in the units assume this checkout at `/home/dev/Projects/hellfire-ass`):

   ```bash
   python3 -m venv server/.venv && server/.venv/bin/pip install -r server/requirements.txt
   (cd server/whatsapp-web && npm ci --omit=dev --omit=optional)
   sudo cp server/systemd/*.service /etc/systemd/system/ && sudo systemctl daemon-reload
   sudo systemctl enable --now hellfire-relay hellfire-whatsapp-web
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
`npm ci` without `PUPPETEER_SKIP_DOWNLOAD` installs Puppeteer's matching Chrome; to use
an installed browser instead, set `CHROME_BIN` in `hellfire-whatsapp-web.service`. Live
WhatsApp/browser compatibility still requires account verification. Do not run two
workers against the same browser profile. The worker has no listening port, runs as the
unit's `User` with `NoNewPrivileges` and keeps Chromium's sandbox enabled; run it only on
the trusted host. The relay listens on `127.0.0.1:8080` only.

Three ignored directories under `server/state/` have distinct purposes:
`whatsapp-web-session/` holds the browser credentials/history; `whatsapp-web/` holds
encrypted pending capture and private QR/status files; `relay.db` holds the
phone-encrypted queue and replay fingerprints. The browser is a decrypted endpoint, and its profile is **not**
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
WhatsApp account. For a second account run a second pair of units (copies with
another name, port and `server/state/` paths) with its own device token, store key and HTTPS URL, then add it in Android under
Settings › WhatsApp › Add another WhatsApp account. All relays use the same phone
public key. Never point two relays at the same browser profile or relay database.

To revoke access, log out this session from WhatsApp's **Linked devices**, stop
the worker, and disconnect the gateway in Android. Disconnecting Android alone
does not revoke the linked server session. If the linked session disconnects or its
authentication fails, the worker exits and systemd restarts it with the saved session;
if WhatsApp no longer accepts that session, the pairing page shows a new QR code.
Changing the WhatsApp account or transport requires draining the old queue first
and a separate relay database/profile; old queued evidence must not be relabeled
as a new account. Never run Web and Meta capture against the same relay database.

Host relay checks:

```bash
cd server
.venv/bin/python -m pytest -q
systemd-analyze verify systemd/*.service
```

### Alternative official Meta transport

`server/` contains the real single-owner FastAPI relay, not a deployed service.
Its host tests use mock Graph responses and generated synthetic keys/events.

```bash
cd server
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
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
| `DEVICE_PUBLIC_KEY_FILE` | Set by `hellfire-relay.service` to `server/device-public.pem` |
| `RELAY_DATABASE` | Set by `hellfire-relay.service` to `server/state/relay.db` |

The relay binds only `127.0.0.1:8080`. A real HTTPS reverse proxy is required for Meta
and the Android client. After provisioning and explicit deployment authorization,
`hellfire-relay.service` is the service entry point; for Meta, delete its two
`WHATSAPP_*` lines and do not install the WhatsApp Web worker. This development run did
not execute that deployment command. Register `/webhook`, verify signatures and
actual account eligibility/Coexistence onboarding with Meta; app configuration alone
cannot prove eligibility. Enter the matching HTTPS URL/device token in Android.

Endpoints: `GET /health`, `GET/POST /webhook`, `GET /device/events`,
`POST /device/events/ack` (batch of up to 100 IDs; Android uses this),
`POST /device/events/{id}/ack`, `POST /drafts`, `POST /drafts/{id}/approve`, and
`POST /drafts/{id}/send`. Device endpoints require bearer auth; webhooks require
verification/signatures and match configured business/phone IDs. Rate limit is
120 requests/minute per client address, taken from the reverse proxy's
`X-Forwarded-For` (trusted only from 127.0.0.1, the local reverse proxy).
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
unmetered network, sufficient battery and free storage. Android/OEM firmware, force-stop,
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
   The API creates its tables and indexes on start.
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
@nestjs/throttler 6.7.1, pg 8.23.1, Next.js 16.4.6, React 19.2.9, TypeScript 6.0.3.

## Storage, recovery and boundaries

Room schema **19** has non-destructive migrations from schema 1. Production uses
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
to explicitly submitted AI chat input under the owner’s privacy/budget settings. Capture/library upload requires configured web sync; ordinary phone-library items stay local unless selected. There is no analytics,
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

## Pre-device validation

Verified local bundle: `~/.cache/hellfire-ass/predevice/releases/536f75d/`.
It contains the signed `apks/HellFire-Ass-0.9.0-code9-release.apk`, nine other APKs, `manifest.json`,
regression reports, R8 mappings and native symbols. Prepared APK names include the
actual app version and variant; instrumentation companions use the app-under-test
version, with their own unset version recorded separately. The manifest records SHA-256
checksums and source revision; private signing material and downloaded test models
are excluded. This was a successful incremental build; byte-identical clean
rebuilds have not been compared.

At the owner’s request, this checkpoint is finalized and further expansion
is deferred. The prioritized remaining software and physical plan is in
[DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md#current-scope-and-remaining-plan--owner-update-2026-11-18).

Production remains ARM64-only. `emulator` is a separate x86_64 package
`com.hellfire.ass.emulator`; `deviceTest` is an isolated ARM64 package
`com.hellfire.ass.devicetest`. Neither replaces the owner's installation.
MediaPipe text 0.10.26 has no x86_64 runtime and reports that limitation;
Whisper, SQLCipher, ONNX Runtime, LiteRT-LM and bundled vision libraries are real
packaged native libraries. A packaged runtime still needs a compatible model test.

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleEmulator :app:assembleEmulatorAndroidTest
cd ..
python3 tools/generate_predevice_fixtures.py --check
python3 tools/fetch_test_models.py
python3 -m venv tools/.venv
tools/.venv/bin/python -m pip install -r tools/requirements-model-tests.txt
tools/.venv/bin/python tools/evaluate_onnx.py --models "$HOME/.cache/hellfire-ass/predevice/models" --sample android/app/src/androidTest/assets/jfk.wav
tools/test_real_whisper.sh "$HOME/.cache/hellfire-ass/predevice/models/ggml-tiny.en-q5_1.bin"
tools/start_fixture_emulator.sh
tools/test_emulator.sh emulator-5580 "$HOME/.cache/hellfire-ass/predevice/models"
```

The emulator script uses its own API 35 AVD and never wipes another AVD. It first
checks denied capture permissions, then grants permissions/notification access and
the assistant role only to the disposable emulator package for capture tests.
Cleanup revokes the test grants and restores the previous assistant. These are ADB
test grants, not user-prompt or OEM verification. Instrumentation disables automatic model downloads
and news fetching only in disposable test packages. Screenshot goldens contain
synthetic bilingual components; production windows retain `FLAG_SECURE`.
The public JFK fixture's provenance and checksum are in
`tools/fixtures/public-audio.json`. Small public model revisions, sizes and SHA-256
checksums are in `tools/fixtures/models.json`; weights stay in the ignored cache.

`tools/test_mail_fixture.sh` runs the full JVM regression with a disposable real
TLS mail server, preserving certificate/hostname checks. Current checkpoint: 521
JVM tests (517 passed, four external AI-provider skips), 20 emulator tests with no
skips, and seven browser checks.

With existing development dependencies installed, Node 24 and Docker available,
`tools/test_predevice_services.sh` creates a disposable tmpfs Postgres database,
runs relay/worker/API/frontend checks and launches a real Chromium browser.
It has no WhatsApp account or real mail recipient. An occupied service port is
never killed. Reports under `android/build/reports/predevice/` and
`web/frontend/build/` distinguish host, emulator and model evidence. Hosted CI
results require an actual workflow run.

`tools/benchmark_device.sh SERIAL NEW_PRIVATE_DIRECTORY startup` installs only
the separate benchmark APKs and collects real JSON/traces plus before/after
hardware snapshots. Enable airplane mode and finish that disposable copy’s English
setup first. `profile` and `compare` modes collect and compare actual app profiles;
the comparison requires collected app rules. No phone benchmark has run.

For later physical bring-up, first build the isolated ARM64 test APKs:
`cd android && ./gradlew -PintegrationBuildType=deviceTest :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest`.
Then `python3 tools/oneplus_bringup.py --serial DEVICE_SERIAL --output /private/path/bringup.json`
collects a read-only hardware survey. Add `--install-fixtures --run-tests` to install
and exercise only `.devicetest`. No production app reset, permissions, role or
background exemptions are performed. Follow the current prioritized physical suite
in [DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).

## News, library and performance

The [2026-11-18 performance audit and measurement protocol](docs/DEVICE_FEASIBILITY.md#performance-audit--2026-11-18)
maps existing optimizations, confirmed source-level overhead and device-only
validation gates. `android/benchmark` contains cold/warm/hot startup, UI journey
and Baseline Profile collectors against the isolated, release-optimized
`com.hellfire.ass.benchmark` app. Physical phone results and generated app profiles
are not available yet. Complete that copy's English setup offline before running
tests; see the protocol for exact commands and fixed-workload requirements.

Host checks and artifact reporting (repository root):

```sh
bash tools/test_native_jni.sh
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/benchmark_media_queue.py
python3 tools/performance_report.py apk android/app/build/outputs/apk/debug/*.apk
```

The JNI check uses the real production bridge with a synthetic Whisper API, no
model or inference. The queue benchmark uses the checked-in Room schema and DAO
queries on host SQLite; its timings and Python allocations are **not** Android
latency, app PSS, energy or battery results. Device profiling uses AndroidX JSON
and Perfetto; the report script computes p50/p95 only from actual recorded samples.

Open **Home → News** for briefings, saved stories and the reader. News settings
control source selection, interests, morning/evening times, quiet hours and audio.
Saved news remains readable offline; fetching new stories needs a connection.
News clustering currently uses English/Kiswahili lexical matching; optional news
embedding vectors are deferred.
X sources require your own supported API credentials; licensed wire sources show
that requirement rather than scraping unavailable content. Scheduled delivery is
best effort under Android/OEM firmware. Actual feed availability and phone TTS still
need live verification.

Open **Library** for accessible device media or grant a folder through Android's
picker. External entries remain references until explicitly imported as managed
originals. Player, analysis queue, search, capture drawer, sharing, sync and backup
pages use the existing local data. The universal drawer is available throughout
HellFire screens and through Android’s Share target; a system-wide overlay is not
implemented. File access and codec/model support depend on
the actual device; absent permission or model is not a successful analysis.

The **Library** web page is separate from the ordinary read-only mirror: optional
library sync encrypts originals and editable metadata with a shared passphrase
before transfer. Enter the same passphrase on the phone and in the browser. The
server sees opaque keyed identifiers, sizes and timing. Local IDs, tags and
original hashes stay encrypted. Library search runs after decryption; its unused
plaintext-search toggle/API was removed. Sync from the phone once to establish
the key check before browser unlock. Existing records upgrade in place; use the
existing library passphrase because key changes are blocked. Notes, collection
names/descriptions, categories and bookmarks are editable. Browser uploads and
downloads are limited to 256 MiB; use the phone for larger originals. Thumbnails
are not synced. Sync is not a backup. Ordinary mirror resets
preserve the encrypted library and its encryption salt.

**Settings → This device → Performance** shows operating policy and lets you
measure language backends. CPU remains the fallback until a compatible imported
model succeeds in a device probe. Queue limits, thermal policy and cooperative
yielding are host-tested; speed, memory, power and heat require the OnePlus 16.

## Verification and change log

- 2026-11-25, Priority 1 calendar slice: CalendarContract reads feed appointments
  and overlap warnings into Ask and morning/evening briefs. Confirmed tasks can
  create/edit/cancel linked one-off events in an owner-chosen device calendar,
  through fresh approval and the existing audit log. Room/backup schema 21 keeps
  older archive allowlists and revokes provider links/approvals after restore.
  Calendar permission status is under Settings → Permissions and capture.
  Open a confirmed task → Device calendar to review changes; task edits never
  silently mutate the calendar. Start/end input uses EAT. Synced calendars may
  upload approved changes through their own sync adapters. No Google Calendar API,
  invitations, new dependency or server change. Physical OnePlus provider/sync,
  permissions and calendar-app source links remain NOT TESTED. Detailed commands,
  limits, recovery behavior and evidence: `docs/DEVICE_FEASIBILITY.md`.

- 2026-11-18, performance checkpoint: bounded native audio ownership/error handling,
  one video retriever per batch, bounded media scheduling queries, idle-model
  cleanup under the existing gate, local stage diagnostics and isolated AndroidX
  startup/navigation/profile collectors. 501 host Android tests pass, native
  CheckJNI and ASan/UBSan checks pass, and debug/release/benchmark/profile APKs
  compile. Raw comparison evidence is in `docs/performance-results.json`.

- 2026-11-17, news/media/performance/universal drawer: consolidated the pending
  feature branches on schema 19; added news Ask and scheduled notices; corrected
  same-refresh clustering, drawer action priorities, player search/navigation,
  thermal/queue policy, encrypted sync and backup/restore recovery. Full host
  regression and lint pass; physical/device, provider and model checks remain open.

Exact host results, artifact checksum and the final physical checklist are in
[DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md). Host tests use synthetic data,
ordinary SQLite/Robolectric and generated AES/RSA keys; they do not prove native
SQLCipher/Keystore, actual model execution, live accounts or Android/OEM behavior.

- 2026-11-16, local contacts (0.7.0, Room/backup v15): contact picker with explained
  runtime access and free-text fallback; all existing person/vendor fields plus
  optional task/payment links; multi-number/email message joins, audited link edits,
  deleted-contact status, older-backup restores and no contact fields in WebSync;
  263 host tests, including 16 new synthetic contact/link/privacy tests. Phone checks
  remain NOT TESTED.
- 2026-11-16, daily outputs and templates (0.7.0, Room v14): morning brief and evening
  wrap-up with a morning notification, waiting-on tracker, people pages, debts with
  repayment suggestions, recurring-bill detection and 30-day shortfall, expiry-date
  task proposals from scans, capture-gap alerts, action templates for tasks, expenses
  and income, compound Kiswahili amounts and income/template commands; 247 host tests.
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
- 2026-11-17, adaptive performance: the same gate and workers now yield to interactive
  work, reuse one heavy runtime when memory allows, skip silent audio and duplicate
  video frames, and cap how many media jobs are scheduled. CPU stays selected until a
  phone benchmark succeeds. Host policy tests pass; OnePlus 16 speed, power and codecs are
  not measured. See `docs/DEVICE_FEASIBILITY.md`.

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

Local release signing material for this machine is kept under
`~/.config/hellfire-ass/release-signing/` (directory mode 700, key/properties mode
600). Ignored `android/signing.properties` links to that private configuration.
Keep a separate private backup of the signing key and properties for future APK
updates. They are deliberately excluded from Git, CI and application backups.

### TheftGuard (owner-device vertical slice, 2026-11-25)

Open **Settings → TheftGuard**. Configure the existing FastAPI relay's HTTPS
**origin**, its existing device bearer token, and a separate random 32-byte,
base64-encoded guard transport key. Store that same key as
`GUARD_PAYLOAD_KEY_BASE64` in the relay's private environment. Do not reuse
`STORE_KEY_BASE64` or put keys in commands, source files or screenshots. Existing
relay configuration (device public key, store key, token and selected WhatsApp
transport requirements) is still required.

Server-only SMTP configuration:

| Variable | Purpose |
|---|---|
| `GUARD_SMTP_HOST` | SMTP hostname with implicit TLS |
| `GUARD_SMTP_PORT` | Implicit TLS port; default 465 |
| `GUARD_SMTP_USER`, `GUARD_SMTP_PASSWORD` | SMTP login; never stored in APK |
| `GUARD_MAIL_FROM`, `GUARD_MAIL_TO` | Sender and owner destination |
| `GUARD_TIMEOUT_SECONDS` | Missing-heartbeat threshold; default 180, env minimum 120 |

`hellfire-relay.service` passes these values from `server/.env` to the relay. Run one
Uvicorn worker per relay database; its lifespan task checks persisted deadlines and
mail approximately every ten seconds. No scheduler runs unless the app lifespan
is started. The configured relay refuses remote arming when SMTP settings are
missing; configured does not mean live delivery has been verified.

Allow **precise location**, camera and notifications, and optionally phone/SIM
access. Background location is a separate Android Settings grant after foreground
location; the app never represents it as granted automatically. Enable Device
Admin for repeated wrong-unlock callbacks (three failures; callback support must
be verified on firmware). Request battery optimization exemption if desired.
Arming, camera resume and disarming each require a fresh strong biometric;
ordinary app-lock screen-lock fallback does not bypass guard biometric checks.

Once armed, the guard service runs in `:guard` with a low-importance persistent
notification. An independent best-effort 60-second heartbeat keeps the server
informed while sequential front/back CameraX and fused-location cycles collect
triggered evidence. Missing cameras, unavailable location, Google Play services,
revoked access and upload failures are recorded as unavailable/unconfirmed, not
synthetic successes. Triggers are repeated wrong unlock, observed subscription
change, loss of validated connectivity, manual owner test and server-reported
missed heartbeat. Only the first trigger is retained until disarm/re-arm. Camera
bytes stay in memory until encrypted in the Room log. Local evidence has a 50 MiB
budget; full storage pauses new capture. Offline uploads are retried while the
same session remains armed. Disarm retains evidence and stops further uploads.

Both new bearer-authenticated, existing-rate-limited routes accept
`{"payload":"<base64 PayloadCipher envelope>"}` with AES-256-GCM v1
(`0x01 || 12-byte nonce || ciphertext-and-tag`), AAD `hellfire-guard-v1`, and
4 MiB maximum request bodies:

- `POST /device/theft/heartbeat`: `session_id` UUID, increasing `sequence`, Unix
  `timestamp` **seconds**, `armed`, `active`, `battery` (-1 unknown or 0–100),
  `network` (`online`/`offline`). The relay uses its receipt clock for timeout;
  heartbeat timestamps must be within five minutes. Acknowledgments report
  `active`, allowing a server dead-man trigger to reach a reconnected phone.
- `POST /device/theft/evidence`: `id` UUID, `session_id`, Unix `timestamp` seconds,
  `trigger`, nullable `location` (latitude, longitude, accuracy metres, timestamp),
  `images` (up to two distinct front/back base64 JPEGs, each ≤1 MiB), and bounded
  capture-status `errors`. An armed heartbeat must establish the session first.
  Evidence IDs are idempotent; reused IDs with changed content are refused.

Evidence is sealed to the existing paired device public key on the relay. A
separate server-store-key-encrypted outbox supplies **readable coordinates and JPEG
attachments** to the owner through implicit TLS SMTP; it clears the payload after
SMTP accepts. This makes recovery email usable without the stolen phone. The
configured owner mailbox intentionally retains its own readable evidence copy.
Relay retention for evidence and unsent mail is seven days, with a 256 MiB retained
evidence budget (storage-full responses preserve the phone's pending upload). Mail acknowledgment is queueing,
not verified inbox delivery; SMTP failure retries and rare crash-related duplicate
emails are possible. Offline local disarm queues remote cancellation with
WorkManager; re-arming waits for acknowledgment. Lost network, power-off, Doze,
force-stop or OEM termination may all cause the same missing-heartbeat alert.

Boot recovery is dedicated to guard and resumes only saved armed state after the
first unlock makes credential-protected storage available. Background location
allows a **location-only** foreground service; stock Android camera recovery needs
an owner tap and biometric. Without background location, tap the guard notification
to resume the service. Android can block either path; no indicator suppression,
power-key interception, shutdown veto or root path exists.

Tier 2 provisioning is a scaffold, **NOT TESTED**: on an empty, owner-approved
provisioning/test device with no conflicting accounts/owners, Android's Device
Owner provisioning tooling can assign `com.hellfire.ass/.guard.GuardAdminReceiver`
(for example `adb shell dpm set-device-owner com.hellfire.ass/.guard.GuardAdminReceiver`
when Android permits it). Do not reset the owner's phone for this slice. Arming
checks actual Device Owner status before applying uninstall/safe-boot restrictions
and lock-task policy. Disarming restores the previous settings. Power-menu
suppression only applies if a future explicitly authorized kiosk flow enters
lock-task mode; this slice does not. Hardware shutdown is always outside userspace
control. Tier 3 root support is out of scope.

Official constraints checked: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager),
[CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera),
[Google Play services releases](https://developers.google.com/android/guides/releases).
See `docs/DEVICE_FEASIBILITY.md` for actual test results and the final-device checklist.
