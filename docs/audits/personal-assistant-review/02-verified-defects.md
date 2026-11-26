# 02 — Verified defects

Audit target `main @ 85f5ae5`. Phase A1 verifies the 22 known findings from the 2026-11-26 source review; the end of this document lists further verified defects found during Phases B and C. Reproductions are either *executed* (regex run in a trace program) or *traced by reading*, as marked.


A/ = `android/app/src/main/java/com/hellfire/ass/`, T/ = `android/app/src/test/java/com/hellfire/ass/`.
Method: source read. The regex reproductions were **executed** in a Java 21 single-file port of the exact Kotlin patterns (`java.util.regex`, the same engine Kotlin/JVM uses): a throwaway trace program outside the repository (not committed). The test clock was Thu 2026-11-26 10:00 EAT (Fri 11:00 EAT for the weekday cases). Gradle was not run.

**Recent merges:** e6047de changed only `EventDao.inbox` (Inbox FTS) and `VaultDataSource`. It did **not** touch the Ask search fallback (#20). Since 2026-11-25 nothing in assistant/, tasks/, Money.kt or NewsAsk.kt has changed except refactors 31b381c and 9b636e1, so none of the 22 items is already fixed.

## Summary

| # | Status | Sev | Fix sketch | Regression test | Existing test? |
|---|---|---|---|---|---|
| 1 | CONFIRMED | high | Parse `h(:mm)?\s*[ap]\.?m` before the 24h regex | TaskLogicTest.pmSuffixShiftsHour | No |
| 2 | CONFIRMED | high | Relative offsets (`in N min/hours`, `baada ya saa/dakika N`); time-only → today, or tomorrow if past | AssistantCommandsTest.relativeAndTimeOnlyReminders | No |
| 3 | CONFIRMED | high | Veto news when FinanceQuestion.isQuestion; news.ask → null on 0 matches | AssistantEngineRoutingTest.taarifaYaMatumiziIsFinance | No |
| 4 | CONFIRMED | high | "credited to X"/"sent to" = outgoing; direction from the owner-account verb | FinanceTest.debitedThenCreditedToIsExpense | No |
| 5 | CONFIRMED | medium | `fees?\|charges?\|transaction cost\|makato\|gharama\|tozo\|levy`; add levy field | FinanceTest.transactionCostAndSwahiliFees | No |
| 6 | CONFIRMED (worse) | medium | Sender-ID map first; `\b` aliases; add AZAMPESA/SELCOM; own-transfer rule | FinanceTest.azamPesaIsNotMpesa / senderIdWinsOverText | No |
| 7 | CONFIRMED | high | Include overdue (`period(0,end)` like the brief); route "what do I have / nina nini / task" | AssistantEngineRoutingTest.tasksTodayIncludesOverdue | No |
| 8 | CONFIRMED | medium | Auto-listen on onShow, auto-speak, voice "ndiyo/yes" confirm, hide UUIDs, optional grounded() | HellFireSessionContractTest.autoListenAndSpeak | No |
| 9 | CONFIRMED | medium | LLM intent fallback before search; stream; move caveats | AssistantEngineTest.unknownIntentFallsBackToModel | No |
| 10 | PARTLY | medium | Separate pre-alert / due-alarm / review channels | TaskSchedulerTest.channelsPerAlertKind | No |
| 11 | CONFIRMED | medium | Done/Snooze actions, full-screen intent for due, ongoing while overdue | TaskSchedulerTest.reminderHasDoneSnoozeActions | No |
| 12 | CONFIRMED | low-med | `setAlarmClock` when exact allowed | TaskSchedulerTest.usesAlarmClockWhenExactAllowed | No |
| 13 | CONFIRMED | medium | Group + summary, setOnlyAlertOnce on repeats | TaskSchedulerTest.overdueTasksGroupedOneSound | No |
| 14 | **REFUTED** | n/a | 24h pre-alert exists (caveats below) | already covered | Yes: TaskLogicTest.kt:35-40 |
| 15 | CONFIRMED (by design) | low | Opt-in auto-post for trusted referenced templates | FinanceTest.trustedTemplateAutoPostsWhenEnabled | No |
| 16 | CONFIRMED | medium | In-memory `sbn.key → reply Action` map in listener | NotificationListenerTest.replyActionRetainedWhileLive | No |
| 17 | CONFIRMED | low-med | READ_CALL_LOG reader, or missed-call notification → task rule | CallFollowUpTest.missedCallNotificationCreatesReviewTask | No |
| 18 | CONFIRMED | low | onHandleAssist/onHandleScreenshot behind toggle | HellFireSessionContractTest.assistContextOptIn | No |
| 19 | CONFIRMED | medium (scales) | `SELECT DISTINCT category` | FinanceDaoTest.distinctCategories | No |
| 20 | CONFIRMED (not fixed by e6047de) | medium | FTS phrase MATCH instead of the decrypt scan | AssistantSearchTest.noFullScanWhenIndexHasMatches | No |
| 21 | CONFIRMED | low-med | Batch-load originals once | FinanceRepositoryTest.breakdownUsesResolvedKinds | No |
| 22 | PARTLY | medium | Explicit priority on generate; preempt long units | PerformanceTest.backgroundGenerateDoesNotClaimInteractive | Partial: PerformanceTest.kt:42-57 |

## Details

### 1. am/pm ignored: CONFIRMED, high (regex executed)
- **Evidence:** A/tasks/TaskLogic.kt:86-88 (`TaskExtractor.extract`) uses the digital regex `\b([01]?[0-9]|2[0-3]):([0-5][0-9])\b`, then SwahiliTime, then `LocalTime.of(9,0)`. There is no am/pm handling. In `5:30pm` the trailing `\b` fails between `0` and `p`, so the regex does not match at all.
- **Repro:**
  - `tomorrow 5:30pm` → 2026-11-27T09:00 (expected 17:30)
  - `tomorrow 5:30 pm` → 05:30 (expected 17:30)
  - `tomorrow 3pm` → 09:00 (expected 15:00)
- **Titles** (`stripWhen`, A/assistant/AssistantCommands.kt:130-137): `call Asha tomorrow at 5:30pm` → title `call Asha at 5:30pm`, and `5:30 pm` leaves the title `pm`.
- **Tests:** none today (TaskLogicTest covers only 24h and Swahili times). Proposed: TaskLogicTest.pmSuffixShiftsHour, AssistantCommandsTest.pmTimeStrippedFromTitle.

### 2. Relative / time-only reminders rejected: CONFIRMED, high (executed)
- **Evidence:** AssistantCommands.kt:75-78. Remind runs `requireNotNull(TaskExtractor.extract(...)) { WHEN_HINT }`, and extract returns null when there is no date word (TaskLogic.kt:65-83, `?: return null`). The owner then gets WHEN_HINT (line 70) as the reply.
- **Repro:** `…in 20 minutes`, `baada ya saa moja` and `…at 17:00` all give `null(no date)` → "Say when: today, tomorrow…". Expected: now+20 min, now+1 h, and today 17:00 (or tomorrow if already past).

### 3. News intercepts finance: CONFIRMED, high (executed)
- **Evidence:**
  - A/assistant/AssistantEngine.kt:34-36 calls `app.news.ask` before the finance branches at :48 and :56.
  - A/news/NewsAsk.kt:61 counts `habari|taarifa` as news words.
  - NewsAsk.kt:78 exempts only messages that start with "habari" and have ≤2 words.
  - A/news/NewsRepository.kt:366 returns a non-null "No saved briefing matches that.", so the query never falls through to finance.
- **Repro:** `nipe taarifa ya matumizi wiki hii` gives newsWord=true and isQuestion=true → news reply. Expected: this week's spending.
- **Tests:** NewsRuntimeTest.kt:40-41 only checks `habari yako` and `spending today`.

### 4. Credit wins over debit: CONFIRMED, high (executed)
- **Evidence:** A/finance/Money.kt:57-62 checks kinds in a fixed order (REFUND, then INCOME `received|credited|umepokea|imeingia`, then EXPENSE) against the whole message.
- **Repro:** `Your account has been debited TZS 50,000.00 and credited to JOHN DOE. Ref ABC12345` → INCOME (expected EXPENSE).
- **Tests:** FinanceTest.kt:151-162 covers only "Umelipa…" and "Reversed…".

### 5. Fee vocabulary: CONFIRMED, medium (executed)
- **Evidence:** Money.kt:63 fee pattern is `\b(?:fee|charge|ada)…`. ParsedFinance (Money.kt:23-24) has no levy field, although MASTER_SPEC.md:291 requires "fee, levy".
- **Repro:** fee=null (so 0) for:
  - M-Pesa `Total transaction cost, Tsh 300.00`
  - `Makato Tsh 300`, `Gharama TZS 200`, `Tozo TZS 100`, `Levy TZS 50`
  - **plural** `Fees TZS 100` and `Charges: TZS 100`
  
  Only `Fee: TZS 100` gives 100.

### 6. Providers / sender / own transfers: CONFIRMED, worse than claimed (executed)
- **Evidence:**
  - Money.kt:28-32 has no AZAMPESA or SELCOM.
  - Money.kt:42-45 `provider()` matches substrings of `"$sender $text"`, and the first map entry wins, so the sender ID has no priority.
  - A/processing/RuleClassifier.kt:6 does know `azam ?pesa|selcom`.
  - The sender passed in is `sender_hint` (SMS address, A/sms/SmsProviderReader.kt:19) plus the package name (A/processing/EventProcessor.kt:41-42).
  - OWN_TRANSFER is manual only (A/finance/FinanceRepository.kt:33-38, A/ui/money/MoneySheets.kt:97-100). The spec asks for it (MASTER_SPEC.md:293).
- **Repro:**
  - **`AzamPesa` → MPESA**, because "azampesa" contains "mpesa". These messages are mislabelled, not ignored.
  - `SELCOM` → null: the classifier says FINANCE_CANDIDATE, but no candidate is created.
  - Sender NMB with text mentioning `M-Pesa 0754…` → MPESA.
  - `nbcnews.com … paid TZS 5,000` → NBC.

### 7. Overdue hidden / "what do I have today?" unrouted: CONFIRMED, high (executed)
- **Evidence:**
  - AssistantEngine.kt:89-93 calls `tasks().period(range)`, and A/tasks/TaskData.kt:53 filters `dueAt >= :start`.
  - The brief uses `period(0, …)` (AssistantEngine.kt:223), so the two answers disagree.
  - Routing words are only `tasks|commitments|ahadi` (:89) plus the calendar words in A/calendar/CalendarAgenda.kt:26.
- **Repro:**
  - An ACTIVE task due yesterday → "tasks today" → "No active commitments…".
  - `what do i have today?` matches neither calendar nor tasks → keyword search.
  - Singular `any task today?` also goes to search.
- **Tests:** AssistantCommandsTest.kt:27-29 only checks that "tasks tomorrow" is not a command.

### 8. Voice session: CONFIRMED, medium (read)
- **Evidence** (A/assistant/HellFireSession.kt):
  - onShow (43-57) never starts listening; the mic only starts from the button at 137-175.
  - TTS only runs from the button at 176-182.
  - Confirmation is the button only (111-123).
  - Line 103 appends "Evidence IDs: <UUIDs>" to `lastAnswer`, so TTS reads them aloud too.
  - Line 102 calls `answer()`; only AskTab.kt:100 ever calls `grounded()`.

### 9. Regex-only intent / no tools / no streaming / caveats: CONFIRMED, medium (read)
- **Evidence:**
  - Intent comes from the regexes at AssistantCommands.kt:57-68 and the `when` chain at AssistantEngine.kt:37-95; anything unmatched becomes a search.
  - A/ai/LocalModel.kt:125 and :159 use `ConversationConfig(automaticToolCalling = false)` with a blocking `sendMessage(prompt).toString()`.
  - Cloud calls send `"stream": false` (A/ai/CloudProviders.kt:133, 170, 194).
  - Fixed caveats appear at AssistantEngine.kt:46, 77, 116, 193-196 and 233.

### 10. One alarm channel: PARTLY, medium (read)
- **Evidence:**
  - A/tasks/TaskScheduler.kt:26 and 31-33: channel `commitment-reminders`, IMPORTANCE_HIGH, TYPE_ALARM sound, USAGE_ALARM.
  - Every task alert (24h, 2h, due, overdue) is posted on it (188-191), and so is the 21:00 evening review (197-199).
  - **The morning brief is separate:** A/insights/Brief.kt:134 uses a "Morning brief" channel at IMPORTANCE_DEFAULT.

### 11. No actions / not ongoing / no full-screen: CONFIRMED, medium (read)
- **Evidence:** TaskScheduler.kt:188-191 has no `addAction`, `setOngoing` or `setFullScreenIntent`. `setAutoCancel(false)` still lets the owner swipe the alert away. The manifest has no USE_FULL_SCREEN_INTENT.
- **Also:** the due-time alert already reads "Overdue" (189: `dueAt <= now` is true at the due instant).

### 12. setExactAndAllowWhileIdle: CONFIRMED, low-medium (read)
- **Evidence:** TaskScheduler.kt:67-75. The exact path uses `setExactAndAllowWhileIdle`, with fallback to `setAndAllowWhileIdle`. MASTER_SPEC.md:487 names `setAlarmClock()`.
- **Why it matters:** while-idle alarms are throttled in Doze.

### 13. Per-task notification on each re-alert: CONFIRMED, medium (read)
- **Evidence:** TaskScheduler.kt:187-193 calls `notify(task.id, 71, …)` for every due task. There is no group, no summary and no `setOnlyAlertOnce`.
- **Effect:** N overdue tasks play the alarm sound N times per fire. The default re-alert interval is 10 min (:192, `repeatInterval` :142).

### 14. 24h pre-alert missing: REFUTED (read)
- **Spec:** MASTER_SPEC.md:482-484 (24h standard, 2h high priority, alarm at due time).
- **Code:** TaskLogic.kt:14-19 `firstAlert` returns `due-24h`. TaskScheduler.kt:91 `activate` uses it, then TaskLogic.kt:22-29 steps to 2h, then due, then repeats. Tested by TaskLogicTest.kt:35-40.
- **Caveats** (not the claimed defect):
  - The 24h alert sounds as an alarm (#10).
  - `reopen` (TaskScheduler.kt:173) uses `nextAlert` and so skips the 24h step.
  - A task activated less than 24h before its due time gets only the 2h alert.

### 15. All candidates go to REVIEW: CONFIRMED, by design, low (read)
- **Evidence:** FinanceRepository.kt:157-178 sets state REVIEW or DUPLICATE. `approve` is called only from MoneySheets.kt:130 (owner tap) and A/templates/TemplateData.kt:131 (owner-run template).
- **Spec:** consistent with MASTER_SPEC.md:292 ("…→ user review") and the approval gates at :110 and :786.

### 16. RemoteInput not retained: CONFIRMED, medium (read)
- **Evidence:** A/notifications/AndroidNotificationAdapter.kt:33-36 store text-only `action_metadata`, and :44 stores `remoteInputCount`. A/events/NotificationNormalizer.kt:19 and :96 hash the count. No Action or PendingIntent is kept anywhere.

### 17. No call log: CONFIRMED, low-medium (read)
- **Evidence:** AndroidManifest.xml lines 3-37 have no READ_CALL_LOG. READ_PHONE_STATE (line 11) is used only by TheftGuard (A/guard/GuardService.kt:109, 126).
- **Partial signal:** the dialer's missed-call notifications are captured and labelled MESSAGE (RuleClassifier.kt:16, `call` category), but nothing turns them into follow-ups.

### 18. No assist / screenshot context: CONFIRMED, low (read)
- **Evidence:** res/xml/voice_interaction.xml declares `supportsAssist="true"`, but there is no `onHandleAssist`/`onHandleScreenshot` override anywhere in src/main (grep found none).

### 19. allEntries() per finance question: CONFIRMED, medium (read)
- **Evidence:** AssistantEngine.kt:50 and :58 call `allEntries()` (A/finance/FinanceData.kt:82, `SELECT * … ORDER BY occurredAt`) only to read `.category`.

### 20. ≤2,000-event decrypt scan: CONFIRMED, not fixed (read)
- **Evidence:** AssistantEngine.kt:171-189 decrypts, JSON-parses and `indexOf`-searches batches of 100 (A/data/EventDatabase.kt:127-128) until 2,000 events are scanned or 20 hits found.
- **When it runs:** on every search, including the `else` fallback at :94, unless FTS already returned 20 hits. e6047de changed only `inbox()`.

### 21. expenseBreakdown N+1: CONFIRMED, low-medium (read)
- **Evidence:**
  - AssistantEngine.kt:247 calls `dao.entry()` per reversal.
  - `selected()` already did that lookup (FinanceRepository.kt:112), plus one per FEE row (:113).
  - `summary` repeats it (:127) and calls `dao.sources` per entry (:133).
- **Cost:** about 3 lookups per reversal per finance answer.

### 22. HeavyModelGate sharing: PARTLY, medium (read)
- **What works:** background loops check `shouldYield(DEFERRED)` between units (A/intelligence/ContentEngine.kt:97, A/intelligence/IntelligenceWork.kt:109, A/media/SpeakerModel.kt:179, A/memory/SemanticIndex.kt:205), and `LocalModel.generate` registers an INTERACTIVE waiter (LocalModel.kt:83-108).
- **Defects:**
  - (a) Every `generate` marks itself INTERACTIVE, including background callers: ContentEngine.draft (:278-281), called per 30-second transcript chunk (:164), and A/drawer/DrawerPipeline.kt:214.
  - (b) In-flight units cannot be preempted: `LocalSpeech.transcribe` holds the gate for a whole 30-second chunk (A/media/LocalSpeech.kt:49-80), an LLM generation can run 4096 tokens (LocalModel.kt:146), and an embed batch is 32 items.
  - (c) The kotlinx Mutex is FIFO, so Ask waits behind everything already queued.
  - (d) `transcribe` never calls noteWaiting.

## NEW defects

| ID | Defect | Evidence | Repro (executed) | Sev | Fix | Test |
|---|---|---|---|---|---|---|
| N1 | Non-zero cents drop the whole transaction | Money.kt:9 (`\.0{1,2}`), :66 inside `runCatching` :65-70 | `NMB: Paid TZS 12,345.50 … ref NMB12345` → null | medium | Round with a flag, don't reject | FinanceTest.centsDoNotDropTransaction |
| N2 | "ada" (school fees) captured as the fee, main amount lost | Money.kt:63-64 | `Umelipa ada TZS 500,000 kwa SHULE YA MSINGI` → fee 500,000, main null → dropped | low-med | Treat only `ada ya muamala` as a fee | FinanceTest.schoolAdaIsMainAmount |
| N3 | Withdrawals and purchases unrecognised | Money.kt:57-62 | `Umetoa Tsh 20,000 kwa wakala…` and `Umenunua muda wa maongezi Tsh 1,000` → kind null → no candidate | medium | Add withdrawal (cash transfer) and purchase (expense) kinds | FinanceTest.agentWithdrawalAndAirtime |
| N4 | Reversed incoming money becomes REFUND (+) | Money.kt:58, :16 | `…you received from JOHN has been reversed` → REFUND (balance goes up, but money left) | low | Sign opposite to the reversed direction | FinanceTest.reversedIncomeIsOutflow |
| N5 | Same-day weekday means today | TaskLogic.kt:79-81 `nextOrSame`; AssistantCommands.kt:78 | Fri 11:00 `pay rent on friday` / `next friday` → Fri 09:00 → "already passed" | medium | Roll forward when "next" or already past | AssistantCommandsTest.sameWeekdayRollsToNextWeek |
| N6 | Day parts without "saa" ignored | TaskLogic.kt:65-68, 86-88 | `call mama tonight` → no date, rejected; `leo usiku` → 09:00 today | medium | Defaults: tonight/usiku 20:00, jioni 18:00, asubuhi 08:00 | TaskLogicTest.dayPartDefaults |
| N7 | Titles keep time words | AssistantCommands.kt:130-137 | `kulipa kodi kesho saa tatu usiku` → title `kulipa kodi saa tatu usiku` | low | Strip the span the parser matched | AssistantCommandsTest.titleStripsSwahiliTime |
| N8 | Finance follow-up hijacks other intents; context shared between Ask and voice | AssistantEngine.kt:56 runs before :82/:89; A/assistant/FinanceQuestion.kt:15 `^(and\|what about\|how about\|na\|je)\b`; singleton HellFireApp.kt:63 used by AskTab.kt:67 and HellFireSession.kt:102 | After any spend question: `and tasks tomorrow`, `je, nina miadi gani kesho`, `na kazi zangu leo` → finance | medium | Not a follow-up if tasks/calendar/news words present; per-surface context | AssistantEngineRoutingTest.followUpDoesNotStealTasks |
| N9 | QueryDates substring matching | AssistantEngine.kt:272 `"kesho" in query` (also jana/wiki/mwaka) | `tasks keshokutwa` → tomorrow | low | Word-boundary regex; keshokutwa first | QueryDatesTest.keshokutwaIsTwoDays |
| N10 | Commitment regex matches nouns | TaskLogic.kt:58 `(?:ni\|u\|a\|tu\|m\|wa)ta[a-z]+` | Incoming `habari ya leo: mtandao umerudi` / `leo utalii umeongezeka` → task candidates today 09:00 | low (REVIEW noise) | Pronoun prefix + known verb roots | TaskLogicTest.nounsWithTaAreNotCommitments |

## Further verified defects found in Phases B–D

Evidence and the full trace for each row are in the document named in the last column. `A/` = `android/app/src/main/java/com/hellfire/ass/`.

| ID | Defect | Evidence | Sev | Detail |
|---|---|---|---|---|
| D-01 | "muhtasari wa matumizi" (spending summary) goes to the daily brief, because "muhtasari" is matched as a substring | `A/assistant/AssistantEngine.kt:43` | medium | 03 §B1.3 C3 |
| D-02 | "remember when I paid rent?" becomes a Remember proposal that saves the question as a note | `A/assistant/AssistantCommands.kt:58,82-88` | medium | 03 §B1.3 C4 |
| D-03 | "i received a message from Asha" is parsed as an income command and fails with an amount error | `AssistantCommands.kt:60,94-97` | low | 03 §B1.3 C5 |
| D-04 | Generic verbs "finish"/"move" trigger task Done/Reschedule ("move 50000 to savings") | `AssistantCommands.kt:66-68,115-121` | medium | 03 §B1.3 C6 |
| D-05 | "tasks and meetings tomorrow" shows calendar only; finance words beat task words | `AssistantEngine.kt` routing order 9→10→11 | low | 03 §B1.3 C8–C9 |
| D-06 | "saa 2:30 usiku" resolves to 02:30: a digital time overrides the Swahili period word | `A/tasks/TaskLogic.kt` time selection | medium | 03 §B1.4 #2 |
| D-07 | The Ask tab's own example ("remind me to pay rent on the 1st at 9:00") is rejected | `A/ui/ask/AskTab.kt:150`, `TaskLogic.kt` date rules | medium | 03 §B1.4 #4 |
| D-08 | Spoken answers read out "Evidence IDs" (SHA-256 hashes) and long footers | `A/assistant/HellFireSession.kt:113,186-191` | medium | 03 §B1.1 #7 |
| D-09 | Engine replies are English-only; with the UI in Kiswahili, TTS selects a Kiswahili voice for English text, or reports TTS unavailable | `HellFireSession.kt:140-146`, `AssistantEngine.kt` | medium | 03 §B1.1 #8 |
| D-10 | Blocking the reminders channel also stops the morning brief alarm (`arm()` returns early) | `A/tasks/TaskScheduler.kt:60-66` | low | 03 §B2.1 #11 |
| D-11 | Voice snooze says "10 minutes" but uses the task's own snooze length | `AssistantCommands.kt:37,165` | low | 03 §B2.1 #14 |
| D-12 | A Remind proposal confirmed after its due time fires an immediate overdue alarm | `AssistantCommands.kt:143-149` | low | 03 §B2.1 #15 |
| D-13 | `UseTemplate` executes the template as it is at confirm time, not the lines shown in the proposal | `AssistantEngine.kt:129-139` vs `AssistantCommands.kt:195-197` | medium | 03 §C1.2 |
| D-14 | Confirming the same owner command twice creates duplicate tasks or cash candidates (random source id) | `AssistantCommands.kt:205-212` | medium | 03 §C1.2 |
| D-15 | The router's "local" fallback is canned help text (`OfflineProvider`), not the on-device model | `A/ai/AiProvider.kt:56-69`, `A/ai/AiRouter.kt:70-78` | medium | 03 §C2.1 |
| D-16 | `InputKind` privacy routing is never used by any caller | `AiRouter.kt:66,77` | low | 03 §C2.1 |
| D-17 | After the owner reverses a ledger entry, the corrected transaction cannot be posted again from its candidate | `A/finance/FinanceRepository.kt:17-30` | medium | 03 §B3 |
| D-18 | Two notifications for one payment (with no reference) create two review items | `A/finance/FinanceData.kt:62-64` | low | 03 §B3 |
| D-19 | Deleting a recording and its transcript leaves the transcript text searchable in Ask (`source_event`, FTS and vectors remain) | `A/media/MediaVault.kt:236-254` | **high (privacy)** | 03 §B4 |
| D-20 | LUKU token digits are redacted at capture, so a purchased token can never be retrieved | `A/events/NotificationNormalizer.kt:43-44` | medium | 03 §B3 |
| D-21 | Media semantic search stops after 1,500 embeddings without telling the owner | `A/intelligence/IntelligenceSearch.kt:122` | low | 03 §B6 |
| D-22 | Search never resolves a person's name to their saved numbers or addresses | `AssistantEngine.search`, `A/insights/People.kt` | high | 03 §B6 |
| D-23 | One corrupt WhatsApp spool file returns 503 for every phone poll until it is removed by hand | `server/app/whatsapp_web.py:49-80` | **high** | 05 §2.5 R18 |
| D-24 | No in-app way to relinquish Device Owner; a lost policy snapshot leaves uninstall blocked | `A/guard/GuardRecovery.kt:77-105` | **high** | 05 §3.8 T2 |
| D-25 | Hot queries have no supporting index (`source_event` time order, processing queue, ledger, candidates, messages) | `A/data/EventDatabase.kt`, `FinanceData.kt`, `CommunicationData.kt` | medium | 05 §1.3 |
| D-26 | Widgets, Inbox and Money load whole tables (`allEntries`, `tasks().all`, `media().items`) and filter in Kotlin | 05 §1.4 U1–U6 | medium | 05 §1.4 |
| D-27 | Room Flows are created in composition without `remember`, so queries restart on every recomposition | 05 §1.5 C1 | low | 05 §1.5 |
| D-28 | Ongoing and progress notification re-posts each become a new encrypted event | `A/events/NotificationNormalizer.kt:91-111` | medium | 05 §1.6 N2 |
| D-29 | An expired Gmail app password is reported the same as a network outage | `A/communications/MailIdleService.kt:77-82` | medium | 05 §2.4 R13 |
| D-30 | Release-build failures that are only logged (brief, restore follow-up, search backfill, vault recovery, mail accounts) leave no record | `A/Diagnostics.kt:11-20` and 05 §2.7 | medium | 05 §2.7 |
| D-31 | The adult classifier opens an ONNX session for every frame, outside the model gate | `A/intelligence/AdultClassifier.kt:76-79` | low | 05 §0 |
| D-32 | README and DECISIONS describe the web as read-only, but news actions and library editing write from the web | README "Web access", DECISIONS | low (docs) | 04 §Multi-device |
| D-33 | `DEVICE_FEASIBILITY.md` still lists the cross-source finance duplicate risk as open, though `crossSourceMatch` (v13) resolves it | `docs/DEVICE_FEASIBILITY.md:972-974` | low (docs) | 03 §B3 |
