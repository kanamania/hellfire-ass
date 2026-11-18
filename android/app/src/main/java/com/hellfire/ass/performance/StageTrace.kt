package com.hellfire.ass.performance

import android.os.SystemClock
import android.os.Trace

/** Synchronous stages only: a trace section must end on the thread where it began. Fixed labels, no content. */
internal inline fun <T> timedStage(name: String, block: () -> T): T {
    val started = SystemClock.elapsedRealtimeNanos()
    Trace.beginSection("hf.$name")
    var ok = false
    return try { block().also { ok = true } }
    finally {
        Trace.endSection()
        PerfLog.record("${name}_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
        PerfLog.record("${name}_ok", if (ok) 1 else 0)
    }
}
