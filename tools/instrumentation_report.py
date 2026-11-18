#!/usr/bin/env python3
"""Parse AndroidJUnitRunner `am instrument -r -w` output without counting skips as passes."""
import argparse
import json
from pathlib import Path
import re


def parse(text, evidence):
    codes = [int(code) for code in re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$', text, re.M)]
    counts = dict(passed=codes.count(0), failed=codes.count(-2), errors=codes.count(-1),
                  ignored=codes.count(-3), assumptions=codes.count(-4), started=codes.count(1))
    summary = re.search(r'OK \((\d+) tests?\)', text)
    complete = bool(summary) and not counts['failed'] and not counts['errors'] and 'INSTRUMENTATION_FAILED' not in text
    if counts['passed'] + counts['ignored'] + counts['assumptions'] == 0:
        complete = False
    return dict(evidence=evidence, complete=complete, reported_tests=int(summary.group(1)) if summary else None, **counts)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--evidence', choices=['emulator-tested', 'physical-device-tested'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--require-no-skips', action='store_true')
    args = parser.parse_args()
    report = parse(args.input.read_text(), args.evidence)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
    if not report['complete'] or (args.require_no_skips and (report['ignored'] or report['assumptions'])):
        raise SystemExit(1)
