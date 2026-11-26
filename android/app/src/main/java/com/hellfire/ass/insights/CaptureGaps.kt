package com.hellfire.ass.insights

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.MainActivity
import com.hellfire.ass.R
import com.hellfire.ass.notifications.HellFireNotificationListener
import com.hellfire.ass.widgets.SourceStatus
import com.hellfire.ass.widgets.loadCaptureSources

/** One capture source that has stopped, or looks like it has. */
data class CaptureGap(val source: String, val message: String)

object CaptureGaps {
    const val NOTIFICATION_SILENCE = 12 * 3_600_000L
    const val SMS_LAG = 30 * 60_000L
    const val SYNC_STALE = 6 * 3_600_000L
    private const val RENOTIFY = 24 * 3_600_000L
    private const val CHANNEL = "capture-health"

    private fun hours(ms: Long) = (ms / 3_600_000L).coerceAtLeast(1).let { if (it == 1L) "1 hour" else "$it hours" }

    /**
     * [notificationsAt] and [smsCapturedAt] are when HellFire last saved one; [smsOnPhoneAt] is the newest SMS in the
     * phone's own store. A source that is switched off or never captured anything is not reported as silent.
     */
    fun find(now: Long, sources: List<SourceStatus>, notificationsAt: Long?, smsOnPhoneAt: Long?, smsCapturedAt: Long?): List<CaptureGap> {
        val gaps = sources.filter { !it.working }.map { CaptureGap(it.name, when (it.name) {
            "Notifications" -> "Notification access is off"
            "SMS" -> "SMS permission is off"
            else -> "${it.name} is not connecting"
        }) }.toMutableList()
        val working = sources.filter { it.working }
        if (working.any { it.name == "Notifications" } && notificationsAt != null && now - notificationsAt > NOTIFICATION_SILENCE)
            gaps += CaptureGap("Notifications", "No notification saved for ${hours(now - notificationsAt)}. The phone may have stopped the listener")
        if (working.any { it.name == "SMS" } && smsOnPhoneAt != null && now - smsOnPhoneAt > SMS_LAG && smsOnPhoneAt - (smsCapturedAt ?: 0) > SMS_LAG)
            gaps += CaptureGap("SMS", "An SMS that arrived ${hours(now - smsOnPhoneAt)} ago has not been saved")
        for (source in working) if (source.syncedAt != null && now - source.syncedAt > SYNC_STALE)
            gaps += CaptureGap(source.name, "${source.name} has not synced for ${hours(now - source.syncedAt)}")
        return gaps
    }

    private fun newestSmsOnPhone(app: HellFireApp): Long? = runCatching {
        app.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE),
            "${Telephony.Sms.TYPE} IN (?, ?)", arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
            "${Telephony.Sms.DATE} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    suspend fun check(app: HellFireApp, now: Long = System.currentTimeMillis()): List<CaptureGap> {
        val sources = loadCaptureSources(app)
        val audits = app.database.events().auditsNow().associateBy { it.source }
        val smsWorking = sources.any { it.name == "SMS" && it.working }
        return find(now, sources, audits["android_notification"]?.lastCapturedAt, if (smsWorking) newestSmsOnPhone(app) else null, audits["android_sms"]?.lastCapturedAt)
    }

    /**
     * Tells the owner once when capture breaks and again each day it stays broken; clears the alert when it recovers.
     * A silent notification listener is also asked to reconnect.
     */
    private const val DAILY_CAP = 3
    fun report(app: HellFireApp, gaps: List<CaptureGap>, now: Long = System.currentTimeMillis()) {
        val prefs = app.getSharedPreferences("capture_gaps", 0)
        val manager = app.getSystemService(NotificationManager::class.java)
        if (gaps.isEmpty()) { manager.cancel("capture-gap", 73); prefs.edit().clear().apply(); return }
        if (gaps.any { it.source == "Notifications" && it.message.startsWith("No notification") })
            runCatching { NotificationListenerService.requestRebind(ComponentName(app, HellFireNotificationListener::class.java)) }
        // No alerts while the owner is still granting access in first-run setup.
        if (!app.getSharedPreferences("ui", 0).getBoolean("setup_done", false)) return
        if (!app.getSharedPreferences("capture", 0).getBoolean("gap_alerts", true)) return
        val signature = gaps.map { it.source }.sorted().joinToString("|")
        if (signature == prefs.getString("signature", null) && now - prefs.getLong("at", 0) < RENOTIFY) return
        // A source that keeps flapping must not alert all day or overnight; the Needs attention page still shows it.
        if (!Interruptions.allow(app, "capture_gap", DAILY_CAP, now)) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Capture problems", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 73, Intent(app, MainActivity::class.java).setData(Uri.parse("hellfire://settings/permissions")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify("capture-gap", 73, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_hellfire)
            .setContentTitle("HellFire Ass is missing some of your activity")
            .setContentText(gaps.first().message + if (gaps.size > 1) " · and ${gaps.size - 1} more" else "")
            .setStyle(Notification.BigTextStyle().bigText(gaps.joinToString("\n") { it.message }))
            .setContentIntent(open).setAutoCancel(true).build())
        prefs.edit().putString("signature", signature).putLong("at", now).apply()
        Interruptions.record(app, "capture_gap", now)
    }
}
