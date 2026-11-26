# 06 — Everyday scenarios

Each scenario is traced through the current code (`main @ 85f5ae5`). "After" names the backlog
items in `07-roadmap-and-backlog.md` that change the outcome. Traces are from reading the code;
nothing was run on a phone. `A/` = `android/app/src/main/java/com/hellfire/ass/`.

## Summary

| # | Scenario | Works today? | Main blocker | After |
|---|---|---|---|---|
| 1 | Morning brief and day plan | Partly | Digest, not a plan; plays nothing aloud | HF-012, HF-041, HF-032 |
| 2 | Customer email needing a reply and follow-up | Partly | Owner must find it and draft by hand; follow-up needs a dated phrase | HF-001, HF-033 |
| 3 | In-person conversation to tasks | Mostly | Tasks need a parseable date; summary not searchable | HF-001, HF-008 |
| 4 | Salary arrives | Partly | Parser direction and sender trust; no income projection | HF-004, HF-006, HF-041 |
| 5 | Project blocked by an unanswered message | Partly | No project entity; waiting-on misses notification-only chats | HF-042, HF-033 |
| 6 | Meeting preparation | Weak | No link from meeting to people and messages | HF-031 |
| 7 | Invoice screenshot shared | Partly | OCR text not offered as a payment or task | HF-031 (search), backlog note below |
| 8 | Decision made months ago | Fails | No name or month resolution in search | HF-031 |
| 9 | Multi-step request | Fails | No conditional reminder; generic verbs misrouted | HF-003, HF-033, HF-014 |
| 10 | Switching devices | Not possible yet | Phone cannot reach server; web is read-mostly | HF-061, HF-062 |
| 11 | Deadline changes while offline | Works | Local-first by design | — |
| 12 | Interruption by process restart | Mostly | Command proposals are memory-only (by design); duplicate confirm possible | HF-014 |
| 13 | Unusual expense | Missing | No anomaly rule | backlog note below |
| 14 | "What should I work on next?" | Fails | Falls to keyword search | HF-003, HF-050 |
| 15 | Evening review and tomorrow's plan | Mostly | Fixed 21:00, alarm sound, plan is a list | HF-010, HF-012 |

## Traces

### 1. Morning brief and day plan
- **Today:** At the brief hour (default 07:00) `TaskScheduler.fire` posts a quiet notification with counts (`A/insights/Brief.kt:132-140`). Opening it shows:
  - today and overdue tasks;
  - bills due in 7 days, with a 30-day shortfall;
  - yesterday's money;
  - replies owed;
  - debts;
  - review count;
  - today's appointments with overlaps;
  - capture problems.

  All of this is deterministic and offline. A good base.
- **Manual steps:**
  - The owner opens and reads it.
  - Nothing slots tasks into free calendar time.
  - The brief is not spoken.
- **Failure points:**
  - A load or notify failure skips that day's brief silently (D-30, F1).
  - Blocking the reminders channel also stops the brief (D-10).
- **Unnecessary processing:** the brief decrypts up to 4,000 SMS for the reply tracker on every load (05 D4).
- **After:**
  - Quiet hours and the coordinator give the brief a single slot (HF-012, HF-032).
  - Budget and cash-flow lines are added (HF-041).
  - A "Plan my day" section proposes time blocks from free calendar gaps, for the owner to accept. This is a P2 extension of `CalendarAgendaLogic`.

### 2. Important customer email needing a reply and follow-up
- **Today:**
  1. IMAP sync captures it, and the Inbox groups it by thread.
  2. Waiting-on lists it after 4 h if it contains a request word (`People.kt:103-130`).
  3. The owner opens it and composes a reply, with optional local "improve".
  4. Approve, then the SMTP send is confirmed against the Sent folder.
- **Manual steps:**
  - Finding the email.
  - Writing the draft.
  - Setting the follow-up by hand: "remind me Friday to check" works only with a date word (A1.2).
- **Failure points:**
  - An expired app password looks like an outage (D-29).
  - A request without request words never appears in waiting-on.
- **After:**
  - Time parsing (HF-001).
  - Conditional follow-up "if no reply" (HF-033).
  - Background reply drafts for waiting-on items (P2, 03 §B4).

### 3. In-person conversation to summary, decisions and tasks
- **Today:**
  1. The owner starts the visible microphone service.
  2. Audio goes to the encrypted vault.
  3. Whisper transcribes per chunk.
  4. Each segment becomes a `media_transcript` event.
  5. `EventProcessor` runs task and finance collection on it.
  6. Summary is owner-triggered and reviewable.
- **Failure points:**
  - Spoken commitments without a parseable date ("next month", "tarehe 5") give no task (HF-001).
  - Spoken invoice numbers may be redacted as codes.
  - Summaries are not searchable.
  - Deleting the recording leaves the transcript searchable (D-19).
- **After:**
  - HF-001 widens what counts as a date.
  - HF-008 makes deletion complete.

### 4. Salary arrives
- **Today:**
  1. The bank SMS is captured and parsed as INCOME if it says "received/credited".
  2. It goes to review.
  3. The owner confirms, and the balance updates.
- **Failure points:**
  - A message with both "debited" and "credited to" is reversed (A1.4).
  - Any number can send a message that claims to be a bank (A1.6).
  - Amounts with cents are dropped (N1).
  - There is no salary projection; `Recurring.forecast` covers bills only.
- **After:**
  - Parser fixes (HF-004, HF-006).
  - Cash-flow from confirmed recurring income and bills (HF-041).

### 5. Project blocked by an unanswered communication
- **Today:**
  - There is no project entity.
  - Waiting-on tracks people, not projects.
  - WhatsApp chats seen only as notifications are not included.
- **After:**
  - HF-042 brings notification-only chats into waiting-on.
  - HF-033 lets a task wait on a reply.
  - A project entity is **not recommended yet**: tags on tasks cover the need (see 10).

### 6. Meeting approaches; context prepared
- **Today:**
  - Calendar events show in the brief with overlaps.
  - Nothing links attendees to people, messages or past notes.
- **After:** with `source_party` (HF-031), a meeting's attendees resolve to people, and the event detail can show their recent messages, open tasks and debts. This is deterministic; no model needed.

### 7. Invoice screenshot shared to HellFire
- **Today:**
  1. `ShareActivity` forwards to the unlocked app.
  2. The Share page saves the item to the media library with the "Review expense" option.
  3. OCR text becomes a `media_ocr` event.
  4. Expiry dates become renewal proposals.
- **Manual steps:** "Review expense" only saves for review; the owner must open Money and enter the amount (`A/ui/SharePage.kt:99`).
- **After:** run `FinanceParser` on the OCR text of an owner-shared image tagged as a receipt, and pre-fill a MANUAL candidate. This is a P2 extension of HF-004; it stays in review.

### 8. Question about a decision made months ago
- **Today:** this fails. See the full trace in 03 §B6:
  - Search does not resolve names to handles.
  - "September" is treated as a word.
  - Loose search keeps only the 100 newest hits.
- **After:** HF-031 adds name and month filters before full-text ranking, and a citation check for model answers.

### 9. Multi-step request
Example: "Message Juma about the 50,000 he owes me, and remind me Friday if it's still unpaid."
- **Today:**
  - The Draft command needs the `whatsapp <name>: text` shape.
  - There is no conditional reminder.
  - The owner needs two separate commands.
- **After:**
  - HF-003 parses both parts.
  - HF-033 adds the condition.
  - The draft still needs the owner's approval.
  - HF-014 makes repeat confirmation safe.

### 10. Switching between phone, tablet, desktop and web (Phase D)
- **Today:** not possible. The phone cannot reach the server (self-signed certificate on a hosts-file name). The web is read-mostly, and a second phone install would reset the web data. See 04 § Multi-device.
- **After:**
  - The web becomes the tablet and desktop client.
  - Approvals become phone-applied commands (HF-061).
  - A second capturing install is refused (HF-062).

### 11. Deadline changes while offline
Works. Tasks, alarms and the ledger are local, and alarms are re-armed on time change and reboot.

### 12. Workflow interrupted by a process restart
- **Today:**
  - Calendar changes and drafts recover to a safe state.
  - Command proposals in Ask are intentionally kept in memory, so they are lost and must be asked again.
  - A confirm racing with recomposition can create a duplicate (D-14).
- **After:** HF-014.

### 13. Unusual expense detected
- **Today:** missing. There is no rule for a new payee, an unusual amount or a duplicate charge.
- **Proposal (P2, deterministic):** flag a confirmed expense that is more than 3× the median for its category or merchant over 90 days, or a repeat of the same amount to the same merchant within 10 minutes. The flag goes through the coordinator (HF-032).

### 14. "What should I work on next?"
- **Today:** this falls to keyword search.
- **After:**
  - HF-003 routes it to a deterministic answer: the next overdue or soonest active task, plus replies owed, ordered by due time.
  - HF-050 can later explain the choice.

### 15. Evening review and tomorrow's plan
- **Today:**
  - The 21:00 review uses the alarm channel (A1.10).
  - The evening brief lists today's money, open tasks, tomorrow and replies.
  - Cash entry is parsed in both languages.
- **After:**
  - HF-010 moves the review off the alarm sound.
  - HF-012 makes the time configurable.

## User experience recommendations
- **Today view:** keep Home as the Today view. It already has "Up next" and "Needs you" (DECISIONS step 3). Add the plan suggestions from scenario 1 instead of a new screen.
- **Approval inbox:** the Review page already queues payments and tasks. Add drafts and calendar proposals to it rather than building a separate inbox.
- **Activity timeline:** `action_audit` exists. Add a read-only "What HellFire did" list over its last 100 rows. This is P2.
- **Fewer taps for common tasks:**

  | Task | Today | After |
  |---|---|---|
  | Snoozing a reminder | Open the app | One tap on the notification (HF-011) |
  | Voice reminder | Tap the mic, then tap confirm | Speak and say "ndiyo" (HF-013) |
  | Logging a provider payment | Review, then confirm | Unchanged until the owner decides on HF-040 |
