#!/usr/bin/env python3
"""Summarize real APK contents or AndroidX benchmark JSON; never manufacture missing metrics."""
import argparse
import json
import math
import hashlib
from pathlib import Path
from zipfile import ZipFile


def percentiles(values):
    """Nearest-rank percentiles; retain counts so small runs cannot look like large samples."""
    ordered = sorted(values)
    if not ordered:
        return {"count": 0, "p50": None, "p95": None}
    return {"count": len(ordered), **{
        f"p{p}": ordered[math.ceil(p / 100 * len(ordered)) - 1] for p in (50, 95)
    }}


def apk_report(path):
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    with ZipFile(path) as apk:
        native = [entry for entry in apk.infolist() if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        return {
            "file": str(path), "sha256": digest, "apk_bytes": path.stat().st_size,
            "native_uncompressed_bytes": sum(entry.file_size for entry in native),
            "native_zip_bytes": sum(entry.compress_size for entry in native),
            "native_libraries": {entry.filename: entry.file_size for entry in sorted(native, key=lambda entry: entry.filename)},
        }


def numbers(value):
    if isinstance(value, list):
        for part in value:
            yield from numbers(part)
    elif isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value):
        yield value


def benchmark_report(data):
    results = []
    for benchmark in data.get("benchmarks", []):
        for group in ("metrics", "sampledMetrics"):
            for name, metric in benchmark.get(group, {}).items():
                results.append({
                    "class": benchmark.get("className"), "test": benchmark.get("name"),
                    "metric": name, "group": group,
                    "sample_kind": "iteration" if group == "metrics" else "pooled_samples",
                    **percentiles(list(numbers(metric.get("runs", [])))),
                })
    return {"source": "AndroidX benchmark JSON", "context": data.get("context", {}), "results": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("apk", "macrobenchmark"))
    parser.add_argument("files", type=Path, nargs="+")
    args = parser.parse_args()
    reports = [apk_report(path) if args.kind == "apk" else benchmark_report(json.loads(path.read_text())) for path in args.files]
    print(json.dumps(reports, indent=2))


if __name__ == "__main__":
    main()
