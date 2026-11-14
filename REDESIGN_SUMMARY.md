# HellFire Ass redesign: summary

Branch `feat/ui-redesign`, worktree `.worktrees/feat/ui-redesign`. Nothing is pushed or merged into main. Every decision is logged in [DECISIONS.md](DECISIONS.md); device evidence is in `docs/DEVICE_FEASIBILITY.md` → "UI redesign checkpoint".

**State at handover:**
- Debug build passes.
- 192 unit tests: 188 passed, 4 opt-in live-AI tests skipped, 0 failures.
- Lint: the earlier redesign passed with 0 errors; the Money follow-up lint run is incomplete under host memory pressure.
- Checked on the Android emulator (Pixel 6 profile, API 34). **Not run on your OPPO**: no device was connected.

## What was built

| Commit | Step | What you get |
|---|---|---|
| `4c3857d`, `6d62636` | 1 · Theme and components | §3 colour tokens (light + derived dark), bundled Inter / Space Grotesk, outline icons from the designs, and the shared components: cards, list rows, action cards, pills, segmented control, chips, KPI tiles, evidence card, bottom sheet, composer, Canvas charts and a sortable table with a pinned first column |
| `9b986cf` | 2 · Navigation | Five tabs with the raised Ask button, pushed screens with back arrow, `hellfire://` deep links, first-run Setup |
| `de62265` | 3 · Five tabs | **Home**: greeting, Ask bar, privacy line, "Needs you", today's confirmed spent/received, Up next. **Money**: balance card, To confirm cards with a review sheet, Recent, Add cash, Accounts and Transaction pages. **Tasks**: Today/Upcoming/Done, proposal cards, checklist with Undo, quick add. **Inbox**: one feed with channel chips and "what the app did" pills, draft approval, reply sheet. **Ask**: one chat for offline answers, command confirm cards and cloud routes, with voice |
| `bdce728` | 4 · Setup and Settings | Six live permission rows with progress; Settings hub with live subtitles; Permissions and capture (status, processing, test tools); restyled Cloud AI, Monthly AI budget, on-device models, Email, WhatsApp, Reminders, Memory and proposals, Recordings, Backup, This device |
| `9e82178` | 5 · Reports | Hub plus Money (category → payments → transaction → source SMS), Accounts and merchants (statement with running balance), Capture with Fix SMS, Tasks, Messages, Media, AI usage. Each has filter and export sheets, a remembered Chart/Table view, KPIs and drill-down. Export: PDF, CSV of the table, or CSV of every record, saved only through the file picker |
| `e1a62be` | 6 · Room v10 | Task completion time (backfilled from your existing "done" audit records), recording length, AI usage log (metadata only); backups stay compatible |
| `f3d5ebf` | 7 · Widgets + Room v11 | 13 Glance widgets: Daily briefing, Capture health, Ask / Cash / Note shortcuts, Money today, Promises, Ask HellFire, Record, Wallets, Replies waiting, Nightly review, Budget. v11 adds promise direction and amount, bills with monthly repeat, category budgets, and last SMS balance per provider |
| `5e14216` | Your extra requests | Add-account tile, opening balance and "Set balance" (with the SMS balance as a suggestion); Money calendar (week/month/year); spending line graph; category and account pies |

Also from your mid-run requests (inside `f3d5ebf`): real widget previews in the picker, widgets that fill the space when made large, an in-app "Add to home screen", and merchant naming as a type-ahead of your saved merchants.

**Ground rules kept:**
- No new network calls; fonts are bundled.
- Cloud AI is off by default and still gated by consent, redaction and the cap.
- Every payment, draft, task proposal and balance needs your explicit tap.
- Ledger totals use confirmed entries only.
- Widgets never send anything.

## Main decisions (details in DECISIONS.md)

- **No navigation library.** A small saved route stack. The only new dependency is Glance (and its Material 3 theme helper), as §8 asks.
- **Nothing was removed.** Controls that no longer fit a tab moved:
  - accounts → Money › Accounts
  - model extraction → On-device models
  - reminder settings → Settings › Reminders
  - mail and gateway setup → Settings › Email / WhatsApp
  - memory and JSON proposals → Settings › Memory and proposals
- **Confirmations:**
  - Payments are confirmed in a review sheet that shows the source SMS.
  - Sends keep a confirmation dialog, because they can't be undone.
  - "Add task" on a proposal card schedules it directly.
  - Ticking a task done gives a 6-second Undo.
- **Posted payments: "Change category" edits only the label** (audited). Amounts still need a reversal.
- **Balances are ledger entries.** A stated balance posts one confirmed, evidenced BALANCE entry for the difference, never counted as income or spending.
- **Money "Expenses" excludes fees** (fees are a separate KPI); own transfers are left out. This matches the mockup's arithmetic.
- **"Not understood" SMS** means a message that looked financial but produced nothing to confirm. It feeds Capture "needs attention" and Fix SMS, whose fixes create normal candidates to confirm.
- **Backups:**
  - New archives are schema 11.
  - Archives from schema 8 and 10 still restore.
  - Unknown columns are still refused.
- **Widgets:**
  - Refresh is driven by database changes, not a timer.
  - Amounts and previews are hidden by default, with per-widget "show" switches and a global "Hide all amounts". Wallets need a tap and hide again after a minute.
  - Record asks for consent once per session (an hour) and always uses the visible foreground service.

## Not finished, and why

- **No physical device testing.** No phone was connected, so nothing is verified on ColorOS. See the checklist below.
- **Large widget layouts not checked on screen.** They are written to fill the space at any size, but I couldn't resize widgets by script on the emulator launcher, so I only checked default sizes.
- **Widget previews need Android 12+.** Android 10–11 launchers still show the app icon.
- **The Record widget can't show a ticking clock** (a Glance limitation). It shows "Recording since HH:mm" instead.
- **No merchant grouping in the Money report.** Posted entries don't store a merchant, so merchants appear under Accounts › "Who you pay most" instead.
- **Balance and money parsing is untested on real messages.** It was checked on synthetic M-Pesa / Airtel texts only.
- **Some lengths are unknown.** Folder-imported recordings have no saved length; the report uses the transcript end or shows "unknown".
- **AI usage history** starts at the upgrade, plus the last 100 requests copied from the old audit.
- **Dark theme** is derived from the tokens, not designed, and was only spot-checked.
- **"Not drawn" screens** (Cloud AI provider editor, Recordings, on-device models) are grouped into cards but still use some older form controls.
- **English only.** Text is hard-coded in English, as before.
- **Not merged.** `docs/DEVICE_FEASIBILITY.md` is updated on this branch only.

## Test by hand on the OPPO

Install the debug APK **over your current install**, so the real database migrates, then work through these.

**Upgrade and data**
- [ ] The app opens without "Encrypted storage is unavailable"; old payments, tasks, messages and recordings are all there.
- [ ] Money totals match what you had before; old "done" tasks show on-time or late where possible.
- [ ] Export an encrypted backup, then restore it on a spare or cleared install (restore needs empty data).

**Permissions (Settings › Permissions and capture)**
- [ ] Each of the six rows turns green after "Turn on" and stays green after reopening the app.
- [ ] Notifications: a real M-Pesa / bank notification reaches Inbox and Money › To confirm.
- [ ] SMS: a new M-Pesa SMS appears in Inbox and To confirm; its "Salio" balance shows on the Wallets widget.
- [ ] On-time reminders: exact alarms allowed; a test task rings at the set minute.

**Assistant gesture**
- [ ] With HellFire as the digital assistant, the ColorOS gesture / power-button hold opens the Ask tab.
- [ ] "Test the assistant session" in Settings › This device works; the mic button in Ask starts voice input (note whether it works offline).

**Recording**
- [ ] Settings › Recordings: start and stop conversation monitoring; the notification is visible the whole time.
- [ ] Record widget: the first tap shows the consent sheet; Start begins recording with the notification; tap again stops it; the widget shows "Saved, transcript queued".
- [ ] A second widget start within the hour starts without the consent sheet; after an hour, or after force-stopping the app, it asks again.
- [ ] An excluded person or place (Settings › Recordings) blocks a widget recording.
- [ ] A phone call or another app using the microphone mid-recording: the recording stops cleanly and is saved as interrupted.
- [ ] After a reboot, recording does **not** restart by itself.

**Widgets**
- [ ] Add each widget from the ColorOS widget picker (or Settings › Home screen widgets › Add); previews look like the widget.
- [ ] Amounts show ••••• until you allow them in Settings › Home screen widgets; "Hide all amounts" hides everything again.
- [ ] Wallets: "Tap to reveal" shows balances and hides them again after about a minute.
- [ ] Resize Daily briefing, Money today, Promises and Budget to full screen: they fill with more rows and sections, without clipping.
- [ ] Confirm a payment or finish a task in the app: the widgets update within a few seconds.
- [ ] At 21:00 the Daily briefing switches to the evening layout (needs the nightly review switched on).
- [ ] Tapping a promise checkbox marks it done (check the task in the app); nothing ever sends from a widget.
- [ ] Widgets after a reboot, before the first unlock: they show "Open HellFire to load this" instead of crashing.

**Battery and background**
- [ ] Turn off battery optimisation for HellFire and allow auto-launch / background activity in ColorOS settings.
- [ ] Leave the phone overnight: next morning, check notifications and SMS were still captured (Reports › Capture shows events per day) and that reminders fired.
- [ ] Note any widget that stops updating overnight.

## Money entry follow-up — 2026-11-14

- Cash spending has date/time pickers (East Africa Time), category and account selectors.
  These selections persist on each review item and prefill the confirmation sheet.
- Accounts are created by name and optional balance. Provider controls, Money labels
  and Money report grouping are removed. Receipt source identifiers remain internal
  for duplicate detection and merchant evidence; they do not restrict account selection.
- Room v12 preserves old records and adds optional review selections. Older backups
  still restore; new backups retain the choices. Phone interaction checks remain NOT TESTED.

Verification: 192 tests, 188 passed, 4 opt-in live AI tests skipped, no failures.
Debug APK assembly passed. Lint is NOT COMPLETED: stopped after prolonged analysis
under host memory pressure (about 600 MiB available RAM, 35 GiB swap in use).
Rerun `./gradlew :app:lintDebug --max-workers=1 --console=plain` when the host has capacity.
APK SHA-256: `dc852ff89217f8ef380e84c736e87338e42954c5dd583950f99b4aa8d40c2c43`.
