# HellFire Ass redesign: decisions log

Decisions made where `HANDOFF.md` was unclear or a screen was marked *not drawn*. Newest entries are added at the end of each step.

## General

- **Branch.** The brief says the worktree is on `ui-redesign`; the actual worktree branch is `feat/ui-redesign` (created for steps 1–2 earlier). Work stays on that branch; no new branch was created.
- **Fonts.** Inter and Space Grotesk are bundled as OFL variable fonts in `res/font` (licences in `assets/licenses`). Google downloadable fonts would add a runtime network call, which §1 forbids. Adds about 1 MB to the APK.
- **Icons.** The design's outline SVG icons are ported to `ImageVector`s (`ui/theme/Icons.kt`) instead of adding an icon library.
- **Dark theme.** Derived from the §3 tokens (not designed). Same roles, dark surfaces `#0E1013` / `#181B20`, lighter accents so text keeps at least 4.5:1.
- **No navigation library.** A saveable route stack (`Route`) plus `BackHandler` covers 5 tabs and the pushed screens. Avoids a new dependency for one use.
- **Glance.** `androidx.glance:glance-appwidget` is the only new dependency, because §8 names Jetpack Glance.

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
- **Money groups:** category, account, provider, week, day. The mockup also mentions merchant; posted entries don't store a merchant, so merchants are in Accounts › "Who you pay most", grouped by description, with "Name it" for unnamed Lipa namba numbers. Category rows drill to the category list → transaction → source; account rows drill to the statement.
- **Accounts.** Opening = sum of entries before the range; in/out exclude fees; closing = opening + in − out − fees (unit-tested). Balance lines use one scale per account, as the mockup says. The statement shows a running balance.
- **Capture "needs attention".** Failed and pending processing, plus **"not understood"**: a message the classifier marked as financial that produced no payment candidate and no ledger entry. That is the case the Fix-SMS mockup describes. Rows group by app or SMS sender (the sender is decrypted on the phone for the report only) and drill to the sender's events, then to Fix (for failed or unread) or the source record.
- **Fix a captured message.** Shows a clearly labelled guess (direction words plus the first TSh amount). "Add as income/expense" creates a normal payment candidate in Money › To confirm, never a ledger entry. "Try again" re-queues processing for that one event.
- **Tasks report.** Outcomes: on time, done late, done with time unknown, still overdue, not due yet, waiting for review. Until step 6 adds completion times, done tasks show "done, time unknown". Snooze counts come from the action audit.
- **Messages report.** Received = incoming email and WhatsApp messages. "Sent by you" = drafts the channel accepted (submitted, confirmed, delivered or read), all owner-approved. The funnel is drafted → approved → sent → confirmed.
- **Media length.** Taken from the saved length (step 6), else the last transcript timestamp, else "unknown".
- **AI usage** reads the router's metadata audit until step 6 adds a longer per-request log.
- **Export.** PDF via Android `PdfDocument` (title, scope, KPIs, a bar chart of the table's main figure, table and totals), CSV of the table, or CSV of every record, with an optional source-ID column. Saved only through the Android file picker; nothing is uploaded. CSV cells starting with `= + - @` (and not numbers) are prefixed with `'` so spreadsheets don't run them as formulas.
- **Sheets scroll.** Bottom sheets now scroll; long sheets such as payment confirmation were cut off on a phone-sized screen.
