# 05 — Performance, reliability and security


Base: `main @ 85f5ae5`, read-only. No Gradle run, no device (`adb` not used), no live services. Paths are relative to the repo root; `A/` = `android/app/src/main/java/com/hellfire/ass/`.
Evidence types: source reading; one host `EXPLAIN QUERY PLAN` run with Python's stock SQLite against the `createSql`/index DDL in `android/app/schemas/com.hellfire.ass.data.EventDatabase/21.json`. That plan is **not** SQLCipher-on-device evidence, and it says nothing about timings. No performance numbers are claimed. Any numbers below that are not quoted from code are labelled as targets.

Labels: **VI** Verified implementation · **VD** Verified defect · **IR** Inferred risk · **M** Missing · **PE** Proposed enhancement · **UA** Unverified assumption.

## 0. Status of the 2026-11-18 performance audit (`docs/DEVICE_FEASIBILITY.md` §Performance audit, lines 300–465)

| Earlier item | Status at 85f5ae5 | Evidence |
|---|---|---|
| P1 JNI array pinned across Whisper inference | Done (VI) | Recorded as implemented. Native file not re-reviewed here. |
| P1 retriever per sampled frame | Done (VI) | Recorded as implemented, with host shadow tests. |
| P1 `MediaWork.pump` per-candidate WorkManager reads and backlog hydration | Done (VI) | Recorded as implemented, with a 1,000-item host test. |
| P1 idle-model release on memory pressure | Done (VI) | `A/HellFireApp.kt:93-113` `onTrimMemory`/`onLowMemory` → `releaseIdleModels()` under `HeavyModelGate.mutex`. Only initialized lazies are released. |
| P2 Macrobenchmark / Baseline Profile infrastructure | Infrastructure done. **Device collection still NOT RUN** (M) | No `app/src/main/baseline-prof.txt` committed (per doc). |
| P2 "adult classification opens a session per frame outside the gate" | **Still open (VD)** | `A/intelligence/AdultClassifier.kt:76-79`: `classify()` calls `onnxEnvironment.createSession(modelFile.path)` on every call. It is called per frame from `A/intelligence/ContentEngine.kt:241`. Not under `HeavyModelGate`, and not released by `releaseIdleModels`. |
| P2 "media search/filter paths materialize up to 100,000 IDs; per-hit queries" | Not re-verified (UA) | Not inspected this pass. |
| P2 news per-story reads / repeated feature JSON parsing | Not re-verified (UA) | — |
| "No measured query plan justifies schema change" | Superseded by §1.3 below | A host plan now shows full scans plus temp B-tree sorts on hot `source_event`/ledger/message queries. |

The 2026-11-26 checkpoint (lines 2028–2044) moved inbox event search to FTS (VI: `A/data/EventDatabase.kt:109-113`, `inboxMatching` uses `source_search MATCH`). The ordering still has no index (§1.3).

---

## 1. C3 Performance

### 1.1 Startup (`HellFireApp.onCreate`)

| # | Label | Finding | Evidence |
|---|---|---|---|
| S1 | VI | `onCreate` stays light. It reads one SharedPreferences value, runs `Diagnostics.init`, returns early in the `:guard` process, then launches `initializeStorage()` on `captureScope` (IO). SQLCipher open, the Keystore key unwrap (`EncryptedDatabase.factory`) and draft/calendar recovery all run off the main thread. | `A/HellFireApp.kt:84-91`, `115-147` |
| S2 | IR | `refreshSmsCapture()` runs on the main thread inside `onCreate`. It does `SmsCaptureSettings` prefs reads plus `contentResolver.registerContentObserver` (a binder call). Small, but it happens on every process start, including receiver- and worker-started processes. | `A/HellFireApp.kt:90`, `164-185` |
| S3 | IR | After READY, each process start runs about 11 scheduling/recovery calls in series: `CaptureWork.recover` (with `.result.get()`), `TaskScheduler.restore`, `MediaWork.recover`, `IntelligenceWork.recover` (`.result.get()`), `SearchBackfill`, `LibraryScanWork.schedule`/`watch`, `NewsWork.schedule`, `ModelUpdates.onStartup`, the widget observer. Every boot/receiver/worker-triggered start repeats them. The same restore also runs every 15 min from `CaptureMaintenanceWorker`. CPU/IO cost is unmeasured. | `A/HellFireApp.kt:128-141`; `A/processing/CaptureWork.kt:58-73` |
| S4 | IR | `SecureRandom().generateSeed(32)` is used for the database key; `generateSeed` can block on entropy. It runs once only, off the main thread. Low impact. `nextBytes` is the idiomatic call. | `A/security/EncryptedDatabase.kt:25`; also `A/security/PortableBackup.kt:56` |
| S5 | PE | Measure: run `StartupBenchmark` (exists) for cold/warm/hot, using `ReportDrawnWhen` full-display. Add `hf.startup.recover` trace spans around S3. Target (unmeasured) for full-display regressions: none until a baseline exists. |

### 1.2 Main-thread DB/crypto, per-row decrypt cost

| # | Label | Finding | Evidence |
|---|---|---|---|
| D1 | VI | All `source_event` payloads use AES-GCM with an **AndroidKeyStore** key (`hellfire-local-v1`). Every `encrypt`/`decrypt` is a `Cipher.init`+`doFinal` against Keystore2, i.e. an IPC per row. Per-op cost is unmeasured. | `A/security/PayloadCipher.kt:13-60` |
| D2 | VD (cost unmeasured) | **Inbox tab decrypts every listed event on each upstream emission.** The `LaunchedEffect(messages, events, media, candidates, tasks, accountLabels)` loop calls `EventText.fields` (a Keystore decrypt) per event, with `limit` growing in steps of 100. One of its keys is `db.media().items()`, an unbounded Flow over all managed media. Every `media_item` write during processing therefore re-runs N Keystore decrypts plus two evidence queries. | `A/ui/inbox/InboxTab.kt:53-58`, `68-110`; `A/media/MediaData.kt:56` |
| D3 | VD | **Replies widget** (`repliesWaiting`) does `labelledPeriod(7 days)`: all events in the window, with no source filter in SQL and an unindexed range (§1.3). It then decrypts every SMS MESSAGE once to group by sender and again for the body. `allDrafts()` (unbounded) is scanned per thread (O(threads × drafts)). | `A/widgets/WidgetData.kt:137-153` |
| D4 | IR | `loadInteractions` decrypts up to `smsLimit = 4000` SMS rows per call (People/insights). `OtherReports.rememberCapture` decrypts every SMS in the selected range with no limit. | `A/insights/People.kt:141-151`; `A/ui/reports/OtherReports.kt:39-55` |
| D5 | VI | Decrypt sites in the UI all run inside `withContext(Dispatchers.IO)` (`EventText` is documented "call off the main thread"). No `runBlocking` on the UI thread was found. The only `runBlocking` is `A/player/VaultDataSource.kt:34`, on the ExoPlayer loader thread. | `A/ui/UiData.kt:57-62` |
| D6 | PE | Keep the Keystore key as the root, but cache a per-process **software AES key** unwrapped from it, as is already done for the SQLCipher password (`EncryptedDatabase.kt:20-31`). Alternatively, stop re-decrypting payloads by persisting the already-indexed `source_search.text` / sender hint, which already lives inside SQLCipher. This is a security/perf trade-off: the key would sit in process memory. Benchmark first: a microbenchmark of 1k `PayloadCipher.decrypt` calls on device, Keystore vs. in-memory key. Report p50/p95 per op. No target until measured. |

### 1.3 Room indexes vs. queries (schema v21 JSON + host EXPLAIN QUERY PLAN)

Indexes present on `source_event`: only `UNIQUE(source, externalId)` plus the PK. `event_processing`: PK `eventId` only. `ledger_entry`: `dedupeKey`, `reversesId`, `accountId`. `finance_candidate`: `(sourceId, position)`. `communication_message`: `(channel, accountId, externalId)`, `sourceId`.

| # | Label | Query (DAO) | Host plan | Called from |
|---|---|---|---|---|
| I1 | VD | `EventDao.recent()` `ORDER BY e.receivedAt DESC LIMIT 20` (`A/data/EventDatabase.kt:121-122`) | `SCAN e; USE TEMP B-TREE FOR ORDER BY` | `EventRepository.recent`, a Flow re-run on every `source_event` insert |
| I2 | VD | `labelledPeriod(start,end)` `WHERE e.receivedAt >= ? AND < ?` (`:217-218`) | `SCAN e` | Replies widget, capture/money reports |
| I3 | VD | `pending()` `WHERE p.state='PENDING' ORDER BY e.receivedAt` (`:184-185`) | `SCAN p; … TEMP B-TREE` | Capture processing drain, each batch |
| I4 | IR | `queueSummary()` `GROUP BY state` (`:181-182`) | full scan of `event_processing` | Needs-attention page Flow |
| I5 | IR | `fromSource(source, occurredAt range)` (`:178-179`) | uses `(source, externalId)` prefix only, then `TEMP B-TREE` | People/interactions (up to 4000) |
| I6 | IR | `inboxMatching` `ORDER BY e.occurredAt DESC` (`:109-110`) | (FTS filter, then sort; not planned here) | Inbox Flow |
| I7 | IR | `searchBatch`/`olderThan` keyset on `(receivedAt, id)` (`:127-128`, `150-151`) | no `(receivedAt,id)` index, so each page scans | Search backfill, search |
| I8 | VD | `FinanceDao.entries()` `ORDER BY occurredAt DESC, createdAt DESC LIMIT 100` (`A/finance/FinanceData.kt:67`) | `SCAN ledger_entry; TEMP B-TREE` | Money UI Flow |
| I9 | VD | `finance_candidate WHERE state='REVIEW' ORDER BY occurredAt` (`A/finance/FinanceData.kt:56`) | `SCAN; TEMP B-TREE` | Inbox, Money Flows |
| I10 | VD | `messagesIn(start,end)` (`A/communications/CommunicationData.kt:69`) | `SCAN communication_message` | Replies widget, People, reports |

Proposed (PE, one migration v21→22, `CREATE INDEX IF NOT EXISTS` only, no data rewrite):
- `source_event(receivedAt, id)`, `source_event(source, occurredAt)`, `source_event(occurredAt)`
- `event_processing(state)`
- `ledger_entry(occurredAt, createdAt)`, `finance_candidate(state, occurredAt)`, `communication_message(occurredAt)`

These need a migration test and `NativeMigrationTest` coverage. Benchmark: extend the existing synthetic host SQL workload (`docs/performance-results.json`) to 10k/100k `source_event` rows, then repeat on device with SQLCipher. Index-space growth must be reported alongside.

### 1.4 Unbounded loads / N+1

| # | Label | Finding | Evidence |
|---|---|---|---|
| U1 | VD | `allEntries()` (whole ledger, `ORDER BY occurredAt`) is loaded and then filtered in Kotlin for date or person. It runs on every Money-today widget refresh. | `A/finance/FinanceData.kt:82`; callers `A/widgets/WidgetData.kt:78`, `A/ui/money/BudgetsPage.kt:38`, `A/ui/reports/MoneyReports.kt:34`, `A/ui/insights/InsightPages.kt:194,327`, `A/assistant/AssistantEngine.kt:50,58`, `A/contacts/ContactLinks.kt:80` |
| U2 | VD | `tasks().all()` and `allCandidates()` are unbounded and filtered in Kotlin, from widgets (`WidgetData.kt:93,96`, `Widgets.kt:261,423`) and reports/brief (`A/insights/Brief.kt:125`). | `A/tasks/TaskData.kt:62`; `A/finance/FinanceData.kt:87` |
| U3 | VD | `media().items()` is an unbounded Flow of all managed media, collected in three screens. The Needs-attention page loads it only to count `FAIL` states and take 20. | `A/ui/settings/EverydayPages.kt:65,92-95`; `A/ui/MediaScreen.kt:33`; `A/ui/inbox/InboxTab.kt:56` |
| U4 | VD | `media().all()` is loaded only to **sum sizes**. A `SELECT SUM(size)` would do. | `A/ui/BackupScreen.kt:115`; `A/ui/settings/EverydayPages.kt:125` (`mediaBytes`) |
| U5 | IR | `debts().all()` is called repeatedly in one screen action. In `ContactLinks` it is called inside a loop (`require(db.debts().all().any{…})` per id), which is an N+1 full-table load. | `A/contacts/ContactLinks.kt:111`; `A/ui/insights/InsightPages.kt:192,195` |
| U6 | VD | `ContactLinks` re-link loads all ledger entries and tasks to update the rows for one person key. A `WHERE personKey=?` UPDATE would be enough. | `A/contacts/ContactLinks.kt:79-80,95` |

### 1.5 Compose hot spots

| # | Label | Finding | Evidence |
|---|---|---|---|
| C1 | VD | Room Flows are created **inside composition without `remember`**, then passed to `collectAsStateWithLifecycle`. Each recomposition gives a new Flow instance, which restarts collection and re-runs the query. | `A/ui/MediaScreen.kt:33,175`; `A/guard/GuardPage.kt:35-36`; `A/ui/sharing/ShareWithAiPage.kt:110-112`; `A/ui/drawer/InboxScreens.kt:47,132-135,142`; `A/ui/sync/SyncDashboardPage.kt:29-32` |
| C2 | IR | Most screens use `ScrollPage` (`Column + verticalScroll`, `A/ui/AppNav.kt:206-212`), which composes every child eagerly. Only 8 Lazy containers exist in the app. The Inbox renders up to `limit` messages + `limit` events + all media in that Column, and re-runs `groupBy/sortedByDescending` in composition on each recomposition. | `A/ui/inbox/InboxTab.kt:145-160` |
| C3 | IR | `AskTab` `LazyColumn items(items)` has no `key`. A chat that appends and scrolls loses item identity. | `A/ui/ask/AskTab.kt:154` |
| C4 | VI | Library grid/list uses keyed `items(rows, { it.id })` with 60-row pages and a bounded 12 MiB thumbnail LRU. | `A/ui/library/LibraryPage.kt:47,171,175` |
| C5 | PE | Convert Inbox and Needs-attention to `LazyColumn` with stable keys, and move the grouping into the IO `LaunchedEffect`. Measure with the existing Macrobenchmark scroll journey (frame overrun p50/p95). |

### 1.6 Notification storms

| # | Label | Finding | Evidence |
|---|---|---|---|
| N1 | VI | The capture queue is bounded (`Channel(256)`, batches of 32) and overflow is counted into `capture_audit` (`CAPTURE_QUEUE_OVERFLOW`). | `A/notifications/HellFireNotificationListener.kt:22-47,78-84` |
| N2 | VD (volume unmeasured) | **Every re-post and every removal is a new stored event.** The event identity hashes `postedAt` (`sbn.postTime`, which changes on each `notify()`), `rank`, `importance` and all text. Updates to ongoing/progress/media-player/navigation/timer notifications each create a new `source_event` row. Each such row costs a Keystore encrypt, an FTS row, an `event_processing` job, a `capture_audit` update and a `CaptureWork.process` WorkManager query. `REMOVED` is captured for every notification. There is no per-key coalescing or throttling for `ongoing` posts. | `A/events/NotificationNormalizer.kt:91-111`; `A/notifications/AndroidNotificationAdapter.kt:37-44`; `A/data/EventDatabase.kt:153-160` |
| N3 | PE | For `ongoing == true` (or category `progress`/`transport`/`navigation`), keep only the latest snapshot per `sbn.key` within a window. Alternatively, upsert by key instead of inserting. This must respect AGENTS.md "ingest every notification": store the first and last of a burst and count the rest in `capture_audit`. Benchmark: a scripted host test posting 1,000 progress updates. Report the row count and job count. |
| N4 | IR | `CaptureWork.process` runs after every captured batch. It does a `WorkManager.getWorkInfos(...).get()` (blocking) under a Mutex on the listener's IO scope. | `A/processing/CaptureWork.kt:22-35` |

### 1.7 Widget refresh fan-out

| # | Label | Finding | Evidence |
|---|---|---|---|
| W1 | VI | No periodic widget timer (`updatePeriodMillis="0"` in all `res/xml/widget_*.xml`). `updateAll` touches only placed instances. | `A/widgets/Widgets.kt:510-514` |
| W2 | VD | Any write to any of 12 observed tables (including `media_item` and `integration_state`, which are written during media processing and mail IDLE) re-renders **all 10 data widget types**, after a 1.5 s trailing debounce. There is no table→widget mapping. Under sustained writes the trailing debounce starves updates, then fires everything at once. Each Money/Replies/Review render runs U1/U2/D3. | `A/HellFireApp.kt:159-170`; `A/widgets/Widgets.kt:510-513` |
| W3 | IR | Widget `load` failures become `getOrNull()` and show fallback content with no recorded state (silent). | `A/widgets/Widgets.kt:42-44` |
| W4 | PE | Map tables to widget classes, and replace U1/U2/D3 with bounded SQL (day-range ledger query, `state='REVIEW'` count, `messagesIn` with an index, SMS sender from the FTS text). |

### 1.8 Model load/unload

| # | Label | Finding | Evidence |
|---|---|---|---|
| L1 | VI | One global `HeavyModelGate.mutex` serializes the LLM, speech, embedder and speaker model, plus memory-pressure release. | `A/ai/LocalModel.kt:184`; `A/media/LocalSpeech.kt:23-51`; `A/memory/SemanticIndex.kt:97-180`; `A/media/SpeakerModel.kt:48-163` |
| L2 | VD | Adult classifier: one ONNX session is created per frame, outside the gate (see §0). The `LearnedVad` session (`A/media/LearnedVad.kt:59`) was not examined for lifecycle (UA). | `A/intelligence/AdultClassifier.kt:76-79` |
| L3 | IR | `ModelCatalog` speech probe takes `HeavyModelGate.mutex.lock()` manually, not with `withLock`. Correctness depends on its `try/finally` (seen at `A/ai/ModelCatalog.kt:66`; not fully traced). |

### 1.9 Worker inventory (unique names, cadence, constraints)

| Unique work | Type / period | Constraints | Source |
|---|---|---|---|
| `capture-maintenance` | Periodic 15 min, KEEP | **none** | `A/processing/CaptureWork.kt:39-40` |
| `process-events` | One-time APPEND_OR_REPLACE, exp. backoff 10 s | none | `CaptureWork.kt:30-32` |
| `communication-sync` | One-time KEEP/APPEND, backoff 30 s; enqueued by maintenance every 15 min and on each IMAP IDLE wake | **none (no network constraint)** | `A/communications/CommunicationWork.kt:50-56` |
| SMS sync (`SmsSyncWorker.WORK_NAME`) | One-time, content-observer + maintenance | — | `A/sms/SmsSyncWorker.kt:58-59` |
| `call-folder-import` | Periodic 15 min, UPDATE | battery not low | `A/media/FolderImport.kt:42-43` |
| `web-sync` periodic + now | Periodic 15 min, backoff 5 min | connected, battery not low | `A/sync/WebSync.kt:272-282` |
| `library-sync` periodic + now | Periodic 15 min, UPDATE | unmetered/connected (setting), battery not low | `A/sync/LibrarySync.kt:79-91` |
| `library-scan` | Periodic 6 h, KEEP | battery not low | `A/library/LibraryScanWork.kt:62-68` |
| `news-ingest` | Periodic 30 min, UPDATE | connected/unmetered | `A/news/NewsWork.kt:30-31` |
| `news-charge` | Periodic 6 h | charging + unmetered | `NewsWork.kt:33-34` |
| `news-briefing` | One-time REPLACE at slot | none | `NewsWork.kt:36-39` |
| Model check (`CHECK`) | Periodic 24 h | unmetered, storage/battery not low | `A/ai/ModelUpdates.kt:175-183` |
| Model download | One-time, foreground `dataSync` | same | `ModelUpdates.kt:189` |
| Drive backup daily/now | Periodic 24 h, initial delay 24 h, backoff 30 min | unmetered, battery/storage not low | `A/security/DriveBackupWork.kt:27-37` |
| Intelligence | One-time KEEP | battery not low; optional storage-not-low / charging / idle | `A/intelligence/IntelligenceWork.kt:46-57` |
| `analyze-media:<id>` | One-time per item, cap 20 | — | `A/media/MediaWork.kt:33-34` |
| Search backfill | One-time KEEP | battery not low | `A/data/SearchBackfill.kt:22-23` |
| `guard-disarm` | One-time KEEP | connected | `A/guard/GuardRecovery.kt:66-72` |
| Widget wallet re-hide | One-time 61 s, **not unique** | none | `A/widgets/Widgets.kt:354` |

| # | Label | Finding |
|---|---|---|
| K1 | IR | Five 15-minute periodic jobs (`capture-maintenance`, `call-folder-import`, `web-sync`, `library-sync`, the communication sync it kicks) plus 30-min news give at least 5 wakeups per 15 min while enabled. The OS batches them in Doze, but in active/charging states they compete. `capture-maintenance` and `communication-sync` have **no network constraint**, so offline IMAP attempts fail into backoff every 15 min. |
| K2 | VI | Heavy inference is gated by battery/charging/idle/thermal policy (`IntelligenceWork.constraints`; `OperatingProfile` per the earlier audit). Capture does not depend on inference. |
| K3 | PE | Add `NetworkType.CONNECTED` to the `communication-sync` request. Consider folding `call-folder-import` into `capture-maintenance`. Verify with `adb shell dumpsys jobscheduler` (in the doc protocol) on device. |

### 1.10 Storage growth and eviction

| Data | Retention / eviction | Label |
|---|---|---|
| `source_event` + `event_processing` + `source_search` FTS | **None.** Only an all-or-nothing delete (`EventDao.deleteAll`), which FK RESTRICT blocks once evidence links exist. Growth is amplified by N2. | M |
| `news_article`, `news_event`, `news_event_version`, `news_briefing`, feedback | **None.** Only `news_run` is pruned after 30 days (`A/news/NewsRepository.kt:136`). Ingest runs every 30 min. | M |
| `ai_usage`, `play_history`, `action_audit`, `calendar_change`, `capture_audit` | None found. | M (low volume except `play_history`) |
| `processing_job` | Deleted by id only (`A/library/LibraryDaos.kt:343`). DONE-row cleanup not found. | UA |
| Managed media originals / recordings | Owner-managed. Orphan recovery in `MediaVault` (`A/media/MediaVault.kt:270-282`). Plaintext temp dir swept after 1 h. | VI |
| Frame-scrubber cache | `FrameScrubber.purgeOrphans` | VI |
| News images / thumbnails | Memory LRU only (`A/ui/news/NewsImages.kt:40`, `LibraryPage.kt:47`) | VI |
| PerfLog | In-memory, 200 samples / 40 details | VI (`A/performance/Performance.kt:233-251`) |
| Relay inbox / seen / drafts | 7 d / 30 d / 1 d (`server/app/main.py:133-136`) | VI (see R7 for loss risk) |
| WhatsApp worker spool | 10,000 events / 7 days (`server/whatsapp-web/src/capture.js:39,66-70`) | VI (see R8) |
| Web mirror (Postgres `record`) | Only a full reset | M |

PE: an owner-visible retention setting for raw notification events (for example, keep evidence-linked rows forever and prune unlinked `REMOVED`/ongoing rows after N days), and news pruning (for example, articles older than 90 days that are not saved). Report DB size growth per day from the Privacy page counters.

---

## 2. C4 Reliability

### 2.1 Process death, reboot, time change, package replaced

| # | Label | Finding | Evidence |
|---|---|---|---|
| R1 | VI | `ReminderRestoreReceiver` handles `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, using `goAsync`. Failures are recorded (`reminders/RESTORATION_FAILED`). | `AndroidManifest.xml` (ReminderRestoreReceiver); `A/tasks/TaskScheduler.kt:228-236` |
| R2 | VI | The process start that a boot receiver triggers runs `initializeStorage` → `CaptureWork.recover`, `MediaWork.recover`, `IntelligenceWork.recover`. WorkManager persists across reboot. `capture-maintenance` re-restores every 15 min. | `A/HellFireApp.kt:115-147` |
| R3 | IR | `GuardBootReceiver` handles only `BOOT_COMPLETED`, not `MY_PACKAGE_REPLACED`. After an app update, an armed guard's FGS is not restarted until the next boot or app open. | `A/guard/GuardRecovery.kt:15-38` |
| R4 | IR | Not direct-boot aware: no `LOCKED_BOOT_COMPLETED`. Correct for credential-encrypted SQLCipher. Before first unlock, `GuardAdminReceiver.onPasswordFailed` (in `:guard`) cannot open the database and swallows the error (`catch (_: Exception)`, `GuardRecovery.kt:41-52`). The **unlock-failure trigger is silently unavailable BFU**. | `GuardRecovery.kt:41-52` |
| R5 | IR | `MailIdleService` is `START_NOT_STICKY`, owner-started and self-limited to 10 min. It is not restarted after process death or reboot by design. Polling falls back to the 15-min maintenance. | `A/communications/MailIdleService.kt:36-52` |

### 2.2 Room migrations

| # | Label | Finding | Evidence |
|---|---|---|---|
| R6 | VI | Version 21, `exportSchema = true`, schemas 1–21 committed, `ALL_MIGRATIONS` 1→21 complete. There is no `fallbackToDestructiveMigration`. A failed open sets `storageStatus = "UNAVAILABLE"`. | `A/data/EventDatabase.kt:237`, `375-378`; `HellFireApp.kt:143-145` |
| R6a | VI / M | Host test: only **v1→21** with one fixture row (`android/app/src/test/java/com/hellfire/ass/DatabaseMigrationTest.kt:20`). Every-start-version coverage (v1..v20 → 21, real SQLCipher, `integrity_check`) exists only as an instrumented test (`android/app/src/androidTest/java/com/hellfire/ass/NativeMigrationTest.kt:15`), which needs a device and has **NOT RUN**. No per-version data-preservation assertions beyond the single `source_event` row. No downgrade handling beyond UNAVAILABLE. | |
| R6b | PE | Port the every-version loop to Robolectric (plain SQLite is enough for DDL correctness) so CI covers v2..v20 starts. |

### 2.3 Idempotency, duplicates, concurrency, partial execution

| # | Label | Finding | Evidence |
|---|---|---|---|
| R9 | VI | Capture is idempotent. Provider-keyed IDs exist for SMS, IMAP and WhatsApp (`NotificationNormalizer.kt:103-109`), and `INSERT … IGNORE` + `exists`/`legacyId` checks run inside a `@Transaction`. `ledger_entry.dedupeKey` UNIQUE, `finance_candidate(sourceId,position)` UNIQUE, `skill_use` UNIQUE and `processing_job(type,itemId)` UNIQUE are in place. Relay ingest uses `INSERT OR IGNORE INTO seen`. | `A/data/EventRepository.kt:18-51`; `server/app/main.py:126-137` |
| R10 | VI | External sends are single-shot. The relay requires a nonce-hashed approval within 300 s, sets status `SENDING` before the call, records `UNKNOWN/DELIVERY_UNCONFIRMED` on any error and refuses automatic retry (`server/app/main.py:353-389`). The phone records `DELIVERY_UNCONFIRMED` and refuses to re-send (`A/communications/CommunicationWork.kt:40-45`). The linked-device transport is capture-only (`/drafts` → 409). | |
| R11 | IR | `EventDao.finish` uses `WHERE state='PENDING'` (optimistic). The processing claim is fine for a single drain. `process-events` APPEND_OR_REPLACE plus a mutex prevents parallel drains. | `A/data/EventDatabase.kt:187-188`; `CaptureWork.kt:22-35` |
| R12 | VI | Portable restore is journaled (`restore-journal` secret, moved-file list, `committed` flag). | `A/security/PortableBackup.kt:270-295` |

### 2.4 Credentials, outages, inference failures

| # | Label | Finding | Evidence |
|---|---|---|---|
| R13 | VD (diagnosability) | IMAP IDLE maps **every** exception (DNS, TLS, timeout, wrong app password) to one state, `CONNECTION_OR_CREDENTIALS_UNAVAILABLE`. An expired or revoked Gmail app password is indistinguishable from an outage. CaptureGaps checks only for `FAILED`/`AUTH_FAILED`/`ERROR` or a non-null error, so it reports "not connecting" either way. | `A/communications/MailIdleService.kt:77-82`; `A/widgets/WidgetData.kt:124-126` |
| R14 | VI | Web/library sync: 401 → `Result.failure()` plus the owner-visible status "server refused the sync token". Other errors retry with exponential 5-min backoff. A quota error has its own status. | `A/sync/WebSync.kt:337-349`; `A/sync/LibrarySyncWorker.kt:36-52` |
| R15 | IR | The generic `catch (_: Exception)` in WebSync/LibrarySync reports "Could not reach the server" for local faults too (decrypt/DB/serialization). Local corruption looks like a network outage and retries forever. | `A/sync/WebSync.kt:345-348`; `LibrarySyncWorker.kt:49-51` |
| R16 | VI | Cloud AI routing uses per-attempt preflight (privacy toggle, disabled, missing key, capability), maps 401/403 → `UNAUTHORIZED` and 429 → `RATE_LIMIT`, holds a monthly budget reservation, falls back to the next route, and records metadata-only audit to `ai_usage`. | `A/ai/AiRouter.kt:66-140`; `A/ai/CloudProviders.kt:70-85` |
| R17 | IR | Provider `status` text is updated only by `testConnection`. A key that expires later shows `UNAUTHORIZED` per attempt in `ai_usage` but is not surfaced in CaptureGaps or Needs-attention. | `AiRouter.kt:41-63`; `A/ui/settings/EverydayPages.kt:59-110` |

### 2.5 Server-side reliability

| # | Label | Finding | Evidence |
|---|---|---|---|
| R18 | **VD** | **Poison spool file blocks all WhatsApp delivery.** `drain_spool` processes sorted files and catches only `FileNotFoundError`. Any `ValueError`/`KeyError`/`InvalidTag` from a single corrupt or truncated file propagates. `/device/events` then returns 503 before reading the relay inbox. The same file sorts first again on every poll, so delivery of **all** relay events (spool and Meta inbox) stops until manual removal. There is no quarantine or dead-letter. | `server/app/whatsapp_web.py:49-80`; `server/app/main.py:301-307` |
| R7 | IR | The relay deletes undelivered sealed events after 7 days (`main.py:133-134`) with no counter or report. A phone offline for more than 7 days silently loses WhatsApp messages. | |
| R8 | IR | The worker spool also unlinks files older than 7 days (`capture.js:66-70`) and throws `QUEUE_FULL` at 10,000. `QUEUE_FULL` is surfaced in `status.json`; age-based loss is not. `prune()` runs `readdirSync` + `statSync` over the whole spool **on every incoming message** (`capture.js:51`), which is O(n) per message and O(n²) on a history-sync burst. | `server/whatsapp-web/src/capture.js:47-72` |
| R19 | IR | The guard monitor loop sets `guard.smtp_error = True` for **any** `tick` exception (including SQLite errors), conflating failure types. | `server/app/main.py:190-200` |
| R20 | VI | systemd units use `Restart=on-failure`. The worker exits non-zero on `disconnected`/`auth_failure` so it restarts and re-pairs. Status is published with an `updated_at` freshness check of 90 s at the relay. | `server/systemd/*.service`; `server/whatsapp-web/src/main.js:26-36`; `whatsapp_web.py:26-45` |
| R21 | IR | `main.js:26` comment says "Compose restarts the worker", but the deployment is systemd (per memory and the unit). Stale comment only. | |

### 2.6 Health monitoring coverage (CaptureGaps / Needs attention / Diagnostics)

| Signal | CaptureGaps notification | Needs-attention page | Label |
|---|---|---|---|
| Notification listener off or silent for more than 12 h (+ `requestRebind`) | yes | yes | VI (`A/insights/CaptureGaps.kt:35-88`) |
| SMS permission off / lag over 30 min against the provider | yes | yes | VI |
| Mail/WhatsApp integration error or stale for more than 6 h | yes | yes | VI (credentials vs outage conflated, R13) |
| Processing queue FAILED/PENDING counts | no | counts only | IR |
| Capture overflow (`CAPTURE_QUEUE_OVERFLOW`) | no | `capture_audit` failures count | IR |
| Drive backup failure, web/library sync failure | no | status text | IR |
| Cloud AI auth/budget failures | no | no | M |
| Morning brief / reminder restore failures | no | `reminders` audit only for restore | M (see S-list) |
| Low storage | no | free MiB shown | IR (no threshold/alert) |
| Relay 7-day expiry / spool poison (R18) | no | WhatsApp "not connecting" only after 6 h stale | M |

`Diagnostics.warn` is **a no-op in non-debuggable builds** (`A/Diagnostics.kt:11-20`). In release, a catch that only calls `Diagnostics.warn` records nothing anywhere.

### 2.7 Silent failures (release builds) in important paths

Counts in `A/`: 326 `runCatching`, 52 `catch (_:`, 144 `Diagnostics.warn`. Notable cases where nothing is persisted:

| # | Label | Location | Effect |
|---|---|---|---|
| F1 | VD | `A/tasks/TaskScheduler.kt:203-210` | Morning brief: `brief_day` is marked first, then `Briefs.load/notify` failure → `Diagnostics.warn` only. The brief is skipped for the day with no record. |
| F2 | VD | `A/security/PortableBackup.kt:288-291` | After restore, failures of `TaskScheduler.restore`, `MediaWork.recover`, `SearchBackfill.reset` and `rebuildSearch` are `Diagnostics.warn` only. A stale search index is not reported. (Reminders self-heal within 15 min via maintenance.) |
| F3 | VD | `A/data/SearchBackfill.kt:50-53` | A row whose payload fails to decrypt is skipped and the cursor advances. That event is **permanently absent from search**, and corrupt ciphertext goes unreported. |
| F4 | VD | `A/media/MediaVault.kt:274-281` | Orphan-recording recovery failure is `warn`-only. The ciphertext stays on disk, invisible to the owner. |
| F5 | VD | `A/communications/MailIdleService.kt:41-45` | `Accounts.mail(app.secrets)` failure (for example SecretStore decrypt) is `warn`-only and no integration state is written. |
| F6 | IR | `A/guard/GuardRecovery.kt:41-52` | Unlock-failure trigger errors are swallowed (`catch (_: Exception)`). |
| F7 | IR | `A/guard/GuardService.kt:134` | SIM-identity read failure is swallowed (comment says the setup screen reports it). |
| F8 | IR | `A/news/NewsWork.kt:68,80,88` | Ingest/briefing retries swallow the cause; `NewsRepository` records per-run rows, which partially covers it (UA). |
| F9 | IR | `A/widgets/Widgets.kt:42-44` | Widget load failure falls back silently. |
| F10 | IR | `A/HellFireApp.kt:46,123-126` | AI usage insert and Drive schedule `runCatching` with no record. Low impact. |

PE: in release, have `Diagnostics.warn` call sites on capture, backup, sync and guard paths write a class-name-only row, for example `capture_audit.failure(source, CODE)` or `integration_state`. That reuses existing tables; no new logging system is needed.

### 2.8 Low storage / corrupt files

| # | Label | Finding |
|---|---|---|
| R22 | VI | Storage-not-low constraints apply to Drive backup, model download and optional intelligence. Recordings use authenticated chunks with `retainInterrupted`. Microphone errors report "storage unavailable" (`A/audio/MicrophoneService.kt:155-182`). |
| R23 | IR | Capture paths (notification insert, SMS, relay pull) have no low-storage pre-check. SQLite `SQLITE_FULL` surfaces as `NOTIFICATION_STORAGE_FAILED` (recorded, VI), but there is no proactive owner alert. |

---

## 3. C5 Security & privacy

### 3.1 Manifest exported surface (`android/app/src/main/AndroidManifest.xml`)

| Component | Exported / protection | Assessment |
|---|---|---|
| `ShareActivity` | exported; SEND/SEND_MULTIPLE incl. `*/*` | VI. It forwards to `MainActivity` without reading. `SharePage` accepts only `content://` streams (`A/ui/SharePage.kt:41-44`) after the unlock gate. IR: the app's own `${applicationId}.share` authority is not rejected, but it maps only `cache/share/` (`res/xml/share_paths.xml`). Low risk. |
| `MainActivity` | `exported=false` | VI |
| `.Launcher` alias | exported MAIN/LAUNCHER + ASSIST | VI. Data from the alias is ignored (`A/MainActivity.kt:165-175`). |
| `.CalendarEntry` alias | exported VIEW `hellfire://task|source`, BROWSABLE | VI. `CalendarLinks.external` checks component, action, host whitelist, exactly one path segment matching `[a-f0-9-]{32,64}`, and rebuilds the URI (`A/calendar/CalendarLinks.kt:8-14`). The app lock still applies. |
| `HellFireVoiceService`, `HellFireSessionService` | `BIND_VOICE_INTERACTION` | VI |
| `HellFireNotificationListener` | `BIND_NOTIFICATION_LISTENER_SERVICE` | VI |
| `HellFireRecognitionService` | exported, `permission=RECORD_AUDIO` | IR (low). Any app holding mic permission can bind it. It only delegates to the platform on-device recognizer, with no app data (`A/assistant/HellFireRecognitionService.kt:12-40`). |
| `HellFirePlaybackService` | exported `MediaSessionService` | VI. `onConnect` allowlist: own package, trusted/system controller, notification controller (`A/player/HellFirePlaybackService.kt:101-107`). |
| `SmsReceivedReceiver` | `BROADCAST_SMS` | VI |
| `GuardAdminReceiver` | `BIND_DEVICE_ADMIN`, `:guard` | VI |
| `ReminderRestoreReceiver`, `GuardBootReceiver`, all widget receivers, `PipActionReceiver`, `ReminderReceiver` | `exported=false` | VI. Widget receivers rely on system-uid delivery. Placement and updates are NOT TESTED on device (UA). |
| `FileProvider` `.share` | not exported, grant-only, `cache/share/` | VI |
| `allowBackup=false`, `dataExtractionRules` exclude all domains, `usesCleartextTraffic=false` | — | VI |
| ONNX `TelemetryInitializer` and `TransportBackendDiscovery` removed | — | VI |
| Permissions | `ACCESS_BACKGROUND_LOCATION`, `READ_PHONE_STATE`, `READ/RECEIVE_SMS`, `CAMERA`, `RECORD_AUDIO`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM` | IR. Broad but owner-authorized for TheftGuard/capture. Sideloaded only. |

### 3.2 Keys, credentials, at-rest encryption

| # | Label | Finding | Evidence |
|---|---|---|---|
| K1 | VI | The SQLCipher password is random 32 bytes (Base64), wrapped by the Keystore AES-GCM key in `noBackupFilesDir/database-key.enc` via `AtomicFile`. There is no plaintext fallback if the key file is missing (`check(...)`). A one-time plain→encrypted migration runs with integrity/count/version checks and an atomic move. | `A/security/EncryptedDatabase.kt:13-63` |
| K2 | IR | The Keystore key has no `setUserAuthenticationRequired`, `setUnlockedDeviceRequired` or StrongBox. That is necessary for background capture, but **app lock is UI-only** (`A/security/AppLock.kt:20-26`), not cryptographically bound, and off by default. Accepted design for a single owner. Document it as a known limit. |
| K3 | IR | If the Keystore key is lost (OS bug, factory reset of the keystore), the wrapped DB password and every `SecretStore` file become unrecoverable. Startup shows `UNAVAILABLE`. Recovery is only through a portable passphrase backup. No specific "key invalidated" diagnosis. | `PayloadCipher.kt:46-58`; `HellFireApp.kt:143-145` |
| K4 | VI | `SecretStore`: per-name AtomicFile in `noBackupFilesDir`, Keystore-GCM, name regex `[a-z-]{1,30}`. | `A/security/SecretStore.kt:8-24` |
| K5 | VI | Portable and Drive backup: PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt, passphrase of at least 12 chars, chunked AES-GCM with AAD. | `A/security/BackupCrypto.kt:7-15`; `A/security/BackupStreams.kt:63` |
| K6 | IR | `encryptStream` (microphone **samples**, not monitoring recordings) is a single GCM stream with no AAD binding to file id. Samples are size-bounded (`MicrophoneService.MAX_BYTES`), and `SamplePlayback` decrypts the whole file in memory. Low risk. | `PayloadCipher.kt:28-38`; `A/audio/SamplePlayback.kt:11-13` |
| K7 | IR | `android/signing.properties` holds keystore passwords in plaintext in the working tree. It is gitignored (`.gitignore:45`), so not a repo leak. It matters for host backups. |

### 3.3 Logging / redaction

| # | Label | Finding |
|---|---|---|
| G1 | VI | Android `Diagnostics` logs exception class + frames only, debug builds only (`A/Diagnostics.kt:6-33`). |
| G2 | VI | Notification OTP/security redaction runs before encryption and FTS. A security keyword withholds all content, and keyed/numeric codes are redacted while amounts and dates are kept (`A/events/NotificationNormalizer.kt:36-88`). FTS excludes sensitive and unavailable rows (`EventRepository.kt:39-40`). |
| G3 | VI | Relay access logging strips the query string (`server/app/logging.py`), so `hub.verify_token` is not logged. The worker never prints QR, bodies or profiles (`main.js:37`). |
| G4 | IR | The nginx access log (`web/nginx/hellfire.local.conf`) records full URIs, including `/api/library/changes?after=…` query strings. These carry no secrets, but they are a request-metadata trail. |

### 3.4 Cloud AI exposure

| # | Label | Finding | Evidence |
|---|---|---|---|
| A1 | VI | Cloud is opt-in (`cloudEnabled`), per-provider enabled, key present, monthly cap and per-model price required (an unknown price yields `BUDGET`). Non-`USER_TEXT` inputs are forced to the offline provider. Size limit is 32 KB. | `A/ai/AiRouter.kt:66-127` |
| A2 | IR | `CloudPrivacy.redact` covers only the OTP/code rules, emails and phone-like digit runs. **Names, amounts, merchant/till numbers kept by the finance exception, addresses and message bodies** pass through to cloud providers. `AssistantEngine` builds evidence snippets with `EventText.raw(...).take(450)` per record (`A/assistant/AssistantEngine.kt:107`). Acceptable only with owner consent. The UI disclosure was not re-verified (UA). | `A/ai/AiRouter.kt:17-26` |
| A3 | VI | Audit and usage store metadata only (no prompt or answer). | `AiRouter.kt:34-36` |

### 3.5 Relay (`server/app/*.py`)

| # | Label | Finding | Evidence |
|---|---|---|---|
| X1 | VI | Device bearer is compared with `hmac.compare_digest`. Startup requires a token of at least 32 chars, a store key of 32 bytes and an RSA key of at least 2048 bits. Meta webhook is HMAC-SHA256 verified over the raw body (1 MiB cap). Strict pydantic models with `extra=forbid`. Event IDs regex-validated on batch ack. | `server/app/main.py:79-86`, `100-104`, `230-236`, `275-290`, `323-331` |
| X2 | VI | Events are sealed to the **phone's RSA public key** (OAEP-SHA256/MGF1-SHA1 + AES-GCM, AAD). The relay cannot read stored inbox rows. | `main.py:120-124` |
| X3 | VI | Binds `127.0.0.1:8080`. Uses `--proxy-headers --forwarded-allow-ips 127.0.0.1`. Rate limit is 120/min per peer and 1200/min for the device, kept in SQLite. Sets `no-store`, `nosniff`, `no-referrer`. `/pair` has a strict CSP. | `server/systemd/hellfire-relay.service`; `main.py:217-228`, `244-248` |
| X4 | M | **No reverse-proxy/TLS config for the relay in the repo.** `web/nginx/hellfire.local.conf` proxies only the website on port 3910. The unit comment says "point the HTTPS reverse proxy here". The phone requires `https` (`A/communications/GatewayAdapter.kt:51`, `A/guard/GuardRelay.kt:17`). The relay's TLS termination is therefore undocumented and untested. | |
| X5 | IR | `/health` is unauthenticated and discloses `configured`, `outbound_configured` and `transport`. Low risk. | `main.py:238-242` |
| X6 | IR | The rate-limit middleware writes SQLite (DELETE + UPSERT + SELECT under a global RLock) for **every** request, including unauthenticated ones. A flood serializes all requests on that lock: a cheap DoS amplifier in front of the phone's sync. | `main.py:105-110`, `217-228` |
| X7 | IR | The draft approval nonce comes from the same bearer that approves and sends, so it guards against replay, not against a stolen token. The real gate is phone-side approval. Sending is disabled for the web transport. | `main.py:333-389` |
| X8 | IR | The one `STORE_KEY_BASE64` is reused for draft encryption, spool encryption (worker) and guard mail boxes, separated by AAD. It sits in `.env` on the same host as the encrypted data, so at-rest encryption protects only against disk-only exposure. | `main.py:340-342`; `theft.py:160-161`; `whatsapp_web.py:67` |

### 3.6 WhatsApp-web worker

| # | Label | Finding |
|---|---|---|
| Y1 | VI | `process.umask(0o077)`. Spool dir `0700`, files `0600`, atomic rename. Bodies are AES-GCM-encrypted with an id-bound AAD. Only selected documented message fields are serialized. Bodies are capped at 16,000 chars and events at 128 KiB. (`server/whatsapp-web/src/main.js:6`; `capture.js:8-58`) |
| Y2 | IR | The `LocalAuth` session dir (`state/whatsapp-web-session`) is a **plaintext full WhatsApp Web credential and history store** (the unit comment acknowledges this). Protection is the `0700` umask, the `dev` user and host disk encryption (UA). Anyone with that user or a backup of it can impersonate the linked device. |
| Y3 | PE | systemd hardening for both units: only `NoNewPrivileges` and `PrivateTmp` today. Add `ProtectSystem=strict`, `ReadWritePaths=` for the state dirs, `ProtectHome=read-only` (paths live under /home, so scope carefully), `PrivateDevices`, `RestrictAddressFamilies`, `UMask=0077`. The worker runs Chrome, so test that the Chrome sandbox still starts. The `ExecStart` path is pinned to an nvm-specific node path; that is fragile across upgrades (IR). |
| Y4 | VI | Lead's dependency audit: 6 moderate (glob/minimatch transitive), not re-run here. |

### 3.7 Web API (`web/api/src/*.ts`) and frontend / nginx

| # | Label | Finding | Evidence |
|---|---|---|---|
| Z1 | VI | Owner password hash is scrypt (N=2^15, r=8). Session is a stateless HMAC token (12 h) with global revocation via `sessionsAfter`. Tokens are compared in constant time via SHA-256 digests. | `web/api/src/auth.ts:8-46`; `db.ts:75-90` |
| Z2 | VI | API listens on loopback (`config.ts`: `HOST` defaults to `127.0.0.1`). Throttle is global 600/min, login 5/min, keyed by `X-Real-IP` that nginx sets. | `app.ts:19`; `view.ts:126`; `auth.ts:86-97` |
| Z3 | VI | Browser session is an `httpOnly; Secure; SameSite=Strict` cookie (`web/frontend/app/actions.ts:18`). The API uses bearer headers, so classic CSRF does not apply. Next server actions add an origin check (framework behaviour, UA). | |
| Z4 | IR | `scryptSync` runs on the request thread in login, blocking the Node event loop per attempt. The 5/min limit is **per X-Real-IP**, so many source IPs can still stall the API. Use async `scrypt`. | `auth.ts:19-24` |
| Z5 | IR | **The web mirror is plaintext.** The phone decrypts event fields (`A/sync/WebSync.kt:89`) and pushes messages, ledger, transcripts, memory, events and summaries as JSONB into Postgres `record` (`web/api/src/sync.ts:58-74`). Server or DB-backup compromise exposes the full corpus. The encrypted library path exists only for media. It is owner opt-in, but the privacy page should state this plainly (UA whether it does). | |
| Z6 | M | No `Content-Security-Policy` and no `Strict-Transport-Security` header (`web/frontend/next.config.ts:12-21` sets only XFO, nosniff, Referrer-Policy; nginx adds none). |
| Z7 | IR | nginx listens on all interfaces (`listen 443`/`[::]:443`), so the "local" site is reachable from the LAN, protected only by the owner password and a self-signed cert. No `ssl_protocols` pin (nginx defaults apply). | `web/nginx/hellfire.local.conf` |
| Z8 | VI (known blocker) | The phone trusts system CAs only (no `network_security_config`; no custom TrustManager found under `A/sync`, `A/communications`, `A/ai`, `A/security`), so it rejects the self-signed `hellfire.local`. Already recorded as BLOCKED (`docs/DEVICE_FEASIBILITY.md:888`; `README.md:706-707`). |
| Z9 | VI | Lead's dependency audit: 0 vulns for web/api and frontend. Not re-run. |

### 3.8 TheftGuard and Device Owner

| # | Label | Finding | Evidence |
|---|---|---|---|
| T1 | VI | Policies apply only if `isDeviceOwnerApp`. Previous state is saved to SecretStore `guard-policy` before applying uninstall-block, `DISALLOW_SAFE_BOOT` and lock-task changes, and `restore` replays it. Arming and disarming require `BIOMETRIC_STRONG` (`A/security/AppLock.kt:29-48`). | `A/guard/GuardRecovery.kt:77-105` |
| T2 | **M / IR (high operability risk)** | **There is no `clearDeviceOwnerApp` / relinquish path in the code or docs.** Once DO is provisioned with `dpm set-device-owner` (a non-`testOnly` app), removing it generally requires the app to relinquish it, or a factory reset. If `restore` cannot read `guard-policy` (Keystore or SecretStore failure → `?: return`, `GuardRecovery.kt:98`), uninstall stays blocked and safe boot stays disallowed, with no in-app escape. | `GuardRecovery.kt:95-105`; `docs/DEVICE_FEASIBILITY.md:2010-2019` |
| T3 | IR | The heartbeat relay endpoint requires the device bearer plus a payload encrypted with `GUARD_PAYLOAD_KEY` and a monotonic `sequence` (stale → 409). Evidence images are validated as JPEG and limited to 2 images; a 256 MiB outbox is retained. SMTP is implicit TLS only. | `server/app/theft.py:26-215` |
| T4 | IR | The BFU unlock-failure trigger is silently unavailable (R4). The `MY_PACKAGE_REPLACED` restart is missing (R3). | |

### 3.9 External actions gating

| # | Label | Finding |
|---|---|---|
| E1 | VI | Mail/WhatsApp sends need an explicit approve step in the Inbox (`sending = d` → confirmation). Relay drafts need a nonce approval within 300 s. Failures become `DELIVERY_UNCONFIRMED` with no auto-retry (R10). Cloud AI DRAFT/EXTRACTION prompts say "Do not send / do not create" (`AiRouter.kt:102-108`). |
| E2 | UA | SMTP send path, calendar writes (`WRITE_CALENDAR`) and template actions were not individually traced for approval gating this pass. |

### 3.10 Owner data rights (inspect / export / correct / delete)

| Right | State | Label |
|---|---|---|
| Inspect | Raw source evidence cards (`EventText.raw`), Needs-attention, Privacy page, Sync dashboard | VI |
| Export | Encrypted portable archive and Drive archive, selective restore | VI. A human-readable export (CSV/JSON) of captured records was not found (UA). |
| Correct | Memory revisions (`memory_revision`), ledger reversals (`reversesId`), owner corrections | VI |
| Delete | Media items, memory `forget`, drawer purge, web mirror reset (`sync.ts:47-56`). **Captured events have only all-or-nothing deletion** (`A/data/EventDatabase.kt:208-212`; UI `A/ui/settings/SettingsScreens.kt:315`), and it fails entirely once any FK evidence exists ("some events are evidence…"). No per-record, per-app or per-period deletion of `source_event`/`communication_message`. Deleting on the phone does not propagate to the relay, web mirror or Drive archives (other than through a mirror reset). | **M** |

### 3.11 Android dependencies (`android/gradle/libs.versions.toml`)

AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2025.10.00, Room 2.8.4, WorkManager 2.10.5, Lifecycle 2.9.4, Glance 1.1.1, Media3 1.11.1, CameraX 1.5.3, SQLCipher-android 4.20.0, ONNX Runtime 1.31.1, LiteRT-LM 0.12.0, MediaPipe tasks-text 0.10.26, ML Kit OCR 16.0.1 / labeling 17.0.9 / barcode 17.3.0, play-services-auth 22.0.0 (uses the current `Identity.getAuthorizationClient`, not the deprecated GoogleSignIn: `A/security/GoogleDriveBackup.kt:21`), play-services-location 21.4.0, `com.sun.mail:android-mail` / `android-activation` 1.6.8, core 1.15.0.

| # | Label | Finding |
|---|---|---|
| V1 | UA | `com.sun.mail:android-mail` is the legacy `javax.mail` line, in maintenance mode compared with Jakarta/Angus Mail. I have **no CVE evidence** for 1.6.8. The production IMAP TLS settings are hardened (`checkserveridentity`, TLSv1.2/1.3 only: `MailIdleService.kt:58-64`). Flag for watch, not for upgrade. |
| V2 | — | Nothing else is flagged: no evidence-based outdated or risky dependency was found without a network lookup, and the 2026-11-16 dependency check (`docs/DEVICE_FEASIBILITY.md:837`) already covers it. |

---

## 4. Prioritized next steps (smallest changes first)

| P | Item | Type | Verify by |
|---|---|---|---|
| 1 | R18: quarantine bad spool files (move to `outbox/rejected/` and count) instead of 503-ing every poll | VD fix | pytest with a corrupt file followed by a valid file |
| 1 | T2: add an owner-biometric "Relinquish Device Owner" action (`restore` + `clearDeviceOwnerApp`) and document it | M | Robolectric DPM shadow; device NOT TESTED |
| 1 | §1.3 indexes (v22 migration, `CREATE INDEX` only) | VD fix | migration tests plus host EXPLAIN plan, then device SQLCipher timing |
| 2 | D2/W2/U1–U4: bounded SQL for widgets/inbox, table→widget map, `SUM(size)`, remember Flows (C1) | VD fix | unit tests on DAO results; Macrobenchmark frame metrics |
| 2 | N2/N3: coalesce ongoing/progress re-posts per key | VD/PE | host test with 1,000 synthetic progress posts |
| 2 | F1–F5 / §2.6: persist release-build failure codes into existing `capture_audit`/`integration_state`; split mail AUTH vs NETWORK (R13) | VD fix | unit tests on error mapping |
| 3 | AdultClassifier session reuse under `HeavyModelGate` | VD fix | host test counting `createSession` calls |
| 3 | Retention for `source_event`/news; per-record delete (§3.10) | M | migration plus FK-aware delete tests |
| 3 | Relay TLS proxy config (X4), CSP/HSTS (Z6), async scrypt (Z4), systemd hardening (Y3) | M/PE | `nginx -t`, curl header check, systemd-analyze security |
| — | Device: Macrobenchmark baseline, Keystore decrypt microbenchmark (D6), notification-volume run, migration androidTest | measurement | all NOT RUN; targets to be set only after baseline |
