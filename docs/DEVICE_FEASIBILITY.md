# HellFire Ass — capture-engine feasibility

Evidence date: 2026-11-13, Africa/Dar_es_Salaam. Phase 0 and Phase 1 implementation
have passed host checks. The owner explicitly deferred physical device testing to
the final stage. Device checks are **NOT TESTED**, rather than blockers to the next
software milestone. Nothing here asserts successful OPPO testing or grants.
The owner-supplied phone specifications remain unverified.

| Question | Test | Result | Evidence | Next action |
|---|---|---|---|---|
| Host JDK / SDK available? | `java -version`; inspect SDK directories | PASS | OpenJDK 21.0.13; SDK `/home/dev/android-sdk`; platform 36 and Build Tools 35.0.0 installed | Continue with project wrapper |
| Current APK compiles? | `./gradlew :app:assembleDebug` | PASS | Version 0.2.0, application ID `com.hellfire.ass`, min/target API 29/36 | Preserve APK for final testing |
| Host logic / SQLite checks? | `./gradlew :app:testDebugUnitTest` | PASS | 58 tests; 0 failures/errors/skips | Keep host gates for subsequent changes |
| Android lint? | `./gradlew :app:lintDebug` | PASS | 0 errors, 31 warnings: pinned dependency updates, English resources and KTX suggestions | Warnings remain enabled and visible |
| Event/job insertion atomic? | Reject job insertion with a synthetic SQLite trigger | PASS | Source insert rolls back; no orphan event, job or audit increment | Preserve transactional contract |
| Phase 0 schema upgrades? | Construct v1 database from committed schema, migrate to v2 | PASS | Original ciphertext unchanged; pending job and audit created; Room validates v2 | Do not use destructive fallback |
| Pending work survives database reopen? | Close/reopen file-backed synthetic DB, drain processing | PASS | Persisted pending event becomes DONE; ciphertext retained | Verify actual process death on phone later |
| Notification replay / storm processing? | 500 distinct synthetic versions, each replayed once | PASS | 500 events/jobs retained and processed, no duplicate count | Measure real callback floods during final testing |
| OTP / missing / malformed content? | Synthetic body/title/message fixtures and malformed messaging extras | PASS | Redaction, unavailable label and preservation of valid top-level text pass | Verify OS-protected fields without bypasses later |
| Per-app processing control? | Disable processing before capture | PASS | Source retained, CAPTURE_ONLY job, no classification/key load | Exercise switch accessibility on phone later |
| SMS identity, paging and read-only adapter? | Synthetic incoming/sent rows and fake provider cursor | PASS | Stable identities, 500-row page, ordered parameterized query, cursor closed; drafts rejected | Verify actual provider behaviour later |
| SMS permission / protected receiver? | Manifest contract; owner consent default | PARTIAL | Receiver requires BROADCAST_SMS; capture defaults off; no SEND_SMS permission | Verify READ_SMS/RECEIVE_SMS grants and provider access on OPPO |
| WorkManager dispatch/reboot/background timing? | Final-stage device tests below | NOT TESTED | Persistent scheduling wired; host tests establish Room persistence, not OEM scheduling | Test after software milestones |
| Physical phone / firmware / ABI? | Final ADB discovery below | NOT TESTED | Deferred by owner; no device used for Phase 1 tests | Capture actual non-secret firmware details at final stage |
| Assistant role and active service selection? | Setup chooser and assistant settings | PARTIAL | Host metadata/session/service contracts pass; no physical role grant | Select and invoke on physical device later |
| OEM gesture, power button, lock screen, hotword? | Exercise each entry point separately | NOT TESTED | Custom hotword/lock-screen launching disabled | Record actual firmware limits |
| Offline assistant / speech / TTS on phone? | Airplane mode; activity and session queries | NOT TESTED | Host static query/timezone logic passes; system recognizer/voice availability unknown | Verify actual engines/languages later |
| Notification permission and real delivery? | Grant/revoke access; shell/app notifications | NOT TESTED | No real phone callback or permission dialog exercised | Follow final checklist |
| Mic capture / playback / interruption? | Visible Start/Stop; privacy toggle, incoming call, screen off | NOT TESTED | No actual microphone or phone used | Verify indicators, notification Stop, 60-second limit and error paths |
| Device Keystore encryption? | Capture/playback; controlled debug-file inspection | NOT TESTED | Host tests use synthetic AES keys, not device Keystore hardware | Confirm ciphertext and key persistence later |
| ColorOS battery / long screen-off capture? | Vendor settings, Doze and overnight checks | NOT TESTED | No survival/timing guarantee asserted | Measure later; keep source status honest |
| Call recordings / local AI / finance / WhatsApp / Gmail? | Later phase-specific checks | NOT TESTED | Features not implemented in Phase 1; no models or credentials used | Continue in master-spec order |

## Verified host commands

Run in the isolated Phase 1 worktree using the installed SDK and JDK:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# BUILD SUCCESSFUL in 37s; 58 actionable tasks: 17 executed, 41 up-to-date
```

**58 tests passed; 0 failures/errors/skips. Lint: 0 errors, 31 warnings.**
Robolectric integration tests use API 34. No emulator, physical phone, personal SMS
corpus, notification history, recordings, credentials or AI models were used.

The only Kotlin warning in changed test execution concerns the public deprecated
StatusBarNotification constructor used for fixtures. The existing Material tab API
also remains deprecated. The debug build packages the prebuilt
`libandroidx.graphics.path.so` without stripping its symbols. None prevents building.

Current artifact: `android/app/build/outputs/apk/debug/app-debug.apk`, version **0.2.0**.
SHA-256: `a7127f3c620f83229ea6f0a5d0cd7bc83549f8e7e92b6f6ec3e1940be1080f78`.
Host reports are preserved under `android/app/build/reports/` after integration:
`tests/testDebugUnitTest/index.html` and `lint-results-debug.html`.

`aapt dump permissions` confirms READ_SMS/RECEIVE_SMS, microphone/notification/FGS
permissions and WorkManager's WAKE_LOCK/ACCESS_NETWORK_STATE/RECEIVE_BOOT_COMPLETED
permissions plus AndroidX's app-scoped receiver permission. There is no INTERNET,
SEND_SMS, media-reading or exact-alarm permission. Declared permissions are not grants.

Phase 0 baseline: 34 tests passed, 0 lint errors / 29 warnings, debug version 0.1.0.
The current artifact above supersedes that debug APK; this does not change historical
physical test outcomes, which remain untested.

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

Inspect only synthetic evidence if exporting a debug database. Sensitive payloads
and audio must be ciphertext; package/timestamp/flag/hash/label/counter metadata is
plaintext. Clear controlled test events and samples in the app. Keep all actual
device artifacts out of Git.

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
