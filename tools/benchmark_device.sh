#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
serial="${1:?Usage: benchmark_device.sh SERIAL NEW_RESULTS_DIRECTORY [startup|profile|compare]}"
output_dir="${2:?Choose a new private results directory}"
mode="${3:-startup}"
adb_bin="${ANDROID_HOME:-/home/dev/android-sdk}/platform-tools/adb"
[[ $("$adb_bin" -s "$serial" get-state) == device ]] || exit 1
[[ $("$adb_bin" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') != 1 ]] || { echo 'Physical-device benchmark required; emulator numbers are not accepted.' >&2; exit 2; }
[[ $("$adb_bin" -s "$serial" shell settings get global airplane_mode_on | tr -d '\r') == 1 ]] || { echo 'Enable airplane mode before the isolated offline benchmark to prevent automatic downloads.' >&2; exit 2; }
[[ ! -e "$output_dir" ]] || { echo 'Choose a new directory so earlier evidence is preserved.' >&2; exit 2; }
variant=benchmark
case "$mode" in
    startup) test_class=com.hellfire.ass.macrobenchmark.StartupBenchmark ;;
    profile) variant=profile; test_class=com.hellfire.ass.macrobenchmark.BaselineProfileGenerator ;;
    compare) test_class=com.hellfire.ass.macrobenchmark.ProfileStartupBenchmark ;;
    *) echo 'Mode must be startup, profile or compare' >&2; exit 2 ;;
esac
app_apk="$repo_dir/android/app/build/outputs/apk/$variant/app-$variant.apk"
test_apk="$repo_dir/android/benchmark/build/outputs/apk/$variant/benchmark-$variant.apk"
[[ -f "$app_apk" && -f "$test_apk" ]] || { echo "Build :app:assemble${variant^} :benchmark:assemble${variant^} first." >&2; exit 2; }
mkdir -p "$output_dir"
python3 "$repo_dir/tools/check_native_apk.py" "$app_apk" --abi arm64-v8a --output "$output_dir/apk.json"
"$adb_bin" -s "$serial" install -r "$app_apk"
"$adb_bin" -s "$serial" install -r "$test_apk"
python3 "$repo_dir/tools/oneplus_bringup.py" --adb "$adb_bin" --serial "$serial" --output "$output_dir/before.json"
# The benchmark's UI precondition fails explicitly until the owner finishes the disposable copy's English setup.
remote_dir="/sdcard/Android/media/com.hellfire.ass.macrobenchmark/hf-$mode-$(date -u +%Y%m%dT%H%M%SZ)"
"$adb_bin" -s "$serial" shell am instrument -r -w -e class "$test_class" \
    -e additionalTestOutputDir "$remote_dir" \
    com.hellfire.ass.macrobenchmark/androidx.test.runner.AndroidJUnitRunner | tee "$output_dir/instrumentation.txt"
"$adb_bin" -s "$serial" pull "$remote_dir" "$output_dir/raw"
python3 "$repo_dir/tools/oneplus_bringup.py" --adb "$adb_bin" --serial "$serial" --output "$output_dir/after.json"
python3 "$repo_dir/tools/instrumentation_report.py" "$output_dir/instrumentation.txt" \
    --evidence physical-device-tested --require-no-skips --output "$output_dir/tests.json"
python3 - "$repo_dir" "$output_dir" "$mode" <<'PY'
import json,pathlib,sys
sys.path.insert(0,str(pathlib.Path(sys.argv[1])/'tools'))
from performance_report import benchmark_report
folder=pathlib.Path(sys.argv[2])
reports=[benchmark_report(json.loads(path.read_text())) for path in folder.joinpath('raw').rglob('*-benchmarkData.json')]
if sys.argv[3] != 'profile' and not reports: raise SystemExit('No actual benchmark JSON was collected')
folder.joinpath('percentiles.json').write_text(json.dumps(reports,indent=2)+'\n')
print('Saved actual benchmark JSON, traces, hardware snapshots and available percentiles; no missing metric was invented.')
PY
