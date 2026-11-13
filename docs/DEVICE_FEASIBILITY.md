# HellFire Ass — Phase 0 feasibility

Evidence date: 2026-11-13, Africa/Dar_es_Salaam. Host-run checks and physical
results are separate. **DEVICE TEST REQUIRED:** no phone or emulator was connected
when `adb devices -l` was run. The owner-supplied OPPO specifications are not verified.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Host JDK / SDK available? | `java -version`; inspect SDK directories | PASS | OpenJDK 21.0.13; `/home/dev/android-sdk`; platforms 34/35/36, Build Tools 34/35/36 installed | Use the project wrapper and SDK 36 |
| APK compiles? | `cd android && ./gradlew :app:assembleDebug` | PASS | Debug APK built; package `com.hellfire.ass`, label HellFire Ass, min/target API 29/36 verified with `aapt dump badging` | Install on authorized device |
| Automated logic / SQLite tests? | `./gradlew :app:testDebugUnitTest` | PASS | 34 tests, zero failures/errors/skips; includes real host SQLite via Robolectric API 34 | Physical tests remain separate |
| Android lint? | `./gradlew :app:lintDebug` | PASS | Zero errors, 29 warnings: dependency updates, English text resources and KTX suggestions | Warnings remain visible in report |
| Physical device attached? | `adb devices -l` | BLOCKED | Empty device list on host | Connect and authorize the OPPO via USB debugging |
| Actual OPPO model, ColorOS, API, ABI? | ADB properties below; Device tab | NOT TESTED | No device connected | Capture non-secret firmware details locally |
| App qualifies for assistant selection? | Setup chooser; assistant metadata test | PARTIAL | Host manifest/service contract passed; no physical role grant | Select HellFire Ass and check role and active voice-service status |
| Gesture/button invocation and background session? | Invoke from launcher, another app and after backgrounding | NOT TESTED | No OEM interaction performed | Record each trigger separately |
| Lock screen / hotword? | Inspect actual system support | NOT TESTED | Custom hotword and lock-screen launching disabled in prototype | Do not enable unsupported features or claim parity |
| Offline typed response? | Airplane mode; `hello`, `habari`, `time` | PARTIAL | Host greeting/timezone/fallback tests pass; no app run on phone | Verify activity and actual assistant session |
| Offline speech / TTS? | Session Speak / Read answer buttons in airplane mode | NOT TESTED | Existing system on-device recognizer/offline voice required | Record API, engine, language and error code; typed fallback must work |
| Notification access on ColorOS? | Grant/revoke listener access, including restricted-settings flow if shown | NOT TESTED | No permission represented as pre-granted | Grant manually and verify status refresh |
| Real callbacks persist in Room? | Non-sensitive shell/app notification; Capture inbox | NOT TESTED | Synthetic fixture is explicitly separate from real listener delivery | Check posted/updated/removed rows and source/package |
| Protected/OTP content and empty notifications? | Synthetic security fixtures; optional non-sensitive device security test | PARTIAL | Redaction and missing-content host tests pass, including decrypted persisted synthetic payload; no personal OTP collected | Verify device delivery without bypassing OS redaction |
| Reconnect / notification flood / screen off? | Toggle access; repeat notifications; screen-off test | NOT TESTED | Bounded queue reports drops; no process-death durability yet | Measure actual losses and queue status |
| Visible microphone / retained sample? | Grant mic + notifications; Start, Stop, Play, Delete | NOT TESTED | AudioRecord + microphone FGS implemented; no device capture performed | Confirm system indicator, persistent Stop action, sample playback |
| Mic denial, muted input, incoming call, screen off? | Deny/revoke permission, privacy toggle, interruption, 60-second limit | NOT TESTED | No OEM test evidence | Verify visible errors, no automatic restart and no false Saved status |
| Device Keystore / encrypted at rest? | Capture/playback and inspect debug app files locally | NOT TESTED | Host uses synthetic AES keys; device encryption is not host-verified | Confirm ciphertext; keep all device data out of Git |
| Battery restrictions / alarms? | Battery settings, later overnight/Doze tests | NOT TESTED | Android exemption displayed; vendor controls unknown; alarms deferred | Verify ColorOS manually; no alarm reliability claim |
| Existing call recordings accessible? | Later MediaStore/SAF probe | NOT TESTED | Outside Phase 0 | Wait for import phase; do not record call audio |
| LiteRT-LM / Swahili model performance? | Later benchmark on actual device | NOT TESTED | No model downloaded | Wait for local-AI phase |
| WhatsApp Coexistence / Gmail app password? | Later authorized account integration tests | NOT TESTED | No credentials requested or integration implemented | Wait for communications phase |

## Verified host commands

Executed in the isolated Phase 0 worktree with the installed SDK and JDK:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# BUILD SUCCESSFUL in 1m 52s; 58 actionable tasks executed
```

Tests: **34 passed**, zero failures/errors/skips. Lint: **0 errors, 29 warnings**.
Warnings were not disabled. Kotlin also reports the older Material tab API as
deprecated; the test uses the public deprecated StatusBarNotification constructor.
The debug build packages the prebuilt `libandroidx.graphics.path.so` without stripping
its debug symbols. These do not prevent the verified debug APK build.

The APK and host reports are preserved under `android/app/build/` in the primary
checkout after merging; these artifacts are ignored by Git. Reports are
`reports/tests/testDebugUnitTest/index.html` and `reports/lint-results-debug.html`.
APK: `outputs/apk/debug/app-debug.apk`, version `0.1.0`.

SHA-256: `0f81a545a388f818621d483ac259f9ed1b9caa1ce0e740ddacab4d9b0cfbebdc`.
`aapt dump permissions` confirms the APK requests microphone, notification posting
and foreground-service permissions plus AndroidX's app-scoped receiver permission;
it has no INTERNET, SMS, media-reading or exact-alarm permission.

**Phase 0 implementation and host gates passed; physical device validation is
pending.** Do not infer device success from host automation or begin Phase 1 automatically.

## Physical device verification

Run from the checkout root; do not assume a device model, SDK level, file path or
permission from the owner's description. With multiple devices, supply `adb -s SERIAL`
locally. Do not commit serial numbers, private notifications, recordings or full dumps.

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

Open Setup. Choose HellFire Ass as digital assistant; if the role chooser declines
or is unavailable, use the assistant settings fallback. Record role held separately
from the voice service selected. Neither proves invocation until the session appears.

```bash
adb shell cmd role get-role-holders android.app.role.ASSISTANT
adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction
```

In airplane mode, ask `hello`, `habari`, and `time` in the activity, then open the
actual session and repeat. Try the supported system gesture/button from another app
and after screen off/on. For each, record PASS/PARTIAL/BLOCKED with firmware/build
and observed behaviour. No hotword or lock-screen support is asserted.

Enable notification access. If Android restricts sideloaded permissions, use its
owner-controlled App info → Allow restricted settings flow only if offered.
In Capture, insert the synthetic fixture twice. Then use a non-sensitive shell test:

```bash
adb shell "cmd notification post -t 'HellFire fixture' hellfire-phase0 'Synthetic device delivery test'"
adb shell "cmd notification post -t 'HellFire fixture edited' hellfire-phase0 'Synthetic updated text'"
```

These shell notifications originate outside HellFire Ass and exercise real listener
callbacks. Verify the shown source package rather than assuming a fixed shell package.
Dismiss the notification manually and check the REMOVED event. Reconnect may create
new evidence when ranking or post time differs; identical snapshots must deduplicate.
Repeat with ongoing, silent, grouped/MessagingStyle and contentless notifications
from authorized test apps. Toggle access off/on; after revocation the UI must report
missing access. Android can omit protected fields; this is not a capture failure to bypass.

For the microphone test, keep the activity visible, grant both permissions, and tap
Start. Verify a persistent notification with Stop and Android's microphone indicator.
Speak a non-sensitive sample; stop and play it. Repeat until automatic stop after
60 seconds, stop from notification, deny/revoke mic permission, mute the system mic,
block recording notifications, switch apps, turn the screen off and interrupt with
a call. Keep media volume audible for playback. Record actual result and firmware.
The app does not use assistant-role background-start exemptions, a boot receiver,
or automatic restarts. Losing the process can leave a partial encrypted file that
playback rejects; do not mark that file as a successfully retained sample.

Optional debug inspection, after disabling notification access and stopping recording:

```bash
adb shell run-as com.hellfire.ass ls databases
adb shell run-as com.hellfire.ass ls files/recordings
```

Inspect only controlled synthetic records if exporting a debug database. Payloads
and audio must be ciphertext; package/timestamp/flag/hash metadata is plaintext by
design. Never publish collected content or temporary device artifacts. Remove test
samples in the app and clear captured events when done.

## References and implementation limits

- [Assistant service](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
  and [AOSP metadata validation](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/service/voice/VoiceInteractionServiceInfo.java).
- [Notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
  and [Android 15 protected notification content](https://developer.android.com/about/versions/15/behavior-changes-all).
- [Microphone foreground-service prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).
- Phase 0 has an in-memory, bounded callback queue. Overflow/storage errors are
  visible; durable replay and large-scale capture resilience belong to Phase 1.
- Encryption covers sensitive payloads and original microphone samples; Room is
  not fully encrypted. No secret-detector heuristic guarantees all formats.
- Host automation cannot establish OEM role qualification, device grants,
  Android Keystore behaviour, actual callback delivery or audio capture.
