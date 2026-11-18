#!/usr/bin/env python3
"""Read-only hardware survey, with explicit opt-in isolated-app installation/tests."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
from instrumentation_report import parse
import subprocess


def run(adb, command, timeout=60):
    result = subprocess.run([*adb, *command], text=True, capture_output=True, timeout=timeout)
    return dict(command=command, exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Exact adb serial; no implicit device selection')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--output', type=Path, required=True, help='Keep device evidence outside version control')
    parser.add_argument('--install-fixtures', action='store_true', help='Install only .devicetest app and its instrumentation APK')
    parser.add_argument('--run-tests', action='store_true')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    adb = [args.adb, '-s', args.serial]
    state = run(adb, ['get-state'])
    if state['exit_code'] or state['stdout'].strip() != 'device':
        raise SystemExit('Selected device is not connected and authorized')
    properties = {}
    for name in ['ro.kernel.qemu', 'ro.product.manufacturer', 'ro.product.model', 'ro.build.version.sdk',
                 'ro.build.version.release', 'ro.build.version.security_patch', 'ro.product.cpu.abilist',
                 'ro.soc.manufacturer', 'ro.soc.model']:
        properties[name] = run(adb, ['shell', 'getprop', name])['stdout'].strip()
    emulator = properties['ro.kernel.qemu'] == '1'
    report = dict(schema=1, at=datetime.now(timezone.utc).isoformat(),
        evidence='emulator-tested' if emulator else 'physical-device-survey', properties=properties, checks=[])
    for command in [['shell', 'cat', '/proc/cpuinfo'], ['shell', 'dumpsys', 'thermalservice'],
                    ['shell', 'dumpsys', 'battery'], ['shell', 'pm', 'list', 'features']]:
        report['checks'].append(run(adb, command))
    if args.install_fixtures:
        if emulator:
            raise SystemExit('Use the .emulator build for x86; this installation path is ARM64 only')
        for relative in ['android/app/build/outputs/apk/deviceTest/app-deviceTest.apk',
                         'android/app/build/outputs/apk/androidTest/deviceTest/app-deviceTest-androidTest.apk']:
            apk = repo / relative
            if not apk.is_file():
                raise SystemExit(f'Build fixture APK first: {relative}')
            result = run(adb, ['install', '-r', str(apk)], timeout=180)
            report['checks'].append(result)
            if result['exit_code']:
                raise SystemExit('Fixture APK installation failed; no app data was cleared')
    if args.run_tests:
        result = run(adb, ['shell', 'am', 'instrument', '-r', '-w', '-e', 'notClass',
            'com.hellfire.ass.ComponentRenderingTest', 'com.hellfire.ass.devicetest.test/com.hellfire.ass.PredeviceRunner'], timeout=900)
        result['tests'] = parse(result['stdout'], 'emulator-tested' if emulator else 'physical-device-tested')
        result['passed'] = result['tests']['complete'] and result['exit_code'] == 0
        report['checks'].append(result)
    report['limitations'] = [
        'A hardware survey does not validate sustained inference, acceleration or security backing.',
        'No permissions, roles, app-ops, background exemptions or personal data are changed by this script.',
        'OEM gestures, hardware codecs, CPU/GPU/NPU model probes, Keystore backing, battery and thermal soak require the documented physical suite.',
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f"Saved {report['evidence']} to {args.output}")
    if args.run_tests and not report['checks'][-1]['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
