#!/usr/bin/env python3
"""Synthetic host SQLite query/materialization benchmark, NOT Android/Room/SQLCipher timing."""
import json
import platform
import sqlite3
import time
import tracemalloc
from pathlib import Path
from performance_report import percentiles

ROOT = Path(__file__).resolve().parents[1]


def measure(db, sql, args):
    for _ in range(5):
        db.execute(sql, args).fetchall()
    elapsed = []
    for _ in range(30):
        started = time.perf_counter_ns()
        rows = db.execute(sql, args).fetchall()
        elapsed.append((time.perf_counter_ns() - started) / 1_000_000)
    tracemalloc.start()
    rows = db.execute(sql, args).fetchall()
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    return {
        "elapsed_ms": percentiles(elapsed), "rows_materialized": len(rows),
        "python_peak_bytes": peak,
        "plan": [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + sql, args)],
    }


def main():
    schema = json.loads((ROOT / "android/app/schemas/com.hellfire.ass.data.EventDatabase/19.json").read_text())
    source = (ROOT / "android/app/src/main/java/com/hellfire/ass/media/MediaData.kt").read_text()
    # Read the actual DAO queries rather than benchmarking a different proposed query.
    import re
    old = re.search(r'@Query\("([^"\n]+)"\) suspend fun unfinished\(', source)[1]
    new = re.search(r'@Query\("([^"\n]+)"\) suspend fun unfinishedIds\(', source)[1]
    with sqlite3.connect(":memory:") as db:
        for entity in schema["database"]["entities"]:
            if entity["tableName"] not in ("source_event", "media_item"):
                continue
            db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity["indices"]:
                db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        db.executemany("INSERT INTO media_item (id,name,mime,kind,size,importedAt,processedMs,state,summary,summaryState,originalMetadata) VALUES (?,?, 'video/mp4','VIDEO',1000,?,0,'QUEUED',?,'NONE',?)",
            [(f"m{i:05}", f"Synthetic {i}", i, "synthetic summary " * 30, '{"synthetic":true}') for i in range(10_000)])
        excluded = [f"m{i:05}" for i in range(10)]
        new = new.replace(":activeIds", ",".join("?" for _ in excluded)).replace(":limit", "?")
        args = (*excluded, 20)
        before = measure(db, old, ())
        after = measure(db, new, args)
        all_rows = db.execute(old).fetchall()
        expected = [row[0] for row in sorted(all_rows, key=lambda row: (row[7], row[0])) if row[0] not in excluded][:20]
        assert expected == [row[0] for row in db.execute(new, args)]
        print(json.dumps({
            "kind": "synthetic host SQLite query + Python row materialization",
            "host": platform.machine(), "python": platform.python_version(), "sqlite": sqlite3.sqlite_version,
            "workload_rows": 10_000, "excluded_active": 10, "requested_ids": 20,
            "before": before, "after": after,
            "limit": "No Android, ART, Room, SQLCipher, WorkManager, battery or native inference measured. Old Kotlin sorting and scheduler calls excluded; Python allocation is not app PSS.",
        }, indent=2))


if __name__ == "__main__":
    main()
