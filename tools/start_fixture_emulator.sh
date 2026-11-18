#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
sdk_dir="${ANDROID_HOME:-/home/dev/android-sdk}"
fixture_root="${XDG_CACHE_HOME:-$HOME/.cache}/hellfire-ass/predevice/avd"
export ANDROID_AVD_HOME="$fixture_root"
mkdir -p "$fixture_root"
serial=emulator-5580
if "$sdk_dir/platform-tools/adb" -s "$serial" get-state >/dev/null 2>&1; then
    echo 'Port 5580 is already occupied; no existing emulator was changed.' >&2
    exit 1
fi
if [[ ! -f "$fixture_root/hellfire_fixture_api35.avd/config.ini" ]]; then
    printf 'no\n' | "$sdk_dir/cmdline-tools/latest/bin/avdmanager" create avd \
        --name hellfire_fixture_api35 --package 'system-images;android-35;google_apis;x86_64' \
        --device pixel_5 --path "$fixture_root/hellfire_fixture_api35.avd"
fi
report_dir="$repo_dir/android/build/reports/predevice"
mkdir -p "$report_dir"
nohup "$sdk_dir/emulator/emulator" -avd hellfire_fixture_api35 -port 5580 \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 1536 -cores 2 \
    > "$report_dir/emulator-start.log" 2>&1 < /dev/null &
fixture_pid=$!
echo "$fixture_pid" > "$report_dir/emulator.pid"
for attempt in {1..180}; do
    kill -0 "$fixture_pid" || { echo 'Fixture emulator exited; inspect emulator-start.log' >&2; exit 1; }
    if [[ $("$sdk_dir/platform-tools/adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
        echo "Ready: $serial (isolated API 35 fixture AVD)"
        exit 0
    fi
    sleep 2
done
echo 'Fixture boot exceeded six minutes; inspect emulator-start.log. No personal AVD was used.' >&2
exit 1
