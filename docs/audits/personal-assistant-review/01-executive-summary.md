# 01 — Executive summary

**Scope:** source-level audit of HellFire Ass at `main @ 85f5ae5` (2026-11-26), covering the Android app, the relay, the WhatsApp worker and the web mirror.

**Not used:** no physical device and no live accounts. Where a statement depends on the phone, it says so.

## Assessment

HellFire is a careful, privacy-first **capture and record system** with a reactive assistant on top.

**What is strong:**
- Capture, the integer ledger, the approval state machines and encryption are well built and well tested.

**What is weak:**
- **The layer the owner actually talks to.** Ask and voice understand requests through ordered regexes, and misread them often:
  - "5:30pm" becomes 05:30;
  - "nipe taarifa ya matumizi" gets a news answer;
  - "debited … credited to JOHN" is income;
  - "in 20 minutes" is rejected.
- **Proactive output is uncoordinated.** Every reminder, including the 24-hour heads-up and the evening review, plays the alarm sound.
- **Cross-source questions** ("what did Asha and I agree in September?") cannot be answered, because search does not know who people are or what a month is.

These are mostly fixable without new architecture: the fixes are parser, routing and query changes on top of components that should be kept.

### Maturity ladder

Highest rung reached: **3, reactive assistant (partial)**.

| Rung | Reached? | Evidence / blocker |
|---|---|---|
| 1 Information collection | Yes | All notification, SMS, mail, WhatsApp-relay, recording and media capture with provenance |
| 2 Searchable knowledge | Partly | FTS4 and semantic index exist; no person or date resolution (D-22) |
| 3 Reactive assistant | Partly | Deterministic answers for money, tasks and calendar; routing defects (A1.3, A1.7) and time parsing (A1.1–A1.2) break everyday requests |
| 4 Proactive assistant | Partly | Morning/evening briefs, escalating reminders, waiting-on, capture alerts; no coordinator, budget or feedback |
| 5 Supervised agent | No | Typed proposals and approval gates exist; no multi-step or conditional actions |
| 6 Multi-device system | No | Phone cannot reach the server; web is read-mostly |

### Area ratings (0–4)

Scale: 0 absent · 1 stored or displayed only · 2 reactive · 3 proactive with an approval gate · 4 reliable proactive, measured on an evaluation set.

| Area | Level | One-line evidence |
|---|---|---|
| Everyday productivity | 2 | Brief, reminders and review work; common voice and Ask phrasings fail (02 A1.1–A1.7) |
| Proactive intelligence | 3 | Briefs, escalation, waiting-on, capture gaps are unprompted; no coordination (03 §B5) |
| Autonomous execution | 2 | Typed commands with confirmation; no conditional or multi-step actions (03 §C1) |
| Memory | 2 | Owner notes, provenance, FTS, semantic index; no people or date resolution (03 §B6) |
| Context awareness | 1 | No location (except TheftGuard), no assist context, finance follow-up context only |
| Communications | 2 | Mail, WhatsApp capture, SMS, waiting-on, approved sends; no notification replies (03 §B4) |
| Finance | 3 | Deterministic ledger, review queue, budgets, bill forecast, debts; parser gaps (A1.4–A1.6) |
| AI orchestration | 2 | Consent/budget/redaction router; local fallback is canned text (D-15) |
| Performance | 2 | Earlier audit fixes landed; missing indexes and whole-table loads remain (05 §1) |
| Reliability | 2 | Restore receivers, journaled restore, nonce sends; silent release-build failures (05 §2.7) |
| Security | 3 | Keystore/SQLCipher, sealed relay, no-backup flags; Device Owner relinquish missing (D-24) |
| UX | 2 | Clear redesigned screens; voice flow needs taps and reads hashes aloud (A1.8) |
| Multi-device | 1 | Read-mostly web mirror, never run end to end with the phone |
| Maintainability | 3 | Exported schemas, 551 unit tests, clear packages; breadth outweighs core (10 §E1) |
| Testing / evaluation | 1 | Strong unit tests; no intelligence-level evaluation (08) |

These are evidence-based levels, not an average or a score.

## Checks run (2026-11-26, host)

| Check | Command | Result |
|---|---|---|
| Android unit tests, lint, debug build | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --max-workers=1` (from `android/`) | PASS: 551 tests, 0 failures, 5 skipped; lint 0 errors, 423 warnings; `HellFire-Ass-0.10.0-code10-debug.apk` |
| Relay | `pytest -q` (from `server/`, fresh venv with `requirements-dev.txt`) | PASS: 40 tests |
| WhatsApp worker | `npm test` (from `server/whatsapp-web/`) | PASS: 6/6 |
| Web API | `npm test` (from `web/api/`, local Postgres) | PASS: 31/31 |
| npm audit (production deps) | `npm audit --omit=dev` | web/api 0, web/frontend 0, whatsapp-web 6 moderate (transitive glob/minimatch) |
| Python dependency audit | `pip-audit -r requirements.txt` | No known vulnerabilities |

No device, emulator or live-service checks were run for this audit.

## Strongest capabilities
1. Capture with provider-keyed identity and OTP redaction before storage.
2. Deterministic integer ledger with evidence, audit, reversals and cross-source duplicate linking.
3. Approval state machines (nonce, single-use claim, read-back, recovery to UNKNOWN) for calendar writes and sends.
4. Deterministic morning and evening briefs that combine tasks, bills, debts, replies and calendar.
5. Privacy defaults: cloud off by default, sealed relay events, encrypted library sync, no-backup flags.

## Critical weaknesses
1. Request understanding: time, routing and finance parsing defects in daily phrases (02 A1.1–A1.7, N1–N10).
2. Deleting a recording leaves its transcript searchable (D-19).
3. One corrupt WhatsApp spool file stops all WhatsApp delivery (D-23).
4. No way to give up Device Owner from the app (D-24).
5. Every reminder uses the alarm sound; there are no quiet hours and no actions on reminders (A1.10–A1.13).
6. Search cannot resolve people or months (D-22).
7. Release-build failures are invisible (D-30).

## Top 15 recommendations
In order; IDs refer to `07-roadmap-and-backlog.md`.

1. HF-001 Time understanding (am/pm, relative, time-only, day parts, Swahili periods)
2. HF-003 Ask routing fixes (news intercept, overdue tasks, follow-up leak)
3. HF-004/HF-005/HF-006 Finance parsing, providers and sender-ID priority
4. HF-008 Purge transcripts on recording delete
5. HF-007 Relay spool quarantine
6. HF-009 Device Owner relinquish
7. HF-010 Reminder channels and grouping
8. HF-014 Idempotent owner commands
9. HF-011 Reminder actions and `setAlarmClock`
10. HF-013 Voice session that listens and speaks short answers
11. HF-020/HF-023 Indexes and bounded queries
12. HF-024 Visible failures in release builds
13. HF-031 Person- and month-aware search
14. HF-032 Interruption coordinator
15. HF-034 Scenario replay evaluation

## Key architecture decisions
- **Keep the phone authoritative.** Web, tablet and desktop send commands that the phone validates and applies; no multi-master sync for new data (04 D6).
- **No workflow engine and no graph store.** Extend `OwnerCommand` with idempotency and conditions, and add `source_party` link rows to Room.
- **Models stay behind the deterministic validator.** A local-model fallback may only rewrite the owner's own words into command text that the existing parser checks.
- **One interruption coordinator.** Owner-activated alarms bypass its budget.
- **Freeze breadth** (news, library, player, sharing, library sync) while the core loop is fixed.

## Summary lists

### The 10 biggest everyday gains
1. HF-001 time understanding
2. HF-003 routing
3. HF-004/HF-005/HF-006 finance parsing
4. HF-010 calmer reminders
5. HF-011 one-tap Done/Snooze
6. HF-013 hands-free voice
7. HF-031 person/month search
8. HF-033 conditional reminders
9. HF-032 fewer, better-timed notices
10. HF-042 waiting-on for all chats

### The 10 most important architectural changes
1. Single understanding layer shared by Ask, voice and widgets
2. Ordered intent table with exclusions
3. Idempotent proposal → approval → execution path for all commands
4. Interruption coordinator plus `notice_log`
5. `source_party` identity rows
6. Phone-applied command queue for other clients
7. Router with a real on-device provider and `InputKind`
8. Persisted failure codes in release builds
9. Retention policy for events and news
10. Model output only through the deterministic validator

### The 10 most important performance changes
1. v22 indexes
2. Bounded widget and Inbox queries
3. Category list via `SELECT DISTINCT`
4. Notification re-post coalescing
5. Table-to-widget refresh map
6. Remembered Flows in Compose
7. LazyColumn for Inbox and Needs-attention
8. FTS instead of the decrypt scan for exact phrases
9. Adult classifier session reuse
10. Keystore decrypt cost measurement before any key-caching change

### Recommended next release scope (0.11.0)
- All P0 items (HF-001 to HF-010).
- The P1 items that need no schema change: HF-011 to HF-014, HF-022, HF-024, HF-025.
- HF-020 (indexes) as the only migration.

### Order of the next implementation sessions
1. Parsers (HF-001, HF-002, HF-004, HF-005, HF-006)
2. Routing (HF-003)
3. Safety (HF-007, HF-008, HF-009, HF-014)
4. Reminders (HF-010, HF-011, HF-012)
5. Performance (HF-020, HF-023, HF-025)
6. Reliability (HF-021, HF-022, HF-024)
7. Voice (HF-013)
8. Search identity (HF-031)
9. Coordinator (HF-032)
10. Conditional reminders (HF-033)
11. Evaluation harness (HF-034)
12. P2 items

## Documents
| File | Contents |
|---|---|
| `02-verified-defects.md` | A1 verification and all further defects |
| `03-feature-gap-matrix.md` | Matrix and detailed Phase B / C1–C2 findings |
| `04-architecture-review.md` | Actual and target architecture, multi-device |
| `05-performance-reliability-security.md` | C3–C5 |
| `06-everyday-scenarios.md` | 15 scenario traces |
| `07-roadmap-and-backlog.md` | Backlog HF-001… with status |
| `08-evaluation-plan.md` | Datasets, metrics, targets, test inventory |
| `09-previous-recommendations.md` | A2 verdicts |
| `10-not-now-and-play-appendix.md` | Not now, preserve, domains, Play appendix |
| `PROGRESS.md` | Session progress |
