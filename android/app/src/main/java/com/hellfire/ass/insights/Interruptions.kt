package com.hellfire.ass.insights

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    /** Each "Not useful" in the last 30 days halves that kind's daily cap, down to one a day. */
    fun cap(dailyCap: Int, notUseful: Int): Int = (dailyCap shr notUseful.coerceAtMost(30)).coerceAtLeast(1)
    private const val FEEDBACK_WINDOW = 30L * 86_400_000

    private fun feedback(app: Context, kind: String, now: Long): List<Long> =
        app.getSharedPreferences("interruption_feedback", 0).getString(kind, "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull() }.filter { now - it in 0..FEEDBACK_WINDOW }

    fun allow(app: Context, kind: String, dailyCap: Int, now: Long = System.currentTimeMillis()): Boolean {
        val quiet = TaskAlerts.quietUntil(now, app.getSharedPreferences("capture", 0).getBoolean("quiet_hours", true)) != null
        val sent = app.getSharedPreferences("interruptions", 0).getInt("$kind:${day(now)}", 0)
        return allowed(sent, cap(dailyCap, feedback(app, kind, now).size), quiet)
    }

    fun notUseful(app: Context, kind: String, now: Long = System.currentTimeMillis()) {
        val kept = (feedback(app, kind, now) + now).takeLast(10)
        app.getSharedPreferences("interruption_feedback", 0).edit().putString(kind, kept.joinToString(",")).apply()
    }

    /** A "Not useful" button for a notification posted as ([tag], [id]); tapping it dismisses the notice too. */
    fun notUsefulIntent(context: Context, kind: String, tag: String, id: Int): PendingIntent = PendingIntent.getBroadcast(context, 77,
        Intent(context, NotUsefulReceiver::class.java).setAction(NotUsefulReceiver.ACTION)
            .setData(Uri.parse("hellfire://not-useful/$kind?tag=${Uri.encode(tag)}&id=$id")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun record(app: Context, kind: String, now: Long = System.currentTimeMillis()) {
        val prefs = app.getSharedPreferences("interruptions", 0)
        val today = day(now)
        prefs.edit().apply {
            prefs.all.keys.filter { !it.endsWith(":$today") }.forEach(::remove)
            putInt("$kind:$today", prefs.getInt("$kind:$today", 0) + 1)
        }.apply()
    }
}

/** "Not useful" tapped on a HellFire notice. Not exported: only this app's PendingIntents reach it. */
class NotUsefulReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val data = intent.data ?: return
        val kind = data.lastPathSegment?.takeIf { it in KINDS } ?: return
        Interruptions.notUseful(context, kind)
        val tag = data.getQueryParameter("tag")
        val id = data.getQueryParameter("id")?.toIntOrNull() ?: return
        context.getSystemService(NotificationManager::class.java)?.cancel(tag, id)
    }
    companion object {
        const val ACTION = "com.hellfire.ass.NOT_USEFUL"
        val KINDS = setOf("capture_gap", "news_update")
    }
}
