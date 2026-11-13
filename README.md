# HellFire Ass

Phase 0 Android feasibility slice for **HellFire**, package `com.hellfire.ass`.
The [master specification](docs/MASTER_SPEC.md) is the product source of truth.
Later phases have not been started.

## Build from a clean checkout

Required: JDK 17 or 21, Android SDK platform 36, Build Tools 35.0.0, and
platform-tools. Dependencies and Gradle require network access on the first build;
the installed app's core demonstrations work offline. No credentials or model downloads.

```bash
export ANDROID_HOME=/path/to/android-sdk
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Alternatively set `sdk.dir=/path/to/android-sdk` in ignored `android/local.properties`.
Open `android/` in Android Studio. The wrapper pins and checksums Gradle 8.13;
do not use an unrelated global Gradle version. The pinned toolchain is AGP 8.13.2,
Kotlin/Compose compiler 2.2.21, KSP 2.2.21-2.0.4, Compose BOM 2025.10.00,
Room 2.8.4, coroutines 1.10.2, Activity 1.11.0, Lifecycle 2.9.4 and AndroidX Core 1.15.0.
Minimum Android: API 29; compile/target: API 36. A single module with separate
integration/data/security/UI packages keeps this feasibility slice small.

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hellfire.ass/.MainActivity
```

The debug APK is for local verification. A private release signing key and release
APK distribution are outside this phase. Never commit a keystore or private data.

## Try the slice

1. **Setup:** select HellFire Ass as digital assistant; enable notification access.
   On a sideloaded ColorOS build, Android may require the owner to allow restricted
   settings first. Grants are read back from Android when returning to the app.
2. **Assistant:** ask `hello`, `habari` or `time` offline. Select **Open assistant
   session**, then separately test the system gesture. The actual
   `VoiceInteractionSession` supports typed input and optional offline TTS.
   Spoken input delegates only to an already installed Android on-device recognizer
   on API 31+; unsupported devices/languages fail visibly and keep typed input.
   No custom hotword, lock-screen support, remote speech fallback or model download.
3. **Capture:** insert the explicitly labeled synthetic fixture twice; the second
   insert must be ignored. This tests the same normalization/encryption/Room path,
   but does **not** prove Android notification delivery. Post a non-sensitive real
   notification from another app or use the command in the device guide.
4. Allow microphone and notification permissions. **Start 60-second recording**
   while the activity is visible. Stop in the app or persistent notification.
   Playback decrypts a retained sample into memory; Delete removes that sample.
   Audio never goes to a plaintext file. System mic muting or another app can
   interrupt capture. The prototype does not restart itself after process death.
5. **Device:** inspect the actual model, OS, API, build and ABI. Follow
   [device feasibility](docs/DEVICE_FEASIBILITY.md) for the remaining physical tests.

## Data and boundaries

Notifications from all packages Android delivers are accepted, except this app's
own notifications. A bounded queue feeds an IO worker, with visible overflow and
storage errors. Reconnect snapshots currently active notifications; notifications
missed while disconnected or lost on process death cannot be recovered reliably.
There is no durable capture queue yet (Phase 1).

`source_event` rows are immutable insert-or-ignore records with deterministic UUIDs,
source/external-ID uniqueness, timestamps, content hash and provenance.
Posted/edited/removed/ranking changes retain separate events. Synthetic fixtures have
a distinct `synthetic_notification` source. Only safe metadata is shown in the inbox.

Payloads are redacted before hashing/encryption/persistence. English/Kiswahili
security context causes the entire textual payload to be replaced; standalone
4–8 digit candidates are conservatively removed even without context. This may
also redact benign numbers. These are heuristics, not a general secret detector;
additional anonymized fixtures are needed before broad personal-data use.
OS-supplied content is used as delivered; empty content is marked unavailable.
The app cannot infer whether an absent field was protected, absent originally,
or redacted upstream, and never tries to recover it.

Room stores AES-256-GCM encrypted notification payloads; package names, timestamps,
flags and hashes remain plaintext metadata. Audio uses the same authenticated
Keystore-backed encryption, streamed as 16 kHz mono signed 16-bit little-endian PCM
inside a version-byte + 12-byte IV + ciphertext/tag envelope. It is not a WAV file.
Samples are bounded to 60 seconds each and retained until deletion. Interrupted or
failed streams are removed; power loss can leave an incomplete sample, which
playback rejects. No transcript or summary is generated.

This is payload/media encryption, not SQLCipher/full-database encryption. The
Android Keystore key stays on the device; backup/device-transfer is excluded.
Uninstalling the app or losing its key makes its encrypted data unrecoverable.
There is no network permission, analytics SDK, external sending, notification reply
execution, SMS reading, finance ledger, mail, WhatsApp, alarms or inference runtime.
SMS/media/alarm settings are honestly labeled as deferred; permissions are not
requested for those later features.

## Tests and Phase 0 change log

Verified on 2026-11-13: **34 tests passed**, lint passed with **0 errors / 29 warnings**,
and the debug APK built. Physical device validation is still pending.

Host tests cover normalization, content absence, OTP redaction, replay/edits/removal,
concurrent SQLite deduplication, encrypted persistence, authentication failures,
streamed encryption, timezone rollover and manifest/Android-extra contracts.
Robolectric uses API 34 with an in-memory Room database and generated test AES keys;
it does not verify Android Keystore hardware, OEM services, mic capture, speech
engines, permission dialogs, gesture invocation or battery behaviour.

2026-11-13: initialized the repository from its starter specification; added the
Phase 0 Android project, assistant/session/on-device recognition adapter,
notification capture, encrypted local storage, permission/device screens and
visible microphone recording/playback/deletion. See the feasibility table for
actual command results and outstanding device gates.

Build choices follow [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes),
[Compose compiler setup](https://developer.android.com/develop/ui/compose/compiler),
[Room releases](https://developer.android.com/jetpack/androidx/releases/room), and
[KSP releases](https://github.com/google/ksp/releases/tag/2.2.21-2.0.4).
