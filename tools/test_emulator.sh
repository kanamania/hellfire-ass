#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
serial="${1:?Usage: tools/test_emulator.sh emulator-SERIAL [PINNED_MODEL_DIRECTORY]}"
adb_bin="${ANDROID_HOME:-/home/dev/android-sdk}/platform-tools/adb"
[[ "$serial" == emulator-* ]] || { echo 'This script installs only on an emulator' >&2; exit 2; }
[[ $("$adb_bin" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Selected target is not an emulator' >&2; exit 2; }
"$adb_bin" -s "$serial" install -r "$repo_dir/android/app/build/outputs/apk/emulator/app-emulator.apk"
"$adb_bin" -s "$serial" install -r "$repo_dir/android/app/build/outputs/apk/androidTest/emulator/app-emulator-androidTest.apk"
if [[ $# -ge 2 ]]; then
    "$adb_bin" -s "$serial" shell mkdir -p /sdcard/Android/data/com.hellfire.ass.emulator/files/models
    "$adb_bin" -s "$serial" push "$2/ggml-tiny.en-q5_1.bin" /sdcard/Android/data/com.hellfire.ass.emulator/files/models/
    "$adb_bin" -s "$serial" push "$2/silero_vad.onnx" /sdcard/Android/data/com.hellfire.ass.emulator/files/models/
fi
report_dir="$repo_dir/android/build/reports/predevice"
mkdir -p "$report_dir"
"$adb_bin" -s "$serial" shell am instrument -r -w \
    com.hellfire.ass.emulator.test/com.hellfire.ass.PredeviceRunner | tee "$report_dir/emulator-instrumentation.txt"
report_args=()
if [[ $# -ge 2 ]]; then report_args+=(--require-no-skips); fi
python3 "$repo_dir/tools/instrumentation_report.py" "$report_dir/emulator-instrumentation.txt" \
    --evidence emulator-tested --output "$report_dir/emulator-results.json" "${report_args[@]}"
