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
    # On a fresh emulator a folder that adb creates under Android/data belongs to the shell user and the app cannot
    # read it. Copy the models into the app's own files/models as the (debuggable) emulator app instead.
    for model in ggml-tiny.en-q5_1.bin silero_vad.onnx; do
        "$adb_bin" -s "$serial" push "$2/$model" "/data/local/tmp/$model"
        "$adb_bin" -s "$serial" shell run-as com.hellfire.ass.emulator sh -c "'mkdir -p files/models && cp /data/local/tmp/$model files/models/$model'"
        "$adb_bin" -s "$serial" shell rm -f "/data/local/tmp/$model"
    done
fi
report_dir="$repo_dir/android/build/reports/predevice"
mkdir -p "$report_dir"
"$adb_bin" -s "$serial" shell am instrument -r -w -e notClass com.hellfire.ass.CaptureIntegrationTest \
    com.hellfire.ass.emulator.test/com.hellfire.ass.PredeviceRunner | tee "$report_dir/emulator-instrumentation.txt"
report_args=()
if [[ $# -ge 2 ]]; then report_args+=(--require-no-skips); fi
python3 "$repo_dir/tools/instrumentation_report.py" "$report_dir/emulator-instrumentation.txt" \
    --evidence emulator-tested --output "$report_dir/emulator-results.json" "${report_args[@]}"
"$repo_dir/tools/test_capture_emulator.sh" "$serial"
python3 - "$report_dir" <<'PYREPORT'
import json,pathlib,sys
folder=pathlib.Path(sys.argv[1])
core=json.loads(folder.joinpath('emulator-results.json').read_text())
capture=json.loads(folder.joinpath('emulator-capture-results.json').read_text())
report=dict(evidence='emulator-tested',complete=core['complete'] and capture['complete'],suites=dict(core=core,capture=capture),
    capture_permission_evidence='ADB test grants on the owned fixture AVD; not user/OEM grant behavior')
for key in ['reported_tests','passed','failed','errors','ignored','assumptions','started']:
    report[key]=core[key]+capture[key] if core[key] is not None and capture[key] is not None else None
folder.joinpath('emulator-results.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
PYREPORT
