# HellFire Ass — implementation and feasibility

Latest update: 2026-11-25 (Priority 1 calendar slice, below). Earlier
checkpoints keep the test counts and APK checksums that were true when they were
written; the newest checkpoint has the current ones.

## Current scope — owner update 2026-11-25

The owner authorized core-depth work in this order: Calendar integration,
OxygenOS background survival, location-aware reminders, then Telegram. This run
implements **Priority 1 only**. The earlier deferral below is historical; no new
media/library/news/player/widget surface is part of this slice. Physical testing
remains last. The existing TheftGuard consent exception is unchanged.

## Earlier scope and remaining plan — owner update 2026-11-18

The owner requested: “Finish what you have done and plan the remaining later.”
This closes the implemented checkpoint and defers further feature expansion.
It does **not** claim every originally requested pre-device feature or software
verification task is complete. The classified audit below is retained for planning.

Next software phases, in order:

1. Run full, incremental and selective backup/restore through native SQLCipher on
   the emulator, including interrupted staging and original-file recovery. Existing
   host coverage, Android cryptographic tamper tests and native migrations are
   complete; a full native backup round trip remains separate work.
2. Exercise forced process death during visible AAC conversation recording and
   background jobs. Audit the current 64 KiB authenticated recording chunks: a
   short recording may not have a committed chunk before abrupt process death.
   Evaluate a backward-compatible smaller recording chunk with explicit storage,
   CPU and durability tests before changing the format behavior.
3. Complete remaining contracted media paths: video blur/pixelation, moving-region
   treatment and optional voice pitch transformation, with decoded-output goldens
   and fail-closed export checks. Static video covers and mute ranges work now;
   automatic tracking and voice transformation remain explicitly unavailable.
   Extend QR redaction suggestions separately from the completed QR text indexing.
4. Expand English/Kiswahili model-quality datasets and cross-source/proactive
   workflow replays. The current deterministic bilingual goldens and real English
   Whisper/VAD contract tests establish software behavior, not real-world Kiswahili
   accuracy, speaker identity or multimodal reasoning quality.
5. Extend acceptance mapping and UI coverage to the remaining settings/recovery/
   interruption flows and API 36 permission/regrant coverage; collect app Baseline Profile rules using an appropriate
   emulator variant where practical. Actual phone performance benefits remain a
   later physical comparison. Add C++ kernels only after comparative evidence meets
   the original gain threshold; no speculative native rewrites are planned.
6. Run the authored hosted CI workflow when repository execution is arranged.
   Local checks and workflow configuration are reported separately; local success
   is not hosted CI success. Then repeat release packaging for that later scope.

The current prioritized physical-only suite below covers the actual OnePlus,
CPU/GPU/NPU delegates, hardware-backed security, hardware codecs, user/OEM permission
behavior, sustained thermals/battery and real phone profiling. Authorized live
Gmail/WhatsApp/Drive/AI eligibility remains an external-service gate. Neither list
is a reason to delay the software phases above when work resumes.

## Pre-device completion audit — 2026-11-18

The owner's updated instruction reserves the OnePlus 16 for final hardware-specific
verification. The current code, not historical NOT TESTED rows below, controls this
audit. Earlier checkpoints remain historical evidence, not a current backlog.
The classifications are retained for later phases; pending rows below are not
completion claims. Production stays ARM64-only; a separate x86_64 emulator build
must report incompatible AI runtimes rather than substitute fake success.

| Outstanding area | Classification | Work / evidence required |
|---|---|---|
| Existing performance slice, release/native footprint, benchmark/profile APKs | HOST TEST NOW | Finish current build, preserve baseline comparisons, verify profile-source prerequisites, symbols/alignment and native cancellation. 501 JVM tests currently pass; debug lint has zero errors. |
| Full repository/master-spec acceptance mapping | IMPLEMENT NOW | Replace stale phone-only deferrals with this classified backlog and one final prioritized hardware list. |
| Native JNI ownership, error paths and sanitizers | HOST TEST NOW | Production bridge with synthetic Whisper API; add ASan/UBSan and real compatible host Whisper checks where models are available. No synthetic inference counted as real. |
| Emulator ABI/build/runtime status | IMPLEMENT NOW | Dedicated x86_64 test variant and isolated app data. Inspect actual packaged libraries; unavailable delegates/models stay visibly unavailable. |
| SQLCipher/Keystore, migrations, encrypted originals and full/incremental/selective recovery | EMULATOR TEST NOW | Use genuine Android libraries and synthetic data; interrupt/reopen/recover and inspect ciphertext. These are not limited to the OnePlus. |
| Compose navigation, permission denial/regrant, app lock and screenshots | EMULATOR TEST NOW | Automate representative flows and deterministic synthetic UI capture; retain production security gates. |
| Notification/SMS capture, assistant selection, microphone foreground-service lifecycle and alarms | EMULATOR TEST NOW | Exercise supported stock-Android flows and interruption/recovery; record ADB-granted test permissions separately from user/OEM behavior. |
| Platform decoding, OCR/labels, playback and sanitized exports | EMULATOR TEST NOW | Generated audio/video/images, timestamps and output verification; distinguish software/emulated codecs from physical hardware codecs. |
| Media semantic pagination, ONNX input-layout handling, cancellation and identity defects | IMPLEMENT NOW | Correct confirmed source defects with regression tests before adding acceleration. |
| Financial/media goldens and English/Kiswahili AI evaluation fixtures | IMPLEMENT NOW | Seeded generators, expected typed outcomes and failure cases; no generated result represented as a real model accuracy score. |
| Cross-source memory, proactive workflows, provider success/failure contracts | HOST TEST NOW | Replay synthetic events through existing adapters, approval policy, deterministic finance/time parsing and memory provenance. |
| Previously deferred media/intelligence capabilities | IMPLEMENT NOW | Audit QR extraction, learned VAD/model adapters, news embeddings, overlay and video-redaction/tracking paths against current contracts; implement feasible missing paths without inventing inference outputs. |
| Relay/WhatsApp adapters and mail protocol behavior | TEST WITH SYNTHETIC SERVICES | Isolated fixture accounts/servers and encrypted capture/replay, failures, malformed content and approval boundaries; never contact a real recipient. |
| Multi-client sync, browser crypto, conflicts, backups and interrupted transfer | TEST WITH SYNTHETIC SERVICES | Disposable Postgres/API instances and multiple synthetic clients; no reset of a personal database. |
| API and web frontend regression/navigation | TEST WITH SYNTHETIC SERVICES | Existing API tests, real frontend build/typecheck and browser checks against synthetic services. |
| Host-compatible real model files/formats | HOST TEST NOW | Inventory available files/tooling and evaluate actual supported models; record unavailable external artifacts explicitly. Android delegate results remain separate. |
| Live AI providers, Gmail, WhatsApp pairing and Google Drive | TEST WITH OPTIONAL AUTHORIZED LIVE SERVICES | Run only when existing explicit opt-in and credentials authorize the exact operation. Do not extract credentials or send real messages to obtain a pass. |
| CI, reproducible packaging, native diagnostics and reports | IMPLEMENT NOW | Automate deterministic checks, artifact manifests and failure reporting; a local run does not establish hosted CI success. |
| Automated handset bring-up and benchmark collection | IMPLEMENT NOW | Read-only hardware inventory, verified artifact install workflow, fixed workloads and local evidence bundle; no automatic permission/security bypass. |
| Actual OnePlus CPU/GPU/NPU compatibility/throughput, OEM restrictions, real sensors, battery/thermals and sustained hardware behavior | REQUIRES PHYSICAL DEVICE | Final controlled tests on the actual handset; emulator/host results cannot satisfy these rows. |
| Real owner corpora, call-recording locations and live integration eligibility | REQUIRES PHYSICAL DEVICE / TEST WITH OPTIONAL AUTHORIZED LIVE SERVICES | Validate only authorized accessible evidence; synthetic correctness does not establish real-world extraction accuracy or provider eligibility. |

Available workstation evidence: SDK images `android-34/google_apis/x86_64` and
`android-35/google_apis/x86_64`, emulator executable and accessible `/dev/kvm`.
Fresh test AVD/data will be isolated from the existing MyPhone/Karibu/Kisima AVDs.
Existing Node 24, PostgreSQL tools, Chromium and test dependency caches are present.
No physical OnePlus is connected. The initial inventory found no owner model files.
Small pinned public Whisper/Silero artifacts were subsequently fetched and tested
separately on the host and emulator, as recorded below.

Fresh host checks: relay **23 passed**; WhatsApp Web capture **6 passed**; API
**30 passed** against a new disposable PostgreSQL 18 container on loopback port
55439 (not the existing workstation databases); frontend TypeScript check passed.
No live AI opt-in is configured, so the four live-provider tests remain skipped.
The current LiteRT-LM, ONNX and SQLCipher AARs include x86_64; MediaPipe text
0.10.26 includes only ARM64. The emulator build can exercise the former natively
and must explicitly report MediaPipe text inference unavailable.

Original execution order: finish the performance checkpoint; establish deterministic host,
native and synthetic-service checks; fix confirmed software defects; add and run
the emulator/native/UI suite; complete remaining contracted capabilities; repeat
regression gates, packaging and evidence collection. The final device list will
contain only checks that genuinely depend on hardware/OEM behavior or unavailable
authorized external services. The later owner scope update above now defers
unfinished software work into the prioritized plan.

### Pre-device execution evidence (completed checkpoint)

- **HOST TESTED:** seeded English/Kiswahili finance/search/media goldens; the
  latest full JVM run completed **521 tests, 517 passed, 4 optional live-provider
  skips, no failures/errors**. This full run included the live local TLS mail fixture; only the four external AI-provider checks skipped.
  The goldens exposed a Kiswahili failed-payment false positive, now rejected.
  Other confirmed fixes cover NHWC image dimensions, composite video-embedding
  cursors, namespaced search identities/duplicate evidence, WebP little-endian
  chunk scanning and cancellation of obsolete library filter queries.
- **HOST REAL MODEL TESTED:** `tools/test_real_whisper.sh MODEL [PINNED_SOURCE]`
  compiled the production CMake/JNI source against real whisper.cpp 1.9.5 and
  transcribed the public 11-second JFK sample with `ggml-tiny.en-q5_1.bin`.
  CheckJNI passed; abort/reset/release ran. One observed host run loaded in
  192.473 ms and inferred in 12,595.655 ms. These are single host observations,
  not phone benchmarks or acceleration results. Model: 32,166,155 bytes,
  SHA-256 `c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b`.
- **HOST REAL ONNX TESTED:** `tools/.venv/bin/python tools/evaluate_onnx.py
  --models CACHE --sample android/app/src/androidTest/assets/jfk.wav` with
  ONNX Runtime 1.31.1 CPU. Actual Silero ONNX model (2,327,524 bytes,
  SHA-256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`)
  returned finite states/probabilities, digital silence maximum 0.008910686 over
  93 frames, 233/343 public English-speech frames over 0.5, deterministic reset.
  These are contract checks, not speech accuracy or Kiswahili evaluation scores.
  Two generated nonsquare NCHW/NHWC graphs also executed real ONNX inference;
  their synthetic weights do not establish adult-classifier accuracy.
- **HOST SYNTHETIC SERVICES TESTED:** relay 23, WhatsApp worker 6, API 30 tests
  passed against isolated Postgres 18. Next.js patched from 16.4.3 to 16.4.6 for
  GHSA-mfr8-8qww-v76h / GHSA-vc7v-qv4w-wrv8; the frontend does not use the affected
  cache APIs. Production build and seven Chromium browser checks passed: private
  redirects, authenticated build-time sync proxy, incorrect password, secure HttpOnly SameSite session, eight routes,
  synced ledger/mobile viewport, logout access removal. `npm audit`: zero known
  vulnerabilities after the patch. No live service credentials or sends used. The real IMAPS/SMTPS fixture test also passed (1 test, zero skips): untrusted certificate, hostname mismatch and wrong password rejected; bilingual capture, replay deduplication and OTP redaction verified through the production mail adapter against cached GreenMail 2.1.3 on loopback. The fixture creates and removes its own short-lived certificate; production TLS checks remain enabled.
- **EMULATOR TESTED:** isolated API 35 Google APIs x86_64 AVD,
  fingerprint `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
  **20/20 integration tests passed, no skips** (16 core and four capture tests).
  Includes real Whisper model
  transcription through Android MediaExtractor/MediaCodec/JNI, actual VAD ONNX
  inference/reset, nonsquare NCHW/NHWC tensor inference, migration of all historical
  schemas 1–18 to 19 through SQLCipher, Keystore/AES-GCM tamper tests,
  wrong-key/truncated-password envelopes, native invalid-model rejection,
  navigation, synthetic screenshot comparison, bundled QR decoding, actual video
  clipping/cover pixels/mute RMS/metadata stripping and cancellation cleanup.
  The first runs exposed localization losing the Activity owner and the emulated
  goldfish video decoder timing out. Both are resolved: locale wrappers preserve
  Activity ownership; only the x86 test variant selects real Android software
  decoders. Production codec selection is unchanged. Automatic ONNX telemetry
  initialization and the CCT transport backend are removed from the manifest,
  and owned ONNX sessions explicitly disable telemetry. Runtime package checks
  verify those automatic components are absent. The prior targeted media/security
  rerun also passed 8/8 tests. Hardware-backed Keystore and production ARM execution
  are not established.
- **EMULATOR CAPTURE TESTED:** `tools/test_emulator.sh emulator-5580
  /home/dev/.cache/hellfire-ass/predevice/models` passed the complete suite.
  Denied SMS/microphone access stays unavailable. On the dedicated
  `hellfire_fixture_api35` AVD, temporary ADB grants exercised the real notification
  listener (ordinary text and OTP withholding), Telephony SMS provider/WorkManager
  import and replay deduplication, foreground AudioRecord capture with an ongoing
  notification and encrypted sample, and the real assistant service/session through
  local voice interaction. Cleanup revokes grants and restores the previous assistant.
  This establishes stock-emulator behavior, not user/OEM permission grant behavior,
  real microphone acoustics or handset gestures. Recording entry points now reject
  missing grants, blocked notices/channels and missing monitoring consent/exclusions
  before requesting a foreground service; three host regression tests cover these
  gates. The real assistant window also retains `FLAG_SECURE`.
- **HOST ARTIFACT INSPECTION:** x86_64 test APK contains exactly that ABI and 11
  actual JNI libraries. The final signed ARM64 release contains only ARM64 and 12 actual
  JNI libraries (including bundled barcode decoding). `python3 tools/check_native_apk.py APK --abi ABI` passed ELF
  load-segment and uncompressed APK 16 KiB alignment for both. MediaPipe is
  correctly absent from x86_64 and explicitly unsupported there.

Implemented software paths: optional imported Silero learned VAD during visible
authorized recordings, with adaptive fallback; optional real news embeddings using
the existing semantic runtime and stored model identities; bundled offline QR
extraction with authentication/Wi-Fi/token-bearing payloads excluded from search. No private corpus,
model weights, credentials or owner recording is committed. CI now contains the
isolated emulator/model/browser gates; no hosted CI success is claimed from local runs.

### Final build and retained evidence — 2026-11-18

**PASS (host build/inspection):** the final APK matrix built from source revision
`536f75df2f4a888c804426e75fc5be30e9e014ad`. Production debug/release remain
ARM64-only. All ten retained APKs
verify their signatures and pass `zipalign -c -P 16 4`; all six app variants pass
ELF machine/16 KiB segment and ZIP alignment inspection. Emulator contains 11 real
x86_64 libraries; ARM64 app variants contain 12. The local release uses the existing
owner signing configuration, which remains ignored/private. CI without that key
produces an unsigned release and must not claim owner signing.

| Final artifact | Bytes | SHA-256 |
|---|---:|---|
| `HellFire-Ass-0.9.0-code9-debug.apk` | 170,855,248 | `8aa7ec5dd4be0f628cc28d8fa96c8ce536a55bd270d7e982386626f8ba0248ba` |
| `HellFire-Ass-0.9.0-code9-release.apk` | 114,932,703 | `dd6847033e08e85249980e0e24af9ea9d0105a220ec7bd230e174f1eaab7b773` |

The retained local bundle is
`/home/dev/.cache/hellfire-ass/predevice/releases/536f75d/`.
Prepared names follow the owner’s versioned-artifact requirement. Instrumentation
companions use the actual app-under-test version; their own empty/zero metadata
version is recorded separately, without changing APK bytes.
Its `manifest.json` records all ten APK hashes, source/toolchain versions, signatures,
ABI counts and test evidence. It also contains raw test/build logs, JVM XML/HTML,
emulator/model/browser reports, release R8 mappings and the native symbol ZIP.
The ZIP is 4,589,707 bytes and includes the owned bridge's full
`libhellfire_whisper.so.dbg` (20,026,744 bytes uncompressed); prestripped third-party
libraries do not acquire missing debug information. Keys, credentials and downloaded
Whisper/Silero weights are excluded. Later documentation-only commits do not change
this bundle's source revision. Successful incremental builds were observed; no
byte-identical clean-rebuild comparison or physical crash-symbolication test ran.

Exact final build commands, from the repository:

```sh
export ANDROID_HOME=/home/dev/android-sdk
cd android
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleBenchmark :benchmark:assembleBenchmark :app:assembleProfile :benchmark:assembleProfile :app:assembleEmulator :app:assembleEmulatorAndroidTest :app:assembleDeviceTest --offline --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
./gradlew -PintegrationBuildType=deviceTest :app:assembleDeviceTestAndroidTest --offline --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
cd ..
python3 tools/check_native_apk.py android/app/build/outputs/apk/release/app-release.apk --abi arm64-v8a --output android/build/reports/predevice/release-native.json
python3 tools/check_native_apk.py android/app/build/outputs/apk/emulator/app-emulator.apk --abi x86_64 --output android/build/reports/predevice/emulator-native.json
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose android/app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/zipalign -c -P 16 4 android/app/build/outputs/apk/release/app-release.apk
```

The matrix completed in **35m 24s**; the separate ARM64 instrumentation compile
completed in **47s**. Final debug lint: **0 errors, 384 warnings, 11 hints**.
Warnings remain visible in the retained report; this is not a warning-free claim. The long-lived 1.5 GiB Gradle
daemon reported heap pressure during release shrinking, completed it successfully
and retired after the build. This is a workstation/build observation, not an Android
memory benchmark.

Final regression commands/outcomes:

- `tools/test_mail_fixture.sh`: **521 JVM tests, 517 passed, four optional live AI
  skips, zero failures/errors**. The TLS mail test is included, not an extra pass.
- `tools/test_emulator.sh emulator-5580 /home/dev/.cache/hellfire-ass/predevice/models`:
  **20 passed, zero skips/failures/errors**; capture uses temporary grants only on
  the named disposable AVD. Its cleanup revoked grants and restored the prior role.
- `PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH" tools/test_predevice_services.sh`:
  **23 relay, six WhatsApp worker, 30 API and seven real-browser checks passed**;
  frontend typecheck/build passed. All external accounts remain disconnected.
- `python3 -m unittest discover -s tools -p 'test_*.py'`: **six passed**;
  `python3 tools/generate_predevice_fixtures.py --check`: **PASS**.
- Cached `koalaman/shellcheck-alpine:stable` ran `shellcheck` over the seven fixture,
  emulator, mail, services, model and benchmark shell scripts: **PASS**. Workflow
  YAML parsed with two jobs; **hosted CI NOT TESTED**.

Only the owned fixture emulator was stopped; fixture containers were cleaned up.
No personal AVD/database was reset. **Physical-device and authorized external
live-service tests remain NOT TESTED.** Further software expansion is deferred by
the owner's scope update, not described as a hardware dependency.

### Current prioritized physical-only suite

This list supersedes historical hardware lists below. All fixture/host/emulator
work remains software work until actually completed; this list does not move it to
hardware to hide an unfinished task.

1. **Platform and security backing:** verify actual model/firmware/SoC/ABI/CPU
   features, install isolated ARM64 fixtures, run native integration, verify
   hardware-backed Keystore/StrongBox and recovery after real lock/reboot.
2. **CPU inference:** pinned real multilingual STT, learned VAD, speaker and
   embedding models; English/Kiswahili/noise fixtures, cancellation/context reuse,
   concurrent recording, actual PSS and sustained correctness.
3. **GPU/NPU:** one explicitly selected runtime/model/delegate at a time, actual
   inference/output comparison to CPU, supported-op/fallback evidence. A chipset
   description or library load is insufficient.
4. **Capture and permissions:** user-granted notification/SMS/mic/media access,
   protected fields, revocation during work, assistant role/gestures, visible
   recording notices, exact alarm and OEM power/background policies.
5. **Hardware media paths:** encoder/decoder identity, codec outputs/timestamps,
   playback/PiP/audio focus, sharing/redaction exports and real call-recording
   file access through authorized Android pickers.
6. **Sustained battery/thermals:** fixed 5/15/30-minute workloads, idle capture,
   screen-off jobs, thermal throttling, charging, low battery/storage, reboot and
   OEM process kills. Record repeatable initial conditions and all interruptions.
7. **Startup/UI/profile:** real cold/warm/hot and scroll traces, app-specific
   Baseline Profile collection/comparison, frame timing and memory after repeated
   model transitions using the fixed synthetic library.

Use `tools/oneplus_bringup.py --serial SERIAL --output PRIVATE_REPORT` for a
read-only survey; `--install-fixtures --run-tests` installs only the separate
ARM64 `.devicetest` package. The script never grants permissions/roles, changes
background exemptions, clears personal app data, or calls an external recipient.
Use the existing Macrobenchmark/Perfetto protocol below for actual timed runs.
Live Gmail eligibility, linked WhatsApp pairing, Drive and optional paid AI remain
explicit authorized external-service checks, separate from hardware results.

## Performance audit — 2026-11-18

Base: `9eae49f`, identical to `origin/main` after fetch. Clean checkout; work is
isolated in `.worktrees/performance-audit`. Repository-wide tracked-file inventory
and performance-path source review preceded implementation. Installed tooling:
JDK 21.0.13, SDK 36, NDK 28.2.13676358, CMake 3.22.1. `adb devices -l` is empty.
All phone performance, accuracy, energy and accelerator outcomes are **NOT TESTED**.

### Architecture and existing optimizations

| Area | Current implementation and limitations |
|---|---|
| Android/build | One Kotlin/Compose app module, min API 29, ARM64 only; AGP 8.13.2/Kotlin 2.2.21. Release R8/resource shrinking already enabled. No Macrobenchmark module or generated app Baseline Profile. Startup opens SQLCipher on IO and gates content on recovery; background recovery follows readiness. Existing startup counters are not cold/warm/hot launch measurements. |
| Native speech | Checksummed whisper.cpp 1.9.5, CPU only, no Vulkan/OpenMP/KleidiAI, context reuse through `HeavyModelGate`, cancellation callback, six-thread maximum. The owner-selected `armv8.2-a+dotprod+fp16+i8mm` target requires every reported core to pass a feature guard; generic ARM64 speech is already unsupported. Preserve that decision; no chipset/SVE/GPU/NPU claim. |
| Other inference | LiteRT-LM 0.12.0 CPU by default; manual single-probe CPU/GPU/NPU selection and CPU fallback. MediaPipe text embedder defaults to CPU with mapped model and normalized float vectors. ONNX 1.31.1 speaker/adult sessions use the default CPU provider, with no explicit GPU/NPU delegate. ML Kit labels/OCR are bundled. No rebuilding of runtime internals is justified. |
| Model lifecycle | `HeavyModelGate`, `ModelSlots`, `OperatingProfile`, cooperative interactive waiter counts and owner-specific caches already exist. No second scheduler is needed. Model slot eviction handles transitions; OS memory-pressure callbacks do not currently release idle models. Adult classification opens a session per frame outside the shared model gate (opportunity requiring output/lifecycle validation). |
| Media/audio | Managed originals use authenticated encrypted 64 KiB chunks, platform decoders and bounded 30-second intelligence audio chunks; visible authorized microphone capture uses AAC and local VAD. Video intelligence persists six-frame checkpoints/scene boundaries, labels/OCR/chapters and transcript provenance. Legacy `VisualReader` scene/silence helpers exist, but current audio/video ownership routes to `ContentEngine`; do not mistake unused helpers for active optimization. |
| Jobs/capture | Bounded notification channel/batches, WorkManager durable recovery, Room processing claims/priorities, charging/battery/thermal constraints, 20-job media cap and periodic maintenance already exist. Capture and alarms do not depend on model inference. Intelligence jobs run sequentially; `maxConcurrent` is a batch count, not parallel native inference. |
| Database/search | SQLCipher/Room schema 19, FTS4, existing indexes and keyset paging; semantic source reads in pages of 500 with bounded top-k pruning. Some media search/filter paths materialize up to 100,000 IDs and run per-hit queries. No measured query plan justifies changing schema/encryption/backup formats yet. |
| News | Bounded feed ingestion, lexical English/Kiswahili features, persistent clusters/versions, backoff, ranking and saved briefings. Per-story/article reads and repeated feature JSON parsing are suspected hotspots. No news embedding runtime exists to accelerate. |
| UI/player | Compose navigation, lazy library grid/list, 60-row library pages, bounded thumbnail cache, Media3 playback and decoder-based scrubbing. Other report/recording screens use bounded windows in scroll containers. Jank/recomposition cannot be established from source. Existing diagnostics frame callback is not Macrobenchmark evidence. |
| Sync/backup/services | Bounded encrypted library transfer/chunks, incremental/change-based records, conflict recovery and verified passphrase archives already exist. Mirror, relay and WhatsApp capture are separate services. Their contracts are preserved; no unsolicited deployment, sending, gzip or database engine changes. |

### Prioritized findings and proposed changes (before implementation)

| Priority | Finding | Evidence and proposed improvement | Risk / measurement |
|---|---|---|---|
| P1 confirmed | JVM array held across Whisper inference | `GetFloatArrayElements` is released only after `whisper_full`. Copy once into a bounded native-owned buffer using `GetFloatArrayRegion`, release language chars before inference, check JNI failures. | Native copy may use more memory if ART previously pinned; same maximum 19.2 MB audio buffer. Test bridge errors/cancellation/timestamps; real inference/GC benefit unmeasured. |
| P1 confirmed | Retriever initialized per sampled frame | `ContentEngine.frame` creates, opens and releases a decoder source for each of six frames, plus duration metadata. Keep one retriever/source for each bounded video step; preserve timestamps, fallback and encryption. | Count opens in host tests; codec throughput/quality requires a phone. Ensure cleanup on exceptions/cancellation. |
| P1 confirmed | Repeated scheduler reads and full backlog hydration | `MediaWork.pump` queries active WorkManager rows, then queries unique work for each candidate and sorts all unfinished `MediaItem`s. Use one active-work snapshot plus a bounded ordered ID query. | Preserve unique-work KEEP, pump lock and cap. Test existing tagged/legacy work, ordering and large queues. No timing gain asserted yet. |
| P1 confirmed | No idle-model release on OS memory pressure | Owners cache runtimes but Application has no pressure callback. Release already-created caches under the existing gate when Android reports pressure; never close active inference concurrently or initialize unused owners. | Host lock/cancellation regression tests; native PSS/thermal behavior needs device verification. |
| P2 measurement | Missing reproducible device baselines | Add an isolated AndroidX Macrobenchmark test module for cold/warm/hot startup and representative UI journeys, plus a Baseline Profile collector and Perfetto trace markers for heavy stages. | Optimized non-debuggable benchmark target; no guessed/generated profile committed without running it. No phone results invented. |
| P2 suspected | Inference initialization, frame OCR/ONNX, semantic scans, UI/filter reads | Record stage load/inference/queue timings locally; use fixed workloads, p50/p95 and profiles before new delegates, vector SIMD, caching or indexes. | New native DSP/vector modules require measured ≥15–20% gain or substantial memory/energy savings. Defer until actual comparative evidence exists. |

The changes above retain Kotlin orchestration and existing AI runtimes. Quantized
models, ISA/thread tuning, ONNX/GPU/NPU delegates, memory-budget sizes and sustained
concurrency remain device experiments, not automatically enabled optimizations.

### Implemented changes and regression evidence

| Change | Result and practical limit |
|---|---|
| Native speech ownership | `GetFloatArrayRegion` copies to a native-owned buffer, freed before JSON formatting. No JNI array borrow or language chars span inference; null/bounds/allocation errors become Java exceptions. Existing abort callback, context reuse, text and timestamp conversion are preserved. No new C++ DSP/vector engine or compiler flags were added without comparative evidence. Native scratch is at most 19.2 MB; peak RAM may increase on ART implementations that previously pinned the array. |
| Video batches | One retriever/encrypted source per six-frame step, including duration metadata; closed before audio inference. Existing scaled extraction and full-frame fallback timestamps are preserved. Host shadow tests check six timestamps, one source open, fallback and release on cancellation/setup failure. These are resource-lifecycle checks, not codec results. ML Kit does not support cancellation of its Task: finish the current label call before recycling its bitmap, then honor cancellation before the next stage. |
| Queue/database reads | New requests carry their item tag. Pump reads active WorkManager rows once and queries only the oldest bounded IDs in Room. Legacy requests retain compatibility lookups until they drain. Existing mutex, unique-work policy and 20-job cap remain. A real host WorkManager/Room test covers a 1,000-item backlog, legacy requests, cap, ordering and resumption; no schema/migration/backup change. |
| Memory/model coordination | Android pressure callbacks and the existing hot/low-memory media path release only initialized model owners, under the same `HeavyModelGate` as active inference. Host test confirms cleanup waits for the gate and does not initialize unused models. Callback delivery/native RSS reclamation on modern Android/OEM firmware is NOT TESTED. No unmeasured budget size, new queue or speculative scheduler. |
| Cooperative background work | Intelligence worker rechecks device policy and interactive waiters between jobs; video checks cancellation/yield between frames and persists completed progress through the existing checkpoint. Android capture/permission/lifecycle restrictions are preserved. |
| Startup and diagnostics | `ReportDrawnWhen` reports the unlocked, storage-ready UI for future full-display startup measurement. Fixed-label synchronous `hf.*` Perfetto spans and bounded local load/inference samples cover language, speech, embedding, speaker load and frame extraction. Diagnostics show counts, PSS snapshot, jobs and nearest-rank p50/p95 on manual refresh. The self-posting per-frame diagnostics callback was removed; no continuous monitoring thread was added. |
| Benchmark infrastructure | Separate optimized `com.hellfire.ass.benchmark` target and test APK; ten-iteration cold/warm/hot startup, tab/library scrolling and offline news navigation, actual profile collectors and a profile-required comparison. APK/native-footprint and AndroidX JSON reporters, synthetic host SQL workload, native CheckJNI harness and CI compile checks are included. Device collection has NOT RUN; no generated app profile is committed. |

Automated checks so far: **501 Android tests, 497 passed, 4 opt-in live-provider
tests skipped, zero failures/errors** (baseline 495/491/4). The host JNI harness
passes with `-Xcheck:jni` against the production bridge and a synthetic Whisper API:
null/bounds errors, UTF-8/JSON escaping, timestamps, unchanged samples, concurrent
abort, reset and context reuse. This is not real Whisper inference. Three Python
report-format tests pass. No device, live account or real model was used for this
performance checkpoint.

| Final performance gate | Observed result |
|---|---|
| `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :benchmark:assembleBenchmark` | PASS, 501 tests / 4 skipped / zero failures; lint zero errors, 380 warnings, 11 hints |
| `:app:assembleRelease :app:assembleBenchmark :app:assembleProfile :benchmark:assembleBenchmark :benchmark:assembleProfile --offline --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m` | PASS, 217 tasks (23 executed, 194 up to date), after rebuilding current test configuration |
| `bash tools/test_native_jni.sh --sanitize` | PASS with real host JNI bridge, synthetic Whisper API, ASan/UBSan and CheckJNI; JVM leak detection is disabled, so this is not a process-leak result |
| Debug signature and 16 KiB ZIP alignment | PASS using SDK 35.0.0 `apksigner verify` and `zipalign -c -P 16 4` |
| Debug APK comparison | 164,173,237 → 164,222,389 bytes (+49,152); Whisper library 5,214,448 → 5,219,080 bytes (+4,632) |
| Unsigned release APK comparison | 108,676,676 → 108,676,676 bytes; Whisper library 2,737,464 → 2,739,168 bytes (+1,704), absorbed within APK alignment/layout |
| Synthetic host queue query | 10,000 full rows → 20 IDs, using the existing imported-time index. Raw p50/p95 and Python allocation evidence are in `docs/performance-results.json`; these are not Android/SQLCipher, battery or app-memory measurements |

Debug SHA-256: `1d2accab3e1a721c36aa3fe0bf04548467736ce94b89575df21059d110bec931`.
Unsigned release SHA-256: `8362e0db635e40f830d07d96ecf6a2164c7a89ff6fa1f62aa82abd12c948f718`.
The comparison APKs were built from this checkpoint's working changes on base
`9eae49f`; the base revision alone does not reproduce those modified sources.
The owner's primary checkout/signing material was not changed.

### Reproducible device measurement protocol (NOT RUN)

The `benchmark` build inherits release optimization and uses a debug signing key
with **separate application ID `com.hellfire.ass.benchmark`**. It does not replace
`com.hellfire.ass`. A separate `profile` variant disables R8/shrinking for collecting
unobfuscated method names, using the same disposable benchmark application ID and
signing key. Only these test variants are shell-profileable. The test APK is separate;
new AndroidX Macrobenchmark/runner/UI Automator dependencies are test tooling.
Macrobenchmark 1.4.1 is a verified stable version compatible with this Kotlin/AGP
setup; existing production inference dependencies are unchanged.

Use a physical ARM64 phone, cool and unlocked. Install with `:app:installBenchmark`,
enable airplane mode before first launch (prevent the existing auto model downloader),
complete setup in English, leave app lock off in this disposable benchmark copy,
and return Home. Do not configure accounts or permissions that ingest private data.
For list/scroll comparisons import the same synthetic library files and record
their hashes, counts and duration; an empty-library run measures empty UI only.
Preserve that dataset between revisions. For inference runs import the same
owner-selected compatible models manually; no weights are part of this repository.

From `android/`:

```sh
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark :app:assembleProfile :benchmark:assembleProfile
./gradlew :app:installBenchmark
# Before optimization and again after, with the same device/setup/workload:
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hellfire.ass.macrobenchmark.StartupBenchmark
# Generate real startup + navigation/library profiles (API 33+ or a rooted test device):
./gradlew :benchmark:connectedProfileAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hellfire.ass.macrobenchmark.BaselineProfileGenerator
```

AndroidX writes JSON, `.perfetto-trace` and generated profile text under the test
module's connected-test `build/outputs/` artifacts. Copy the **generated**
`*-baseline-prof.txt` rules (combine startup and navigation results, remove duplicate
lines) to `app/src/main/baseline-prof.txt`; copy the startup collector's generated
`*-startup-prof.txt` to `app/src/main/startup-prof.txt`. Rebuild/reinstall, repeat
`StartupBenchmark`, then run `ProfileStartupBenchmark` with the same `class` argument
above. The latter requires an installed profile and fails if it is missing.
Its Gradle configuration also requires generated app rules in the source file:
profiles bundled by dependency libraries alone are not evidence of app coverage.
No made-up class list is installed. Device-generated profiles are still absent.
Profile packaging/install is therefore **NOT TESTED** pending this collection.
Use the **profile** variant only for collection; verify names are unobfuscated in
the output. Reinstall **benchmark** for timing comparisons so R8 optimization is
enabled. Collection itself is not a release-performance result.

```sh
# From the repository root; paths are actual files copied from connected-test output:
python3 tools/performance_report.py macrobenchmark path/to/before-benchmarkData.json path/to/after-benchmarkData.json
python3 tools/performance_report.py apk android/app/build/outputs/apk/release/*.apk
adb shell getprop ro.build.fingerprint
adb shell getprop ro.product.cpu.abilist
adb shell cat /proc/cpuinfo
adb shell dumpsys meminfo com.hellfire.ass.benchmark
adb shell dumpsys thermalservice
adb shell dumpsys battery
adb shell dumpsys jobscheduler
# In one terminal; exercise the fixed workload during the 30-second capture:
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/hellfire.perfetto-trace < tools/performance.pbtxt
adb pull /data/misc/perfetto-traces/hellfire.perfetto-trace ./hellfire.perfetto-trace
```

Use Macrobenchmark JSON for ten cold/warm/hot launches and frame CPU/overrun samples.
Full-display timing uses the app's unlocked/storage-ready `ReportDrawnWhen` signal;
initial-display timing alone can include the storage-opening screen. API 34+ is
recommended for preserving the fixed dataset across compilation resets; on older
Android the benchmark library can reinstall the app and clear prepared state.
The report uses nearest-rank p50/p95 and distinguishes per-iteration values from
pooled frame samples; missing metrics remain null. Archive raw JSON/traces before
changing revisions. Trace synchronous `hf.*` stages, CPU scheduling/frequency,
GC and process memory in Perfetto; one `dumpsys meminfo` sample is not peak RSS.
Device kernel trace availability and GPU/NPU counters vary: verify advertised
Perfetto data sources and working runtime delegates on the actual phone. No GPU
or NPU utilization percentage can be inferred from a selected backend name.

| Workload | Repeated controlled measurement still required |
|---|---|
| Speech | Same English/Kiswahili audio and multilingual GGML/quantized model; verify exact segment text/timestamps before accepting speed. Record load time separately, per-chunk inference ms / audio ms (RTF), wall time, max PSS/RSS and thermals. Compare thread counts/backends only with compatible CPU features. |
| Video/images | Same managed encrypted and external files, same FAST/BALANCED/DEEP mode, all optional models/settings identical. Reset analysis checkpoints via existing retry/reprocess UI for each full run; compare frame/item throughput, OCR/scene/chapter output, decode errors and queue completion. Test cancellation/resume and revoked URI access. |
| Embedding/search | Fixed synthetic 1k/10k-source libraries, same model hash/dimensions, fixed literal/semantic queries. Separate embedding/load ms, FTS/semantic search p50/p95, query plans, result IDs/scores and maximum process memory. Host SQLite timings do not establish SQLCipher performance. |
| Language | Import same `.litertlm`; record separate cold load and warm inference on identical prompts, token budget 4096. Existing synchronous API does not expose true first-token timing or token counts: TTFT/tokens per second remain unmeasured, never estimate from text characters. Verify accelerator output/quality as well as time. Existing one-probe backend chooser is not a sustained benchmark. |
| Resource policy | Run indexing while asking questions, then charging/unplugged, saver, low memory and moderate/severe thermal states; confirm source capture continues and jobs resume. Compare serial/gated throughput and peak memory before increasing concurrency. No second queue/scheduler. |
| Battery/sustained load | Equal-duration overnight idle/capture/indexing runs with the same screen/radio/battery state and source rates; record battery/thermal traces, work completed and interruptions. Instant current is not energy per job; do not call a short burst sustained performance. |

Official guidance checked for these choices: [JNI region calls](https://developer.android.com/ndk/guides/jni-tips),
[scaled frame extraction](https://developer.android.com/reference/android/media/MediaMetadataRetriever),
[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview),
[Baseline Profile collection](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile),
[Benchmark 1.4.1 release](https://developer.android.com/jetpack/androidx/releases/benchmark#1.4.1).

## Sequential media review fixes — 2026-11-17

A subsequent code review found functional defects despite the earlier 428-test pass.
The integration checkpoint below records build evidence, not end-to-end feature acceptance.
The owner asked to finish the four interrupted fix branches one at a time.

| Area | Result | Current evidence / next action |
|---|---|---|
| Library/intelligence | PASS (host); device NOT TESTED | Fixed legacy job normalization/discovery, crash recovery/pause/checkpoints, filter-only and intersected search, capture dates, duplicate metadata preservation, user category priority and persistent descriptions. Added accessible search/settings/queue, folder removal, name/tag/date controls, similar-picture review, sampled chapters/summary and cache thumbnails. 441 tests: 437 passed, 4 opt-in live-provider tests skipped, zero failures/errors. `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --max-workers=1` with 1.5 GiB heap passed. Lint: 0 errors, 350 warnings, 8 hints. Native source hash checked against the pinned archive. Real inference/codec/OEM behavior remains NOT TESTED. |
| Players/sharing | PASS (host); device NOT TESTED | Completed live position/application-thread A–B, outgoing resume state, trusted controllers, PiP/track/queue/clip controls and shared legacy playback. MP4 metadata removal preserves offsets; readable samples/frame required. Rotated OCR and frame-sized covers corrected; edited timestamps and range-only facts used. Synthetic sanitized H.264 fixture decodes with host ffmpeg. Pixel tests use native graphics. 455 tests: 451 passed, 4 skipped, zero failures/errors. Android export/codec/PiP still NOT TESTED; combined APK/lint checks follow integration. |
| Drawer/backup/status | PASS (host); device NOT TESTED | Shared/imported/user-organized originals retained during inbox purge. Drawer AI is local-only and grounded in saved content with per-item conversations. Destination read-back and upload-then-commit added; UI supports base+incremental selection and selective restore. Synthetic host tests exercise full/partial/incremental/selective recovery with a SQLite staging factory; production SQLCipher/device/Drive remain NOT TESTED. Status/backup/settings routes connected; safe orphan-thumbnail cleanup and retention controls added. 474 tests: 470 passed, 4 skipped, zero failures/errors. |
| Sync/web | PASS (host); device NOT TESTED | Android/server/browser protocol 2 implemented with opaque IDs, key check, legacy in-place upgrades, parked dependencies, isolated failures, merged conflict choices and original repair. API: 30/30 passed against disposable `hellfire_test`; browser typecheck/webpack build passed. Android full suite: 490 total, 486 passed, 4 skipped, zero failures/errors. Final merged lint/APK checks follow integration. |
| CPU target | PASS (host guard); device NOT TESTED | Five CPU-feature tests pass, including mixed cores, missing scalar FP16 and unreadable cpuinfo. Native build and integrated checks follow. No speed, SVE2 or NPU claim. |
| Device | NOT TESTED | Owner now targets a OnePlus 16, described as Snapdragon 8 Elite Gen 6. Firmware, instruction-set features, accelerators, permissions, playback, exports and actual backup/sync remain unverified. No hardware capability is inferred from the name. |

Scene thumbnails are derived, disposable files in app-private no-backup cache; originals
remain encrypted. Search uses stored OCR/labels/transcripts, not an assertion that the
language model sees images. Analysis jobs run sequentially in bounded batches.
System-wide overlay and QR decoding remain unimplemented. Video export supports static covers; blur and moving-target tracking remain unimplemented.
All four review-fix branches have passed their host checks and are merged. CPU guard tests pass; final merged checks follow.

### OnePlus 16 verification checklist (still NOT TESTED)

The owner describes the handset as Snapdragon 8 Elite Extreme Gen 6 / SM8975;
these names are requirements context, not observed hardware facts. No physical
phone has been connected during this pass.

1. Inspect model, Android/API/build fingerprint, ABI, actual RAM and SoC using
   `adb shell getprop` and `adb shell cat /proc/cpuinfo` before choosing backends.
2. Check `asimddp`, `fphp`, `asimdhp` and `i8mm` on every reported CPU feature
   line. Whisper is compiled with `armv8.2-a+dotprod+fp16+i8mm`, at most six
   threads, and refuses loading when required features cannot be established.
   This changes the supported CPU target; it does not establish device speed.
3. Inspect SVE/SVE2 flags if reported; this build does not enable or assume SVE2.
   Test native library loading and English/Kiswahili transcription before relying
   on it. Compare timing, PSS, heat and battery on a fixed recording.
4. Measure language CPU and GPU first. Keep CPU as fallback until a successful
   probe; NPU enablement comes later, after driver/model compatibility and output
   checks. A product name or advertised TOPS does not establish a working delegate.
5. Verify assistant role/gesture, notification visibility/redaction, foreground
   microphone indicators, OEM background policy, actual call-recording folder,
   playback/PiP/seek/AB/track selection, sanitized exports and permission revocation.
6. Exercise encrypted sync with a second client: more than 40 items, Unicode tags,
   browser note/collection/bookmark edits, uploads, wrong-passphrase rejection,
   pauses, conflicts, reinstall/full pull, missing-original repair and tombstones.
7. Verify backup destination read-back, failed upload staying uncommitted, complete
   plus incremental restore, blocked-original report, and selective restore using
   a disposable empty installation before touching an owner database.


## News, media and performance integration — 2026-11-17

Room/backup schema **19** preserves the media-library schema 18 and adds news.
Pending news and seven media implementations were consolidated with adaptive
performance. Original source snapshots are preserved in Git history. After the source audit
and merges, all extra worktrees and merged branches were removed; only `main`
and the primary checkout remained at that historical checkpoint. New review-fix worktrees are now active.

- News: feeds/source settings, clustering/ranking, versioned stories, scheduled
  briefings, quiet-hour alerts, reader/TTS controls, saved/followed stories, Ask
  routing and web state synchronization. Publisher availability, licensed sources,
  X access and actual phone speech/delivery remain unverified. News embedding
  vectors are deferred; current clustering uses English/Kiswahili lexical matching.
- Media: external library references and managed originals, library scanning,
  playback/PiP/bookmarks, content analysis/search, drawer/inbox, sanitized sharing,
  encrypted optional library sync and backup primitives. Later review found unusable paths and missing selective-restore UI; see the current checkpoint above.
- The universal drawer is the in-app edge drawer plus the Android Share target,
  with durable inbox captures, context/follow-ups and reviewed action suggestions.
  A system-wide overlay remains unimplemented. Drag/drop and share permission
  behavior require device verification. Duplicate action types retain their
  highest-priority provider suggestion.
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
| Combined frontend typecheck / production build | PASS (host) | Pinned Node 24, `npm run typecheck` and `npm run build -- --webpack`; all 14 routes. Next emitted an existing security upgrade reminder; unchanged retry passed, no dependencies changed. No browser rendering claim |
| Final integrated Android tests | PASS (host) | `:app:testDebugUnitTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m --offline`: 428 total, 424 passed, 4 opt-in live-provider tests skipped, zero failures/errors |
| Android lint | PASS (host) | `:app:lintDebug` with the same worker/heap limits; 0 errors, 345 warnings, 8 hints. Android 10 compatibility guards and AndroidX Media3 opt-in contracts corrected without disabling checks |
| Debug APK | PASS | Built from merged `main`, 0.9.0/code 9, 163,583,413 bytes; v2 signature and `zipalign -c -P 16 4` pass. SHA-256 `1a44746303a6b66dd96b8533b2d67b6fe5ce1fe17dcd12efe4bcd0ed84940898` |
| Signed release APK | PASS | `:app:assembleDebug :app:assembleRelease --max-workers=1` with 1.5 GiB Gradle heap, offline. Release 0.9.0/code 9, 108,529,220 bytes; v2 signature and `zipalign -c -P 16 4` pass. SHA-256 `245e511fbd6b5e67a308d7dce4d5dfcfbceab4117ca6ca22417c55239c1885cc` |
| Local web services | PASS | Main API and default Turbopack frontend builds passed. Local systemd API/frontend restarted and active; HTTPS login 200, signed-out News 307, unauthenticated library API 401. No signed-in browser/rendering test |
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
| LiteRT CPU/GPU/NPU speed on the OnePlus 16 | — | NOT TESTED | Build can construct `Backend.CPU`, `Backend.GPU` and `Backend.NPU`. Selection stays CPU until Settings → This device → Performance runs the probe. A failed accelerator falls back to CPU | Run the probe on the OnePlus 16 with the imported Gemma file. Record ms, PSS and whether GPU/NPU initialize |
| whisper.cpp, ONNX, MediaPipe accelerators | Code review | NOT SWITCHED | Sessions may be reused and dropped under low memory. Execution provider is still CPU. Switching ONNX/whisper/MediaPipe delegates could change speaker and embedding results | Do not enable NNAPI/GPU delegates until a device A/B test shows the same outputs |
| Scene-aware video OCR and silence skip | `PerformanceTest` plus `VisualReader` / `MediaWork` | PASS (policy) / NOT TESTED (codecs) | Near-duplicate frames are not OCR'd. Uniform timestamps are unchanged when frames differ or fingerprints are missing. Near-silence chunks do not load whisper | Play a real recording and a static video on the OnePlus 16. Confirm speech is not dropped and sampled OCR still says it is a sample |
| Encrypted search | Existing FTS `LIMIT` and semantic pages of 500 | UNCHANGED + timed | Search SQL and encryption are unchanged. `QueryTrace` stores the label, duration and row count only | Time a large owner database on the phone. Add an index only if a named query is slow |
| Compose | Settings counts; media list pages of 30 | PASS (code) / NOT TESTED (frames) | Settings no longer loads every memory and recording to show a count. The recordings list composes 30 rows at a time inside the existing scroll page | Macrobenchmark frame times on the OnePlus 16 |
| Baseline / startup profile | `StartupProfile.critical` | NOT INSTALLED | A guessed profile was not put in the release APK | Generate a baseline profile on the OnePlus 16 and compare startup before installing it |
| Web sync compression | `PayloadCompression` | NOT ENABLED | Change-based batches and retry already exist. Gzip is smaller in the host test but the server does not advertise it | Add server gzip support before sending `Content-Encoding: gzip` |
| Android 16 / OEM firmware quotas | `JobBudget` cap of 20 media jobs; existing WorkManager constraints | NOT TESTED (device) | Backlog stays in Room and is pumped by the existing 15-minute maintenance worker. Stop reason is recorded as a number if a job is cancelled | On the OnePlus 16, queue more than 20 analyses, unplug, heat the phone, and confirm jobs resume without a second queue |
| Battery, thermal, power | — | NOT TESTED | Dashboard can show thermal, battery percent and `BATTERY_PROPERTY_CURRENT_NOW` when the kernel exposes it | Overnight before/after on the same workload. Do not treat host tests as power results |

### OnePlus 16 verification matrix

Do this on the physical phone after installing a debug build. Do not treat a host
PASS as a device PASS.

1. Language model: Performance → Measure language backends. Record CPU/GPU/NPU ok-or-fail and milliseconds. Confirm a failed NPU still answers on CPU.
2. Leave the dashboard open and scroll Home, Money and Recordings. Record frame samples. This is not a Macrobenchmark.
3. Import one speech recording and one near-static video. Confirm silence is skipped, speech is transcribed, and video OCR stays a sample of at most 20 frames.
4. Start indexing, then ask a local question. Indexing should stop at the next batch so the question can run, then continue.
5. Queue more than 20 media analyses. Confirm only a bounded set is scheduled and the rest stay queued until maintenance or a finished job.
6. Force thermal severe or low battery. Confirm analysis waits instead of failing, and capture still ingests.
7. Read `BATTERY_PROPERTY_CURRENT_NOW` if present. Compare an overnight idle with and without HellFire. OEM firmware may defer WorkManager; that is not a host result.
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
software changes only; physical OnePlus 16 tests remain the final stage.

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
| Contacts permission prompt, denial and later grant? | Owner opens picker → explanation → Allow; deny, type name, then App settings → Permissions → Contacts | **NOT TESTED (phone)** | No Android/OEM firmware permission dialog was displayed; no permission claimed granted | Verify prompt timing, denial fallback and Settings state after resume |
| Real Google-synced contacts on OEM firmware? | Search name/number/email in each requested screen | **NOT TESTED (phone)** | Only SQLite-backed synthetic ContactsContract provider exercised | Check sync visibility, search latency and each field's saved result |
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
| Morning brief notification | `DailyOutputsTest` (timing only) | PARTIAL | Next time is today at the chosen hour if still morning and not shown, else tomorrow. It shares the reminder alarm; delivery was not exercised | Check 07:00 delivery on OEM firmware with Doze and battery limits |
| Waiting-on correct? | `DailyOutputsTest` | PASS (synthetic) / NOT TESTED (real traffic) | Latest message per person across channels; a reply on another channel settles it; groups, short codes, sender names and automated mailboxes ignored; cleared rows return on a newer message | Measure false positives on the owner's real messages; tune the 4 h / 24 h limits |
| Numbers and addresses joined? | `DailyOutputsTest` | PASS (host) | `+255712345678`, `0712 345 678` and `255712345678@c.us` are one handle (last nine digits); a shared name joins a number and an email | Check real sender formats, especially WhatsApp `@lid` ids, which stay separate until named |
| People page linked to calls? | Code review | PARTIAL | Recordings match by file name (name or number) only; there is no caller-ID link | Check what the OnePlus 16 names its call recordings |
| Debts net correctly? | `DailyOutputsTest` | PASS (host) | Balance per person, case-insensitive; settled people hidden; overdue from the agreed date; repayment suggestions need the whole name in an approved payment's source text | Try with real M-Pesa names, which are often upper-case full names |
| Debt reminder sending | Compile | NOT TESTED | Share sheet intent, or an email draft that needs Inbox approval; nothing is sent by the app | Share to WhatsApp and SMS on the phone |
| Recurring bills found? | `DailyOutputsTest` | PASS (synthetic) | Three consecutive months, at most 1.5 payments a month, still current; stopped, gapped, daily, generic and reversed payments are ignored; 30-day shortfall computed | Run on the owner's ledger; descriptions must repeat exactly for a match |
| Expiry dates read? | `DailyOutputsTest` | PASS (typed text) / NOT TESTED (real OCR) | Passport `12 MAR /MARS 31`, `30/06/2027`, ISO, `02/2027`, `December 1, 2027`; past and non-expiry dates ignored; one proposal per date; the document number is not copied | Scan a real passport, licence and insurance cover note; record misses |
| Capture gaps detected? | `DailyOutputsTest` | PASS (host rules) | Revoked access, 12 h without a notification, an SMS on the phone unsaved after 30 min, 6 h without a sync; a new install and a just-arrived SMS are not gaps | — |
| Capture alert and listener rebind | Compile | NOT TESTED | 15-minute maintenance worker posts one notification per problem set per day and calls `requestRebind`; skipped until first-run setup is done | Force-stop or battery-restrict the app on OEM firmware; confirm the alert appears and whether rebind restores capture |
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
| Does the file load on the OnePlus 16? | — | NOT TESTED | After download the worker opens the file on CPU before installing it; a failure leaves any previous model in place and reports it | Install 0.6.0 on Wi-Fi, wait for the download, then run a local question on CPU and GPU; record time, memory and heat |
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
| Phone sends only changes and deletions? | `WebSyncTest` | PASS (host) | Second pass with no changes sends nothing; a closed task and a forgotten memory send one upsert and one delete | Watch the first full sync and a later pass on the OnePlus 16 with real volumes |
| Security codes stay on the phone? | `WebSyncTest` | PASS (host) | Sensitive events and the messages and tasks captured from them are not sent; the code text appears nowhere in the fake server | Inspect the server copy after a real OTP arrives |
| Wiped or new server repaired? | `WebSyncTest`, API tests | PASS (host) | A changed dataset id makes the phone reset the server and send a full copy; batches for an old dataset get 409 | — |
| Large kinds bounded? | `WebSyncTest`, API tests | PASS (host) | 650 2 KB rows go in batches of ≤300 rows / ~1 MB; the API accepts 500 rows of ~1.5 MB and rejects 501 | Time a full sync over mobile data |
| Access control | API tests | PASS (host) | Sync routes need `SYNC_TOKEN`, read routes an owner session; expired or forged sessions rejected; repeated wrong passwords get 429 | Verify through the real reverse proxy and HTTPS |
| Local deployment | `curl --cacert /etc/nginx/ssl/hellfire.local.crt` against https://hellfire.local | PASS (this PC) | nginx 1.30.6 → website 127.0.0.1:3910 → API 127.0.0.1:3911 → local PostgreSQL `hellfire`. HTTP redirects to HTTPS; signed-out pages redirect to `/login`; `/api/overview` 404; `/api/sync/state` 401 without the token, 200 with it | Sign in once in a browser |
| Phone reaches hellfire.local? | Manifest review | BLOCKED | The name is only in this PC's `/etc/hosts` (no mDNS), and the app has no network security config, so it trusts system CAs only and rejects the self-signed certificate | Pick an option: a LAN DNS record plus a debug-build user-CA override, or a real domain with a public certificate |
| Sync over a real network | Compile only | NOT TESTED | HTTPS-only client, 15-minute WorkManager job (network connected, battery not low), status in Settings | Install the units, add the HTTPS proxy, connect the OnePlus 16, check OEM firmware background limits |
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
| AAC recording recovered after process death? | `ReviewFixesTest` | PASS (host) | Recovery accepts the AAC and PCM containers and registers the right type; empty orphans are removed; recovery is skipped while the microphone is busy. Start time is the file creation time where the filesystem reports it | Kill the process mid-recording on the OnePlus 16; check the recovered file plays |
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
| Call-folder scan finds new files? | Compile; code review | PARTIAL | Every folder entry is read; up to 25 new audio files are imported per scan, newest first | Test with the OnePlus 16 call-recording folder |
| Drive storage bounded? | Compile; code review | PARTIAL | After a confirmed upload the app deletes its own older archives beyond the newest 3/7/14 (default 7), or keeps all | Verify on a live Drive account |
| Device problems diagnosable? | `ReviewFixesTest` | PASS (host) | Debuggable builds log exception class and stack frames (tag `HellFire`), never messages, from the previously silent handlers | Collect with `adb logcat -s HellFire` during the OnePlus 16 pass |
| Other apps steering the UI? | Compile; manifest review | PARTIAL | `MainActivity` is no longer exported; the launcher, assist gesture and assistant settings use the exported `.Launcher` alias, whose data is ignored. Widgets and reminders address `MainActivity` directly. Launchers may need the icon re-added after the update | Check launcher icon, assist gesture and widget links on OEM firmware |

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
| Does each new SMS trigger a bounded import? | Code review | **DEFECT (cost)** | Every trigger (incoming SMS, provider change, app resume, the 15-minute maintenance job) starts `SmsSyncWorker` at row 0 and pages through the whole SMS store, normalizing and hashing each row before the duplicate check. No high-water mark is saved | Save the last imported row ID and rescan only a short look-back window (for outbox to sent changes); measure on the OnePlus 16's real SMS count |
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
| Can device problems be diagnosed? | Code review | **GAP** | The app writes no log lines at all; about 95 `catch` blocks turn the exception into a generic status string | Add debug-build-only logging of exception class and stack trace, without message content, before the OnePlus 16 test pass |
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
phases and explicitly kept physical OnePlus 16 testing until last. Resource-use validation
used host tests only; the separate UI checkpoint below records earlier emulator
evidence. No live account, personal message corpus, recording or model weights were
used for the resource-use changes.
The existing optional cloud providers/settings were merged from main; their live
tests remain opt-in and unconfigured.
No device-dependent phase is fully accepted solely because its host checks pass.

## Fingerprint and Google Drive checkpoint — 2026-11-14

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Fingerprint login | Enroll fingerprint and device PIN; enable app lock; cancel/wrong/correct fingerprint; use PIN fallback; background/recreate/restart; disable lock after authentication | NOT TESTED (physical) | Platform `BiometricPrompt`; API 30+ strong biometric or device credential, API 29 platform credential fallback; no fingerprint data stored. Only `emulator-5554` was listed by read-only `adb devices -l`; it was not used for authentication testing | Run on the physical OnePlus 16; verify OEM firmware sensor/lockout/PIN behavior and revoking enrollment |
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
| Lower battery, CPU and peak RAM on the OnePlus 16? | Compare identical controlled workloads before/after | NOT TESTED | No physical profiling performed | Record baseline and optimized overnight battery, CPU time, PSS/peak memory and index latency; verify unplug/replug, severe thermal status and capture during indexing |

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
indexing; battery and peak-memory improvements are still NOT TESTED on the OnePlus 16.

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
(Pixel 6 profile, API 34). **No physical OnePlus 16/OEM device was connected; nothing here is a device result.**

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Does the app build and pass the host suite? | `./gradlew assembleDebug :app:testDebugUnitTest :app:lintDebug` | PASS (host) | 188 tests, 184 passed, 4 opt-in live AI tests skipped, 0 failures; lint 0 errors (warnings only) | — |
| Do Room migrations 9→10→11 keep data with no destructive fallback? | `SchemaV10Test`, `WidgetDataTest`, `DatabaseMigrationTest`; emulator upgrade over existing encrypted v9 data | PASS (host + emulator) | Rows kept; completion times backfilled from owner done-audits; promise direction backfilled for message-extracted tasks; SQLCipher DB opened and migrated in place on the emulator | Install over the owner's real database on the OnePlus 16 before relying on it |
| Do older encrypted backups still restore? | `SchemaV10Test`, `WidgetDataTest` | PASS (host) | Schema 8 and 10 rows restore into v11 with defaults; unknown or missing core columns still refused | Run a real export → restore on a spare install |
| Do confirm-before-act rules still hold? | Emulator walkthrough | PASS (emulator) | SMS candidates post only after the review sheet's Confirm; drafts send only after "Approve and send" confirmation; task proposals need Add task; balances need Confirm balance; widgets only open the app (promise checkbox marks done, as specified) | — |
| Do reports compute from confirmed entries only, and export? | `ReportDataTest`, `BalanceAndCalendarTest`, emulator | PASS (host + emulator) | Totals, groups, account reconciliation, running balance, calendar totals; PDF exported through the file picker and read back | Compare report totals with real statements |
| Do home screen widgets render, refresh and protect privacy? | Emulator (Pixel launcher) | PARTIAL (emulator) | 13 widgets listed with previews; pinned and rendered; amounts masked by default; a new SMS refreshed Wallets and Nightly review without a timer; widget record → consent → visible notification → stop → "Saved, transcript queued" | OEM firmware launcher, resize to large, lock-screen/after-reboot behaviour |
| Widget recording on OnePlus 16/OEM | — | NOT TESTED | No device | See REDESIGN_SUMMARY.md checklist |
| Assistant gesture opens Ask | — | NOT TESTED | Intent routing implemented (`ACTION_ASSIST` → Ask) | Test the OEM firmware gesture and power-button assistant |
| Background capture overnight with the new UI | — | NOT TESTED | Unchanged services; Setup row shows battery exemption and OEM firmware caveat | Overnight test with battery optimisation off and auto-launch on |

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
| Do picker controls behave on OEM firmware? | Select and cancel date/time; change category/account; rotate, reopen review, confirm multi-item cash entry; add an account without a provider | NOT TESTED | No phone test performed for this change | Verify timezone, retained selections and balances on the OnePlus 16 |

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
adb shell getprop ro.build.fingerprint
adb shell getprop ro.soc.model
adb shell cat /proc/cpuinfo
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
   addressed, time and memory per minute. Confirm manual labels are never replaced. Import/replay accessible phone recordings
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

## TheftGuard — owner-authorized slice, 2026-11-25

This request supersedes the earlier feature-expansion deferral **for TheftGuard
only**. Guard-specific pre-armed consent and unattended uploads/alerts are recorded
in `DECISIONS.md`. Normal microphone and outbound approval contracts remain in
place. No real credentials, recordings or phone data were used. `adb devices -l`
returned an empty device list; no physical/emulator capture result is implied.

| Piece | Result | Evidence / limitation | Next action |
|---|---|---|---|
| Debug build, Kotlin logic and Room v1→20 migration | PASS (host) | Debug APK assembled; 523 unit tests passed, 5 skipped, 0 failed; JDK 21.0.13 and SDK 36; Room migrations checked | Physical runtime remains separate |
| Arm/active/disarm logic; stale acknowledgments; fresh session identities | PASS (host) | `GuardTest`: 7 passed, 0 failed; handlers always call fresh strong-biometric prompt | Verify physical prompt last |
| Permissions and separate guard process | PARTIAL | Manifest declares camera/location FGS and grants individually; `:guard` avoids main app job startup; Room multi-instance invalidation enabled | Verify OEM lifetime and permission revocation |
| Encrypted evidence, normalized provenance, offline queue, 50 MiB local budget | PARTIAL | Sensor bytes stay in memory then PayloadCipher/SQLCipher; guard provenance is not searchable or enqueued for general inference; existing SecretStore holds token/transport key | Verify actual camera/fix and offline replay on phone |
| Backup schema 20 and older schemas | PASS (host) | Guard evidence re-key/consent revocation and legacy migrations pass; schema-19 allowlist preserved; unknown-column rejection retained | Native replacement-device round trip remains NOT TESTED |
| Relay evidence + heartbeat routes and dead-man | PASS (host, synthetic) | 40 passed, 0 failed (18 new guard cases); encryption, auth, rate limits, idempotency, sequence/clock/session rejection, deadlines/disarm and route-to-SMTP message covered | Owner-configured service/device integration last |
| Durable SMTP queue / owner email adapter | PARTIAL | Synthetic TLS SMTP adapter and failure/retry tests; no actual SMTP login, external send or inbox delivery | Owner-configured SMTP, then one controlled delivery test |
| CameraX front then back; no concurrent-camera assumption | NOT TESTED (OnePlus 16) | Real adapter compiled/tested as software only; missing facing/provider/timeouts produce unavailable status | Test both cameras, contention, privacy toggle, lock screen and cancellation |
| Fused precise/background location | NOT TESTED (OnePlus 16) | Real Play services adapter, bounded wait and fix-age validation; no invented coordinates | Grant/revoke precise/all-time access; test Play services absent and no fix |
| Wrong unlock, SIM and connectivity triggers | NOT TESTED (OnePlus 16) | Watch-login Device Admin callback, three-failure threshold, subscription-ID fingerprint, validated-network loss; pure trigger cases covered on host | Verify actual PIN/password callbacks and dual-SIM/radio transitions |
| Biometric arm/disarm | NOT TESTED (OnePlus 16) | Fresh strong biometric even while ordinary app lock disabled/unlocked; cancellation/backgrounding do not authorize transitions | Cancel, fail, rotate/background and disarm while inside app |
| 60-second heartbeat / partial wake locks / Doze | NOT TESTED (OnePlus 16) | Independent bounded heartbeat path so camera/upload latency does not block it; best effort, no delivery guarantee | Screen-off/Doze/low-battery/termination measurements |
| Boot re-arm | PARTIAL software; NOT TESTED (OnePlus 16) | Dedicated BOOT_COMPLETED receiver preserves armed state after first unlock; background-location grant allows location-only FGS; camera waits for tap/biometric | Test granted/denied/revoked access and blocked notifications |
| Device Owner uninstall and safe-boot policy | NOT TESTED (OnePlus 16) | Runtime owner checks and previous-policy snapshots/restoration; stock installs never claim owner privileges | Provision only an approved empty test device; verify and restore policy |
| Device Owner power menu | PARTIAL scaffold; NOT TESTED (OnePlus 16) | Global actions policy is scoped to lock-task mode; no kiosk entry in this slice | Separate explicit kiosk decision before testing power-menu suppression |
| OEM power-off-PIN interplay | NOT TESTED (OnePlus 16) | No OEM firmware/device evidence | Record OEM Settings behavior without claiming interception |
| Hardware power-off / dead battery prevention | BLOCKED / OUT OF SCOPE | Not controllable from userspace; no KEYCODE_POWER interception or ACTION_SHUTDOWN veto | Verify server missing-heartbeat alert instead |
| Hidden camera/location indicators, hidden foreground notification, root | BLOCKED / OUT OF SCOPE | Stock Android indicators stay; guard low-importance notification remains; Tier 3 not implemented | None for this slice |

Host verification (from the task worktree, same committed source):

- `cd server && .venv/bin/python -m pytest -q`: **40 passed, 0 failed**, including
  18 new guard cases. One existing FastAPI/Starlette httpx deprecation warning;
  no dependency upgrade was made to silence it.
- `cd android && ./gradlew --offline --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`:
  unit reports **528 total, 523 passed, 5 skipped, 0 failures/errors**, including
  **7/7 guard tests**; debug assembly and lint **PASS** (0 errors, 391 warnings, 11 hints). The five
  skipped existing tests are four live AI providers and the separately configured
  TLS mail fixture, not guard cases. No live credentials were supplied.
- Initial lint found an API-33-only stream call and a Compose activity-context
  cast; both were corrected. The final offline run keeps source lint enabled
  while avoiding external Maven version lookups; final Gradle result: **BUILD SUCCESSFUL**. Existing unrelated compiler/
  dependency/UI warnings were not fixed as part of this slice. Guard lint warnings
  concern the explicitly requested battery-exemption prompt and KTX-style suggestions;
  no check or approval/permission gate was disabled.
- `git diff --check`: passed. APK ZIP inspection: **ARM64 only**. SDK 36
  `apksigner verify --verbose`: **PASS**, v2 signature, one debug signer.
- Versioned artifact: `build/theftguard/HellFire-Ass-0.9.0-code9-debug.apk`
  (173,423,805 bytes), metadata **versionName 0.9.0 / versionCode 9 / debug**.
  SHA-256: `9e4b570a4e8656c5b820e5fdeed6f6d0031bb547aebfa02072591287afaba32f`.

No relay was deployed and no external email was sent. Sensor, biometric, boot,
Device Owner, OEM power-PIN, and live SMTP/inbox outcomes remain NOT TESTED.

OnePlus 16 hand-test checklist (kept for last):

1. Record actual model, Android/OxygenOS build and API/ABI. Install the versioned
   ARM64 debug APK without replacing owner data unexpectedly. Confirm the guard is
   initially disarmed and no camera/location permission is represented as granted.
2. Configure the owner relay and server-only TLS SMTP using private configuration.
   Grant notification, precise location and camera access deliberately. Cancel the
   biometric prompt and confirm no arming; complete a fresh biometric and verify
   the persistent guard notification plus system access indicators.
3. Wait for an acknowledged heartbeat, run the manual trigger, verify a real fix
   plus sequential front/back photos (or explicit unavailable reasons), encrypted
   local evidence, relay acknowledgment, and readable owner email with actual JPEG
   attachments. Inspect neither real evidence nor credentials into Git or logs.
4. Deny/revoke camera, precise location, notifications and phone permissions in
   separate runs. Test an unavailable camera/another app holding it and no location
   fix. Confirm honest errors, no invented photos/fixes and continuing heartbeats
   when OS allows them.
5. While armed, enable Device Admin and make three wrong PIN/password attempts;
   exercise SIM removal/replacement and validated-network loss. Confirm the first
   observed trigger activates capture. Check dual-SIM/radio-startup false positives.
6. Go offline after a successful heartbeat. Verify one missing-heartbeat email per
   outage after the configured timeout (default three minutes, monitor jitter).
   Reconnect and verify server-active escalation plus queued evidence upload.
   Test server restart during the outage and SMTP outage/retry without losing state.
7. Disarm from inside the already unlocked app: cancellation/failure must leave it
   armed; success must stop local service/capture immediately. While offline,
   confirm remote cancellation stays visibly pending and another arm is blocked
   until acknowledgment; a timeout email during that interval is expected.
8. Reboot both with and without background location. Before first unlock, promise
   no credential-protected recovery. After unlock, verify location-only service or
   tap-to-resume notification, then biometric camera resume. Ordinary microphone
   recording must not restart. Force-stop/Doze/OEM termination cannot be defeated;
   verify the server alarm rather than assume survival.
9. Only on an approved provisioned test device, verify `isDeviceOwnerApp`, uninstall
   blocking/safe-boot policies while armed and prior-policy restoration on disarm.
   This slice does not enter lock-task mode; do not claim ordinary power-menu
   suppression. Record OEM power-off-PIN behavior separately. Never try to veto
   hardware shutdown; power off and verify the relay's missing-heartbeat email.
10. Export/restore an encrypted backup to an empty test installation. Verify guard
    evidence can be decrypted with the replacement key, the state stays disarmed,
    and the relay transport secret must be reconfigured deliberately.


## Priority 1 — device calendar integration, 2026-11-25

Calendar reads and writes use the local synced Android provider, with no Google
Calendar API or additional dependency. Reads are permission-gated, bounded to
32 days and 200 occurrences, and display incomplete/unavailable status honestly.
`CalendarContract.Instances` supplies recurring occurrences; canceled, declined
and hidden events are excluded. All-day dates use UTC date boundaries and local
EAT window membership; free/all-day entries do not generate timed overlap flags.
Touching appointment endpoints do not conflict.

Read snapshots first become normalized immutable `source_event` records, with
redacted searchable content and encrypted payloads. They are labeled calendar
snapshots instead of being re-extracted into duplicate commitments or payments.
Ask understands calendar/appointment/meeting/schedule queries, including upcoming
appointments; grounded local explanations receive bounded, source-cited upcoming
context. Morning/evening briefs show today's/tomorrow's appointments, upcoming
events and overlap warnings. Calendar context is not automatically sent to cloud AI.

Use **Settings → Permissions and capture → Device calendar** to request read/write
access; live status refreshes on resume. On an active, confirmed task, use
**Device calendar → Choose calendar…**, check the title, start/end and timezone,
and review the event. Enter start/end in EAT; the event timezone determines its
calendar display. The confirmation shows the selected account/calendar and warns
that the calendar's own sync adapter may upload changes. No attendee invitations
are created. A writable visible calendar must already exist on the phone.

Create, edit and cancellation each require a separate immutable proposal and
fresh five-minute owner approval, single-use claim and `action_audit` entries.
The app checks task version, destination and current event fingerprint again
before writing. Only linked HellFire one-off events can be edited/canceled;
recurrence or attendee changes outside HellFire require manual calendar review.
Task edits and completion never silently write/cancel a provider event: the owner
reviews the corresponding calendar action on the task. Reminders remain separate.
The provider description and custom-app link point back to the task/source; the
read-only exported calendar alias preserves ordinary app-lock enforcement and
accepts no commands or approval parameters.

Interrupted/uncertain results are checked read-only with **Check calendar result**;
no provider mutation is retried automatically. If the provider's current row is
neither the approved result nor the expected original, the action remains
unconfirmed and blocks more calendar writes for that task. Inspect it manually;
this deliberately does not guess whether an external calendar edit was ours.

Room **20 → 21** adds `calendar_link` and `calendar_change`, retaining task/source
foreign keys. Backup schema **21** includes both tables; schema **20** and every
previously accepted archive version retain their old table allowlists. Unknown
columns still fail closed. Restore preserves history but invalidates all provider
links and pending approvals, so old-device calendar IDs can never be mutated.
A replacement event requires explicit review, with a warning to check the synced
calendar for an existing event before creating another. Calendar grants/accounts
are not restored.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Calendar adapter and permissions | Synthetic local ContentProvider via production `PhoneCalendar` | PASS (host) | Permission denial/revocation, writable calendars, recurring-instance rows, canceled/declined/hidden exclusion, unspecified status, 200-row cap, provider writes, provenance and concurrent-edit refusal | Verify OEM provider/account behavior last |
| Approvals and recovery | Room + deterministic integration adapters | PASS (host) | Create/edit/cancel approvals, nonce expiry/replay, duplicate proposals, task/destination changes, process interruption and read-only reconciliation | Exercise actual provider interruption on phone |
| Calendar context and brief overlap logic | Synthetic source snapshots and brief inputs | PASS (host) | Encrypted/redacted provenance, snapshot dedupe/no re-extraction, EAT/all-day boundaries, half-open overlaps, free entries, morning/evening appointment sections | Inspect actual synced recurrence/timezone data |
| Schema migration and backup safety | Schema 20 fixture and current backup round trip | PASS (host) | Migration to 21, preserved source/history, restored consent/link invalidation, unknown-column refusal; existing older-backup regressions included | Native replacement-device restore remains separate |
| Read-only provenance entry | Restricted intent parser | PASS (host) | Task/source links accepted; commands, wrong aliases/actions and malformed IDs refused; query parameters stripped | Physical calendar app link and app-lock interaction NOT TESTED |
| Android permission prompts and actual local/synced provider read/write | OnePlus 16 | NOT TESTED | `adb devices -l`: no devices attached; no live accounts or credentials used | Grant/revoke access; create/edit/cancel synthetic event in chosen calendar; verify external sync and source link |
| Screen-off/OxygenOS calendar access and brief delivery | OnePlus 16 | NOT TESTED | No OEM/device execution claimed | Verify with Priority 2 survival work and physical tests last |

Host regression: **550 total, 545 passed, 5 skipped, 0 failures/errors**;
**22 new calendar tests passed** (14 `CalendarIntegrationTest`, 8 `PhoneCalendarTest`).
The five existing skips require live AI credentials or the separately configured
TLS mail fixture. The first full run exposed a SQLite affinity bug in a nullable
status comparison; the corrected query excludes canceled/declined rows while
retaining unspecified status. The final full run passes this regression.

Command from `android/`:

```sh
ANDROID_HOME=/home/dev/android-sdk ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
```

Final command: **BUILD SUCCESSFUL**. The strengthened schema-20 fixture restores
an existing commitment/source relationship and unchanged encrypted source bytes.
Both `git diff --check` and the exported-schema comparison pass: existing tables
are unchanged, with exactly the two calendar tables added.

- Lint: **0 errors, 371 warnings, 11 hints**. No lint gate was disabled.
- Debug APK: **ARM64 only**, **172,896,708 bytes**, `com.hellfire.ass`, actual metadata
  **versionName 0.9.0 / versionCode 9 / debug**.
- Versioned artifact: `build/calendar/HellFire-Ass-0.9.0-code9-debug.apk`.
- SHA-256: `2cbf771e7b3ce949f0de4406484f9c9b6fa8d716548d5971c66068db1f9c7c51`.
- SDK 36 `apksigner verify --verbose`: **PASS**, v2 signature, one debug signer.
  `zipalign -c -P 16 4`: **PASS**.
- Full JUnit XML reports, lint XML, APK metadata and the successful final Gradle log
  are retained beside the versioned artifact in `build/calendar/` (ignored build outputs).

 Priorities 2–4 remain queued; no survival, geofence or Telegram behavior is
claimed by this calendar slice.

References consulted: [Android Calendar Provider guide](https://developer.android.com/identity/providers/calendar-provider),
[Events contract](https://developer.android.com/reference/android/provider/CalendarContract.Events).

## History moved from the README (2026-11-26)

Moved from the README so it stays a setup and run guide; only link paths were adjusted.

### Everyday improvements and AI skills (0.9.0)

**Settings › AI skills** shows pending, active and disabled personal decision rules.
Corrections during payment review, rejecting false task/payment suggestions, and
saving/correcting source memories can propose skills. Proposals need approval;
choosing **Remember my account and category** is explicit approval for that default.
Existing encrypted review defaults migrate when next used.

- Add a guidance skill with a topic and instruction, or choose a real recent payment
  to set sender/merchant account/category suggestions. Edit, disable, re-enable or
  remove any skill. Removal also removes its usage log; original source records stay.
- Each skill shows why it was created, evidence, revision, status, use count and
  paged history linking to where it was used. Counts reflect guidance supplied to
  local Ask, defaults applied to review, or a repeated false candidate suppressed.
  They do **not** prove a language model followed an instruction or authorize actions.
- New advice about the same trigger is a separate pending update. Enabling it
  disables the previous active rule for that trigger. Past usage keeps its revision.
  Disabled rules are not automatically reactivated by repeated observations.
- Guidance matches all meaningful topic words. False-candidate rules are exact
  normalized-text rules scoped to their original app/sender/conversation/account.
  They suppress derived suggestions, never capture of the underlying source.
- Skill application records are accessible from history but excluded from full-text,
  semantic and assistant fallback retrieval, so old advice is not learned back after
  a skill is removed. Skills/usage are encrypted and included in portable backups;
  restored active skills become pending for review. They are not added to web sync.

**Settings › On-device models › Model catalog** offers compatible published formats,
without typing model names or URLs:

| Model | Purpose | Publisher/source | Download size |
|---|---|---|---|
| Gemma 4 E2B | Language, existing default | Hugging Face `litert-community` | 2,588 MB |
| Qwen3 0.6B | Smaller language model | Hugging Face `litert-community` | 614 MB |
| Gemma 4 E4B | Larger language model | Hugging Face `litert-community` | 3,660 MB |
| Whisper Base multilingual | Transcription | Hugging Face `ggerganov/whisper.cpp` | 148 MB |
| Whisper Small multilingual | Transcription | Hugging Face `ggerganov/whisper.cpp` | 488 MB |
| Universal Sentence Encoder | English semantic search | Google MediaPipe | 6 MB |

The picker shows source, license information, format notes, size, pinned revision
and SHA-256. Downloads wait for Wi-Fi, adequate battery and storage, retain partial
bytes for retry, verify integrity and load the runtime before replacement. Existing
models remain on failure. Gemma's existing daily update checks and explicit update
button remain. Advanced compatible-file imports remain available; there is no free-
text local model identifier or download URL. Speaker models retain their advanced
ONNX file picker because a generic model name cannot establish compatible inputs.
Actual accuracy, speed, memory use and Kiswahili performance need device evaluation;
the English text embedder is not advertised as verified Kiswahili semantic search.

Further daily-use changes:

- Guided setup starts from spending, conversations or commitments, preserves the
  chosen goal, allows finishing later, and distinguishes permissions from real
  delivery tests. Resume in Settings › Guided setup.
- Home/Settings › **Needs attention** combines capture, connection results, queued
  work, models, recordings, backup status and recovery links. Retry processes saved
  sources; it never retries uncertain sends.
- **Privacy and storage** distinguishes encrypted phone records, readable optional
  server copies, encrypted Drive archives and explicitly submitted cloud-AI text.
  Switching a destination off does not claim to erase previously exported copies.
- Memory has search, filters, older pages and earlier correction versions. Private
  drafts for memory, reconciliation, source notes, shares and skill editors are
  encrypted locally and excluded from backups. Capture-grant expiry may require
  sharing files again; a partial import resumes saved per-file checkpoints and
  content hashes prevent duplicate originals. Shared text/derived records commit
  atomically; a shared reminder remains a review proposal until approved.
- Ask accepts `compare spending on Fuel this month vs last month`,
  `why was transport spending higher this month`, and `budget for Fuel this month`.
  Differences and category contributors use integer ledger arithmetic; they do not
  invent causes. Budgets are monthly, category-based, across all accounts.
- English/Kiswahili labels now cover the new daily flows and common errors. Status
  banners announce changes; permission targets and bottom navigation better support
  touch and larger text. Advanced model descriptions/diagnostics remain English.
  Touch, TalkBack, voice and large-font rendering still require phone verification.

### Review improvements (0.8.0)

- Home › **Review** combines payment, task and draft review; **Record** opens recordings.
- Ask supports `spending on Fuel from M-Pesa this month`, `and last month?`,
  `spending at merchant "Cafe" last week`, and `spending for person "Asha" this month`
  (use your own account/category/person names). Unknown filters need clarification.
  Choose **Local model + saved sources** for read-only model explanations. **History**
  offers optional encrypted history and Clear.
- **Share → HellFire Ass** accepts text, screenshots, PDFs and audio/video through
  the existing app unlock gate. Review before saving a note, reminder or expense
  candidate. It never sends or posts a payment automatically.
- Inbox has Conversations / All items, saved-history search and older-page loading.
  Waiting on has per-request clearing and thread snooze. People can have identical
  names; Manage linked identities offers explicit merge/separation.
- Task editing has date/time pickers, recurrence, reminder intensity, per-task
  snooze and **Add to calendar** (you save or cancel in your calendar app).
- Money › **Check account balance** guides reconciliation before an adjustment.
  Review sheets can remember account/category suggestions after your approval.
- Settings › **Language** selects English/Kiswahili main controls and speech locale;
  advanced diagnostics and some dynamic explanations remain English. Captured
  messages, names and source excerpts remain unchanged.
- Backup shows staging-space estimates and **Verify an archive**. Streaming archives
  support more than the previous 7 GiB originals ceiling, subject to free space:
  500 GiB originals / 512 GiB archive; individual originals remain limited to 8 GiB.
  Verification checks authentication and original hashes without replacing data.

#### Release APK and automated checks

`cd android && ./gradlew :app:assembleRelease` builds a shrunk release APK. To sign,
put private `android/signing.properties` (mode 600, ignored) beside Gradle settings:
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` is relative to
`android/`. Never commit that file or the keystore. Without it, CI builds an unsigned
release for validation. A release key differs from the debug key: keep using the
debug APK for an in-place upgrade of a debug install; do not uninstall personal data
just to change signing identity. Android/Google authorization must be configured for
whichever signing certificate you use.

`.github/workflows/checks.yml` runs Android tests/lint/debug/release builds, relay and
worker tests, web API tests against disposable Postgres, and frontend typecheck/build.
It needs no personal messages, accounts, model weights or production secrets and
performs no deployment. A hosted successful run has not been claimed.

### Verification and change log

- 2026-11-25, Priority 1 calendar slice: CalendarContract reads feed appointments
  and overlap warnings into Ask and morning/evening briefs. Confirmed tasks can
  create/edit/cancel linked one-off events in an owner-chosen device calendar,
  through fresh approval and the existing audit log. Room/backup schema 21 keeps
  older archive allowlists and revokes provider links/approvals after restore.
  Calendar permission status is under Settings → Permissions and capture.
  Open a confirmed task → Device calendar to review changes; task edits never
  silently mutate the calendar. Start/end input uses EAT. Synced calendars may
  upload approved changes through their own sync adapters. No Google Calendar API,
  invitations, new dependency or server change. Physical OnePlus provider/sync,
  permissions and calendar-app source links remain NOT TESTED. Detailed commands,
  limits, recovery behavior and evidence: `docs/DEVICE_FEASIBILITY.md`.

- 2026-11-18, performance checkpoint: bounded native audio ownership/error handling,
  one video retriever per batch, bounded media scheduling queries, idle-model
  cleanup under the existing gate, local stage diagnostics and isolated AndroidX
  startup/navigation/profile collectors. 501 host Android tests pass, native
  CheckJNI and ASan/UBSan checks pass, and debug/release/benchmark/profile APKs
  compile. Raw comparison evidence is in `docs/performance-results.json`.

- 2026-11-17, news/media/performance/universal drawer: consolidated the pending
  feature branches on schema 19; added news Ask and scheduled notices; corrected
  same-refresh clustering, drawer action priorities, player search/navigation,
  thermal/queue policy, encrypted sync and backup/restore recovery. Full host
  regression and lint pass; physical/device, provider and model checks remain open.

Exact host results, artifact checksum and the final physical checklist are in
[DEVICE_FEASIBILITY.md](DEVICE_FEASIBILITY.md). Host tests use synthetic data,
ordinary SQLite/Robolectric and generated AES/RSA keys; they do not prove native
SQLCipher/Keystore, actual model execution, live accounts or Android/OEM behavior.

- 2026-11-16, local contacts (0.7.0, Room/backup v15): contact picker with explained
  runtime access and free-text fallback; all existing person/vendor fields plus
  optional task/payment links; multi-number/email message joins, audited link edits,
  deleted-contact status, older-backup restores and no contact fields in WebSync;
  263 host tests, including 16 new synthetic contact/link/privacy tests. Phone checks
  remain NOT TESTED.
- 2026-11-16, daily outputs and templates (0.7.0, Room v14): morning brief and evening
  wrap-up with a morning notification, waiting-on tracker, people pages, debts with
  repayment suggestions, recurring-bill detection and 30-day shortfall, expiry-date
  task proposals from scans, capture-gap alerts, action templates for tasks, expenses
  and income, compound Kiswahili amounts and income/template commands; 247 host tests.
- 2026-11-16, web access: optional read-only web copy (`web/`: NestJS API, Postgres,
  Next.js site) and Settings › Web access with an incremental WebSync worker on the phone;
  no Room change. 226 Android, 11 web API tests.
- 2026-11-16, Gemma 4 E2B (0.6.0): pinned official model downloaded on first launch over
  Wi-Fi with resume, SHA-256 check and load test; daily update check with owner-approved
  updates; arm64-v8a-only build; 232 host tests.
- 2026-11-16, review fixes and multiple accounts (0.5.0, Room v13): stable SMS/mail/relay
  identity, SMS high-water mark, money-preserving redaction, cross-source payment
  duplicates, tighter finance/task rules with Swahili clock times, full-text search,
  AAC recording recovery, recent-window mail sync, whole-folder call import, Drive
  retention, AI output-limit and thinking-block handling, relay rate-limit and batch
  ack, worker restart, internal-only deep links, debug-build diagnostics, and several
  email inboxes and WhatsApp accounts; 221 host tests.
- 2026-11-16, project review: full code and docs review; host suites re-run (205
  Android, 21 relay, 6 worker tests, no failures; lint 0 errors). Findings and next actions are in
  DEVICE_FEASIBILITY.md → "Project review checkpoint". No code changed.
- 2026-11-14, money entry: date/time, category and account pickers for cash entry,
  accounts without a provider field, Room v12; 192 host tests at that checkpoint.
- 2026-11-14, UI redesign: new theme and five-tab navigation, redesigned Home, Money,
  Tasks, Inbox and Ask, settings hub, reports with PDF/CSV export, Room v10–v11 and 13
  Glance home screen widgets. Emulator-checked only; see REDESIGN_SUMMARY.md.
- 2026-11-14, device security/recovery: opt-in platform fingerprint/device-credential
  app lock, encrypted daily Google Drive upload and confirmed backup selection/restore.
  Live Google/OEM verification remains pending.
- 2026-11-13, Phase 0: skeleton, assistant/session, notification capture, encrypted
  prototypes and access/status screens; 34 host tests.
- 2026-11-13, Phase 1: durable processing/recovery, audit/policy, authorized SMS history
  and reconciliation; 58 host tests.
- 2026-11-13, Phases 2–6 software slices: finance/local AI/OCR, encrypted database,
  mail/relay/drafts, tasks/alarms, encrypted multimedia/transcription adapters,
  evidence queries/memory and portable backup/recovery. Device testing stays last.
- 2026-11-13, advanced audio: compressed AAC conversation recordings, adaptive-noise-floor
  speech intervals, and experimental ONNX speaker grouping with owner-voice enrollment
  and conversation-name placeholders.
- 2026-11-13, smarter memory: on-device image labels and owner-imported text-embedding
  meaning search (schema 9 `semantic_vector`, rebuildable and excluded from backup); 141 host tests.
- 2026-11-13, agent workflows: confirm-first voice/chat commands for reminders,
  memory notes, task done/snooze/reschedule, cash review items and email/WhatsApp
  drafts; 137 host tests.
- 2026-11-13, WhatsApp Web: owner-authorized switch to linked-device capture with
  persistent LocalAuth, authenticated QR pairing/status, encrypted spool and relay,
  separate Android provenance and capture-only send guards. No live account paired.
- 2026-11-13, capture quality: narrower whole-item security redaction (above);
  notification removal records stay as evidence without re-creating finance/task
  candidates; a candidate repeating an existing provider reference is kept as
  duplicate evidence outside the review queue and linked on approval; promotions,
  system notices and deadlines over seven days old create no task review items;
  `leo`/`kesho` match whole words and `keshokutwa` means two days ahead; 152 host tests.
- 2026-11-14, resource use: coalesced queued processing drains with a successor for
  events arriving during a running drain; duplicate-only notification/SMS batches
  skip scheduling. Automatic meaning indexing waits for charging and adequate battery,
  skips native model initialization for an empty backlog, respects the requested
  batch limit and shares the heavy-model lock. Actual battery/peak RAM gains remain
  unmeasured pending device profiling.
- 2026-11-17, adaptive performance: the same gate and workers now yield to interactive
  work, reuse one heavy runtime when memory allows, skip silent audio and duplicate
  video frames, and cap how many media jobs are scheduled. CPU stays selected until a
  phone benchmark succeeds. Host policy tests pass; OnePlus 16 speed, power and codecs are
  not measured. See `docs/DEVICE_FEASIBILITY.md`.

References: [Android assistant](https://developer.android.com/reference/android/service/voice/VoiceInteractionService),
[exact alarms](https://developer.android.com/develop/background-work/services/alarms/schedule),
[foreground timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout),
[LiteRT-LM](https://developers.google.com/edge/litert-lm/overview),
[Gemma 4](https://developers.google.com/edge/litert-lm/models/gemma-4),
[bundled OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android),
[SQLCipher Android](https://github.com/sqlcipher/sqlcipher-android),
[JavaMail IMAP](https://javaee.github.io/javamail/docs/api/com/sun/mail/imap/IMAPFolder.html),
[Gmail app passwords](https://support.google.com/accounts/answer/185833),
[whisper.cpp](https://github.com/ggml-org/whisper.cpp/tree/v1.9.5),
[WhatsApp Business](https://developers.facebook.com/docs/whatsapp/).

Local release signing material for this machine is kept under
`~/.config/hellfire-ass/release-signing/` (directory mode 700, key/properties mode
600). Ignored `android/signing.properties` links to that private configuration.
Keep a separate private backup of the signing key and properties for future APK
updates. They are deliberately excluded from Git, CI and application backups.

#### TheftGuard (owner-device vertical slice, 2026-11-25)

Open **Settings → TheftGuard**. Configure the existing FastAPI relay's HTTPS
**origin**, its existing device bearer token, and a separate random 32-byte,
base64-encoded guard transport key. Store that same key as
`GUARD_PAYLOAD_KEY_BASE64` in the relay's private environment. Do not reuse
`STORE_KEY_BASE64` or put keys in commands, source files or screenshots. Existing
relay configuration (device public key, store key, token and selected WhatsApp
transport requirements) is still required.

Server-only SMTP configuration:

| Variable | Purpose |
|---|---|
| `GUARD_SMTP_HOST` | SMTP hostname with implicit TLS |
| `GUARD_SMTP_PORT` | Implicit TLS port; default 465 |
| `GUARD_SMTP_USER`, `GUARD_SMTP_PASSWORD` | SMTP login; never stored in APK |
| `GUARD_MAIL_FROM`, `GUARD_MAIL_TO` | Sender and owner destination |
| `GUARD_TIMEOUT_SECONDS` | Missing-heartbeat threshold; default 180, env minimum 120 |

`hellfire-relay.service` passes these values from `server/.env` to the relay. Run one
Uvicorn worker per relay database; its lifespan task checks persisted deadlines and
mail approximately every ten seconds. No scheduler runs unless the app lifespan
is started. The configured relay refuses remote arming when SMTP settings are
missing; configured does not mean live delivery has been verified.

Allow **precise location**, camera and notifications, and optionally phone/SIM
access. Background location is a separate Android Settings grant after foreground
location; the app never represents it as granted automatically. Enable Device
Admin for repeated wrong-unlock callbacks (three failures; callback support must
be verified on firmware). Request battery optimization exemption if desired.
Arming, camera resume and disarming each require a fresh strong biometric;
ordinary app-lock screen-lock fallback does not bypass guard biometric checks.

Once armed, the guard service runs in `:guard` with a low-importance persistent
notification. An independent best-effort 60-second heartbeat keeps the server
informed while sequential front/back CameraX and fused-location cycles collect
triggered evidence. Missing cameras, unavailable location, Google Play services,
revoked access and upload failures are recorded as unavailable/unconfirmed, not
synthetic successes. Triggers are repeated wrong unlock, observed subscription
change, loss of validated connectivity, manual owner test and server-reported
missed heartbeat. Only the first trigger is retained until disarm/re-arm. Camera
bytes stay in memory until encrypted in the Room log. Local evidence has a 50 MiB
budget; full storage pauses new capture. Offline uploads are retried while the
same session remains armed. Disarm retains evidence and stops further uploads.

Both new bearer-authenticated, existing-rate-limited routes accept
`{"payload":"<base64 PayloadCipher envelope>"}` with AES-256-GCM v1
(`0x01 || 12-byte nonce || ciphertext-and-tag`), AAD `hellfire-guard-v1`, and
4 MiB maximum request bodies:

- `POST /device/theft/heartbeat`: `session_id` UUID, increasing `sequence`, Unix
  `timestamp` **seconds**, `armed`, `active`, `battery` (-1 unknown or 0–100),
  `network` (`online`/`offline`). The relay uses its receipt clock for timeout;
  heartbeat timestamps must be within five minutes. Acknowledgments report
  `active`, allowing a server dead-man trigger to reach a reconnected phone.
- `POST /device/theft/evidence`: `id` UUID, `session_id`, Unix `timestamp` seconds,
  `trigger`, nullable `location` (latitude, longitude, accuracy metres, timestamp),
  `images` (up to two distinct front/back base64 JPEGs, each ≤1 MiB), and bounded
  capture-status `errors`. An armed heartbeat must establish the session first.
  Evidence IDs are idempotent; reused IDs with changed content are refused.

Evidence is sealed to the existing paired device public key on the relay. A
separate server-store-key-encrypted outbox supplies **readable coordinates and JPEG
attachments** to the owner through implicit TLS SMTP; it clears the payload after
SMTP accepts. This makes recovery email usable without the stolen phone. The
configured owner mailbox intentionally retains its own readable evidence copy.
Relay retention for evidence and unsent mail is seven days, with a 256 MiB retained
evidence budget (storage-full responses preserve the phone's pending upload). Mail acknowledgment is queueing,
not verified inbox delivery; SMTP failure retries and rare crash-related duplicate
emails are possible. Offline local disarm queues remote cancellation with
WorkManager; re-arming waits for acknowledgment. Lost network, power-off, Doze,
force-stop or OEM termination may all cause the same missing-heartbeat alert.

Boot recovery is dedicated to guard and resumes only saved armed state after the
first unlock makes credential-protected storage available. Background location
allows a **location-only** foreground service; stock Android camera recovery needs
an owner tap and biometric. Without background location, tap the guard notification
to resume the service. Android can block either path; no indicator suppression,
power-key interception, shutdown veto or root path exists.

Tier 2 provisioning is a scaffold, **NOT TESTED**: on an empty, owner-approved
provisioning/test device with no conflicting accounts/owners, Android's Device
Owner provisioning tooling can assign `com.hellfire.ass/.guard.GuardAdminReceiver`
(for example `adb shell dpm set-device-owner com.hellfire.ass/.guard.GuardAdminReceiver`
when Android permits it). Do not reset the owner's phone for this slice. Arming
checks actual Device Owner status before applying uninstall/safe-boot restrictions
and lock-task policy. Disarming restores the previous settings. Power-menu
suppression only applies if a future explicitly authorized kiosk flow enters
lock-task mode; this slice does not. Hardware shutdown is always outside userspace
control. Tier 3 root support is out of scope.

Official constraints checked: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager),
[CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera),
[Google Play services releases](https://developers.google.com/android/guides/releases).
See `docs/DEVICE_FEASIBILITY.md` for actual test results and the final-device checklist.

## Checkpoint — 2026-11-26 project review follow-up (host only)

| Check | Command | Result |
|---|---|---|
| Android JVM tests | `./gradlew :app:testDebugUnitTest` | PASS: 551 tests, 546 passed, 5 existing credential/fixture skips, 0 failures |
| Android lint | `./gradlew :app:lintDebug` | PASS: 0 errors (445 warnings, 11 hints); no `MissingTranslation` |
| Debug APK | `./gradlew :app:assembleDebug` | PASS: `HellFire-Ass-0.10.0-code10-debug.apk`, 172,868,880 bytes |
| Release APK | `./gradlew :app:assembleRelease` (no owner signing file in the worktree) | PASS: `HellFire-Ass-0.10.0-code10-release-unsigned.apk`, 115,504,474 bytes |
| Web API | `npm test` in `web/api` | PASS: 31/31 (new: logout revocation, per-visitor login limit) |
| Relay / WhatsApp worker | `pytest -q`, `npm test` | PASS: 40/40, 6/6 |

Changes: versioned APK names from Gradle (0.10.0, code 10), inbox event search through the
FTS index, named string resources replacing the English-text lookup, draft and player errors
as resources, web logout revocation, relay and worker under systemd, large-file splits,
imports instead of fully qualified names, and explicit errors where Android may return a null
stream. Kiswahili rendering of the migrated screens, the systemd relay and all phone behaviour
remain NOT TESTED on a device or live service.
