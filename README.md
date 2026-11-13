# HellFire Ass

Native, local-first Android assistant **HellFire**, package `com.hellfire.ass`.
Phase 0 and the Phase 1 capture engine are implemented. The
[master specification](docs/MASTER_SPEC.md) remains the product source of truth.

Owner direction on 2026-11-13: continue building incrementally and leave physical
device testing until the end. Device outcomes remain **NOT TESTED** until observed.
No physical-device gate is being represented as passed.

## Build from a clean checkout

Required: JDK 17 or 21, Android SDK platform 36, Build Tools 35.0.0, and platform-tools.
The first build downloads Gradle and dependencies. Core app demonstrations work
offline without credentials or model downloads.

```bash
export ANDROID_HOME=/path/to/android-sdk
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Alternatively set `sdk.dir=/path/to/android-sdk` in ignored `android/local.properties`.
Open `android/` in Android Studio. Use the checksummed Gradle 8.13 wrapper.
Pinned tools: AGP 8.13.2, Kotlin/Compose compiler 2.2.21, KSP 2.2.21-2.0.4,
Compose BOM 2025.10.00, Room 2.8.4, coroutines 1.10.2, Activity 1.11.0,
Lifecycle 2.9.4, AndroidX Core 1.15.0, and WorkManager 2.10.5.
Minimum Android: API 29; compile/target: API 36. Version: **0.2.0**.

The single app module keeps capture, processing, database, security and UI code in
separate packages. No backend or AI runtime has been added.

## Use the capture engine

- **Setup:** select HellFire Ass as digital assistant and grant notification access.
  Sideloaded ColorOS builds may require Android's owner-controlled restricted-settings
  flow. The app reads back actual grants; permission denial remains visible.
- **Assistant:** ask `hello`, `habari`, or `time` offline. The actual system session
  supports typed input and optional offline TTS. Spoken questions use only an already
  installed Android on-device recognizer on API 31+. Unsupported engines/languages
  keep typed input available; no model download or remote recognition fallback.
- **SMS:** opt in with **Enable SMS capture and history import**. This authorizes
  reading existing incoming/sent SMS and new records from the Android SMS provider.
  READ_SMS and RECEIVE_SMS are requested separately from recording permissions.
  Android/installer restrictions may prevent grants; the app does not bypass them
  or request the default SMS role. No SMS sending is implemented or permitted.
- **Capture:** inspect source counters, persisted processing states and safe metadata.
  Insert the explicitly labeled synthetic notification fixture twice; its duplicate
  is ignored. This is not evidence of real Android listener delivery.
- **Processing controls:** all accessible notifications continue to be retained.
  Turn processing off for a captured app to mark its future/pending events as
  **CAPTURE_ONLY**. Existing results are unchanged. **Resume saved processing**
  schedules pending work and retries explicitly failed payloads.
- **Recording:** allow microphone and notification permissions, then start from the
  visible activity. Stop in the app or ongoing notification, or wait for the
  60-second limit. Samples remain encrypted until deleted and play locally from
  decrypted memory. No transcript, VAD, background start or automatic mic restart.
- **Device:** shows actual Android model, build, API and ABI. Follow the
  [device guide](docs/DEVICE_FEASIBILITY.md) when physical testing begins.

Final-stage installation, from the `android/` directory:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hellfire.ass/.MainActivity
```

This debug APK is for local verification. Private release signing/distribution
is not implemented. Never commit signing keys or device data.

## Capture and recovery guarantees

Each newly captured event and its **PENDING** processing row commit in one Room
transaction. Event identity excludes receipt time, so identical replays are ignored.
Edits, removals, changed ranking and SMS direction/content changes retain distinct
immutable evidence. Processing state, attempts, rule labels and safe error codes live
in `event_processing`; the original event's legacy `processingStatus` is not rewritten.
Room schema 1 migrates to schema 2 without deleting Phase 0 events or ciphertext.

The notification listener accepts all Android-delivered packages except this app's
own notifications. Its 256-item memory queue drains in batches of up to 32 on IO.
WorkManager processes committed jobs independently. Queue overflow/storage issues
are visible, with persisted audit counters when storage is available. Notifications
lost before a Room commit or while the listener is disconnected cannot be promised
recoverable. Reconnect snapshots active notifications; it cannot restore vanished history.

Processing assigns conservative **candidate** labels for finance, delivery and
promotion, plus system/message/security/unavailable/unclassified labels. These rules
create no ledger entries, reminders or sends. Security content bypasses general
classification. Invalid ciphertext/key access is marked FAILED for owner-controlled
retry; source evidence remains intact. Source counters/errors are historical audit
records, not claims of a current connection. Deleting captured events cascades to
processing jobs; counters and processing preferences remain.

WorkManager persists scheduling, retries transient worker errors with exponential
backoff and performs best-effort maintenance at a 15-minute interval. App startup
also schedules recovery, covering a crash between a Room commit and work enqueue.
Work is appended after current processing to avoid ignoring events arriving while
that worker finishes. OEM restrictions, Doze and force-stop can delay execution;
no exact-time or OS-level survival guarantee is made. Nothing restarts recording.

SMS broadcasts schedule reconciliation rather than putting message bodies/PDUs into
WorkManager. The provider is the source of truth; unreadable or provider-omitted SMS
are not fabricated. Imports use ordered, parameterized queries and pages of 500
incoming/sent rows. Page continuations persist only a row-ID cursor in WorkManager
before completing the prior page. Repeated initial triggers coalesce while a chain
is active. Observer callbacks while the process lives and periodic full reconciliation
also catch outgoing rows and outbox-to-sent updates. A change racing the final page
may wait for the next reconciliation; background timing requires device measurement.
Large SMS histories will be rescanned, although duplicate rows skip encryption and
writes. Battery cost and provider behaviour need physical measurements before tuning.
Disabling SMS capture persists the choice, unregisters observation and cancels its
pending chain; existing evidence is retained. SMS permissions can be revoked in Android.

## Privacy and boundaries

Payloads are redacted before persistence and encrypted with Android Keystore-backed
AES-256-GCM. English/Kiswahili security context replaces textual fields; standalone
4–8 digit candidates are conservatively removed. This may redact benign amounts.
The heuristic is not a universal secret detector. No personal fixtures or OTPs are
needed for host tests. Protected fields are used only as Android delivers them;
missing content stays unavailable, without pretending to know why it is absent.

Package names, timestamps, flags, hashes, processing labels and counters remain
plaintext database metadata. This is payload/media encryption, not full SQLCipher
database encryption. Backup/device transfer is excluded; key loss or uninstall
makes encrypted payloads unrecoverable.

Audio is 16 kHz mono signed 16-bit little-endian PCM, streamed directly into an
AES-GCM envelope (version byte, 12-byte IV, ciphertext/tag). It is not WAV. Failed
streams are removed; process/power loss can leave an incomplete file that playback
rejects. Retained samples are limited to 60 seconds each and decrypted into memory
for user-started playback.

There is no INTERNET permission, analytics, external sending, notification reply
execution, finance ledger, mail/WhatsApp integration, alarms, media indexing or AI
inference. WorkManager supplies normal scheduling permissions such as boot reception
and wake locks; none authorizes microphone capture. Financial provider parsing and
cross-source transaction deduplication remain Phase 2 work. WhatsApp Coexistence,
Gmail IMAP/SMTP and later recording imports retain the locked master-spec choices.

## Verification and change log

The current build's exact results, APK checksum and deferred device cases are in
[DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md). Host tests cover normalized
identities/redaction, encrypted Room persistence, transaction rollback, duplicate
storms, pending-work recovery after database reopen, schema migration, rule labels,
processing controls, SMS paging/identity and Android metadata contracts.
Robolectric uses API 34 and synthetic AES keys; it does not establish device
Keystore behaviour, SMS grants, OEM services, callback delivery, speech or mic capture.

- 2026-11-13, Phase 0: Android skeleton, assistant/session, notification capture,
  encrypted payload/audio prototypes, permission/device screens; 34 host tests passed.
- 2026-11-13, Phase 1: transactional durable processing, recovery scheduling,
  source audit/status dashboard, per-app processing policy, opt-in SMS capture and
  paged reconciliation, schema migration and expanded synthetic host coverage.
  Physical tests deferred by owner instruction. Later phases remain unimplemented.

References: [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes),
[Room](https://developer.android.com/jetpack/androidx/releases/room),
[WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work),
[work chaining](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/chain-work),
[Android SMS provider](https://developer.android.com/reference/android/provider/Telephony.Sms).
