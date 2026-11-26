# 10 — Not now, preserve, and Google Play appendix

## Features not to prioritize yet

| Feature | Reason |
|---|---|
| General DAG workflow engine | Every in-scope side effect is a linear propose → approve → apply → verify chain; extending typed commands covers it (03 §C1.3). An autonomous engine conflicts with the approval rule |
| Separate graph database | All needed traversals are one or two hops over existing Room tables (03 §B6) |
| Project workspaces | No project entity is needed yet; task tags or collections cover blocked-work tracking (06 scenario 5). Revisit after HF-031 |
| Native tablet or desktop apps | The web workspace with phone-applied commands serves both (04 § Multi-device). A second capturing install is harmful (D5) |
| Multi-master sync for new data | The command pattern is simpler and safer (04 D6). Freeze library sync where it is |
| Video blur/tracking, voice pitch transformation (2026-11-18 phase 3) | Rare use, large effort |
| Sandboxed user-written skills | Conflicts with the approval model |
| Shopping, price tracking, travel, health, vehicle, home automation, habits, journaling, location history | No everyday need shown over existing receipts, expiry, memory and tasks (see E2 below). Location reminders stay (HF-064) |
| New news sources, news embeddings | News is already the largest package; freeze it |
| Native/SIMD kernels | No measurement shows a need (05 §1, 2026-11-18 audit) |
| Auto-posting money without review | Locked requirement; owner decision only (HF-040) |

## Components to preserve rather than rewrite
- **Capture:**
  - `NotificationNormalizer` (identity, redaction);
  - `EventRepository`;
  - the FTS4 setup.
- **Finance:** `FinanceRepository` and the ledger schema (exact math, evidence, reversals, cross-source linking).
- **Approval:**
  - calendar change and outbound draft state machines;
  - `action_audit`.
- **Briefs and insights:** `Briefs.build`, `Waiting`, `Debts`, `Recurring`, `ExpiryFinder`. These are deterministic and tested.
- **AI routing:** `AiRouter` consent, budget and audit design; `HeavyModelGate` and `ExecutionScheduler`.
- **Relay and worker:**
  - sealed relay envelope;
  - WhatsApp worker spool encryption;
  - nonce-gated relay sends.
- **Storage and backup:**
  - `EncryptedDatabase`, `PayloadCipher`, `SecretStore`;
  - backup crypto and journaled restore.
- **Sync:** the news-actions command channel. It is the model for web approvals.

---

## Part E: Secondary domains and breadth


### E1. LOC per top-level package (`wc -l` over `A/*/`, total 40,176 LOC / 273 files)

| Package | LOC | Files | Bytes | Group |
|---|---:|---:|---:|---|
| ui (all) | 13,514 | 69 | 871 KB | shell |
| **news** | **4,066** | 16 | **260 KB** | breadth |
| library | 1,976 | 12 | 144 KB | breadth |
| security | 1,946 | 15 | 112 KB | infra |
| media | 1,809 | 20 | 105 KB | mixed: recording/Whisper/VAD/OCR is core-ish |
| sync | 1,592 | 5 | 94 KB | 1,241 library (breadth) + 351 mirror |
| sharing | 1,454 | 8 | 76 KB | breadth |
| intelligence | 1,304 | 12 | 71 KB | breadth (media classification/search) |
| player | 1,289 | 15 | 60 KB | breadth |
| ai | 1,205 | 10 | 75 KB | core |
| drawer | 1,194 | 7 | 73 KB | breadth (capture entry) |
| widgets | 988 | 4 | 61 KB | core-adjacent |
| assistant | 927 | 10 | 68 KB | **core** |
| guard | 752 | 7 | 47 KB | breadth |
| communications | 698 | 8 | 45 KB | **core** |
| insights | 613 | 6 | 42 KB | **core** (brief, people, debts, expiry, gaps, recurring) |
| data | 574 | 5 | 33 KB | core |
| **finance** | **481** | 6 | **35 KB** | **core** |
| memory | 459 | 3 | 31 KB | **core** |
| calendar | 439 | 6 | 33 KB | **core** |
| performance | 427 | 4 | 19 KB | infra |
| **tasks** | **405** | 4 | **28 KB** | **core** |
| reports 298 · audio 280 · contacts 216 · sms 158 · processing 150 · notifications 143 · templates 138 · events 118 · root 563 | | | | core/infra |

**UI sub-packages:**

| Group | Sub-packages | LOC |
|---|---|---:|
| Breadth | news 1,372 · library 833 · player 772 · drawer 479 · sharing 352 · intelligence 260 · sync 141 | 4,209 |
| Core | money 1,032 · inbox 571 · insights 578 · ask 447 · tasks 429 · templates 221 · home 214 (+ reports 1,050, mostly money) | ≈4,540 |

**Aggregate (VI):**
- **Breadth:** news, library, player, sharing, intelligence, drawer, guard, library sync, plus their UI. About **17.5k LOC (~44%)**.
- **Core domain:** tasks, finance, assistant, communications, calendar, memory, contacts, insights, templates, ai, sms, notifications, events, processing, data, plus core UI. About **11.2k LOC (~28%)**.
- **The news package alone is 260 KB, more than finance, tasks, calendar, memory, communications and assistant combined (240 KB).**

**Room tables (71 total):**

| Domain | Tables |
|---:|---|
| News | 10 |
| Library/media/player/sharing/sync | ~24 |
| Guard | 3 |
| Core and the rest | the remainder |

**Unit tests:** about 165 of 551 (~30%) target breadth.

**Is breadth slowing the core loop? Yes. IR, with documentary support:**
1. The owner froze expansion on 2026-11-18 ("Finish what you have done and plan the remaining later"). On 2026-11-25 the owner reprioritized to core depth (calendar → OxygenOS survival → location reminders → Telegram) and explicitly excluded new media/library/news/player/widget surface (DEVICE_FEASIBILITY l.7-13).
2. The 2026-11-17 media review "found functional defects missed by the integration checks" and needed four sequential fix branches (README status). Library sync needed its own review.
3. Every breadth table taxes the migration chain (schema 21), backup/selective-restore coverage (`BackupTablesTest`, `BackupCoverageTest`) and the sync allow-lists.
4. Core capabilities that are still thin or queued: OEM background survival, location reminders, Telegram, a real SMS corpus, evaluation.

### E2. Domain verdicts

| Domain | What exists (evidence) | Verdict |
|---|---|---|
| **News & research** | VI. `A/news` 16 files / 4,066 LOC + `ui/news` 1,372; 10 tables; feeds/X connectors, EN/SW lexical clustering (`NewsLexicon.kt` 500), versioned events, scheduled briefings, quiet-hour alerts, TTS (`NewsAudioService`), news Ask, web reader + news actions; `NewsEngineTest`/`NewsRuntimeTest`/`NewsFeedTest`/`NewsSyncTest` (25 tests). Not in MASTER_SPEC. "Research" beyond news Ask: **M** | **Freeze.** The daily brief has value, but this duplicates commodity news apps and is the single biggest package. No embeddings or new sources. Consider trimming the X/licensed connectors |
| **Media library / player / sharing / intelligence / drawer** | VI. Library 1,976; player 1,289 (PiP, A-B, sleep timer, waveform, `OverlayEvaluation.kt` documents the overlay as rejected); sharing 1,454 (image/MP4 privacy, PCM mute); intelligence 1,304 (labels, BPM, adult classifier, hybrid ranker); drawer 1,194; encrypted library sync 1,241. MASTER_SPEC only asks for recording playback (§l.440) and on-device media classification (§0.10) | **Keep:** drawer/Share target (capture entry) and recording playback. **Freeze:** library, intelligence, sharing, library sync. **Drop candidate:** general-purpose player features beyond recordings |
| **Shopping / price tracking** | **M.** Only keyword hits (`insights/Recurring.kt`, news lexicon) | Drop (do not build). Receipts already flow through finance |
| **Travel** | **M.** Only image categories (`intelligence/CategoryMap.kt:18-21`) and a "travel advisories need a live source" string (`drawer/ActionEngine.kt:29`) | Drop. Bookings arrive as messages/tasks already |
| **Documents vault & expiry** | VI. `insights/Expiry.kt` (84 LOC, EN/SW rule-based; passport, licence, insurance, vehicle licence, visa, permit, NIDA, warranty, contract). A hit becomes one task proposal with no document number (`DailyOutputsTest`). Encrypted originals via `media/MediaVault.kt` | **Keep.** Small and high value |
| **Health** | **M.** Only "capture health" / "news health" hits | Drop |
| **Vehicle** | **M** as a domain. Covered only by expiry labels (road licence, insurance) | Drop; expiry covers it |
| **Home automation** | **M** (0 hits) | Drop |
| **Location history** | **M.** `saved_location` holds drawer-captured places only (`library/LibraryData.kt:143-149`). Guard uses location. Location-aware reminders are queued as Priority 3 | Keep only location reminders. No history |
| **Routines** | **M** as a domain. Task recurrence (`TaskScheduler.kt:119-142`) and action templates (`templates/TemplateData.kt`, 138) cover the need | Freeze. Templates suffice |
| **Habits & journaling** | **M** | Drop. Memory notes and Record cover it |
| **TheftGuard** | VI. `A/guard` 7 files / 752 LOC; Room v20 (3 tables); separate process; relay `server/tests/test_theft.py` (13 tests), `GuardTest` (7). Owner-authorized consent exception (DECISIONS 2026-11-25). All physical checks NOT TESTED | **Freeze.** Rare event but high stakes; isolated, so it costs the core little |

---


---

## Appendix: Google Play (informational)

Play distribution is **not** a goal (MASTER_SPEC: private signed APK). This appendix lists what Play policy would require, so the cost is known. It is based on Google's published policy categories; current policy text was not re-checked during this audit (**unverified assumption**).

| Area | Current app | Play requirement (summary) | Cost to comply |
|---|---|---|---|
| SMS read and receive | `READ_SMS`, `RECEIVE_SMS` for finance capture | SMS permissions only for the default SMS handler or approved exception categories, with a declaration form | High. Either become a default SMS app or drop SMS capture and rely on notifications |
| Notification listener over all apps | Core capture | Allowed with a prominent disclosure and a core-feature justification | Medium: disclosure UI and review |
| WhatsApp linked-device bridge | Unofficial whatsapp-web.js via own server | Server-side; not in the APK, but describing it in the listing invites policy review | Low in the APK; reputational risk |
| Device Owner / device admin (TheftGuard) | Device admin receiver, Device Owner policies | Device admin use is restricted; Device Owner is for managed devices | High. Remove from the Play build |
| Background location | TheftGuard | Background location requires approval and strong justification | Medium–high. Remove from the Play build |
| Call recordings import, microphone monitoring | Visible foreground service | Allowed with disclosure; foreground service type declarations | Medium: declarations and review |
| Exact alarms | `SCHEDULE_EXACT_ALARM` | Allowed for alarm or reminder apps with declaration | Low |
| Accessibility | Not used | — | None |

A Play build would therefore be a separate flavour without SMS, TheftGuard and background location. That is a large product change and is not recommended while the app is personal.
