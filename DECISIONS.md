# HellFire Ass redesign: decisions log

Decisions made where `HANDOFF.md` was unclear or a screen was marked *not drawn*. Newest entries are added at the end of each step.

## General

- **Branch.** The brief says the worktree is on `ui-redesign`; the actual worktree branch is `feat/ui-redesign` (created for steps 1–2 earlier). Work stays on that branch; no new branch was created. **Status 2026-11-16:** the branch is merged into `main` (`49f2722`, then `566a291` for the Money entry pickers); the worktree and branch have since been removed.
- **Fonts.** Inter and Space Grotesk are bundled as OFL variable fonts in `res/font` (licences in `assets/licenses`). Google downloadable fonts would add a runtime network call, which §1 forbids. Adds about 1 MB to the APK.
- **Icons.** The design's outline SVG icons are ported to `ImageVector`s (`ui/theme/Icons.kt`) instead of adding an icon library.
- **Dark theme.** Derived from the §3 tokens (not designed). Same roles, dark surfaces `#0E1013` / `#181B20`, lighter accents so text keeps at least 4.5:1.
- **No navigation library.** A saveable route stack (`Route`) plus `BackHandler` covers 5 tabs and the pushed screens. Avoids a new dependency for one use.
- **Glance.** `androidx.glance:glance-appwidget` and `glance-material3` 1.1.1 are the only new dependencies, because §8 names Jetpack Glance and `GlanceTheme` colour providers. Both are the same library family.

## Step 1 · Theme and components

- Extra neutral tokens found in the design files but not in the §3 table were added (`subtle #F7F8FA`, `fill #F1F3F5`, `track #E9ECEF`, `faint #8A929C`, `bodySecondary #3D444D`, `handle #D3D8DE`, `info #1D4ED8/#E8F0FF`, `onSuccessContainer #14532D`).
- Material 3 components used by older screens inherit the tokens through the colour scheme, so un-redesigned fields and buttons already look consistent.
- The table component pins the first column and scrolls the rest horizontally, as in the report mockups.

## Step 2 · Navigation

- **Routes.** Pushed screens are a `Route(screen, arg)` stack saved across process death. Tabs reset the stack. Back pops the stack, then returns to Home.
- **Deep links.** `hellfire://…` URIs (`ask?voice=1`, `money/cash`, `money/review`, `task/<id>`, `draft/<id>`, `note`, `settings/permissions`, `report/money?category=…`) are handled by the activity, which is now `singleTop` with `onNewIntent`. They are only sent as explicit intents (widgets, reminder notifications), so nothing new is exported. The assist gesture maps to `hellfire://ask`.
- **First run.** The app opens on Setup until the owner taps Continue or Skip (`ui/setup_done` preference). Previously it always opened on the Setup tab.
- **Status messages** from permission flows show as a dismissible banner over the current screen instead of a line at the top of every tab.

## Step 3 · Home, Money, Tasks, Inbox, Ask

- **Where the old tab features went.** Nothing was removed. Moved items:
  - Add account → Money › Accounts (tap the balance card).
  - Local-model payment extraction → Settings › On-device models.
  - Reminder access, nightly review switch and overdue interval → Settings › Reminders.
  - Email and WhatsApp account setup and sync → Settings › Email / WhatsApp.
  - Personal memory and typed JSON proposals → Settings › Memory and proposals.
  - System assistant session test → Settings › This device.
  - Ledger reversal → the Transaction page (Money › Recent row).
- **Payments: Confirm opens a review sheet.** The card's Confirm opens a sheet with the source message and editable values; the sheet's "Confirm · post TZS …" button is the explicit approval and replaces the old pop-up dialog. "Not a payment" lives in that sheet (the mockup card has two buttons).
- **Change category on a posted entry.** The design's Transaction page has "Change category". Implemented as `FinanceRepository.recategorize`: changes only the category label of an original payment, never amount, account, fee or balance; audited as `OWNER_LEDGER_RECATEGORIZE`. Fee and correction entries are refused. Amount fixes still need a reversal.
- **Tasks: "Add task" on a proposal card activates it directly.** The card shows the quoted message, title and suggested time, and the tap is the owner's confirmation. Task changes are reversible, unlike sends or ledger posts.
- **Tasks checklist.** Tapping a row's checkbox marks it done immediately with a 6-second Undo (`TaskScheduler.reopen`, audited). The chevron opens the task page (snooze, reschedule, source).
- **Quick add** parses dates with the existing `TaskExtractor`; with no date it suggests the next full hour. It opens the edit sheet and stores nothing until "Add task".
- **Inbox contents.** Email/WhatsApp messages, SMS, notifications the classifier labels `MESSAGE` or `FINANCE_CANDIDATE` (system and promo notifications are left out), and media items. WhatsApp/Gmail notifications go under their channel; call recordings under Calls. Pills: "Payment to confirm", "Task found", "Added to Money", "Summary ready".
- **Approve and send keeps a confirmation dialog** showing channel, recipient and full text, because a send cannot be undone. Sent and failed drafts appear under the list with their delivery state.
- **Ask.** One chat. "On this phone" uses the offline evidence engine (answers, sources and command proposals); choosing an AI task or model uses the existing router, which still enforces cloud consent, redaction, the budget cap and fallbacks. Chat history is kept in memory only. Spend answers gained an optional structured total and per-category breakdown, computed with the same confirmed-only rules as `FinanceRepository.summary`.
- **Voice input** uses Android's speech recogniser intent with `EXTRA_PREFER_OFFLINE`. Whether it stays offline depends on the phone's recogniser; HellFire itself makes no network call.
- **Home "Up next"** shows active tasks due before the end of tomorrow, plus the 21:00 nightly review when it is on. "Needs you" also lists unfinished nightly reviews.

## Step 4 · Setup and Settings

- **Setup rows map to real Android state:**
  - Notifications = notification-listener access.
  - SMS = owner-enabled capture and read permission.
  - Microphone = record permission and notification posting (recording must show its notification).
  - On-time reminders = exact-alarm access and notifications.
  - Phone assistant = HellFire voice service selected.
  - Run in background = battery-optimisation exemption. The ColorOS auto-launch caveat is in that row's detail.
- **Leaving first-run Setup.** Back, Continue and Skip all mark it seen. Everything stays reachable in Settings › Permissions and capture, which reuses the same rows.
- **Permissions and capture** also holds what was the Capture tab: listener and SMS status, processing queue, per-source audits, resume processing, SMS history sync, per-app processing switches, the synthetic test event and "Delete captured events" (still only possible with capture turned off).
- **Settings groups** follow the mockup (Access, AI, Connected accounts, Your data), with extra rows for Reminders, Memory and proposals and Recordings, which have no other home. Subtitles are live: setup count, cloud state and provider count, imported models, this month's AI spend, mail and gateway state, memory count, originals count and last backup time.
- **Monthly AI budget** opens the Cloud AI screen in a budget-only mode (cap, month spend, usage) instead of a separate copy of that logic.
- **Restyled "not drawn" screens** keep their fields, wording and behaviour, grouped into cards: Cloud AI, On-device models, Recordings (import and analysis, conversation monitoring, originals, the 60-second microphone test), Encrypted backup and This device.
- **Last backup time** is stored in preferences on successful export so Settings can show it.

## Step 5 · Reports

- **Shared range.** One app-wide date range (Today, last 7 days, this month, Custom via the Material date-range picker), set on the hub or from any report's dark date chip. "Week" means the last 7 days including today.
- **Chart buckets:**
  - 4 × 6 hours for one day.
  - Daily up to 14 days.
  - The mockup's four week-blocks (1–7, 8–14, 15–21, 22–end) for a calendar month.
  - Otherwise 7-day blocks, at most about 12.
- **Money figures.** Built from confirmed ledger entries only; candidates never appear. "Expenses" is expenses net of refunds and **excluding fees**; fees are their own KPI, and Net = income − expenses − fees (the mockup's figures add up this way). Own transfers are left out of income and expenses and shown in the Accounts footer. A reversal counts under the kind of the entry it reverses, as in `FinanceRepository.summary`. "vs previous" compares with the period of equal length just before.
- **Money groups:** category, account, week, day (provider grouping removed by owner request on 2026-11-14). The mockup also mentions merchant; posted entries don't store a merchant, so merchants are in Accounts › "Who you pay most", grouped by description, with "Name it" for unnamed Lipa namba numbers. Category rows drill to the category list → transaction → source; account rows drill to the statement.
- **Accounts.** Opening = sum of entries before the range; in/out exclude fees; closing = opening + in − out − fees (unit-tested). Balance lines use one scale per account, as the mockup says. The statement shows a running balance.
- **Capture "needs attention".** Failed and pending processing, plus **"not understood"**: a message the classifier marked as financial that produced no payment candidate and no ledger entry. That is the case the Fix-SMS mockup describes. Rows group by app or SMS sender (the sender is decrypted on the phone for the report only) and drill to the sender's events, then to Fix (for failed or unread) or the source record.
- **Fix a captured message.** Shows a clearly labelled guess (direction words plus the first TSh amount). "Add as income/expense" creates a normal payment candidate in Money › To confirm, never a ledger entry. "Try again" re-queues processing for that one event.
- **Tasks report.** Outcomes: on time, done late, done with time unknown, still overdue, not due yet, waiting for review. Until step 6 adds completion times, done tasks show "done, time unknown". Snooze counts come from the action audit.
- **Messages report.** Received = incoming email and WhatsApp messages. "Sent by you" = drafts the channel accepted (submitted, confirmed, delivered or read), all owner-approved. The funnel is drafted → approved → sent → confirmed.
- **Media length.** Taken from the saved length (step 6), else the last transcript timestamp, else "unknown".
- **AI usage** reads the router's metadata audit until step 6 adds a longer per-request log.
- **Export.** PDF via Android `PdfDocument` (title, scope, KPIs, a bar chart of the table's main figure, table and totals), CSV of the table, or CSV of every record, with an optional source-ID column. Saved only through the Android file picker; nothing is uploaded. CSV cells starting with `= + - @` (and not numbers) are prefixed with `'` so spreadsheets don't run them as formulas.
- **Sheets scroll.** Bottom sheets now scroll; long sheets such as payment confirmation were cut off on a phone-sized screen.

## Step 6 · Schema additions (Room v9 → v10)

- **`assistant_task.completedAt`** (nullable). Set when the owner marks a task done; cleared by Undo / "Not done yet"; not set for rejected tasks. The migration **backfills it from the existing `OWNER_TASK_DONE` action audit** (latest record per task). That is real recorded evidence, so those tasks get a true on-time/late result. Done tasks with no audit stay "done, time unknown", as §6 asks.
- **`media_item.durationMs`** (nullable). Saved at import from Android's `MediaMetadataRetriever` for audio/video picked through the file picker, and computed from the captured PCM length for in-app recordings. Folder imports and older items have none; reports fall back to the last transcript timestamp, then "unknown". No backfill.
- **`ai_usage` table.** One row per finished request attempt: request ID, time, task, provider, model, result, input/output tokens, estimated micro-USD. Written through a new `onRecord` hook on `AiRouter`; prompts and answers are never passed to it. On first start after upgrade, the router's existing encrypted audit (the last 100 attempts) is copied once so earlier usage appears in reports.
- **Migration** is additive only (two `ADD COLUMN`s, one `CREATE TABLE` and index), with no destructive fallback. Tested from a real v9 database built from the exported schema, and run on the emulator against the existing encrypted v9 data.
- **Backups.** New archives are written as schema 10 and include `ai_usage`. **Schema 8 archives still restore**: their tables lack only the columns added after schema 8 (which restore as NULL), and `ai_usage` is simply absent. Archives with unknown columns, or missing any other column, are still refused. Before this change, adding columns would have made every existing backup unrestorable.

## Step 7 · Home screen widgets (Room v10 → v11)

- **New data (v11 migration, tested):**
  - **Tasks** gain `direction` (I_OWE / OWED_TO_ME / NONE), an optional display-only `amount`, `kind` (TASK / BILL) and `repeatMonthly`. Existing commitments extracted from messages get a direction from who the extractor said does it (owner → I owe, other → owed to me); owner-typed tasks stay NONE. Owners set direction, amount, bill and monthly repeat in the task edit sheet. A done repeating bill schedules next month's bill from the same evidence (audited `OWNER_BILL_REPEAT`).
  - **`category_budget`** (category, month, amount), edited in Money › Accounts › Monthly budgets.
  - **`provider_balance`**: the newest balance stated in a provider SMS ("Salio jipya ni…", "New balance…"). An older or replayed SMS never overwrites a newer one. Display only; never in ledger totals.
- **Backups** move to schema 11 and still restore schema 8 and 10 archives (new columns take their defaults).
- **Widget data:**
  - "Bills coming up" are active BILL tasks due within 14 days.
  - "Replies waiting" are email/WhatsApp threads whose latest message is incoming (last 7 days), plus SMS senders whose latest message is incoming. A tap opens the waiting draft's approval if one exists, else the message.
  - Nightly review counts payments waiting for review, broken down as unknown merchants (Lipa namba without a saved name), entries with no reference, and unfinished nightly-review days ("cash gaps").
  - Capture health counts the sources the owner turned on (notifications always, then SMS, email and WhatsApp if set up): all working = green, some = amber, none = red.
  - Budget pace compares the share of the limit used with the share of the month gone: more than 5 points ahead = "Ahead of pace".
  - Wallets show the SMS balance per provider account; cash accounts show their ledger balance.
- **Privacy:**
  - Every amount and preview starts hidden. Settings › Home screen widgets has a per-widget "show" switch (per widget type, not per placed instance) and a global "Hide all amounts" that wins.
  - Wallet balances additionally need a tap and hide again after a minute (a one-shot WorkManager job, not a timer).
  - The Ask widget's last question and answer are stored in the encrypted secret store.
- **Nothing sends from a widget.** The only in-widget actions are the promise checkbox (marks done; audited, same path as the app) and Wallets reveal/hide. Name, Reply, Review and "+ Cash" open the app on the approval screen.
- **Recording from the widget** always opens a small activity. It stops a running recording, or starts one through the same consent and exclusion check as Recordings (MicrophoneService re-checks saved exclusions). Consent is asked the first time in a session (60 minutes in this process; never stored). It runs as the existing visible foreground service with its notification, never auto-restarts, and queues local transcription once saved. Glance can't run a live timer, so the widget shows "Recording since HH:mm" instead of a ticking clock.
- **Freshness:**
  - Widgets update from a Room invalidation observer on ledger, candidates, accounts, tasks, reviews, messages, drafts, media, budgets, balances, merchant names and integration state, debounced to one refresh. That catches every write path, which is the same effect as calling `update` from each repository.
  - The 21:00 nightly alarm triggers a refresh so the briefing switches to evening.
  - There is no periodic timer (`updatePeriodMillis = 0`), and each widget shows "as of HH:mm".
- **Look:**
  - §3 tokens through `GlanceTheme` (light and dark), 22 dp corners, system sans as §8 says.
  - The stack bar, sparkline and budget ring are drawn to bitmaps, because Glance can't draw shapes.
  - Category colours follow §8.
- **Previews (owner request).** Every widget has an Android 12+ `previewLayout` that draws its real layout with sample content and masked amounts, themed light/dark, instead of the app icon in the widget picker. Android 10–11 launchers still show the icon.
- **Large sizes (owner request).** Widgets render at their exact placed size and fill it:
  - Lists grow with height.
  - Daily briefing shows both morning and evening sections when tall.
  - Money today adds the category breakdown and today's confirmed payments.
  - Nightly review lists each item.
  - Budget shows every category's progress.
  - Capture health lists each source.
  - Wallets add a total.
- **Add to home screen.** Settings › Home screen widgets can pin any widget through Android's `requestPinAppWidget`; Android asks for confirmation first.
- **Merchant names (owner request).** Naming a merchant is a type-ahead field suggesting merchants already named; picking one also fills its usual category.

## Owner requests after step 7 · Accounts, balances, calendar and charts

- **Adding accounts is now visible.** A "+ Add account" tile sits in the Money balance card. Accounts opens with the add form first when you came to add, or when there are no accounts.
- **Balances.**
  - **How it is stored:** "Current balance (optional)" when adding an account, and "Set balance" on each account, become one confirmed **BALANCE ledger entry for the difference**. Balances stay ledger-derived, so nothing is overwritten.
  - **Confirmation and evidence:** each needs an explicit confirmation dialog. The evidence is your own statement (captured as an owner entry, like a cash note), or the provider SMS when you pick the "Use the balance from SMS" suggestion and keep that figure.
  - **Effect on totals:** BALANCE entries change account balances but **never count as income or spending** (Money totals, reports, Ask answers, calendar, widgets). They can be reversed from the Transaction page like any entry. "Opening balance" vs "Balance correction" is set by whether the account already had entries.
  - **Past dates:** a balance "as of" a past time accounts for entries after that time. Audited as `OWNER_BALANCE_SET`.
- **Calendar.** The Money report has Chart / Calendar / Table.
  - Week: one row per day with a spending bar, spent and received.
  - Month: a 7-column grid shaded by spending, with today outlined.
  - Year: 12 month tiles.
  - Each has ‹ › navigation and period totals. A day or month opens its payments (expenses and income, signed), with a category pie.
  - Filters apply. Fees, own transfers and balance entries are left out of spent/received.
- **Charts.**
  - The Money report adds "Spending over time" (daily line graph), "Expenses by category" (pie, or bars via a switch) and "Spending by account" (pie).
  - Pie slices beyond six go into "Other".
  - Legend rows drill to that category's or account's payments.
  - The Money tab shows a "Where it went" pie for the selected month, with Calendar and Full report shortcuts.

## Money entry follow-up (Room v11 → v12, 2026-11-14)

- **Cash entry pickers.** Date and time (East Africa Time), category and account are chosen when logging cash, stored on the review item (`finance_candidate.accountId`, `category`) and prefilled in the confirmation sheet.
- **Accounts have no provider.** Accounts are created by name and optional balance. Provider labels and the provider report grouping are gone. Receipt provider and reference stay internal for duplicate detection and merchant evidence.
- **Backups** move to schema 12 and still restore schema 8, 10 and 11 archives.

## Review fixes and multiple accounts (Room v12 → v13, 2026-11-16)

- **Accounts are lists.** Email inboxes and WhatsApp gateways are stored as encrypted lists (up to 10 each). An earlier single-account setting becomes the first entry on first read. Each account keeps the id it already had, so messages, drafts and mail cursors stay attached.
- **One relay per WhatsApp account.** A whatsapp-web.js session and a Meta phone number are both one account, so a second WhatsApp account is a second relay URL and token. All relays share the phone's public key. Relay events now include the account in their key, because two linked accounts in one group see the same message ID.
- **Which account sends.** A reply uses the account the message arrived on. New messages and edited drafts choose from accounts that can send on that channel (WhatsApp Web cannot). Assistant commands use the first account and say which. The approval dialog names the account. "Edit" on a draft now updates it instead of saving a second draft.
- **Per-account status.** Integration states are keyed `EMAIL_IMAP:<id>`, `WHATSAPP_GATEWAY:<id>` and `IMAP_IDLE:<id>`; the v13 migration deletes the old single-account rows. Capture health shows one row per account. Live mail opens one IDLE connection per inbox inside the same ten-minute service.
- **Provider identity.** SMS, IMAP and relay events are identified by their provider key (row ID, UID, relay message), not by redacted content, so changing redaction rules cannot re-import history. Rows stored under the old content identity are found and reused. Notifications keep content identity, because an edited notification is new evidence.
- **Cross-source payment duplicates** need a different source type (SMS vs notification), the same provider, kind and amount, within 10 minutes. Two equal SMS payments in a row are genuinely two payments and both stay in review. Stored as `finance_candidate.duplicateOf`.
- **Full-text search** is an FTS4 table inside the SQLCipher database, so indexed text is protected by database encryption but not by the second payload-encryption layer. It holds only non-sensitive, available content, is rebuilt rather than backed up, and is cleared with captured events.
- **Task proposals from messages need commitment wording.** Owner-typed commands and quick add are exempt because the owner is asking for a task.
- **Drive retention** defaults to keeping the newest 7 app-created archives, deleting older ones only after a confirmed upload. "All" restores the earlier keep-everything behaviour.
- **Deep links** are accepted only by the unexported `MainActivity`; the launcher, assist gesture and assistant settings enter through the exported `.Launcher` alias, whose data is ignored.
- **Diagnostics** log only in debuggable builds, and only exception classes and stack frames.
- **Not changed:** no release build type or CI was added; no backfill-on-request for older mail.

## Web access (2026-11-16)

- **Read-only mirror, phone as the only writer.** The owner asked for web access with all processing on the phone. The server only stores, filters, counts and pages what the phone sends; month totals and account balances come from `FinanceRepository` on the phone as a `summary` row. Confirming, sending and editing stay on the phone, so the web needs no conflict handling.
- **Plain copy on the owner's server.** Rows are stored as JSON in Postgres so the site can filter, page and search them. This is a deliberate change from "everything encrypted on the phone": the Settings privacy line says so when web access is on, and the Web access page says what is and is not copied. End-to-end encryption to the browser was not chosen because server-side listing and search would then be impossible.
- **One generic table.** `record(kind, id, at, data jsonb)`, not a copy of the Room schema, so Room migrations do not need server migrations. Filterable fields are an allow-list per kind; values are parameters.
- **Change tracking on the phone, no Room migration.** The phone keeps an encrypted `web-sync-state` (dataset id + id → hash per kind) in the secret store and diffs it against current rows each pass. Hashes cover only the sent columns (plus `payloadHash` for events), so payloads are decrypted only for rows being uploaded. A mismatched dataset id resets the server and sends everything.
- **What is copied.** Only listed columns. Events are SMS plus notifications classified MESSAGE or FINANCE_CANDIDATE (as in the Inbox), with only their text fields. Anything tied to a security-sensitive source is excluded. Budgets are not copied, because pace needs spending computed per category.
- **Browser talks only to Next.js.** Next keeps the session token in an HttpOnly cookie and calls the API server-side. The site forwards only `/api/sync/*` (for the phone) to the API, so the read API is not exposed. Sessions are stateless HMAC tokens (12 h).
- **No ORM, no Nest CLI, no Jest.** `pg` with SQL, `tsc` builds, and Node's test runner against a real Postgres. Nest 12 is ESM-only, which makes Jest awkward. `@nestjs/throttler` is the one extra dependency, for login and sync rate limits.
- **No Docker (owner request).** The server runs on the host: local PostgreSQL 18 through the Unix socket with peer authentication (role `dev`, databases `hellfire` and `hellfire_test`), and two systemd units bound to 127.0.0.1 (website 3910, API 3911; 3000/3001 were already taken). Tests use the local `hellfire_test` database.
- **Password hash uses `:` separators** (`scrypt:salt:key`), so it can sit in an env file without `$` escaping.


## Gemma 4 E2B download and updates (2026-11-16)

- **Downloaded on first launch, not bundled** (owner choice). LiteRT-LM opens models only from a file path, so a model inside the APK would have to be copied out, using about 5.3 GB on the phone and leaving a stale copy in the APK after any update. The APK stays about 149 MB.
- **Source:** Google's `litert-community/gemma-4-E2B-it-litert-lm` repository on Hugging Face (Apache-2.0, not gated, no account). First install uses the release pinned in the app (commit `2d74ac4`, file SHA-256 `181938…`, 2,588,147,712 bytes), not whatever is newest.
- **File variant:** the generic `gemma-4-E2B-it.litertlm`, which runs on CPU and GPU. There is no MediaTek NPU build, and the GPU-only and Tensor/Qualcomm/Intel variants do not fit this phone.
- **Integrity:** the size and SHA-256 must match, and the file must load in the app's runtime (LiteRT-LM 0.12.0) before it replaces anything. For updates, the expected checksum comes from the repository metadata over HTTPS. Redirects are followed by the app itself and must stay on HTTPS.
- **Updates** (owner choice): a daily Wi-Fi check records a newer published file; downloading needs a tap on **Update**. The old model stays in use until the new one is verified, so an update briefly needs room for both.
- **Owner control:** removing the model stops automatic downloads; "Download now" undoes that. An imported model of the owner's own is never replaced by an update; "Switch to Gemma 4 E2B" is explicit.

## Project review implementation — 2026-11-16 (completed)

Owner authorized implementation of the review and asked to continue to completion.
Work stays in existing packages, with no new runtime dependencies. Physical OPPO,
live account and real-corpus checks remain the final stage.

- [x] Correct filtered/contextual Ask, integrate local evidence answers, readable sources.
- [x] Stable person identities, reviewed merge/separation, migration and backup compatibility.
- [x] Accurate action results, web contrast, date/time pickers and source-based memory actions.
- [x] Unified daily review with explicit approvals and remembered review choices.
- [x] Share text/media to HellFire behind app unlock and an explicit save action.
- [x] Threaded Inbox with full saved-history search and follow-up controls.
- [x] Recording workspace with readable queue state and transcript actions.
- [x] Guided balance reconciliation and explicit forecast assumptions.
- [x] Flexible reminders and calendar handoff.
- [x] English/Kiswahili main workflows and speech locale.
- [x] Recovery readiness and backups beyond the previous 7 GiB originals ceiling.
- [x] Regression tests, lint/build, existing feasibility docs, integration and cleanup.

Merged to `main`; the feature worktree and branch were removed. Host checks: 282
Android tests passed, 4 live-AI tests skipped; lint has no errors. Debug and signed
release APKs verify. Primary-checkout frontend typecheck/Turbopack build pass; the
local systemd frontend was restarted and its HTTPS login/CSS checked. Physical
phone, live account/model, real-corpus and hosted CI checks remain NOT TESTED.


## Everyday reliability and AI skills — 2026-11-17 (in progress)

Owner authorized the eight follow-up recommendations, editable learned AI skills
with reasons/evidence/usage history, and trusted selectable local model downloads.
No new runtime dependencies. Existing action approvals and capture-all policy stay.

- [ ] Durable drafts and resumable, duplicate-safe shared imports.
- [ ] Guided setup and a consolidated recovery screen.
- [ ] Explain suggestions; learn owner-reviewed, scoped AI skills from corrections.
- [ ] Add/edit/disable/remove skills, provenance, use counts and navigable use history.
- [ ] Searchable memory with paging and correction history.
- [ ] Deterministic finance comparisons and budget variance.
- [ ] Data-location/storage dashboard and accurate privacy messages.
- [ ] Language/accessibility completion for the affected daily flows.
- [ ] Curated compatible model catalog with trusted provenance and verified downloads.
- [ ] Regression tests, migrations/backup compatibility, lint/APKs, merge and cleanup.

## Media review: library and processing — 2026-11-17

- Analysis uses one `intelligence` job type, including legacy `ANALYZE`/`transcribe`
  rows. Audio/video transcription has one owner (ContentEngine); the legacy media
  worker delegates instead of writing a second transcript stream.
- Discovery selects items without a job rather than repeatedly scanning the newest
  forty. Bounded sequential runs drain the queue. Interrupted steps count toward
  the retry limit; explicit pauses survive checkpoint/release/failure.
- External capture dates come from DATE_TAKEN, with modified/imported date fallback.
  Missing external duplicates retire into live rows; live rows are never deleted
  during reconciliation. USER category assignments/exclusions beat AI links.
- User descriptions have a stable analysis row and survive reanalysis/search rebuild.
  Media-type/date-only searches retrieve candidates directly; refinements intersect
  text and category constraints. Scene sampling modes differ before a scene cut is
  found, carry histogram state across batches, and use deterministic segment IDs.
- Adult classifier output remains uncertain. Neutral/drawing labels are excluded;
  supported adult labels use the canonical private/local-only root. Derived scene
  thumbnails use disposable app-private cache, outside portable backups.
