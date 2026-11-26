# 08 — Assistant evaluation plan

All thresholds below are **targets**, not results. No intelligence-level measurement exists today. The only evaluation code is deterministic unit tests and parser goldens; see the existing-test inventory at the end of this file.

## Principles
- **Reuse what exists.** Extend `tools/generate_predevice_fixtures.py`, which already follows these conventions:
  - fixed seed;
  - a `synthetic: true` marker;
  - a `--check` mode for byte equality;
  - an `evaluation_limits` block.
  
  Also extend `PredeviceGoldenTest` with `android/app/src/test/resources/predevice/golden.json`. Do not replace either.
- **Inject through the real entry points:**
  - `NotificationNormalizer` and the capture path;
  - `SmsProviderReader` (virtual modem in `CaptureIntegrationTest`);
  - relay JSON through `GatewayEvents`;
  - the GreenMail mail fixture (`tools/test_mail_fixture.sh`).
- **No real SMS, messages or recordings in the repository (AGENTS.md).**
  - Provider formats are written as templates from documented or owner-described shapes, marked synthetic.
  - Calibration on real messages happens on the phone, and only the scores are recorded, in `docs/DEVICE_FEASIBILITY.md`.

## Datasets

| Set | Content | Size (target) | Source |
|---|---|---|---|
| Life timeline | 12 weeks for one synthetic owner: SMS from each provider (M-Pesa, Airtel, Mixx, HaloPesa, AzamPesa, Selcom, CRDB, NMB, NBC), notifications, email threads, WhatsApp relay events, calendar, debts, two recordings' transcripts, contradictions (a rescheduled meeting, a corrected amount) and interruptions (process death between capture and processing) | ~2,000 events | seeded generator |
| Intent routing | Phrasings per intent (finance, tasks, calendar, news, search, each command), in English, Kiswahili and mixed speech, including spoken amounts ("elfu tano", "5k"), Swahili clock times and relative times | ≥ 30 per intent | hand-written plus generated variants |
| Time expressions | am/pm, 24 h, Swahili clock with period, relative, day parts, weekdays (including today), ordinals | ≥ 150 | hand-written |
| Finance formats | Per provider: payment, receipt, transfer, withdrawal, airtime, bundle, LUKU, fee/levy, reversal, cents; each in English and Kiswahili | ≥ 10 per provider | templates |
| Cross-source questions | "What did <person> and I agree about <topic> in <month>?" and similar, with the expected source ids | ≥ 40 | from the timeline |

## Metrics and targets

| Metric | Definition | Target |
|---|---|---|
| Route accuracy | correct handler for an intent-set utterance | ≥ 95 % |
| Time accuracy | exact due time for the time-expression set | ≥ 98 % |
| Finance extraction | provider, kind, amount and fee all correct | ≥ 95 % per provider (spec §5) |
| False finance candidates | candidates from non-finance events in the timeline | ≤ 1 % of non-finance events |
| Task extraction precision / recall | against labelled commitments in the timeline | ≥ 0.85 / ≥ 0.70 |
| Ledger exactness | sum of confirmed entries equals the expected balance | 100 % |
| Retrieval recall@10 | expected source in the top 10 for cross-source questions | ≥ 0.80 |
| Grounded hallucination | model answers citing a source outside the evidence set, or stating an unsupported number | 0 citations outside the set; ≤ 5 % unsupported statements (human-rated sample) |
| Proactive precision | owner-useful alerts ÷ alerts, rated on the timeline | ≥ 0.7 |
| Alert volume | non-alarm notifications per day on the timeline | ≤ 6 |
| Duplicate actions | double posts or double tasks after replaying interrupted runs | 0 |
| Unauthorized actions | external sends or ledger posts without an approval record | 0 |
| Recovery | pending work completed after injected process death | 100 % |
| Steps per task | taps or utterances for: set reminder, confirm payment, snooze, reply | each ≤ current count |

## Performance and reliability measurements
These reuse the existing benchmark module and protocol in `docs/DEVICE_FEASIBILITY.md` (Performance audit 2026-11-18). New items:

| Measurement | Where |
|---|---|
| Query plans for the HF-020 indexes at 10k and 100k events | host SQL workload, extended |
| Keystore decrypt cost (p50/p95 for 1,000 decrypts) | device |
| Notification re-post volume over one day with media playback and navigation | device |
| Brief load time on the timeline dataset | host and device |

## Regression testing
- Every defect fixed from `02-verified-defects.md` gets the named regression test in the Android unit suite.
- The intent-routing and time sets run as a parameterised JVM test on every build. A drop below target fails the build once the targets are met for the first time; until then the test reports the score.
- The timeline replay runs as a host test, with Room in-memory, and reports every metric above to `build/reports/assistant-eval.json`. Scores are recorded in `docs/DEVICE_FEASIBILITY.md` checkpoints, never in code.

## Existing tests (inventory)


### T1. Inventory

**Android JVM unit tests** (`android/app/src/test`): 71 files, **551 `@Test`**. The README checkpoint says 546 passed and 5 skipped (credential- or fixture-gated). Not re-run here.

| Area | Files (tests) | Total |
|---|---|---:|
| Capture/notifications/SMS | NotificationNormalizer 19, CaptureProcessing 13, EventRepository 7, SmsCapture 6, RuleClassifier 5 | 50 |
| Finance/reports/widgets | Finance 12, ReportData 11, WidgetData 7, BalanceAndCalendar 4 | 34 |
| Tasks/calendar | CalendarIntegration 14, PhoneCalendar 8, TaskLogic 6, TaskPersistence 2 | 30 |
| Assistant/AI/models | AiRouter 18, AiProvider 14, AssistantCommands 7, ModelUpdates 6, AiLiveProvider 4 (opt-in), SemanticIndex 4, AiSettings 3, AssistantContract 3, LocalAssistant 3, LanguageContext 2 | 64 |
| Comms/contacts | ContactLinks 8, Communication 7, PhoneContacts 7, GatewayEvents 4, MailProtocolFixture 1 | 27 |
| Daily outputs/review cycles | ReviewImprovements 24, EverydaySkills 23, DailyOutputs 15, ReviewFixes 13, RedesignLogic 3 | 78 |
| Security/backup/migrations | BackupCoverage 11, DriveBackup 9, PayloadCipher 6, BackupTables 5, SchemaV10 3, DatabaseMigration 1 (+ `MigrationFixture` helper) | 35 |
| Web mirror | WebSync 8 | 8 |
| Library sync | LibrarySyncReview 16, LibrarySync 14 | 30 |
| News | NewsEngine 11, NewsRuntime 7, NewsSync 4, NewsFeed 3 | 25 |
| Media/recording/ML | MediaFoundation 10, Intelligence 9, Media 8, Speaker 5, WhisperCpu 5, MediaVaultContract 4, BarcodeEvidence 3, RecordingEntryPoints 3, LearnedVadSegmentation 2 | 49 |
| Library/player/sharing/drawer | DrawerBackupReview 18, PlayerShareReview 14, LibraryReviewRegression 13, LibraryFeature 8, PlayerLogic 7, SharingPrivacy 7, Drawer 5, PlayerStore 4 | 76 |
| Performance | Performance 13, ResourceUse 6, PerformanceRegression 5 | 24 |
| Guard | Guard 7 | 7 |
| Pre-device/platform contracts | PredeviceRegression 6, AndroidIntegrationContract 5, PredeviceGolden 3 | 14 |

**Android instrumented tests** (`androidTest`): 10 files, **20 tests**.
- Platform 6, Capture 5 (real notification listener withholds OTP; virtual-modem SMS through the provider and worker; denied SMS/mic stay unavailable), ONNX 2, MediaExport 2, Whisper 1, Navigation 1, NativeMigration 1, ComponentRendering 1 (`goldens/components-light.png`), Barcode 1, plus the `PredeviceRunner` runner.
- Assets: `jfk.wav`, `qr-public.png`, `synthetic-av.mp4`, two ONNX layout models.

**Server and web:**

| Suite | Tests |
|---|---|
| Python relay (`server/tests`) | 31: `test_gateway.py` 9, `test_whatsapp_web.py` 9, `test_theft.py` 13 |
| WhatsApp worker (`server/whatsapp-web/test/capture.test.js`) | 6 |
| Web API (`web/api/test/api.test.ts`) | 31 `it()` against real Postgres: auth/sessions/logout, sync batches and dataset 409, filters/paging, library chunks/resume/quota/GC/conflicts/legacy upgrade/key check/crypto parity, news actions/overlay |
| Browser (`tools/test_web_browser.mjs`) | 7 Chromium checks (per README) |
| Tool self-tests | `tools/test_predevice_tools.py`, `tools/test_performance_report.py` |
| Orchestrator | `tools/test_predevice_services.sh` runs the generator check, pytest, worker, API, frontend build and browser checks against a disposable Postgres container |

### T2. Golden fixtures

- **`PredeviceGoldenTest`** (3 tests) reads `src/test/resources/predevice/golden.json` (6.9 KB, `schema 1`, `synthetic: true`, seed 18112026, `now 2026-11-18T12:00+03:00`):
  - `finance`: 12 cases. 8 positive (EN "Paid" / SW "Umelipa" × M-PESA, Airtel, CRDB, NMB; random amounts; asserts provider, kind, amount, fee, reference) and 4 rejects (failed transaction, decimal 1.25, zero; EN+SW). Run through `FinanceParser.parse`.
  - `queries`: 12 (6 EN/SW pairs) through `QueryParser.parse`: kinds, categories and date windows (last-year, today, yesterday, last-week), with residual text asserted blank.
  - `media`: `Sampling.schedule` FAST/BALANCED timelines and `BpmEstimator` on a 120 BPM click track.
  - The fixture's own `evaluation_limits` field: "Parser goldens are not model accuracy measurements", "Synthetic tones do not validate speech recognition or speaker identity", "No GPU, NPU, codec, battery or thermal result is implied".
- **`tools/generate_predevice_fixtures.py`** (74 LOC) deterministically regenerates that JSON. `--check` asserts byte-equality, and `--media` writes a 440 Hz WAV plus a truncated WAV. The finance templates are a single sentence shape ("{verb} TZS {amount} ref {ref}. Synthetic fixture."), which says nothing about real M-Pesa, Airtel or bank formats (README: "checked on synthetic M-Pesa / Airtel texts only").
- **`tools/fixtures/`:**
  - `models.json`: pinned public test models (whisper tiny.en q5_1, Silero VAD) with URL, SHA-256, size and purpose.
  - `public-audio.json`: `jfk.wav` provenance.
  - `synthetic-media.json`: ffmpeg recipe, hash and trim/mute expectations for `synthetic-av.mp4`.

### T3. Intelligence-level evaluation vs unit tests

**VI: everything present is deterministic contract or regression testing:**
- Parser goldens.
- Rule behaviour: Ask filter clarification (`ReviewImprovementsTest`), Swahili spoken amounts and commands (`DailyOutputsTest`, `AssistantCommandsTest`).
- News ranking and clustering on synthetic feeds (`NewsEngineTest`).
- Real-model *runtime* contracts: Whisper on `jfk.wav`, Silero, ONNX layouts. These check format and timestamps, not accuracy.
- `AiLiveProviderTest` (OpenAI, Claude, Grok, DeepSeek): opt-in connectivity, skipped without env keys.

**M:**
- An answer-quality scorer, precision/recall metrics or a regression scoreboard.
- Cross-source scenario replays (notification → candidate/task → reminder → brief → Ask answer).
- Kiswahili ASR WER, extraction precision on realistic messages, false-task and false-payment rates.
- Ask answer grounding (cited source correct).
- Proactive-noise metrics (alerts per day).

DEVICE_FEASIBILITY lists this as next-phase item 4 (planned).

**PE: what a seeded synthetic personal-life dataset should reuse.**

| Reuse | Why |
|---|---|
| The generator pattern in `generate_predevice_fixtures.py` | Fixed seed, `synthetic: true`, `--check` byte-equality, an `evaluation_limits` block. Extend it from single sentences to a timeline: N weeks of SMS, notifications, email/WhatsApp relay events, calendar entries and recordings' transcripts, with expected candidates, tasks, debts, waiting-on, expiry hits and brief contents |
| Existing entry points as the injection layer, so replays exercise the real pipeline | `NotificationNormalizer` / `CaptureProcessingTest` capture path, `SmsProviderReader` (virtual-modem path in `CaptureIntegrationTest`), `GatewayEvents` relay JSON (`server/tests` builders), `MailProtocolFixtureTest` / `tools/test_mail_fixture.sh` (GreenMail-style IMAP/SMTP container) |
| Expected-output oracles that already exist | `FinanceParser` and `CashParser` goldens, `ExpiryFinder`, `Recurring.detect` / `forecast`, `People.build`, `Briefs.build` (`DailyOutputsTest` already asserts "the brief gathers tasks, bills, replies, debts and capture problems"), `QueryParser` |
| `MigrationFixture` + exported Room schemas | Seed old-schema databases from the same timeline to test upgrade plus backup/restore on realistic volume (`BackupCoverageTest`) |
| `WebSyncTest`'s fake `Call` server and the API test harness | Measure mirror volume and pass time on the synthetic corpus (validates the IR in D7) |
| Public audio (`tools/fixtures/public-audio.json`, `models.json`) | Add licensed Kiswahili speech samples with transcripts, recorded under the same provenance schema; do not synthesize "speech" from tones |

**Hard boundary (AGENTS.md):** no real SMS corpora or personal data in the repo. Real-format calibration stays a device/owner-side step, with results recorded in DEVICE_FEASIBILITY rather than committed.
