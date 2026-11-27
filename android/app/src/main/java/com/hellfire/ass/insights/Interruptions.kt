package com.hellfire.ass.insights

import android.content.Context
import com.hellfire.ass.tasks.TaskAlerts
import com.hellfire.ass.tasks.TaskTime
import java.time.Instant

/**
 * One gate for notifications HellFire raises on its own (not owner alarms): the shared quiet hours (22:00–06:30 EAT,
 * Settings › Reminders) and a daily cap per kind. Due commitments never pass through here; they always ring.
 */
object Interruptions {
    /** Pure decision, for tests. */
    fun allowed(sentToday: Int, dailyCap: Int, quiet: Boolean): Boolean = !quiet && sentToday < dailyCap

    private fun day(now: Long) = Instant.ofEpochMilli(now).atZone(TaskTime.zone).toLocalDate().toString()

    fun allow(app: Context, kind: String, dailyCap: Int, now: Long = System.currentTimeMillis()): Boolean {
        val quiet = TaskAlerts.quietUntil(now, app.getSharedPreferences("capture", 0).getBoolean("quiet_hours", true)) != null
        val sent = app.getSharedPreferences("interruptions", 0).getInt("$kind:${day(now)}", 0)
        return allowed(sent, dailyCap, quiet)
    }

    fun record(app: Context, kind: String, now: Long = System.currentTimeMillis()) {
        val prefs = app.getSharedPreferences("interruptions", 0)
        val today = day(now)
        prefs.edit().apply {
            prefs.all.keys.filter { !it.endsWith(":$today") }.forEach(::remove)
            putInt("$kind:$today", prefs.getInt("$kind:$today", 0) + 1)
        }.apply()
    }
}
