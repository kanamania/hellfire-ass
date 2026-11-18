#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
serial="${1:?Usage: test_capture_emulator.sh emulator-SERIAL}"
adb_bin="${ANDROID_HOME:-/home/dev/android-sdk}/platform-tools/adb"
[[ "$serial" == emulator-* ]] || exit 2
[[ $("$adb_bin" -s "$serial" emu avd name | tr -d '\r' | head -n 1) == hellfire_fixture_api35 ]] || { echo 'Capture grants are limited to the owned fixture AVD.' >&2; exit 2; }
package=com.hellfire.ass.emulator
listener="$package/com.hellfire.ass.notifications.HellFireNotificationListener"
permissions=(android.permission.READ_SMS android.permission.RECEIVE_SMS android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS)
role_file=$(mktemp)
"$adb_bin" -s "$serial" shell cmd role get-role-holders android.app.role.ASSISTANT | tr -d '\r' > "$role_file"
cleanup() {
    "$adb_bin" -s "$serial" shell cmd notification disallow_listener "$listener" >/dev/null 2>&1 || true
    "$adb_bin" -s "$serial" shell cmd role remove-role-holder android.app.role.ASSISTANT "$package" >/dev/null 2>&1 || true
    while IFS= read -r holder; do
        if [[ -n "$holder" ]]; then "$adb_bin" -s "$serial" shell cmd role add-role-holder android.app.role.ASSISTANT "$holder" >/dev/null 2>&1 || true; fi
    done < "$role_file"
    for permission in "${permissions[@]}"; do "$adb_bin" -s "$serial" shell pm revoke "$package" "$permission" >/dev/null 2>&1 || true; done
    rm -f -- "$role_file"
}
trap cleanup EXIT
for permission in "${permissions[@]}"; do "$adb_bin" -s "$serial" shell pm grant "$package" "$permission"; done
"$adb_bin" -s "$serial" shell cmd notification allow_listener "$listener"
"$adb_bin" -s "$serial" shell cmd role add-role-holder android.app.role.ASSISTANT "$package"
"$adb_bin" -s "$serial" emu sms send +255700000000 'HellFire synthetic SMS fixture habari'
report_dir="$repo_dir/android/build/reports/predevice"
mkdir -p "$report_dir"
"$adb_bin" -s "$serial" shell am instrument -r -w -e class com.hellfire.ass.CaptureIntegrationTest \
    com.hellfire.ass.emulator.test/com.hellfire.ass.PredeviceRunner | tee "$report_dir/emulator-capture.txt"
python3 "$repo_dir/tools/instrumentation_report.py" "$report_dir/emulator-capture.txt" --evidence emulator-tested \
    --require-no-skips --output "$report_dir/emulator-capture-results.json"
