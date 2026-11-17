# HellFire Ass — implementation and feasibility

Latest update: 2026-11-17 (news/media/performance integration, directly below). Earlier
checkpoints keep the test counts and APK checksums that were true when they were
written; the newest checkpoint has the current ones.

## News, media and performance integration — 2026-11-17

Room/backup schema **19** preserves the media-library schema 18 and adds news.
Pending news and seven media implementations were consolidated with adaptive
performance. Source worktrees were preserved during integration.

- News: feeds/source settings, clustering/ranking, versioned stories, scheduled
  briefings, quiet-hour alerts, reader/TTS controls, saved/followed stories, Ask
  routing and web state synchronization. Publisher availability, licensed sources,
  X access and actual phone speech/delivery remain unverified.
- Media: external library references and managed originals, library scanning,
  playback/PiP/bookmarks, content analysis/search, drawer/inbox, sanitized sharing,
  encrypted optional library sync and backup/selective recovery pages.
- Corrected severe thermal threshold (Android status 3), concurrent interactive
  waiter accounting, capped concurrent media queue requests, nullable video hashes,
  and cached-model eviction before benchmarks.
- Integration/recovery fixes cover metadata-only archives, selective transcript
  evidence, stable encrypted chunk identity, bounded upload geometry, concurrent
  record versions, deleted search entries and preservation of library data/salt
  during ordinary mirror resets.

| Check | Result | Evidence / limit |
|---|---|---|
| Performance/media regression subset | PASS (host) | 49 tests, zero failures/errors/skips, including concurrent queue cap and media foundation/vault/speech/semantic tests |
| Combined web API | PASS (host) | 24 tests against disposable `hellfire_test`, including news actions, encrypted chunks, version conflicts, quota checks and mirror-reset preservation |
| Combined frontend typecheck | PASS (host) | `npm run typecheck`; no browser rendering claim |
| Final integrated Android tests, lint and APK | PENDING | Updated after final combined verification |
| Physical device | NOT TESTED | `adb devices -l` lists no connected devices; codecs, Keystore/native SQLCipher, actual inference, battery, thermals, permission flows and OEM behavior are not established by host tests |

## Adaptive performance — 2026-11-17

Host-tested policy and hooks around the existing WorkManager jobs, `HeavyModelGate`
and model classes. No second queue, worker or model manager. The verified release
APK was not rebuilt or replaced. Token budget for language answers stays 4096 so
financial JSON extraction is not truncated. Encryption, backup restore and send
approval are unchanged. Room schema stays 18; managed-original counts stay separate from external library entries.

| Check | Evidence | Result | What was shown | Still required |
| --- | --- | --- | --- | --- |
| Operating profile, backend fallback, yield, model-slot eviction, incremental SHA-256, scene filter, silence skip, job cap, gzip decision, query/perf logs | `./gradlew :app:testDebugUnitTest --tests com.hellfire.ass.PerformanceTest` | PASS (host) | 11 tests, 0 failures. Duplicate frames collapse to 1 of 20; frames that differ by 8+ bits keep the old 20; silence skips; a tone does not; CPU wins until a successful measurement; deferred work yields only while interactive work is waiting | None for the policy itself |
| LiteRT CPU/GPU/NPU speed on the OPPO | — | NOT TESTED | Build can construct `Backend.CPU`, `Backend.GPU` and `Backend.NPU`. Selection stays CPU until Settings → This device → Performance runs the probe. A failed accelerator falls back to CPU | Run the probe on the OPPO with the imported Gemma file. Record ms, PSS and whether GPU/NPU initialize |
| whisper.cpp, ONNX, MediaPipe accelerators | Code review | NOT SWITCHED | Sessions may be reused and dropped under low memory. Execution provider is still CPU. Switching ONNX/whisper/MediaPipe delegates could change speaker and embedding results | Do not enable NNAPI/GPU delegates until a device A/B test shows the same outputs |
| Scene-aware video OCR and silence skip | `PerformanceTest` plus `VisualReader` / `MediaWork` | PASS (policy) / NOT TESTED (codecs) | Near-duplicate frames are not OCR'd. Uniform timestamps are unchanged when frames differ or fingerprints are missing. Near-silence chunks do not load whisper | Play a real recording and a static video on the OPPO. Confirm speech is not dropped and sampled OCR still says it is a sample |
| Encrypted search | Existing FTS `LIMIT` and semantic pages of 500 | UNCHANGED + timed | Search SQL and encryption are unchanged. `QueryTrace` stores the label, duration and row count only | Time a large owner database on the phone. Add an index only if a named query is slow |
| Compose | Settings counts; media list pages of 30 | PASS (code) / NOT TESTED (frames) | Settings no longer loads every memory and recording to show a count. The recordings list composes 30 rows at a time inside the existing scroll page | Macrobenchmark frame times on the OPPO |
| Baseline / startup profile | `StartupProfile.critical` | NOT INSTALLED | A guessed profile was not put in the release APK | Generate a baseline profile on the OPPO and compare startup before installing it |
| Web sync compression | `PayloadCompression` | NOT ENABLED | Change-based batches and retry already exist. Gzip is smaller in the host test but the server does not advertise it | Add server gzip support before sending `Content-Encoding: gzip` |
| Android 16 / ColorOS quotas | `JobBudget` cap of 20 media jobs; existing WorkManager constraints | NOT TESTED (device) | Backlog stays in Room and is pumped by the existing 15-minute maintenance worker. Stop reason is recorded as a number if a job is cancelled | On the OPPO, queue more than 20 analyses, unplug, heat the phone, and confirm jobs resume without a second queue |
| Battery, thermal, power | — | NOT TESTED | Dashboard can show thermal, battery percent and `BATTERY_PROPERTY_CURRENT_NOW` when the kernel exposes it | Overnight before/after on the same workload. Do not treat host tests as power results |

### OPPO verification matrix

Do this on the physical phone after installing a debug build. Do not treat a host
PASS as a device PASS.

1. Language model: Performance → Measure language backends. Record CPU/GPU/NPU ok-or-fail and milliseconds. Confirm a failed NPU still answers on CPU.
2. Leave the dashboard open and scroll Home, Money and Recordings. Record frame samples. This is not a Macrobenchmark.
3. Import one speech recording and one near-static video. Confirm silence is skipped, speech is transcribed, and video OCR stays a sample of at most 20 frames.
4. Start indexing, then ask a local question. Indexing should stop at the next batch so the question can run, then continue.
5. Queue more than 20 media analyses. Confirm only a bounded set is scheduled and the rest stay queued until maintenance or a finished job.
6. Force thermal severe or low battery. Confirm analysis waits instead of failing, and capture still ingests.
7. Read `BATTERY_PROPERTY_CURRENT_NOW` if present. Compare an overnight idle with and without HellFire. ColorOS may defer WorkManager; that is not a host result.
8. Generate a baseline profile with Macrobenchmark on this device before adding one to a release APK. The current release APK has no baseline profile from this change.

## Everyday reliability and AI skills — 2026-11-17

Version **0.9.0** (code 9), Room/backup schema **17**. Owner authorized the eight
follow-up improvements, editable learned AI skills with reasons/usage history,
and selectable local models from reliable published sources.

- Skills are typed guidance/payment-default/false-task/false-payment rules, stored
  with creation reason, source, status and revision. Normal use proposes rules;
  explicit approval enables them. New advice creates a pending update, and approving
  it disables the older active rule for the same trigger. They do not execute tools,
  post money, grant permissions or authorize sends.
- Usage records count actual applications, deduplicate repeated application to the
  same item/revision, and link to captured evidence. Counts do not establish that
  a model obeyed guidance. Old application records are excluded from AI retrieval.
  Deleting a skill removes its use log; original source evidence remains.
- Schema 16 → 17 adds skills, usage and memory correction history. Backups preserve
  the new records, still accept schemas 8 and 10–16, and reset restored active skills
  to pending. Skills are excluded from the existing web-copy allowlist.
- Private encrypted drafts cover memory/reconciliation/source notes/shares/skill
  editing. Shared text plus derived records commit atomically; per-file progress
  survives a failed import, with media hash deduplication. Temporary Android file
  access can expire and require re-sharing. Shared reminders stay in review.
- Guided goal-based setup is resumable. Recovery consolidates actual permission,
  capture/queue, connection, model/media, web and backup states and repair links.
  Permissions enabled is never presented as proof of delivery. Privacy/storage
  distinguishes phone data from optional web/Drive/cloud destinations.
- Memory search/filter/paging reaches beyond 50 entries; earlier corrected text is
  available through revision history, not presented as the current fact.
- Finance comparisons retain filters and show deterministic changes/contributors;
  monthly budget variance covers all accounts and clarifies unsupported scopes.
  Contributors are not claimed to prove causes.
- Added English/Kiswahili labels for new workflows/common errors, polite status
  announcements, larger permission targets and adaptable bottom navigation.
  Advanced descriptions/diagnostics can remain English; device accessibility remains
  NOT TESTED.
- The curated catalog has Gemma 4 E2B/E4B, Qwen3 0.6B, multilingual Whisper Base/Small,
  and Google's MediaPipe USE text embedder. Published revisions, byte sizes, hashes
  and licenses/source links are visible. No arbitrary local model ID/URL is accepted.
  Download integrity and a real runtime load gate replacement; previous models remain
  on failure. Advanced local-file imports remain, including compatible speaker ONNX.

Publisher evidence checked 2026-11-17: Hugging Face repository metadata for
[Qwen3](https://huggingface.co/litert-community/Qwen3-0.6B),
[Gemma 4 E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm),
[whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp), and
[Google MediaPipe's text embedder](https://ai.google.dev/edge/mediapipe/solutions/text/text_embedder).
The 6,120,274-byte version-1 Google file was streamed only to compute the pinned
SHA-256; no inference was run. Other new model files were not downloaded. Synthetic
bytes test transfer integrity; real model/runtime and physical performance checks
remain NOT TESTED. Selecting a published format does not prove device compatibility.

| Check | Result |
|---|---|
| Android unit tests (`:app:testDebugUnitTest`, 1 worker, 1.5 GB heap) | PASS: 309 total, 305 passed, 4 opt-in live AI tests skipped, 0 failures/errors |
| Android lint (`:app:lintDebug`) | PASS: 0 errors, 288 warnings, 6 hints |
| Debug APK (`apksigner verify`, `zipalign -c -P 16 4`) | PASS: 0.9.0/code 9, 151,308,801 bytes; v2 signature and alignment verify. SHA-256 `4b19a221f1a812ca635fb1b860a8da7513b615791c608e9a5326cf9755fb79e2` |
| Signed release APK (same checks) | PASS: 0.9.0/code 9, 104,713,756 bytes; v2 signature and alignment verify. SHA-256 `56895b23853b673083bfcbd8e63a0ac11c7b3323a58b9926b02157f17bd61af1` |
| Phone, TalkBack, large text, voice, actual model loads, live accounts, real corpus | NOT TESTED |
| Hosted GitHub CI | NOT TESTED |

Final phone checks: upgrade populated encrypted v16 data; rotate/unlock/relaunch
private drafts; fail and resume a multi-file share; approve/edit/disable/delete a
learned skill and inspect its use history; verify sender/account separation; download,
pause/resume/switch/remove models under memory pressure; verify model-load rejection
retains the previous file; test English/Kiswahili, TalkBack and large text; export,
verify and restore a synthetic v17 backup on a separate empty install. Test actual
permissions and overnight behavior only on the connected owner-authorized phone.

## Project review implementation — 2026-11-16

Owner requested implementation of the review and continuation to completion.
Version **0.8.0** (code 8), Room/backup schema **16**. This checkpoint covers
software changes only; physical OPPO tests remain the final stage.

Implemented:

- Ask validates category, account, quoted merchant and saved-person filters and
  retains finance scope for period/metric follow-ups. Conflicting periods and unknown qualifiers ask for
  clarification. Money stays integer-based and source-linked; associated fees and
  reversals retain their effective person/category. The explicit **Local model +
  saved sources** route uses the installed model for read-only explanations with
  source links, prioritizing saved owner corrections. Keyword retrieval removes
  common question words and labels partial matches as evidence candidates.
  No cloud fallback is added.
- Source links have readable channel/sender/date labels. Optional Ask history is
  encrypted, bounded to 40 pairs, off by default, clearable and never restores
  executable proposals or approvals. Confirmed commands display Applying until
  execution reports success; failures never show Completed.
- Selected contacts with identical names retain separate stable identities.
  Renaming preserves links. Reviewed merge/separation moves only the selected
  associations, never ledger amounts. Migration and old-archive restore backfill
  alias/debt keys with Unicode-aware normalization. Foreign phone country codes
  are preserved; Tanzania local/international representations still match.
- Home › **Review** offers one queue, with due-soon task proposals ahead of
  payments, existing approval sheets, batch account/category suggestions (never
  batch posting), and cash check-ins. Opt-in encrypted review defaults suggest
  account/category on later captures; each payment still needs confirmation.
- Android's Sharesheet accepts text, provider-backed images/audio/video/PDFs.
  Shared data passes the existing app unlock gate and requires explicit Save.
  Notes and finance/task candidates use sanitized text; files are encrypted and
  locally queued. Unsupported URIs/types fail visibly; a partial multi-file import
  reports possible earlier saves instead of claiming nothing changed.
- Inbox has conversation grouping, body search across saved history and Load older
  history. Duplicate posted-payment proofs group by their ledger link without
  deleting sources. Waiting-on tracks requests rather than greetings, keeps email threads
  separate, and offers No reply needed / Tomorrow. Source events remain intact.
- Task editing uses date/time pickers, daily/weekly/monthly recurrence, per-task
  snooze and daily/hourly/persistent overdue intervals. Undo also closes the spawned
  repeat. Calendar handoff opens an editable system calendar form; HellFire does
  not claim that the user saved it.
- Home › **Record** reaches the recording workspace. Queue failures/waits have
  readable states; each transcript passage offers Remember this / Create task
  beside timestamp playback. Model setup is collapsed behind a settings control. Original audio is retained until owner deletion.
- Money › **Check account balance** compares a stated/observed balance at a chosen
  time with the ledger, surfaces pending/unassigned items and source SMS, then
  offers the existing explicitly approved adjustment. Forecast assumptions are
  visible; no income or settlement is fabricated.
- Settings › **Language** selects English/Kiswahili for main navigation and common
  controls. Source content is not translated. Voice requests use the selected
  locale; installed recognizer/voice support still needs a phone test. Advanced
  technical details and some dynamic explanatory text remain English.
- Backup readiness shows retained original size, free/estimated staging space and
  archive-verification status. Streaming archives allow up to 500 GiB of originals
  (each media import retains its 8 GiB bound), with storage preflight and a 512 GiB
  archive cap. **Verify an archive** authenticates tables and original hashes in
  encrypted staging without replacing local data. Real large archives and native
  replacement-phone restores are not established by small host fixtures.
- Web buttons have explicit foreground tokens: calculated contrast is 5.18:1 in
  light appearance and 7.29:1 in dark appearance. No browser rendering claim.
- Release builds use R8/resource shrinking and optional ignored owner signing
  properties. GitHub Actions runs the existing checks sequentially, with a
  disposable test database; it never installs personal credentials or deploys.

| Check | Command / method | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'` | PASS: 286 total, 282 passed, 4 opt-in live AI tests skipped, 0 failures/errors |
| Android lint | `./gradlew :app:lintDebug` with the same worker/heap limits | PASS: 0 errors, 275 warnings, 6 hints; advanced/dynamic English remains documented |
| Debug APK | `./gradlew :app:assembleDebug`; `apksigner verify --verbose`; `zipalign -c -P 16 4` | PASS: version 0.8.0/code 8, 150,856,321 bytes; signature and alignment verify. SHA-256 `f6b79bf799f67fab73e83de9ff8b871530de7c1544b774b898c31280aaeba47e` |
| Signed release APK | `./gradlew :app:assembleRelease`; `apksigner verify --verbose`; `zipalign -c -P 16 4` | PASS: version 0.8.0/code 8, 104,539,808 bytes; v2 signature verifies, alignment check passes. SHA-256 `ad6dc13d09a3f0d58c38f1b42032ed59dd08ae8006357e456eb98a6e8d6c14d9` |
| Web frontend | `npm run typecheck`; `npm run build` in primary checkout after merge | PASS: default Turbopack production build, all 10 routes. Worktree webpack build also passed; its default build rejected dependency symlinks outside the worktree root |
| Local web service | `sudo systemctl restart hellfire-web-frontend.service`; HTTPS GET `/login` and compiled stylesheet | PASS: frontend/API active, login HTTP 200; served CSS contains and uses the corrected dark button foreground token. Local self-signed certificate bypass was limited to this health probe; product TLS checks are unchanged. No browser/rendering test |
| Device/account/model/real-corpus behavior | ADB / actual owner-authorized integrations | NOT TESTED; no connected device or live accounts used |
| Hosted CI | Push/PR in GitHub | NOT TESTED; workflow is added, not executed remotely |

Integration: merged to `main`; only the primary worktree and `main` branch remain.
Verified APKs and host reports are in the primary checkout’s Android build outputs.
No personal data, signing material or dependency symlinks were committed.

Final phone checks: upgrade populated encrypted schema 15 data; use two same-name
contacts, merge and separate selected records; rotate/unlock during review; share
from Gallery, Files and a chat app; reject unsupported shares; verify financial
filter answers and source links; try English/Kiswahili speech; test recurrence,
Undo, snooze, calendar cancel/save and Doze; verify archive then restore a synthetic
backup on an empty separate installation. Record actual OEM results here.

## Local contacts and person links — 2026-11-16

Branch `feat/contact-links`. Version 0.7.0 (code 7), Room and backup schema 15.
Migration 14 → 15 adds `person_contact` and nullable `personKey` columns on
`assistant_task` and `ledger_entry`; the exported Room schema is committed.
Backups still restore schemas 8 and 10–14. No dependencies or network integrations
were added. All fixtures are synthetic. No phone, real contacts, live account or
physical permission dialog was used.

The owner opens **Choose contact** from a person/vendor field, then sees why access
is needed before choosing **Allow contacts**. Android's local ContactsContract
provider supplies contacts already synced on the phone; no People API or OAuth is
used. Blank searches read nothing, and background searches read at most 30 matching
data rows. Only saved picks and their normalized phone/email aliases are persisted.
Free text continues to work without access or a match. Settings › Permissions and
capture displays the actual Contacts grant; returning from App settings refreshes it.

| Check | Command | Result |
|---|---|---|
| Android tests, lint and APK | From `android/`: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'` | PASS: 263 tests, 259 passed, 4 existing opt-in live AI tests skipped, 0 failures/errors; 16 new synthetic tests. Lint: 0 errors, 267 warnings, 6 hints. APK assembled successfully |
| Debug APK | `sha256sum app/build/outputs/apk/debug/app-debug.apk` | 0.7.0 (code 7), arm64-v8a only, 151,378,091 bytes; SHA-256 `4ae6e17a299d8ba9dee89bef0bc3ea90db36f4098a1bfeb1fa00d56a58e5d7e7`. Not installed on any device |

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Populated schema 14 database upgrades? | `ContactLinksTest` | PASS (host) / NOT TESTED (phone SQLCipher) | Amounts, balance, task and aliases survive; new links start NULL | Upgrade the owner's encrypted database on the phone |
| Backup compatibility? | `ContactLinksTest`, existing backup tests | PASS (host) / NOT TESTED (phone) | Schema 15 round-trip retains selected contact, aliases and task/payment links; populated schema 14 rows restore without the new columns/table; schemas 8 and 10–14 remain allowed; restore requires `person_contact` empty too | Encrypted export/restore on an empty phone install |
| Search, denial and free text? | `PhoneContactsTest`, `ContactLinksTest` | PASS (synthetic provider/data layer) / NOT TESTED (Compose interaction) | Name, normalized/local number and email searches; bounded cursor, no query on blank/denied access, mid-query revocation; typed names save without provider access | Exercise each picker by touch and TalkBack |
| Numbers join across channels? | `ContactLinksTest` | PASS (synthetic) | Two numbers plus an explicitly selected vendor email (`info@…`) produce one person across SMS, WhatsApp and email using `Handles.key()` | Verify the owner's actual message sender formats |
| Link changes affect money? | `ContactLinksTest` | PASS (host) | Edit, replace and clear task/payment links; ledger row and balance unchanged; action_audit counts actual changes; duplicate payment review retains its person without another debit | Compare real balances before/after a link edit |
| Naming an existing conversation? | `ContactLinksTest` | PASS (host) | All known handles, explicit tasks/payments and debt names follow the new name; no orphan old person link | Rename a picked person on the phone |
| New contact data reaches web copy? | `WebSyncTest` | PASS (host) | Selected display name, lookup key, phone/email aliases and personKey absent from fake server payload; WebSync allowlist unchanged | Keep excluded unless the owner authorizes web contacts |
| Contacts permission prompt, denial and later grant? | Owner opens picker → explanation → Allow; deny, type name, then App settings → Permissions → Contacts | **NOT TESTED (phone)** | No Android/ColorOS permission dialog was displayed; no permission claimed granted | Verify prompt timing, denial fallback and Settings state after resume |
| Real Google-synced contacts on ColorOS? | Search name/number/email in each requested screen | **NOT TESTED (phone)** | Only SQLite-backed synthetic ContactsContract provider exercised | Check sync visibility, search latency and each field's saved result |
| Real contact with several numbers? | Pick one contact with multiple phones/emails, then open its SMS/WhatsApp/email person page | **NOT TESTED (phone)** | Synthetic cross-channel test passes; no real contact was read | Confirm all identities and linked tasks, payments and debts appear |
| Deleted or unresolved contact? | Save a pick, delete it in Contacts, return to HellFire | **NOT TESTED (phone)** | Synthetic deletion returns MISSING; stored name kept; unavailable/revoked provider distinguished; screens compile | Confirm “not in contacts” on person, debt/vendor, task and transaction fields |

Names retain the existing shared identity rule: identical saved names join into
one person. Contact matching does not resolve WhatsApp `@lid` identifiers without
an owner-confirmed handle. Selected contact links participate in the already
configured encrypted backup, and are excluded from the read-only web copy.

## Daily outputs and action templates — 2026-11-16

Branch `feat/daily-outputs-and-templates`. Version 0.7.0 (code 7), Room schema 14,
backup schema 14 (schemas 8 and 10–13 still restore). New tables: `action_template`,
`debt_entry`, `person_alias`. Synthetic data only; no phone, account, real SMS,
real document scan or notification delivery was used.

| Check | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'` | PASS: 247 tests, 243 passed, 4 opt-in live AI tests skipped, 0 failures; 15 new in `DailyOutputsTest` |
| Lint and debug APK | `./gradlew :app:lintDebug :app:assembleDebug` with the same limits | PASS: lint 0 errors, 266 warnings, 6 hints. Debug APK 0.7.0 (code 7), arm64-v8a only, 150,091,685 bytes, SHA-256 `bf947b77ad15f3e8239c2b32ddb1ef51a6584ae9977c75f6f9c59837b7067d74`. Not installed on any device |

| Question | Method | Result | Evidence / limit | Next step |
|---|---|---|---|---|
| Schema 13 → 14 migrates and backs up? | `DailyOutputsTest` | PASS (host) / NOT TESTED (SQLCipher) | A schema 13 file opens at 14 with the three new tables usable; backup schema 14 adds them and schema 13 archives restore without them; restore now also requires these tables empty | Upgrade over the owner's database on the phone |
| Templates add a mix in one step? | `DailyOutputsTest` | PASS (host) | One run scheduled 1 task (active, bill, due 5 days later 08:30), posted 1 expense and 1 income to the ledger with the run as evidence, and left the line without an account in To confirm; a payment line without an amount is refused; lines round-trip through JSON | Create and use a template on the phone; check the reminder fires |
| Template and brief screens | Compile, lint | NOT TESTED (device) | Compose screens for Templates, Brief, Waiting on, People, Debts and Bills compile; none was drawn or tapped | Hand-test every new screen and the Home, Money, Tasks and Inbox entry points |
| Morning brief content | `DailyOutputsTest` | PASS (host, synthetic) | Sections and headline for morning and evening; overdue first; bill shortfall flagged; empty day says so | Compare with a real day's data |
| Morning brief notification | `DailyOutputsTest` (timing only) | PARTIAL | Next time is today at the chosen hour if still morning and not shown, else tomorrow. It shares the reminder alarm; delivery was not exercised | Check 07:00 delivery on ColorOS with Doze and battery limits |
| Waiting-on correct? | `DailyOutputsTest` | PASS (synthetic) / NOT TESTED (real traffic) | Latest message per person across channels; a reply on another channel settles it; groups, short codes, sender names and automated mailboxes ignored; cleared rows return on a newer message | Measure false positives on the owner's real messages; tune the 4 h / 24 h limits |
| Numbers and addresses joined? | `DailyOutputsTest` | PASS (host) | `+255712345678`, `0712 345 678` and `255712345678@c.us` are one handle (last nine digits); a shared name joins a number and an email | Check real sender formats, especially WhatsApp `@lid` ids, which stay separate until named |
| People page linked to calls? | Code review | PARTIAL | Recordings match by file name (name or number) only; there is no caller-ID link | Check what the OPPO names its call recordings |
| Debts net correctly? | `DailyOutputsTest` | PASS (host) | Balance per person, case-insensitive; settled people hidden; overdue from the agreed date; repayment suggestions need the whole name in an approved payment's source text | Try with real M-Pesa names, which are often upper-case full names |
| Debt reminder sending | Compile | NOT TESTED | Share sheet intent, or an email draft that needs Inbox approval; nothing is sent by the app | Share to WhatsApp and SMS on the phone |
| Recurring bills found? | `DailyOutputsTest` | PASS (synthetic) | Three consecutive months, at most 1.5 payments a month, still current; stopped, gapped, daily, generic and reversed payments are ignored; 30-day shortfall computed | Run on the owner's ledger; descriptions must repeat exactly for a match |
| Expiry dates read? | `DailyOutputsTest` | PASS (typed text) / NOT TESTED (real OCR) | Passport `12 MAR /MARS 31`, `30/06/2027`, ISO, `02/2027`, `December 1, 2027`; past and non-expiry dates ignored; one proposal per date; the document number is not copied | Scan a real passport, licence and insurance cover note; record misses |
| Capture gaps detected? | `DailyOutputsTest` | PASS (host rules) | Revoked access, 12 h without a notification, an SMS on the phone unsaved after 30 min, 6 h without a sync; a new install and a just-arrived SMS are not gaps | — |
| Capture alert and listener rebind | Compile | NOT TESTED | 15-minute maintenance worker posts one notification per problem set per day and calls `requestRebind`; skipped until first-run setup is done | Force-stop or battery-restrict the app on ColorOS; confirm the alert appears and whether rebind restores capture |
| Spoken money commands | `DailyOutputsTest`, `AssistantCommandsTest` | PASS (typed text) / NOT TESTED (speech) | `elfu kumi na tano` = 15,000, `laki mbili na elfu hamsini` = 250,000, income amount-first or last, leading "HellFire,", `use template <name>`; `income this month` is still a question | Speak them through Whisper on the phone; record recognition errors |
| Web copy | Code review | NOT INCLUDED | Templates, debts and person names are not synced to the web copy; tasks and payments made from them are, as before | Decide whether the web copy should show them |

## Gemma 4 E2B download and updates — 2026-11-16

Branch `feat/gemma-download`. Version 0.6.0 (code 6); no Room or backup schema change.
No phone, model download or network request was made in testing.

| Check | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'` | PASS: 232 tests, 228 passed, 4 opt-in live AI tests skipped, 0 failures; 6 new in `ModelUpdatesTest` |
| Lint and debug APK | `./gradlew :app:lintDebug :app:assembleDebug` with the same limits | PASS: lint 0 errors, 254 warnings, 5 hints. Debug APK 0.6.0 (code 6), arm64-v8a only, 149,469,089 bytes, SHA-256 `d9b99631d1b2e260b271380986b608041d2120c0a98efa2e2623e658382e63bc`; the model is not in the APK. Not installed on any device |

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Is the published model the expected one? | Hugging Face API, 2026-11-16 | PASS (metadata) | `litert-community/gemma-4-E2B-it-litert-lm`: Apache-2.0, not gated; `gemma-4-E2B-it.litertlm` 2,588,147,712 bytes, LFS SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, uploaded 2026-05-04, listed at commit `2d74ac41`. LiteRT-LM release notes list Gemma 4 support from 0.10.1; the app uses 0.12.0 | — |
| Download resumes and rejects bad files? | `ModelUpdatesTest` | PASS (host, synthetic bytes) | Metadata parsing; full download; resume with `Range` and a matching `Content-Range`; a server ignoring `Range` restarts the file; wrong checksum deletes it; HTTP redirects and 404 rejected | — |
| Updates offered correctly? | `ModelUpdatesTest` | PASS (host) | Offered only when the published SHA-256 differs from the installed downloaded model; never for the owner's own model; removal stops automatic download | — |
| Does the file load on the OPPO? | — | NOT TESTED | After download the worker opens the file on CPU before installing it; a failure leaves any previous model in place and reports it | Install 0.6.0 on Wi-Fi, wait for the download, then run a local question on CPU and GPU; record time, memory and heat |
| Real 2.6 GB download behaviour | — | NOT TESTED | Foreground WorkManager job (dataSync) on unmetered network with storage/battery constraints and a progress notification | Test Wi-Fi drop, reboot and force-stop mid-download; confirm it resumes and the notification is visible |

## Dependency update check — 2026-11-16

Report only; nothing was upgraded. Sources: lint `GradleDependency`/`NewerVersionAvailable`,
Google Maven and Maven Central metadata, `pip list --outdated`, `npm outdated`, `npm audit`.

| Package | In use | Newest | Note |
|---|---|---|---|
| Gradle | 8.13 | 8.14.7 | Patch-level for this AGP |
| Android Gradle Plugin | 8.13.2 | 9.5.1 | **Major**; 8.13.2 is the newest 8.x |
| Kotlin (android, compose plugin) | 2.2.21 | 2.4.21 | Minor; KSP must move with it (2.3.14 available) |
| KSP | 2.2.21-2.0.4 | 2.3.14 | Tied to the Kotlin version |
| Compose BOM | 2025.10.00 | 2026.09.00 | Many UI library updates |
| Room | 2.8.4 | 2.8.5 | Patch |
| Lifecycle runtime compose | 2.9.4 | 2.11.0 | Minor |
| Activity compose | 1.11.0 | 1.13.0 | Minor |
| Core | 1.15.0 | 1.19.1 | Minor |
| WorkManager | 2.10.5 | 2.12.0 | Minor |
| Glance appwidget / material3 | 1.1.1 | 1.2.0 | Minor |
| Coroutines | 1.10.2 | 1.11.0 | Minor |
| LiteRT-LM | 0.12.0 | 0.19.1 | Model runtime; earlier note says newer versions need newer Kotlin metadata |
| MediaPipe tasks-text | 0.10.26 | 1.2.3 | **Major** (0.x → 1.x) |
| ONNX Runtime | 1.31.1 | 1.32.0 | Minor |
| Robolectric (tests) | 4.16 | 4.17 | Minor |
| FastAPI (relay) | 0.162.5 | 0.163.1 | Minor (0.x) |
| Python base image (relay) | 3.14 slim (resolves to latest 3.14.x at build) | 3.14.10 | Unpinned patch tag |
| Up to date | — | — | Play services auth 22.0.0, ML Kit text recognition 16.0.1 and image labeling 17.0.9, SQLCipher 4.20.0, JavaMail 1.6.8, JUnit 4.13.2, uvicorn, httpx, cryptography, pytest, whatsapp-web.js 1.34.8, qrcode 1.5.4, Puppeteer 25.17.0; `npm audit`: 0 vulnerabilities |

## Web access — 2026-11-16

Branch `feat/web-sync` (based on `main` after the multiple-accounts merge). Adds the
optional read-only web copy in `web/` and Settings › Web access on the phone. No Room
or backup schema change; version unchanged at 0.5.0. Synthetic data only; nothing was
deployed, and no phone or real server was used.

| Check | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest` | PASS: 226 tests, 222 passed, 4 opt-in live AI tests skipped, 0 failures; 5 new in `WebSyncTest` |
| Lint and debug APK | `./gradlew :app:lintDebug :app:assembleDebug` | PASS: lint 0 errors on the branch, 251 warnings (1 new: `UseKtx` in `WebSync.kt`, same pattern as `DriveBackupWork`). After merging into `main` (arm64-v8a only), tests re-run (226, 0 failures) and debug APK 0.5.0 rebuilt: 150,314,771 bytes, SHA-256 `60565a4a13e6a263b231447abc29a63357f366b332a1f39a110db6688fc61945`. Not installed on any device |
| Web API | `cd web/api && TEST_DATABASE_URL='postgresql:///hellfire_test?host=/run/postgresql' npm test` (Node 24.13.0, local PostgreSQL 18.6) | PASS: 11 tests |
| Website | `cd web/frontend && npm run typecheck && npm run build` | PASS |
| systemd units | `systemd-analyze verify web/systemd/*.service` | PASS; not installed or started |
| Local end-to-end | API and `next start` on 127.0.0.1:3911/3910 with the local `hellfire_test` database; rows pushed through the site's `/api/sync` forward, pages fetched with a session cookie | PASS: all seven pages rendered the pushed rows; `/api/overview` is not reachable through the site (404); signed-out pages redirect to `/login` |

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Phone sends only changes and deletions? | `WebSyncTest` | PASS (host) | Second pass with no changes sends nothing; a closed task and a forgotten memory send one upsert and one delete | Watch the first full sync and a later pass on the OPPO with real volumes |
| Security codes stay on the phone? | `WebSyncTest` | PASS (host) | Sensitive events and the messages and tasks captured from them are not sent; the code text appears nowhere in the fake server | Inspect the server copy after a real OTP arrives |
| Wiped or new server repaired? | `WebSyncTest`, API tests | PASS (host) | A changed dataset id makes the phone reset the server and send a full copy; batches for an old dataset get 409 | — |
| Large kinds bounded? | `WebSyncTest`, API tests | PASS (host) | 650 2 KB rows go in batches of ≤300 rows / ~1 MB; the API accepts 500 rows of ~1.5 MB and rejects 501 | Time a full sync over mobile data |
| Access control | API tests | PASS (host) | Sync routes need `SYNC_TOKEN`, read routes an owner session; expired or forged sessions rejected; repeated wrong passwords get 429 | Verify through the real reverse proxy and HTTPS |
| Local deployment | `curl --cacert /etc/nginx/ssl/hellfire.local.crt` against https://hellfire.local | PASS (this PC) | nginx 1.30.6 → website 127.0.0.1:3910 → API 127.0.0.1:3911 → local PostgreSQL `hellfire`. HTTP redirects to HTTPS; signed-out pages redirect to `/login`; `/api/overview` 404; `/api/sync/state` 401 without the token, 200 with it | Sign in once in a browser |
| Phone reaches hellfire.local? | Manifest review | BLOCKED | The name is only in this PC's `/etc/hosts` (no mDNS), and the app has no network security config, so it trusts system CAs only and rejects the self-signed certificate | Pick an option: a LAN DNS record plus a debug-build user-CA override, or a real domain with a public certificate |
| Sync over a real network | Compile only | NOT TESTED | HTTPS-only client, 15-minute WorkManager job (network connected, battery not low), status in Settings | Install the units, add the HTTPS proxy, connect the OPPO, check ColorOS background limits |
| Sign-in form | Build; API login tested separately | PARTIAL | The server action sets the cookie from the API token; not exercised in a browser | Sign in once in a browser |

## Phone-only build — 2026-11-16

The owner asked for arm64-v8a only; x86_64 served only the PC emulator. `abiFilters`
is now `arm64-v8a`. `./gradlew :app:assembleDebug` PASS: debug APK 0.5.0 (code 5),
**149,321,625 bytes** (was 249,567,013 with x86_64), SHA-256
`a9da8cbce2378555aa92172055051bf47fa7e8709b7ade719d3c956a4952fb0e`. `aapt` reports
`native-code: 'arm64-v8a'`; `zipalign -c -P 16 4` passes. Contents: native libraries
93.6 MB (ONNX Runtime 34.2, LiteRT-LM 14.7, ML Kit OCR and common pipeline 22.1,
MediaPipe text 7.6, whisper 5.1, LiteRT 7.8, SQLCipher 2.1), dex 49.7 MB (debug,
unshrunk), assets 3.3 MB, resources 2.1 MB. Installed size on the phone is NOT TESTED;
native libraries are stored uncompressed and not extracted, so it should be close to
the APK size plus ART-compiled code and app data. Check with Settings › Apps › HellFire
Ass › Storage after installation. Emulator checks now need an arm64 system image.

## Review fixes and multiple accounts — 2026-11-16

Branch `feat/multi-account`, merged into `main`. Version 0.5.0 (code 5), Room schema
13, backup schema 13 (schemas 8, 10, 11 and 12 still restore). Synthetic data only; no
phone, model file, live account or real SMS corpus was used.

Host verification on the branch:

| Check | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'` | PASS: 221 tests, 217 passed, 4 opt-in live AI tests skipped, 0 failures. 13 new tests in `ReviewFixesTest` and 3 in the AI provider and classifier tests; `DatabaseMigrationTest` migrates schema 1 through 13, including the FTS table |
| Relay | `python -m pytest -q` (Python 3.14.8) | PASS: 23 passed (2 new: device rate allowance, batch acknowledgment) |
| WhatsApp Web worker | `npm test`, Node 24.13.0; `node --check src/main.js` | PASS: 6 passed; restart wiring is not exercised without a browser |
| Lint and debug APK | `./gradlew :app:lintDebug :app:assembleDebug` with the same limits | PASS: lint 0 errors, 250 warnings, 5 hints. Debug APK 0.5.0 (code 5), 249,567,013 bytes, SHA-256 `0cba7145a431cdbb5081c0c72c8c855c1dcd4a278e215a754f5635d30060df52`. Not installed on any device |

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| AAC recording recovered after process death? | `ReviewFixesTest` | PASS (host) | Recovery accepts the AAC and PCM containers and registers the right type; empty orphans are removed; recovery is skipped while the microphone is busy. Start time is the file creation time where the filesystem reports it | Kill the process mid-recording on the OPPO; check the recovered file plays |
| SMS import bounded per trigger? | `ReviewFixesTest` | PASS (host) | The last imported provider row is saved; fresh triggers re-read from 200 rows before it (outbox → sent keeps its row ID). Settings › Sync SMS history runs a full check. First run after upgrade reads everything once; existing rows are matched, not duplicated | Measure the time and battery of a trigger on the real SMS store |
| Identity independent of redaction rules? | `ReviewFixesTest` | PASS (host) | SMS, IMAP and relay events are identified by source, provider key, time and kind. Rows from earlier versions are found by their old content identity and reused. Notifications keep content identity on purpose | Upgrade over the owner's database; confirm a full SMS check adds no duplicates |
| Money facts kept by redaction? | `ReviewFixesTest`, `NotificationNormalizerTest` | PASS (synthetic) / NOT TESTED (real formats) | Trailing-currency amounts, `/=`, day/month/year dates, and in finance messages amounts after finance words plus till/Lipa Namba/reference numbers are kept; bare codes are still removed | Run an owner-approved real corpus through the normalizer; record losses per provider |
| SMS + notification posted once? | `ReviewFixesTest` | PASS (host) | Leading transaction IDs (`QK12ABC3D4 Confirmed.`) are references. Without a shared reference, the same provider, kind and amount from a *different* source type within 10 minutes is a duplicate: linked as evidence on approval, attached directly if already posted, rejected together. Two equal SMS payments both stay in review | Check with real paired SMS/notification deliveries |
| Review queues usable? | `RuleClassifierTest`, `ReviewFixesTest` | PASS (synthetic) | Finance needs a provider/currency word, or a finance word plus an amount. Task proposals from messages need commitment wording (English or Swahili future tense, meeting, pay, send, tafadhali…). Swahili clock times (`saa saba na nusu mchana` = 13:30) are parsed. Owner-typed commands and quick add are exempt | Measure precision on real traffic |
| Relay drains a large backlog? | `test_gateway.py` | PASS (host) | Device-token requests have their own 1,200/minute allowance; others are limited per forwarded client address (`--proxy-headers`, `FORWARDED_ALLOW_IPS` for the loopback-only port). Android acknowledges each batch with one `POST /device/events/ack` after the whole batch is stored | Verify behind the real reverse proxy |
| Worker recovers from disconnect? | `node --check`; code review | PARTIAL | Worker exits non-zero on `disconnected` or `auth_failure`, so Compose restarts it | Disconnect a linked test account and watch the restart |
| First mail sync starts with recent mail? | Code review, compile | PARTIAL | First sync (or a UIDVALIDITY reset) takes the 500 newest messages per folder, oldest of those first; later syncs continue by UID | Test against a real large mailbox |
| Cloud AI answers accepted? | `AiProviderTest`, `AiRouterTest` | PASS (mocked) / NOT TESTED (live) | Output limit 2,048 tokens; answers stopped by the limit are kept and marked "Cut off" (Claude `max_tokens`, Responses `incomplete`/`max_output_tokens`, DeepSeek `length`); Claude thinking blocks are skipped; Claude refusals are their own error and fall back. The per-request budget reservation is about 4× larger | Run the opt-in live tests once per provider |
| Long-term search? | `ReviewFixesTest`; migration tests | PASS (host) / NOT TESTED (SQLCipher) | FTS4 (unicode61) table inside the encrypted database; filled at capture and by a one-time resumable backfill; rebuilt after restore; not in backups; cleared with captured events. Sensitive and unavailable sources are never indexed | Confirm FTS4 works in SQLCipher on the phone; time the first backfill |
| Call-folder scan finds new files? | Compile; code review | PARTIAL | Every folder entry is read; up to 25 new audio files are imported per scan, newest first | Test with the OPPO call-recording folder |
| Drive storage bounded? | Compile; code review | PARTIAL | After a confirmed upload the app deletes its own older archives beyond the newest 3/7/14 (default 7), or keeps all | Verify on a live Drive account |
| Device problems diagnosable? | `ReviewFixesTest` | PASS (host) | Debuggable builds log exception class and stack frames (tag `HellFire`), never messages, from the previously silent handlers | Collect with `adb logcat -s HellFire` during the OPPO pass |
| Other apps steering the UI? | Compile; manifest review | PARTIAL | `MainActivity` is no longer exported; the launcher, assist gesture and assistant settings use the exported `.Launcher` alias, whose data is ignored. Widgets and reminders address `MainActivity` directly. Launchers may need the icon re-added after the update | Check launcher icon, assist gesture and widget links on ColorOS |

Multiple accounts:

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Several inboxes and WhatsApp accounts stored safely? | `ReviewFixesTest` | PASS (host) | Encrypted lists `mail-accounts` and `gateway-accounts` (up to 10 each). Earlier single-account settings become the first entry. Restore still requires every account disconnected | — |
| Sync, status and sending per account? | Compile; code review | PARTIAL | Each account syncs independently with its own integration state (`EMAIL_IMAP:<id>`, `WHATSAPP_GATEWAY:<id>`, `IMAP_IDLE:<id>`; old single-account states are removed by the v13 migration). Sends resolve the draft's account. Replies default to the receiving account; new messages and edited drafts choose it; the approval dialog names it. Relay events are keyed by account, so one group message seen by two linked accounts is two events | Connect two test inboxes and two relays; send one approved test message from each |
| Live mail for several inboxes? | Compile | NOT TESTED | One bounded IDLE connection per inbox inside the existing ten-minute foreground service | Verify on device with two inboxes |

## Project review checkpoint — 2026-11-16

Full read of the Android app, relay, WhatsApp Web worker, tests and docs at `main`
`566a291`, plus a re-run of the host suites. No code was changed. No phone, model
file, live account or real message corpus was used; every "code review" result below
is established by reading the source, not by reproducing it on a device.

Host verification on this tree:

| Check | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'` from `android/` | PASS: 205 tests, 201 passed, 4 opt-in live AI tests skipped, 0 failures or errors. Gradle reported the task up to date, so these are the results of the run made on this tree earlier the same day |
| Relay | `python -m pytest -q` in a fresh Python 3.14.8 virtualenv from `requirements-dev.txt` | PASS: 21 passed, one Starlette/httpx deprecation warning |
| WhatsApp Web worker | `npm ci --omit=optional --ignore-scripts` then `npm test`, Node 24.13.0 | PASS: 6 passed |
| Room migrations | `DatabaseMigrationTest`, `SchemaV10Test`, `WidgetDataTest`, `FinanceTest` | PASS (host): schema 1 migrates through schema 12 |
| Android lint | `./gradlew :app:lintDebug --max-workers=1` with the same heap limit | PASS: 0 errors, 244 warnings, 3 hints (mostly hard-coded English text, unused attributes, KTX suggestions and dependency update notices). This closes the lint run left NOT COMPLETED in the Money entry update below |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` | PASS: `./gradlew :app:assembleDebug` reports it up to date. 249,964,215 bytes, SHA-256 `2c93f66ebe77685d113924f3900fbdd63ecd0c6fc1a890eccf10f1c8c01a7778`, version 0.4.0 (code 4). Not installed on any device |

Findings, in the order they should be handled. Result is the state of the code at
review time; every row was fixed the same day (see "Review fixes and multiple accounts" above).

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Is a compressed conversation recording recovered after the process dies mid-recording? | Code review | **DEFECT** | `MicrophoneService` writes monitoring audio with the container's PCM flag off (AAC). `ChunkedMedia.recoverRecording` returns null unless that flag is on, so `MediaVault.recover` skips the file: up to 30 minutes of audio is not registered and the encrypted file stays on disk. `MediaTest` covers recovery for the PCM container only | Accept the AAC container in recovery, add a test, and remove or register orphan `.hfm` files |
| Does each new SMS trigger a bounded import? | Code review | **DEFECT (cost)** | Every trigger (incoming SMS, provider change, app resume, the 15-minute maintenance job) starts `SmsSyncWorker` at row 0 and pages through the whole SMS store, normalizing and hashing each row before the duplicate check. No high-water mark is saved | Save the last imported row ID and rescan only a short look-back window (for outbox to sent changes); measure on the OPPO's real SMS count |
| Is a captured event's identity stable if the redaction rules change? | Code review | **RISK** | The event ID and external ID are hashes of the *redacted* fields. Any later change to `NotificationNormalizer` gives already-imported SMS and mail new IDs, so the next sync re-imports them as new events and creates new review items unless a provider reference matches | Calibrate redaction on a real corpus **before** the first real history import, or derive SMS/mail identity from the provider row ID / IMAP UID instead of content |
| Does redaction keep the money facts in real provider messages? | Code review | **RISK, NOT TESTED on real formats** | Any standalone 4–8 digit number is replaced unless it follows `TZS`/`TSh`, sits in an ISO date, or follows a reference keyword in a message that also has a marked amount. Amounts written without a leading currency mark (`5000`, `120000 TZS`), the year in `13/11/2026`, and till numbers in messages without a marked amount are removed before storage, permanently | Run an owner-approved real corpus through the normalizer and record what is lost per provider |
| Does one payment seen as both an SMS and the SMS app's notification create one review item? | Code review | **RISK** | `FinanceRepository.collect` only detects a duplicate when both sources yield the same parsed reference. The reference pattern needs a `ref`/`kumbukumbu`/`muamala` keyword; a message that leads with a bare transaction ID yields no reference, so both sources go to review and both can be posted | Verify reference parsing per provider on a real corpus; add a fallback match (same provider, amount and time window) that links instead of posting twice |
| Are review queues usable on real traffic? | Code review | **RISK** | `RuleClassifier` labels any text containing `paid`, `payment` or `received` as a finance candidate. `TaskExtractor` proposes a task for any text containing today/tomorrow/a weekday/an ISO date (or `leo`/`kesho`), with no commitment wording required; only one Swahili clock phrase (`saa nne asubuhi`) is understood | Measure candidate precision on real traffic before trusting the queues; tighten rules from the measured errors |
| Does the relay drain a backlog larger than one batch? | Code review | **DEFECT** | The relay limits every caller to 120 requests per minute, counted per peer address and including the phone's own authenticated calls. One sync is 1 fetch plus up to 100 acknowledgments, so the follow-up batch is rejected with 429 and Android records the gateway as FAILED until a retry. Behind the required reverse proxy all callers share one address, so unauthenticated traffic can use up the same allowance | Exempt authenticated device calls or acknowledge a batch in one request; key the limit on the proxy-supplied client address |
| Does the WhatsApp Web worker recover from a disconnect? | Code review | **GAP (documented)** | On `disconnected` the worker only updates status and keeps running, so Compose `restart: unless-stopped` never restarts it | Exit non-zero on disconnect so Compose restarts it with the saved session |
| Does the first mail sync start with recent mail? | Code review | **RISK** | With no cursor the adapter asks for every UID in INBOX and Sent and imports oldest first, 100 per run. A large mailbox takes many runs and recent mail arrives last | Start from a recent window and backfill on request |
| Do the cloud AI adapters accept normal answers? | Code review | **DEFECT (likely), NOT TESTED live** | Every request asks for at most 512 output tokens, and an answer that ends because of that limit is treated as a failed request: the estimated cost stays reserved and the next provider in the route is tried. The Claude adapter also rejects any response that contains a non-text content block, which current Claude models return when thinking is on by default | Accept truncated answers and mark them truncated, raise the limit, skip thinking blocks; then run the opt-in live tests once per provider |
| Is long-term literal search available? | Code review | **GAP vs spec** | Search decrypts and scans only the 2,000 most recent events (a few days once all notifications are captured). The spec asks for SQLite FTS | Add an FTS table inside the already-encrypted database |
| Are newly recorded calls found by the folder scan? | Code review | **RISK** | Each scan reads only the first 300 folder entries in the order the storage provider returns them. A call-recording folder that grows past 300 files may never show its newest files | Sort by last-modified or page through the folder |
| Do daily Drive backups stay within the account's storage? | Code review | **RISK** | Every daily upload is a complete archive including all originals (up to 7 GiB). Nothing prunes old archives | Keep the newest N archives the app created, or back up originals less often |
| Can device problems be diagnosed? | Code review | **GAP** | The app writes no log lines at all; about 95 `catch` blocks turn the exception into a generic status string | Add debug-build-only logging of exception class and stack trace, without message content, before the OPPO test pass |
| Can another app steer the UI? | Code review | **LOW** | `MainActivity` is exported for the launcher and assist intents and accepts any `hellfire://` data URI from any caller. Links only navigate, and the app lock still applies | Accept deep links only from this app's own pending intents |

Repository and build state on 2026-11-16:

- `main` and `origin/main` are both at `566a291`. The merged `feat/ui-redesign`
  worktree and branch have been removed; no feature branches remain.
- Only a debug build type exists. There is no release signing configuration, code
  shrinking or CI. The debug APK is about 238 MiB because it carries arm64-v8a and
  x86_64 native libraries; a phone-only arm64 build would be much smaller. **Done
  later on 2026-11-16:** the build is arm64-v8a only; see "Phone-only build" below.
- `AGENTS.md` still tells agents to implement Phase 0 only and to use official
  WhatsApp Coexistence; `docs/MASTER_SPEC.md` records the owner's later decisions that
  supersede both. An agent that reads only `AGENTS.md` gets the old rules.

## Earlier checkpoints

Evidence date: 2026-11-14, Africa/Dar_es_Salaam. The owner authorized remaining
phases and explicitly kept physical OPPO testing until last. Resource-use validation
used host tests only; the separate UI checkpoint below records earlier emulator
evidence. No live account, personal message corpus, recording or model weights were
used for the resource-use changes.
The existing optional cloud providers/settings were merged from main; their live
tests remain opt-in and unconfigured.
No device-dependent phase is fully accepted solely because its host checks pass.

## Fingerprint and Google Drive checkpoint — 2026-11-14

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Fingerprint login | Enroll fingerprint and device PIN; enable app lock; cancel/wrong/correct fingerprint; use PIN fallback; background/recreate/restart; disable lock after authentication | NOT TESTED (physical) | Platform `BiometricPrompt`; API 30+ strong biometric or device credential, API 29 platform credential fallback; no fingerprint data stored. Only `emulator-5554` was listed by read-only `adb devices -l`; it was not used for authentication testing | Run on the physical OPPO; verify ColorOS sensor/lockout/PIN behavior and revoking enrollment |
| Private UI and assistant access | Open task deep link, assistant role entry, recents, account/document picker; return locked; unlock and consume pending result | NOT TESTED (device) | Main activity gates private Compose content and uses secure-window flag. Assistant session redirects to app when lock is enabled. Saveable UI holder preserves result-registration keys; manual archive passphrase survives only in an in-memory ViewModel | Verify no private content/dialog/voice response is exposed while locked; verify picker return, rotation, screen-off and process death |
| Daily encrypted Google Drive backup | Register Cloud Android OAuth client/certificate, enable Drive API, connect Google account, enable schedule, inspect Drive file and reported success | NOT TESTED (live Google/device) | `drive.file` authorization, existing passphrase archive, unique 24-hour WorkManager job, unmetered/battery/storage constraints, streamed upload, server size/checksum confirmation | Configure actual Cloud project and signing certificates; test first/manual/overnight upload, offline/reboot/Doze/quota/consent revocation; no live Google account used |
| Restore on replacement installation | Connect original account, list/select backup, enter original passphrase, confirm restore into empty database | NOT TESTED (native/live Google/device) | Bounded ciphertext download, existing authenticated archive/schema/table/media validation and empty-database guard; device-key re-encryption, stale send approvals cleared | Perform full SQLCipher/Keystore/media round trip, wrong-password/corrupt-download/insufficient-space tests, regrant access afterward |
| Large archive scheduling | Upload representative original-media archive on actual Wi-Fi | NOT TESTED | Existing 7 GiB originals/1 GiB table limits; 9 GiB transfer ceiling. Normal WorkManager execution window applies; upload sessions are not persisted across process death | Measure timing/disk use; use manual archive upload when transfer cannot fit the worker window |

Host verification for the feature branch (2026-11-14): **PASS** — APK assembly,
lint (**0 errors, 244 warnings**), and **196 tests: 192 passed, four opt-in live AI
tests skipped, zero failures/errors**. All eight `DriveBackupTest` cases passed:
confirmed upload/checksum, token-safe upload destination, checksum mismatch,
app-specific listing/ID validation, short/oversized/cancelled download cleanup,
expired authorization, background/recreated lock session and fail-closed preferences.
Command from `android/`: `./gradlew :app:assembleDebug :app:lintDebug
:app:testDebugUnitTest --console=plain --max-workers=1
'-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8'`.
The native build reused the existing pinned whisper 1.9.5 archive after a repeat
GitHub download stalled; its SHA-256 matched the source's configured hash. No
checksum, lint gate or dependency version was bypassed.

Integrated-main verification (2026-11-14): `./gradlew :app:assembleDebug
:app:testDebugUnitTest --console=plain --max-workers=1
'-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8'` — **PASS**.
Includes the independently merged resource-use changes: **201 tests,
197 passed, 4 opt-in live AI tests skipped, zero failures/errors**.
Current APK: `android/app/build/outputs/apk/debug/app-debug.apk`
(249,353,969 bytes), SHA-256 `b161484f6a3c1155201d52d353d0aa378bf505339292a2cb49d8c61b0bef05b4`.
Feature lint and synthetic test reports are retained under
`android/app/build/reports/device-auth-backup/`; merged-main test reports use
the normal `test-results/testDebugUnitTest/` and `reports/tests/` paths.

No physical fingerprint, live Google authorization, cloud upload or native restore
success is inferred from host tests. Existing OS backup exclusions remain enabled;
Google Drive receives only the owner-approved encrypted portable archive.

## Current phase gates

| Phase | Software evidence | Status | Remaining gate |
|---|---|---|---|
| 0 — feasibility | Android skeleton, real assistant/session metadata, capture, visible recording controls, access/status UI | PARTIAL | OEM role/gesture/microphone/access behavior NOT TESTED |
| 1 — capture | Transactional immutable sources, processing jobs, audits/policy, read-only paged SMS reconciliation | PASS (host) / PARTIAL (device) | Real delivery, SMS grants, screen-off/process/boot behavior NOT TESTED |
| 2 — AI/finance | Integer ledger, separate fees/transfers/reversals/evidence, review edits, provider-scoped merchant aliases, cash/date review, strict candidate schemas, LiteRT-LM adapter and bundled OCR | PARTIAL | Gemma 4 E2B/fallback benchmark BLOCKED without owner-selected compatible model/device; representative provider corpus unavailable; 95% accuracy NOT ESTABLISHED |
| 3 — communications | IMAPS/SMTP, UIDVALIDITY/MIME handling, bounded visible IDLE, drafts/one-use approval/recovery, encrypted relay, owner-selected whatsapp-web.js capture and optional Meta adapters | PARTIAL | Live Web pairing/restart/message coverage and Gmail access NOT TESTED; optional Meta eligibility/Coexistence remains unverified; no real sends or deployment |
| 4 — tasks/alarms | Evidence-linked candidates, owner activation, escalation, Done/Snooze/Reschedule, durable restoration, nightly review and morning/evening query briefs | PARTIAL | Physical alarm/Doze/DND/permission/reboot tests NOT TESTED; overdue repeat interval owner-configurable (5–1440 minutes, default 10) |
| 5 — media | Encrypted originals, SAF import/folder scanning, adaptive-noise-floor VAD intervals and AAC-LC/ADTS compressed encrypted recording during visible owner recording, compiled whisper JNI adapter, chunked processing, OCR/PDF/video sampling, bundled ML Kit image labels, playback/export/delete/manual and experimental automatic speaker labels/summary review | PARTIAL | Actual decoder/STT/OCR/label/model accuracy and OEM file visibility NOT TESTED. On-device AAC encoder output/size/battery NOT TESTED. Speaker grouping, owner-voice matching and name placeholders are implemented (owner-imported ONNX embedding model, ONNX Runtime 1.31.1) but their accuracy is NOT TESTED; learned VAD is not implemented; no simulated outputs |
| 6 — assistant/stability/backup | Source queries/links, deterministic finance/briefs, typed review-only proposals, confirm-first English/Kiswahili voice/chat commands (reminders, memory notes, task done/snooze/reschedule, cash review items, email/WhatsApp drafts), memory provenance/correction/forget, encrypted portable backup/staging/recovery, startup storage and send recovery | PARTIAL | Full native/device backup round trip, SQLCipher conversion/Keystore, battery/peak RAM/thermal and usability tests NOT TESTED. Literal retrieval is bounded. Semantic search code is present (owner-imported MediaPipe text embedder, arm64-v8a only) but retrieval quality, indexing speed and battery cost are NOT TESTED; no autonomous capability is claimed |

## Host verification

From `android/`:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Final result: **193 tests (merged redesign and resource-use regressions): 189 passed,
4 opt-in live AI tests skipped; 0 failures/errors. Lint: 0 errors, 240 warnings and
3 hints. APK assembly passed.** Native whisper JNI compiled for
arm64-v8a and x86_64. Robolectric uses API 34 and ordinary SQLite/generated AES keys;
it does not execute phone Keystore, native SQLCipher, MediaCodec, ML Kit or models.
Warnings remain enabled, including English widget resources, unused attributes,
KTX suggestions, content descriptions and dependency/update notices.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, version **0.4.0**, code 4,
package `com.hellfire.ass`, min/target API 29/36, arm64-v8a and x86_64 only.
SHA-256: `0183db716798b22363128de5bf8f3d30d052f67f8ab7b96348ffcc41b2154e84` (248,694,596 bytes, ≈237 MiB; uncompressed 16 KiB-aligned native libraries — ONNX Runtime adds ≈75 MB across arm64-v8a and x86_64).
HTML/XML reports are preserved under `android/app/build/reports/` and
`android/app/build/test-results/testDebugUnitTest/` after integration.

| Check | Result | Evidence / limits |
|---|---|---|
| Tooling | PASS | JDK 21.0.13, SDK 36/build tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1; no global package upgrades |
| Normalization/capture/SMS regression | PASS (host) | Immutable identities, malformed/missing/protected content, OTP precedence, marked money/reference/date preservation, queue/policy/rollback/persistence and SMS paging fixtures |
| Room migrations | PASS (host) | Construct schema 1, migrate non-destructively through schema 9; source ciphertext retained and Room validates tables/indices/FKs |
| Finance | PASS (host) | Integer limits, synthetic provider candidates, duplicate-reference evidence, transfer balancing, separate fees, edited approval, refunds/expense totals and append-only reversals |
| Candidate/action contracts | PASS (host) | Strict integer/schema fields, invalid dates/parties/channels, unsupported execution and injected approval/header data rejected |
| Speaker features / grouping / names | PASS (host) / NOT TESTED (device) | `SpeakerTest`: Kaldi-style fbank frame counts, mean normalization and tone peaks in the expected mel bin; centroid clustering of synthetic embeddings; English/Kiswahili introduction and direct-address name detection; placeholder assignment needs score ≥2, one group per name, owner group skipped. No ONNX model is run on host; real-voice grouping/owner/name accuracy NOT TESTED |
| Semantic index / image labels | PASS (host) / NOT TESTED (device) | `SemanticIndexTest`: L2 normalization, little-endian vector round trip, cosine ranking, pending excludes sensitive/unavailable/indexed sources, empty-text markers never searched, model switch re-indexes, vectors cascade with sources, confident-label formatting. MediaPipe `libmediapipe_tasks_text_jni.so` 0.10.26 ships arm64-v8a only (16 KiB aligned); no embedding or ML Kit labeling is executed on host |
| Owner voice/chat commands | PASS (host) / NOT TESTED (device) | `AssistantCommandsTest`: questions stay queries; reminder dates/titles (EN/SW, past/missing dates rejected), memory subjects, cash parsing, email/WhatsApp validation and header injection, task action/reschedule parsing, active-only task lookup. `execute` composes existing tested task/draft/finance/memory writes but is not run on host (needs Keystore-backed `HellFireApp`). Spoken commands through the system session NOT TESTED |
| Communication persistence/MIME | PASS (host) | Approval freshness/single claim, interrupted-process recovery, monotonic delivery states, UID cursor isolation, Unicode/plain-text alternatives and bounded deep MIME traversal |
| Tasks | PASS (host) | Source-relative EAT dates, invalid dates, escalation math, review-only until activation, snooze/done, source FKs and explicit nightly review completion |
| Media container | PASS (host) | Authenticated seeking across chunks, corruption/wrong identity rejection, interrupted recording prefix recovery, exclusion logic, ADTS header bits, and adaptive VAD on synthetic signals (steady 0.03 RMS noise rejected where the old fixed threshold fired, 20 ms clicks ignored, odd read boundaries); no real microphone/codec — `MediaCodec` AAC encoding does not run on host |
| Backup components | PASS (host) | Table allowlists, Unicode rows/evidence, different payload key after restore, wrong passphrase and stale approval revocation. Does NOT establish full SAF/SQLCipher/Keystore/device restore |
| APK/native alignment | PASS (host) | `zipalign -c -P 16 4` passes; all 21 packaged ELF libraries have LOAD alignment >=16 KiB. Physical installation remains NOT TESTED |
| Relay tests | PASS (host) | 21 pytest tests (including linked-device capture): signature/verification/auth, encrypted replay/ACK, business filtering/statuses, approval/replay/expiry/unknown delivery, bounds/rate limiting and log query redaction; mocked Graph only |
| Relay image / Compose | PASS (host) | `docker build -t hellfire-relay:whatsapp-web server`; non-root image `4903d7c497e2`; Compose parses with no env resolution. Ephemeral ASGI smoke confirms UID 10001, honest unconfigured health and protected endpoint 503; no listener/deployment/live network |
| Physical/model/live-account tests | NOT TESTED | Explicitly deferred; neither grants, Coexistence eligibility, actual inference nor successful delivery is asserted |

Relay verification from `server/`:

```bash
.venv/bin/python -m pytest -q
docker compose config --no-env-resolution --no-interpolate
```

Result: **21 passed**. One Starlette TestClient/httpx deprecation warning remains;
no runtime/security check was disabled to hide it. Docker's legacy-builder notice
also remains visible. A separate isolated container smoke test imports the app and
checks ASGI routes without opening a port or contacting Meta.

`aapt` confirms INTERNET, SCHEDULE_EXACT_ALARM, data-sync/microphone foreground
permissions, SMS read/receive, microphone/posting, and normal WorkManager permissions.
It declares no SEND_SMS or broad media-reading grant. Declarations are not grants.
The app acquires files/folders through owner-controlled SAF selection.

Historical host checkpoints: Phase 0 — 34 tests; Phase 1 — 58; combined initial
finance/comms slice — 66; media slice — 80; initial assistant/backup slice — 87.
Current artifact/results above supersede prior debug builds, not their untested
physical outcomes.

## Resource-use checkpoint — 2026-11-14

Processing coalesces repeated requests into one queued drain. If the current drain
is already running, one successor covers events committed after its final queue
read. Duplicate-only notification/SMS batches no longer request processing. Capture,
finance/task rules and alarms do not wait for a charging constraint.

Automatic semantic indexing has a separate, unique 15-minute periodic worker,
requiring charging and adequate battery. It is registered on model import/startup
and cancelled on model removal. New meaning matches can lag until charging and an
OS-scheduled run; literal search still reads captured sources. Manual **Index now**
works on battery. Empty backlogs skip native model creation; batch limits are exact,
thermal status is checked between batches, and embedding runtimes share the existing
speech/assistant model lock. No source/audio retention or permission is changed.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Work queue bounded under bursts and running/failed drains? | Real WorkManager persistence with worker execution disabled; concurrent requests and explicit work states | PASS (host) | 100 concurrent triggers leave one queued drain; 50 triggers during RUNNING leave one BLOCKED successor; FAILED work allows a new drain | Verify actual process/OS scheduling on device |
| Automatic indexing power policy and idle behavior? | Persisted worker constraints; invalid synthetic model with empty Room backlog | PASS (host) | 20 registrations retain one periodic job with charging/battery constraints; empty backlog and zero budget return without loading the invalid model; negative budget rejected. All 5 `ResourceUseTest` checks pass | Verify actual embedding throughput, batch limits, cancellation and model serialization on device |
| Lower battery, CPU and peak RAM on the OPPO? | Compare identical controlled workloads before/after | NOT TESTED | No physical profiling performed | Record baseline and optimized overnight battery, CPU time, PSS/peak memory and index latency; verify unplug/replug, severe thermal status and capture during indexing |

Scheduling references: [unique work and chaining](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work),
[work constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

Final combined verification (after incorporating the separately merged UI redesign):

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' --console=plain
```

PASS: 193 tests (189 passed, 4 opt-in live tests skipped), lint 0 errors / 240
warnings / 3 hints, debug APK assembled, and `zipalign -c -P 16 4` passed. The run
used one worker and a command-line heap limit after concurrent workstation builds
caused memory pressure; no project/global build settings or checks were changed.
The earlier 165-test pre-redesign suite also passed. No device profiling or model
inference was performed. Source capture remains independent of automatic meaning
indexing; battery and peak-memory improvements are still NOT TESTED on the OPPO.

## Capture quality checkpoint — 2026-11-13

Review fixes for silent loss and duplication between capture and review. Synthetic
fixtures only; real provider formats remain uncalibrated.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Do everyday "code/PIN/token" words keep finance and mail content while codes are removed? | `NotificationNormalizerTest` | PASS (host) | "Usitoe PIN yako" payment, LUKU token purchase and "code review" mail keep content; keyed numeric/alphanumeric codes removed in every field, including a PIN repeated as a reference; OTP/password/verification/msimbo still withhold the whole item | Check against real M-Pesa/Airtel/Mixx/bank messages on device |
| Do removal records, promotions and old history stay out of review queues? | `CaptureProcessingTest`, `TaskLogicTest` | PASS (host) | REMOVED events labelled `REMOVAL_RECORD` without finance/task collection; promotion/system labels skip task extraction; deadlines over seven days old are not review items | Observe queue volume with real notification/SMS/mail backfill |
| Does a repeated provider reference avoid a second review item? | `FinanceTest` | PASS (host) | Second source stored as `DUPLICATE`, linked as ledger evidence on approval; one entry posted. Candidates without a parsed reference still need manual review | Calibrate reference parsing on a real corpus |
| Are relative day words parsed as whole words? | `TaskLogicTest`, `AssistantCommandsTest` | PASS (host) | "call Leonard on monday" → Monday; keshokutwa/kesho kutwa/day after tomorrow → +2 days | — |
| Full host suite and lint? | `./gradlew :app:testDebugUnitTest :app:lintDebug` | PASS (host) | Branch: 152 tests, 148 passed, 4 opt-in live AI tests skipped. Merged main with advanced audio: 155 tests, 151 passed, 4 skipped; lint 0 errors, 46 warnings | — |

## WhatsApp linked capture checkpoint — 2026-11-13

The owner explicitly selected `whatsapp-web.js` and authorized this capture-only
slice, superseding the former Coexistence-only selection. Meta mode is preserved
as an alternative. No live account, phone or personal message was used.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Worker normalization, encrypted queue and lifecycle? | `cd server/whatsapp-web && npm test` | PASS (host) | 6 tests: incoming/outgoing/group author and media metadata, AES-GCM identity binding, restart/deduplication, quota/expiry, lifecycle/late QR and text bounds | Verify real provider events after owner linking |
| Relay and existing Meta regression? | `cd server && .venv/bin/python -m pytest -q` | PASS (host) | 21 passed; includes actual Node-generated ciphertext imported by Python, phone-envelope decryption, replay/ACK, failed commit/tampering preservation, auth, QR expiry, web send rejection and bounded batches fitting Android's 2 MiB limit. Existing Starlette/httpx deprecation warning remains | Exercise real HTTPS and paired Android persistence |
| Dependency advisories? | `npm audit --omit=dev --omit=optional` | PASS (host) | Zero known vulnerabilities at this checkpoint after Puppeteer 25.17.0 override and omitting unused RemoteAuth dependencies; no global packages changed | Recheck on later updates; live browser/library compatibility remains unverified |
| Library/browser adapter imports? | Construct real `Client` and `LocalAuth`, import Puppeteer launch function without initialization | PASS (host) | whatsapp-web.js 1.34.8 and Puppeteer 25.17.0 import/construct under Node 22.23.3. No browser or WhatsApp connection exercised | Verify full pairing and own-message sync |
| Compose wiring? | `docker compose -f compose.yaml -f compose.web.yaml config --no-env-resolution --no-interpolate` | PASS (host) | Relay remains loopback-only; worker has no port; private profile volume separated from encrypted shared spool | Provision private env/public key and HTTPS before deployment |
| Android capture adapter and setup controls? | Full host unit suite, lint and debug APK; final UI assembly/lint | PASS (host) | 145 tests: 141 passed, 4 opt-in live tests skipped. Four new GatewayEvents tests cover group author/thread, outgoing direction, transport mismatch and OTP/envelope redaction. Lint: 0 errors, 46 warnings; no suppression added | Verify phone capture and background behavior |
| Relay and worker images? | Build both Dockerfiles; offline, network-disabled import/ASGI smoke | PASS (host) | Relay image `4903d7c497e2`; worker image `70cccf1ffad9`. Both run as UID 10001; final worker verifies Puppeteer 25.17.0 and constructs Client/LocalAuth. Pairing shell serves without exposing QR; unconfigured private endpoint rejects access | Browser startup, sandbox and account pairing remain NOT TESTED |
| Live pairing and browser sandbox? | Start provisioned worker, scan QR, check linked-device list | NOT TESTED | No owner account linked. The official Puppeteer image needs SYS_ADMIN for its enabled Chromium sandbox; no `--no-sandbox` bypass added | Verify on selected trusted Linux Docker host |
| Complete incoming/own/group/media coverage? | Controlled messages from phone and another account, disconnect/restart/replay | NOT TESTED | Software normalization and provenance pass host tests only. Attachments are metadata; history/edits/deletions/reactions and Web sending are outside this slice | Record actual gaps; never infer missing content |
| Android grants/background sync? | Connect mode web, Sync, screen-off and periodic maintenance | NOT TESTED | Existing manual/best-effort maintenance reused; no push guarantee | Verify phone envelope decryption, Room persistence, redaction and subsequent ACK |

Combined verification on `main` preserves the separately merged semantic-memory
update: 145 Android tests (141 passed, 4 opt-in live tests skipped), 0 lint errors /
46 warnings, debug APK assembled; 21 relay and 6 worker tests pass. The initial
combined attempt hit a GitHub timeout fetching the existing whisper.cpp source.
The already cached v1.9.5 archive was verified against the pinned SHA-256 and copied
into the ignored CMake download cache; the normal checksummed build then passed.
No build script, source pin, model or global package was changed for that recovery.

Worker spool retention: seven days / 10,000 events, 128 KiB per event and 16,000
body characters with explicit truncation. Queue/storage failures can lose new
events; status reports errors. The relay retains its seven-day inbox / 30-day
replay fingerprints. Session profile/QR status are sensitive, separately persisted
and excluded from Git/build context; browser history/credentials are not encrypted
by the event spool. Disconnecting the Android relay does not revoke the server's
linked session. See README for deployment, pairing and revocation steps.

## Preserved AI provider checkpoint — 2026-11-13

The earlier main-branch update added optional AI provider support. Its adapters,
settings, privacy/budget gates, UI and tests are preserved. The table records that
standalone checkpoint; the combined current suite is reported above. Local queries
and imported native model support have since been added in separate screens.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Existing local/capture workflows preserved? | Full host unit suite | PASS | All 58 previous tests still pass; original entry points preserved at that checkpoint; now extended with local source queries | Verify actual device behavior at final stage |
| Official provider wire contracts? | Mocked discovery and generation | PASS | Claude versioned Messages, OpenAI/xAI Responses, DeepSeek Chat Completions; headers, payloads, text extraction and usage checked | Test accounts with inference permissions later |
| Provider capability boundaries? | Request tools/images/streaming/structured output | PASS | Unsupported capabilities fail before transport; local test cannot claim reasoning, drafting, extraction or summarization | Add further capabilities only with explicit implementation |
| Routing and manual switching? | Preferred model, ordered/deduplicated fallback, per-task routes | PASS | Distinct authorization/quota/network/outage/capability failures advance in saved order; no implicit cloud fallback | Exercise selectors on device |
| Cancel avoids further inference? | Suspend mocked transport; cancel coroutine | PASS | Cancellation reaches transport and no fallback starts; uncertain reservation retained | Verify actual HTTPS disconnect/provider billing behavior later |
| Cloud privacy boundary? | Protected input categories, cloud-disable, redaction tests | PASS | Notification/SMS/finance/recording/media categories never call cloud; submitted security text/email/phone redacted | Review prose manually; redaction is not exhaustive |
| Credential/settings persistence? | Encrypted file round trip/reopen/delete/wrong key/corruption | PASS | Synthetic AES keys; authenticated ciphertext only; failure blocks cloud while basic local chat works | Verify Android Keystore and UI lifecycle on device |
| Cost cap/accounting? | Integer prices, reservation, settlement, missing usage, restart and month tests | PASS | Monthly owner-timezone counters survive router restart; absent prices/cap or exhausted estimate prevents cloud call | Configure accurate highest applicable prices and provider-side account limits |
| Prompt/secret-free debug metadata? | Audit/debug-string assertions; source review | PASS | Last 100 attempt metadata only; no prompt/answer/credential logging calls added | Keep actual credentials and personal input out of Git/test reports |
| Live Claude/Grok/DeepSeek/OpenAI? | Opt-in `AiLiveProviderTest` | NOT TESTED | Four tests skipped; no configured credential-based verification run | Explicit opt-in plus key/model required; tests can incur charges |
| Provider settings UI and actual device encryption? | Manual Android interaction | NOT TESTED | Compose compiles; no emulator or physical phone exercised | Save/delete keys, discover models, switch routes, rotate during responses, cancel, and test offline |
| Subscription/account sign-in? | Existing code and official authentication documentation | PARTIAL | No existing sign-in found; API keys only added; no participating-tool entitlement assumed | Add account auth only with provider-authorized third-party inference scope |

Host validation uses the same command from `android/`:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**Historical provider-only checkpoint: 91 tests passed, 0 failures/errors; 4 live
tests skipped. Lint: 0 errors, 31 warnings.** These are host results with mocked
network responses and synthetic encryption keys, not successful live API or device
tests. That standalone update changed no dependency versions. API references and opt-in live test setup are
in the existing README's **Optional AI providers** section.

The cap covers local estimated/reserved spend using manually supplied prices,
not exact provider invoices or spending by other clients. Errors, cancellations,
missing usage and process loss retain conservative reservations. No automatic
same-model retries or outward actions exist. Local fallback is only the original
basic offline test; imported LiteRT-LM inference now lives separately in Local AI. The system
voice session remains offline; provider/model selection lives in the activity.

For device verification: start with cloud disabled, confirm hello/habari/time,
then configure an API key and cap using non-sensitive text. Check unauthorized,
exhausted credit, airplane-mode fallback, model rejection, cap exhaustion, and
cancel. Verify response provider/model survives activity recreation. Saving keys
must not expose them in logs, saved-instance state or backup/transfer. Test model
listing separately from generation permissions. Do not use personal histories.

## UI redesign checkpoint — 2026-11-14

Merged branch `feat/ui-redesign`. Redesign per the UI handoff: new theme, five-tab navigation, redesigned tabs, settings,
reports, Room v10/v11 and Glance home screen widgets. Evidence below is host tests and the Android emulator only
(Pixel 6 profile, API 34). **No physical OPPO/ColorOS device was connected; nothing here is a device result.**

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Does the app build and pass the host suite? | `./gradlew assembleDebug :app:testDebugUnitTest :app:lintDebug` | PASS (host) | 188 tests, 184 passed, 4 opt-in live AI tests skipped, 0 failures; lint 0 errors (warnings only) | — |
| Do Room migrations 9→10→11 keep data with no destructive fallback? | `SchemaV10Test`, `WidgetDataTest`, `DatabaseMigrationTest`; emulator upgrade over existing encrypted v9 data | PASS (host + emulator) | Rows kept; completion times backfilled from owner done-audits; promise direction backfilled for message-extracted tasks; SQLCipher DB opened and migrated in place on the emulator | Install over the owner's real database on the OPPO before relying on it |
| Do older encrypted backups still restore? | `SchemaV10Test`, `WidgetDataTest` | PASS (host) | Schema 8 and 10 rows restore into v11 with defaults; unknown or missing core columns still refused | Run a real export → restore on a spare install |
| Do confirm-before-act rules still hold? | Emulator walkthrough | PASS (emulator) | SMS candidates post only after the review sheet's Confirm; drafts send only after "Approve and send" confirmation; task proposals need Add task; balances need Confirm balance; widgets only open the app (promise checkbox marks done, as specified) | — |
| Do reports compute from confirmed entries only, and export? | `ReportDataTest`, `BalanceAndCalendarTest`, emulator | PASS (host + emulator) | Totals, groups, account reconciliation, running balance, calendar totals; PDF exported through the file picker and read back | Compare report totals with real statements |
| Do home screen widgets render, refresh and protect privacy? | Emulator (Pixel launcher) | PARTIAL (emulator) | 13 widgets listed with previews; pinned and rendered; amounts masked by default; a new SMS refreshed Wallets and Nightly review without a timer; widget record → consent → visible notification → stop → "Saved, transcript queued" | ColorOS launcher, resize to large, lock-screen/after-reboot behaviour |
| Widget recording on OPPO/ColorOS | — | NOT TESTED | No device | See REDESIGN_SUMMARY.md checklist |
| Assistant gesture opens Ask | — | NOT TESTED | Intent routing implemented (`ACTION_ASSIST` → Ask) | Test the ColorOS gesture and power-button assistant |
| Background capture overnight with the new UI | — | NOT TESTED | Unchanged services; Setup row shows battery exemption and ColorOS caveat | Overnight test with battery optimisation off and auto-launch on |

## Money entry update — 2026-11-14

Cash entry now has date/time pickers in East Africa Time, a category selector and an
account selector. Each review candidate retains those choices across reloads. Account
creation no longer asks for a provider; Money cards, sheets and report grouping no
longer display it. Any saved account can be chosen when confirming a payment.
Receipt identifiers remain internal to preserve duplicate detection and merchant evidence.
Room v12 adds nullable account/category selections without discarding existing data;
backups retain these selections and schemas 8, 10 and 11 remain readable.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Do selections, migration and ledger approval work? | Host unit tests/build | PASS (host) | 192 tests: 188 passed, 4 live-AI tests skipped; no failures/errors. Debug APK built. Migration v11→v12, selected account/category/EAT timestamp, approval and old/new backup rows covered | Verify real phone interactions |
| Do picker controls behave on ColorOS? | Select and cancel date/time; change category/account; rotate, reopen review, confirm multi-item cash entry; add an account without a provider | NOT TESTED | No phone test performed for this change | Verify timezone, retained selections and balances on the OPPO |

Command: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=1 --console=plain`
from `android/`. Test and assemble tasks passed. Lint was **NOT COMPLETED** at this
checkpoint: its analysis was stopped after prolonged runtime under host memory pressure
(about 600 MiB RAM available, 35 GiB swap in use). It was rerun on 2026-11-16 and
passed with 0 errors; see the project review checkpoint at the top.
APK SHA-256:
`dc852ff89217f8ef380e84c736e87338e42954c5dd583950f99b4aa8d40c2c43`.

## Final-stage device verification

Run these when physical testing begins, from the checkout root. Do not assume the
model, firmware, ABI, feature support, storage paths or access from the owner's
hardware description. With multiple devices use `adb -s SERIAL` locally. Do not
commit serials, private dumps, messages, databases or recordings.

```bash
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.security_patch
adb shell getprop ro.build.version.opporom
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hellfire.ass/.MainActivity
```

In Setup, select HellFire Ass. Record role held separately from voice service active;
neither proves the session works until it opens. Use the settings fallback if needed.

```bash
adb shell cmd role get-role-holders android.app.role.ASSISTANT
adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction
```

In airplane mode ask `hello`, `habari`, and `time` in the activity, then in the real
session. Test the supported gesture/button from another app and after backgrounding.
Try optional offline speech and TTS only if system engines are available. Record
engine/language/error and firmware. No custom hotword or lock-screen parity is claimed.

Grant notification access manually; use Android's owner-controlled restricted-settings
flow only if offered. Insert the synthetic fixture twice, then post controlled input:

```bash
adb shell "cmd notification post -t 'HellFire fixture' hellfire-phase1 'Synthetic delivery test'"
adb shell "cmd notification post -t 'HellFire fixture edited' hellfire-phase1 'Synthetic updated text'"
```

Verify actual source package, posted/updated/removed evidence, redaction flags and
processing labels. Dismiss manually for removal. Test silent, ongoing, grouped,
image-only and malformed/empty inputs from authorized test apps. Toggle access,
background the app and reconnect. Identical snapshots must deduplicate; changed
rank/post time can retain new evidence. Protected content stays unavailable.

Turn an app's processing switch off: its next notification must still be captured,
with CAPTURE_ONLY status. Turn it back on: new/pending events can be classified;
existing capture-only/completed records are not rewritten. Retry failed jobs only
through the visible Resume control. Check historical failures versus live access.

For SMS, use only a test SIM/provider corpus you control. Tap **Enable SMS capture
and history import**; verify READ_SMS and RECEIVE_SMS separately. If Android or the
installer refuses them, record BLOCKED/PARTIAL and leave other sources functional.
Do not bypass restrictions or claim default-assistant selection guarantees SMS access.
Check incoming and sent provider rows, multipart messages as stored by the default
SMS app, imports over 500 rows, replayed imports and outbox-to-sent transitions.
Drafts/failed/outbox rows must not appear as sent. Some messages can be omitted by
the provider; broadcast receipt alone is not stored-body evidence.

Test new incoming broadcasts, outgoing provider observation, app/process restart and
best-effort periodic reconciliation. WorkManager may defer work under Doze/OEM rules;
15 minutes is a requested maintenance interval, not a delivery deadline. Disabling
SMS capture must stop future imports, cancel its pending chain and retain old evidence.
Permission revocation must be reported honestly. No action in this app sends an SMS.

For microphone tests keep the app visible, grant mic and posting permissions, and
Start. Verify Android's mic indicator, persistent notification and Stop action.
Record a non-sensitive sample, stop/play/delete, then repeat to automatic 60-second
stop. Test permission denial/revocation, privacy mute, blocked notification channel,
competing mic, call interruption and screen off. No restart or hidden capture should
occur. Process/power loss can leave incomplete ciphertext, which playback rejects.

For controlled lifecycle tests, terminate the process only after known events have
committed, then reopen and verify pending processing resumes without duplicates.
Separately test before-commit termination and queue overflow: losses can occur there
and must not be claimed recoverable. Test reboot and force-stop separately; Android
can prevent work until user relaunch. Perform battery measurements later.

Optional debug inspection after stopping capture and recording:

```bash
adb shell run-as com.hellfire.ass ls databases
adb shell run-as com.hellfire.ass ls files/recordings
```

Inspect only synthetic evidence if exporting a debug database. The production database, payloads and audio must be ciphertext. Keystore/native
SQLCipher behavior and legacy plaintext conversion need explicit physical verification. Clear controlled test events and samples in the app. Keep all actual
device artifacts out of Git.

## Final checks for Phases 2–6

Run only after the owner starts physical testing, using controlled synthetic material.
Keep messages, credentials, recordings, serials and exported backups out of Git.

1. **Encryption and migration:** install over the previous debug app after a safe
   backup of synthetic data. Confirm legacy events migrate and the database file
   no longer exposes SQLite headers/plaintext. Exercise denied/unavailable Keystore,
   storage-full and retry paths; original files must remain, with no plaintext
   fallback. Host Room schema validation does not establish native conversion.
2. **Finance:** create test accounts and approve expenses, income, own transfers,
   separate fees, refunds and reversals. Replay SMS/notification evidence with the
   same provider reference; it must not double post. Test edited dates/amounts,
   merchant reuse and nightly skipped-day review. Obtain a representative,
   owner-approved anonymized provider corpus before measuring the >=95% parser
   target; synthetic fixtures are not that corpus. Review unfamiliar/reversal
   templates explicitly.
3. **Models/OCR:** owner imports licensed compatible model files. In airplane mode,
   test Gemma 4 E2B and an appropriate smaller fallback separately on CPU/GPU.
   Record cold-start/request latency, actual output correctness, peak memory,
   thermal state and battery deltas, including failed runs. UI PSS is a process
   snapshot after the request, not peak/model-only RAM. No NPU support is inferred
   from the phone marketing description. Test bundled OCR with non-sensitive images,
   receipts, multi-page PDFs, video samples and unsupported files; OCR never proves
   settlement. Schema-invalid output must stay rejected.
4. **Accounts:** only with actual owner permission, configure eligible Gmail app
   password / IMAP and the owner-provisioned HTTPS Meta relay. Verify Coexistence
   eligibility, number/account linkage, webhook subscriptions, encryption pairing,
   incoming/echo/status delivery and unsupported envelopes. Drop network mid-sync,
   change UIDVALIDITY and replay batches. Stop IDLE via UI/notification/timeout and
   disconnect. Inspect source IDs and ensure no message is marked sent merely from
   preparation or SMTP acceptance.
5. **Approval/failure:** approve one controlled external reply; compare actual
   recipient/body/reply headers. Test stale/replayed approval, app death before and
   during transport, credential changes and delayed/out-of-order confirmations.
   Recovery must require fresh approval or keep UNKNOWN; it must not resend. Check
   the actual channel before manually creating another send after uncertainty.
6. **Alarms:** review candidate versus active task, 24h/2h/due/overdue escalation,
   configurable repeat interval, Done/Snooze/Reschedule, revoked posting/exact-alarm
   access, blocked channel, reboot/update/time changes, Doze/DND and force-stop.
   Record real delay, sound and restoration separately; best effort does not promise
   DND bypass or force-stop survival. Check morning/evening brief totals and sources.
7. **Recordings/media:** test owner notice/exclusions before visible monitoring;
   verify Android indicator, ongoing notice, Stop and 30-minute bound. Revoke
   permissions/notice and take a call; retain authenticated recording prefix with
   visible interruption status. No boot/process interruption may restart the mic.
   Test noise/speech/music and VAD false positives, manual speaker correction and
   playback from timestamps. Confirm new recordings are `audio/aac`, play, seek,
   transcribe and export as `.aac`; measure bytes per minute (expect ≈240 KB/min
   versus ≈1.9 MB/min PCM), battery, and an interrupted recording's playable prefix.
   Compare VAD spans in quiet rooms, fans/traffic and music. Import a speaker model,
   enroll from a test sample, and label a consented multi-person recording: record
   group purity, owner-match rate/false matches, name placeholders versus who was
   addressed, time and memory per minute. Confirm manual labels are never replaced. Import/replay accessible OPPO recordings
   and selected-folder changes. If files are private/inaccessible, record BLOCKED
   and use dialer sharing. Test rename, unknown/known original dates, video without
   audio, charging/thermal constraints, analysis cancellation/recovery, decrypted
   export and media deletion versus retained linked source snapshots.
8. **Assistant/memory:** compare deterministic finance answers against the ledger;
   inspect source links, query limits, facts versus inferences, owner corrections
   and index-only forgetting. Verify both typed and installed offline speech/TTS
   queries. Incoming instruction-like source text cannot execute tools. Typed
   proposals only retain Tasks/Messages review items; separate approval is required.
   Speak and type each owner command (`remind me to … tomorrow 15:00`, `nikumbushe …
   kesho`, `remember Asha: …`, `mark … done`, `snooze …`, `reschedule … to …`,
   `log cash … 3000 na … elfu saba`, `email … about …: …`, `whatsapp +255…: …`):
   confirm nothing changes before Confirm, reminders alarm at the stated EAT time,
   cash items appear only in Finance review, and drafts are never sent without the
   separate Messages approval.
   Import a MediaPipe text-embedder model (e.g. Universal Sentence Encoder), run
   Index now, and record indexed/pending counts, time per 100 sources, battery and
   peak memory. Compare literal versus `≈` meaning matches on synthetic paraphrases;
   confirm OTP/sensitive sources never appear. Import photos and check that
   `media_labels` sources are plausible and marked unverified.
9. **Backup:** export synthetic registered originals and relational data with a
   strong test passphrase. Authenticate/restore into an empty separate installation
   or test profile with capture/accounts off. Compare ciphertext decryption, media
   hashes, ledger evidence, tasks, transcripts, Unicode memory and source links.
   Wrong password, truncation, tampering and unsupported schema must fail without
   replacing current data. Test process loss during staging/commit and verify
   journal cleanup. Restored approvals must not send; accounts, model files,
   grants and exclusions need reconfiguration. Do not rely on backups until this
   full native/device round trip passes.
10. **Stability/energy:** compare baseline and enabled-source overnight runs, model
    jobs on/off charger, screen off, low storage/battery, severe thermal status,
    interrupted connectivity and process death. Record actual losses and delays.
    Tune only from measurements; no overnight survival or energy claim is made.

## References and limits

- [Assistant service](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
  and [notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService).
- [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work)
  and [work chaining policies](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/chain-work).
- [SMS provider](https://developer.android.com/reference/android/provider/Telephony.Sms)
  and [protected SMS broadcast](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents).
- [Microphone foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).
- Notifications before a durable commit or while disconnected can be lost; committed
  jobs persist. OTP filtering is conservative, not a universal secret detector.
- Host tests establish software contracts, not OEM permissions or device success.
