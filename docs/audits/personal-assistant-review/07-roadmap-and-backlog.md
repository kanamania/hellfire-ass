# 07 — Roadmap and backlog

One deduplicated backlog, ordered by priority and then by dependency. Defect IDs (A1.n,
N-n, D-nn) refer to `02-verified-defects.md`. `A/` = `android/app/src/main/java/com/hellfire/ass/`.

**Status values:** `OPEN`, `DONE (<commit>)`, `OWNER DECISION` (changes a locked requirement
or adds a provider/dependency; not started without the owner), `DEVICE` (needs the phone).

**Priorities:**
- **P0:** wrong results, data exposure or a stuck pipeline in daily use.
- **P1:** foundations that the everyday improvements depend on, or high daily value.
- **P2:** valuable but less frequent.
- **P3:** optional.

**Ordering rules:**
1. Parser and routing fixes come first, because every later assistant feature sits on them.
2. Indexes come before new queries.
3. Search identity (HF-031) comes before conditional reminders and cross-source answers.
4. Commands with idempotency keys (HF-014) come before any model fallback (HF-050).

## Mapping to the existing plan in `docs/DEVICE_FEASIBILITY.md`

| Existing item | Decision |
|---|---|
| 2026-11-25 Priority 1: Calendar | Done before this audit |
| 2026-11-25 Priority 2: OxygenOS background survival | **Keep.** Software part is HF-011 (`setAlarmClock`, actionable alarms) and HF-024 (health signals). Device part stays HF-070 (`DEVICE`) |
| 2026-11-25 Priority 3: location-aware reminders | **Keep, reorder after HF-032.** Location prompts must go through the interruption coordinator. HF-064 |
| 2026-11-25 Priority 4: Telegram | **Keep as HF-060, `OWNER DECISION`.** The cheapest path (TDLib capture worker on the relay) adds a dependency and API credentials |
| 2026-11-18 phase 1: native backup round trip | Keep, P2 (HF-071, emulator) |
| 2026-11-18 phase 2: process death during recording | Keep, P2 (HF-071) |
| 2026-11-18 phase 3: video blur, tracking, voice pitch | **Retire to "not now"** (see 10). Rarely used; large |
| 2026-11-18 phase 4: bilingual datasets and replays | **Promote to P1 as HF-034** |
| 2026-11-18 phase 5: UI coverage and baseline profiles | Keep, P2 (HF-072) |
| 2026-11-18 phase 6: hosted CI | Keep, P2 (HF-073); needs the repository host arranged |

## Backlog

| ID | Pri | Title | Defects | Cx | Status |
|---|---|---|---|---|---|
| HF-001 | P0 | Time understanding: am/pm, relative, time-only, day parts, weekday rollover, Swahili period, title stripping | A1.1, A1.2, N5, N6, N7, D-06, D-07 | M | OPEN |
| HF-002 | P0 | Commitment detection without noun false positives | N10 | S | OPEN |
| HF-003 | P0 | Ask routing: news, finance, tasks, follow-ups, word boundaries, generic verbs | A1.3, A1.7, N8, N9, D-01–D-05 | M | OPEN |
| HF-004 | P0 | Finance direction, fee words, cents, school fees, withdrawals, airtime, reversed income | A1.4, A1.5, N1–N4 | M | OPEN |
| HF-005 | P0 | Providers: AzamPesa, Selcom, whole-word aliases | A1.6 | S | OPEN |
| HF-006 | P0 | Sender-ID priority for provider detection | A1.6 | M | OPEN |
| HF-007 | P0 | Relay: quarantine unreadable WhatsApp spool files | D-23 | S | OPEN |
| HF-008 | P0 | Deleting a recording purges its transcript events, search rows and vectors | D-19 | S | OPEN |
| HF-009 | P0 | TheftGuard: owner-gated Device Owner relinquish | D-24 | S | OPEN |
| HF-010 | P0 | Reminder channels: pre-alerts and review off the alarm channel; one sound for many overdue tasks | A1.10, A1.13 | S | OPEN |
| HF-011 | P1 | Reminder actions (Done, Snooze), open the exact task, `setAlarmClock` when exact is allowed | A1.11, A1.12 | M | OPEN |
| HF-012 | P1 | Configurable evening review time; quiet hours for non-alarm notices | — | S | OPEN |
| HF-013 | P1 | Voice session: listen on open, speak a short answer without source IDs | A1.8, D-08, D-09 | M | OPEN |
| HF-014 | P1 | Owner commands: idempotent execution, re-validation, template snapshot | D-11, D-12, D-13, D-14 | S | OPEN |
| HF-020 | P1 | Indexes for hot queries (schema v22) | D-25 | S | OPEN |
| HF-021 | P1 | Coalesce ongoing and progress notification re-posts | D-28 | M | OPEN |
| HF-022 | P1 | Separate mail authentication failures from network failures | D-29 | S | OPEN |
| HF-023 | P1 | Bounded queries for widgets, Inbox, Money, Ask categories; remembered Flows | A1.19, A1.21, D-26, D-27 | M | OPEN |
| HF-024 | P1 | Record release-build failures; alerts for brief failure and cloud key failure | D-30, D-10 | S | OPEN |
| HF-025 | P1 | Ask search uses the full-text index, not the decrypt scan | A1.20 | S | OPEN |
| HF-030 | P1 | Ledger: corrected re-post after reversal | D-17 | M | OPEN |
| HF-031 | P1 | Person- and month-aware search | D-22 | L | OPEN |
| HF-032 | P1 | Interruption coordinator: quiet hours, dedup, budget, "not useful" | — | M | OPEN |
| HF-033 | P1 | Conditional reminders ("remind me Friday if Juma hasn't paid") | — | M | OPEN |
| HF-034 | P1 | Scenario replay evaluation harness | — | M | OPEN |
| HF-040 | P2 | Opt-in auto-post for trusted provider formats | A1.15 | M | OWNER DECISION |
| HF-041 | P2 | Budget warnings and 30-day cash-flow in the brief | — | M | OPEN |
| HF-042 | P2 | Waiting-on includes notification-only chats | — | S | OPEN |
| HF-043 | P2 | Reply through a live notification's reply action | A1.16 | M | OPEN |
| HF-044 | P2 | Missed-call follow-up from dialer notifications | A1.17 | S | OPEN |
| HF-045 | P2 | Quick Settings tile and text-selection action | — | S | OPEN |
| HF-046 | P2 | LUKU token kept in an encrypted field | D-20 | S | OPEN |
| HF-050 | P2 | Local-model fallback that emits existing `OwnerCommand` text | A1.9 | M | OPEN |
| HF-051 | P2 | Router: real on-device provider, `InputKind`, citation check | D-15, D-16 | M | OPEN |
| HF-052 | P2 | Interactive inference priority for background `generate` | A1.22 | S | OPEN |
| HF-060 | P2 | Telegram capture | — | L | OWNER DECISION |
| HF-061 | P2 | Web approvals through a phone-applied command channel | — | L | OPEN |
| HF-062 | P2 | Refuse a second phone resetting the web dataset | — | S | OPEN |
| HF-063 | P2 | Per-record deletion and retention for events and news | — | M | OPEN |
| HF-064 | P2 | Location-aware reminders (owner Priority 3) | — | L | OPEN |
| HF-065 | P2 | Assist context (screen text) behind an owner toggle | A1.18 | M | OPEN |
| HF-066 | P2 | Microsoft 365 mail via OAuth2 (XOAUTH2) | — | M | OWNER DECISION |
| HF-070 | P1 | OxygenOS background survival verification | — | — | DEVICE |
| HF-071 | P2 | Emulator backup round trip and process death during recording | — | M | OPEN |
| HF-072 | P2 | UI coverage and baseline profiles | — | M | OPEN |
| HF-073 | P2 | Hosted CI run | — | S | OWNER DECISION |
| HF-074 | P3 | Adult classifier session reuse | D-31 | S | OPEN |
| HF-075 | P3 | Relay TLS proxy config, web CSP/HSTS, async scrypt, systemd hardening | — | M | OPEN |

## Item details

Each item lists:
- problem → solution;
- code to reuse;
- files;
- acceptance criteria;
- tests;
- risks;
- migration.

### HF-001 Time understanding (P0, M)
- **Problem:** "5:30pm", "in 20 minutes", "baada ya saa moja", "at 17:00", "tonight", "leo usiku", "next friday" and "the 1st" are misread or rejected. Swahili period words lose to a digital time.
- **Solution:** extend `TaskExtractor`:
  - am/pm before the 24 h pattern;
  - a period word (asubuhi/jioni/usiku) shifts a 1–12 digital hour;
  - relative offsets (`in N minutes/hours`, `baada ya dakika/saa N`, Swahili number words);
  - time-only means today, or tomorrow if passed;
  - day-part defaults (tonight/usiku 20:00, jioni 18:00, asubuhi 08:00);
  - "next <weekday>" and a passed same-day weekday roll a week;
  - ordinal "1st/tarehe N" means the next such day of the month.
  
  `stripWhen` removes every matched time phrase.
- **Reuse:** `TaskExtractor`, `SwahiliTime`, `AssistantCommands.stripWhen`.
- **Files:** `A/tasks/TaskLogic.kt`, `A/assistant/AssistantCommands.kt`.
- **Acceptance:** every example in A1.1, A1.2, N5–N7, D-06 and D-07 gives the expected time and title.
- **Tests:** `TaskLogicTest` (am/pm, day parts, Swahili period), `AssistantCommandsTest` (relative, time-only, rollover, titles).
- **Risk:** captured messages ("nitakutumia kesho") must keep their existing behaviour.
- **Migration:** none.

### HF-002 Commitment false positives (P0, S)
- **Problem:** "umerudi" and "umeongezeka" in news text become tasks.
- **Solution:** require a subject-prefixed future or promise form (`nita-`, `tuta-`, `uta-`, `ata-` + verb), or the English promise words. Drop the loose `-ta-` infix match.
- **Files:** `TaskLogic.kt:58`.
- **Tests:** `TaskLogicTest.nounsWithTaAreNotCommitments`; the existing commitment tests still pass.

### HF-003 Ask routing (P0, M)
- **Solution:**
  - (a) `news.ask` returns null when nothing matches, so routing continues.
  - (b) "taarifa/habari" count as news only without a finance, task or calendar word.
  - (c) brief only on the whole words "brief/muhtasari" with no finance word.
  - (d) task listing includes overdue items and matches "what do I have", "nina nini", "kazi", "majukumu", "reminders" and singular "task".
  - (e) finance follow-up requires a finance word or a short follow-up shape, and never takes task, calendar or news words. Keep the context per surface and expire it after 10 minutes.
  - (f) `QueryDates` uses word boundaries and checks keshokutwa first.
  - (g) remember, received, finish and move commands need their full command shape. "remember when…?" questions and "i received a message" fall through.
- **Files:** `A/assistant/AssistantEngine.kt`, `AssistantCommands.kt`, `A/news/NewsAsk.kt`, `A/news/NewsRepository.kt`.
- **Tests:** a new `AssistantRoutingTest` driving `AssistantEngine.route`-level decisions (no database), plus parser tests.
- **Risk:** news phrasing regressions; existing `NewsRuntimeTest` cases must pass.

### HF-004 Finance parsing (P0, M)
- **Solution:**
  - Owner-side verbs decide direction: "debited/paid/sent/umelipa/umetuma" outrank a later "credited to X". "credited to <name>" counts as outgoing.
  - Fee words: `fees?|charges?|transaction cost|makato|gharama|tozo|levy|ada ya muamala`. Plain "ada" is a fee only with "ya muamala" or after the main amount.
  - Cents round half-up to whole shillings and are flagged in evidence, never dropped.
  - Withdrawals (umetoa/withdrawn) become EXPENSE with category Cash.
  - Airtime, bundle and LUKU purchases (umenunua/bought) become EXPENSE with a matching category.
  - "you received … reversed" gives an outflow.
- **Files:** `A/finance/Money.kt`.
- **Tests:** a `FinanceTest` case per defect; `golden.json` unchanged.
- **Risk:** the review queue still catches mistakes; nothing auto-posts.

### HF-005 Providers (P0, S)
- **Solution:**
  - Add AZAMPESA and SELCOM.
  - Use whole-word aliases, checking longer aliases first, so "azampesa" no longer matches "mpesa" and "nbcnews" no longer matches "nbc".
- **Files:** `Money.kt`.
- **Tests:** `FinanceTest.azamPesaIsNotMpesa`, `selcomCreatesCandidate`.

### HF-006 Sender-ID priority (P0, M)
- **Solution:**
  - A known sender-ID map (e.g. `M-PESA`, `AirtelMoney`, `MIXX BY YAS`, `HaloPesa`, `AzamPesa`, `SELCOM`, `CRDB BANK`, `NMB`, `NBC`) decides the provider when the SMS address matches.
  - Text aliases are the fallback only for non-SMS sources and unknown senders.
  - SMS from an ordinary phone number that claims a provider gets the evidence note "sender not a known provider ID".
  - The exact sender strings are an **unverified assumption** until checked on the phone, so the map must be owner-editable later and never blocks review.
- **Files:** `Money.kt`, `A/finance/FinanceRepository.kt`.
- **Tests:** `FinanceTest.senderIdWinsOverText`, `personalNumberClaimingMpesaIsFlagged`.

### HF-007 Relay spool quarantine (P0, S)
- **Solution:** in `drain_spool`, move a file that fails to decrypt or parse to `rejected/`, count it in status, and keep draining.
- **Files:** `server/app/whatsapp_web.py`.
- **Tests:** pytest with a corrupt file sorted before a valid one.

### HF-008 Transcript purge on delete (P0, S)
- **Solution:** when deleting a media item, also delete its `media_transcript`, `media_ocr` and `media_labels` source events. That covers their FTS rows, semantic vectors and processing rows, provided no ledger evidence references them; any referenced event is redacted instead of deleted.
- **Files:** `A/media/MediaVault.kt`, `A/data/EventDatabase.kt` DAO.
- **Tests:** a host test showing that searching for the transcript text after deletion returns nothing.

### HF-009 Device Owner relinquish (P0, S)
- **Solution:** a biometric-gated "Remove TheftGuard device control" action. It disarms, restores the saved policies (or the safe defaults if the snapshot is missing), then calls `clearDeviceOwnerApp`. Document it in the README.
- **Files:** `A/guard/GuardRecovery.kt`, `A/guard/GuardPage.kt`.
- **Tests:** a unit test for the restore defaults when the snapshot is missing.
- **Device:** NOT TESTED.

### HF-010 Reminder channels (P0, S)
- **Solution:**
  - Channel `task-heads-up` (DEFAULT importance, normal sound) for the 24 h and 2 h alerts.
  - `commitment-reminders` (alarm) only for the due and overdue alerts.
  - `daily-review` (DEFAULT) for the evening review.
  - Overdue alerts share a group with a summary and `setOnlyAlertOnce` on repeats.
- **Files:** `A/tasks/TaskScheduler.kt`, `A/tasks/TaskLogic.kt`.
- **Tests:** a channel-selection unit test on a pure function.
- **Migration:** new channels, created on first use.

### HF-011 Reminder actions and alarm API (P1, M)
- **Solution:**
  - Done and Snooze actions go to the existing receiver, which calls `TaskScheduler.action`.
  - The content intent opens `hellfire://task/<id>`.
  - Use `setAlarmClock` when exact alarms are allowed.
- **Tests:** a pure-function test of the action intent extras.
- **Device:** heads-up and DND behaviour NOT TESTED.

### HF-012 Review time and quiet hours (P1, S)
- **Solution:**
  - An owner setting for the review hour (default 21).
  - Quiet hours (default 22:00–06:30) defer the brief, review and heads-up notices. Due and overdue owner alarms are never deferred (locked requirement 9).

### HF-013 Voice session (P1, M)
- **Solution:**
  - Start listening when the session is shown (unless locked).
  - Speak the answer automatically, using a short spoken form with no source IDs or footers.
  - Accept a spoken "yes/ndiyo/confirm" or "no/hapana/cancel" for a pending proposal; the tap stays available.

### HF-014 Owner command safety (P1, S)
- **Solution:**
  - The source id is derived from the proposal (`sha256(utterance|proposedAt)`), so a second confirm is a no-op.
  - Re-check that the due time is in the future at execute.
  - `UseTemplate` executes the snapshot that was shown.
  - The snooze text states the actual minutes.

### HF-020 Indexes (P1, S)
- **Solution:** schema v22 migration with only `CREATE INDEX IF NOT EXISTS` on:
  - `source_event(receivedAt, id)`, `source_event(source, occurredAt)`;
  - `event_processing(state)`;
  - `ledger_entry(occurredAt)`;
  - `finance_candidate(state, occurredAt)`;
  - `communication_message(occurredAt)`.
- **Tests:** `DatabaseMigrationTest` to v22, and the exported schema.
- **Risk:** index size; measure on device.

### HF-021 to HF-025
See `05-performance-reliability-security.md` §4 for the evidence and verification steps of each.

### HF-030 Re-post after reversal (P1, M)
- **Solution:**
  - Reversing an entry that came from a candidate returns the candidate to REVIEW.
  - The new post uses a fresh dedupe key that includes the reversal id.
  - The reversal is dated at the original `occurredAt`, so period totals net to zero in the original month.

### HF-031 Person- and month-aware search (P1, L)
- **Solution:**
  - A `source_party(sourceId, handleKey, role)` table, filled at capture and backfilled, plus an index.
  - Search resolves names through `person_contact` and `person_alias` to handle keys.
  - Month names in English and Kiswahili become date filters.
  - Grounded answers show only the sources the model actually cited.
- **Acceptance:** the "Asha / invoice / September" trace in 03 §B6 returns her messages from September.

### HF-032 Interruption coordinator (P1, M)
- **Solution:** one `Interruptions` gate plus a `notice_log` table, as described in 03 §B5.3.
  - News, capture gaps, the brief and future nudges go through it.
  - Owner-activated task alarms bypass the budget.

### HF-033 Conditional reminders (P1, M)
- **Solution:**
  - A nullable `condition` on `assistant_task` (`DEBT_UNPAID:<person>`, `NO_REPLY:<handle>`).
  - It is evaluated at fire time with the existing `Debts` and `Waiting` code; a met condition closes the task with the audit entry `CONDITION_MET`.
  - Voice grammar: "remind me <when> if <person> hasn't paid / replied".

### HF-034 Scenario replay harness (P1, M)
See `08-evaluation-plan.md`.

### P2 and P3
Each P2/P3 item has its problem and solution in the matching section of 03, 04 or 05. Owner-decision items are not started without approval.
