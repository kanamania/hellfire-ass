package com.hellfire.ass

import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Debug-build-only failure logging for device testing. Records the exception class and stack frames,
 * never the exception message, which can carry message text, addresses or credentials.
 */
object Diagnostics {
    @Volatile var enabled = false
        private set

    fun init(app: android.app.Application) {
        enabled = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    fun warn(error: Throwable) {
        if (!enabled) return
        runCatching { Log.w("HellFire", describe(error)) }
    }

    /** Class names and frames only, following causes. Visible for tests. */
    fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            if (depth > 0) append("\ncaused by ")
            append(current.javaClass.name)
            current.stackTrace.take(12).forEach { append("\n  at ").append(it) }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }
}
