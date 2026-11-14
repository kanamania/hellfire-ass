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
