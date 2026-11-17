package com.hellfire.ass.news

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import com.hellfire.ass.MainActivity
import com.hellfire.ass.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** One notification to post. [tag] groups per event so an update replaces its earlier alert. */
data class NewsNotice(val channel: String, val tag: String, val title: String, val text: String, val link: String)

/** Posts a notice; returns false when notifications are not permitted (nothing is posted). */
fun interface NewsPoster { fun post(notice: NewsNotice): Boolean }

/** [quiet]: quiet hours suppress it (the event is marked handled; the briefing still carries it). */
data class AlertDecision(val notice: NewsNotice, val quiet: Boolean)

object NewsAlerts {
    const val BREAKING = "news-breaking"
    const val UPDATES = "news-updates"
    const val BRIEFING = "news-briefing"
    private const val HOUR = 3_600_000L
    private val material = setOf("MAJOR", "CORRECTION", "RESOLVED")

    /** Breaking: CRITICAL (or HIGH and protected public interest) first seen within 3 h, never alerted before. Update: a followed,
     * bookmarked or delivered-critical event whose latest version is MAJOR/CORRECTION/RESOLVED and newer than the last alert. */
    fun decide(event: NewsEvent, state: NewsEventState?, latest: NewsEventVersion?, settings: NewsSettings, now: Long): AlertDecision? {
        if (event.mergedInto != null) return null
        val critical = event.level == "CRITICAL"
        val protectedCritical = critical && event.protectedPublic
        if (state?.hidden == true && !protectedCritical) return null
        val link = "hellfire://news/story/${event.id}"
        val notice = if (settings.breakingAlerts && event.breakingNotifiedVersion == 0 && (critical || (event.level == "HIGH" && event.protectedPublic)) &&
            now - event.firstSeenAt in 0..3 * HOUR) {
            NewsNotice(BREAKING, event.id, "Breaking: ${event.headline}".take(200), describe(event), link)
        } else {
            val interested = state?.followed == true || state?.bookmarked == true || (critical && (state?.deliveredVersion ?: 0) > 0)
            if (!settings.updateAlerts || !interested || latest == null || latest.version <= 1 || latest.classification !in material ||
                latest.version <= event.breakingNotifiedVersion) return null
            NewsNotice(UPDATES, event.id, "Update: ${latest.whatsNew.ifBlank { latest.classification.lowercase() }}".take(200), event.headline, link)
        }
        val quiet = inQuiet(settings, now) && !(protectedCritical && settings.criticalOverridesQuiet)
        return AlertDecision(notice, quiet)
    }

    fun describe(event: NewsEvent): String {
        val sources = if (event.independentSources == 1) "1 source" else "${event.independentSources} independent sources"
        val check = when (event.verification) { "CONFIRMED" -> "confirmed"; "CONFLICTING" -> "reports conflict"; "UNVERIFIED_SOCIAL" -> "unverified social posts"; else -> "single source" }
        return "${event.level.lowercase().replaceFirstChar { it.uppercase() }} · $sources · $check"
    }

    fun inQuiet(settings: NewsSettings, now: Long): Boolean {
        val zone = runCatching { ZoneId.of(settings.zone) }.getOrDefault(ZoneId.of("Africa/Dar_es_Salaam"))
        val start = runCatching { LocalTime.parse(settings.quietStart) }.getOrNull() ?: return false
        val end = runCatching { LocalTime.parse(settings.quietEnd) }.getOrNull() ?: return false
        val time = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
        return when {
            start == end -> false
            start < end -> time >= start && time < end
            else -> time >= start || time < end
        }
    }

    fun channels(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel(BREAKING, "Breaking news", NotificationManager.IMPORTANCE_HIGH)
            .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        manager.createNotificationChannel(NotificationChannel(UPDATES, "Story updates", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        manager.createNotificationChannel(NotificationChannel(BRIEFING, "News briefings", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
    }

    /** Shown while an expedited briefing worker runs as a foreground job (Android 11 and lower). */
    fun foregroundInfo(context: Context): androidx.work.ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        channels(manager)
        val notification = Notification.Builder(context, BRIEFING).setSmallIcon(R.drawable.ic_hellfire)
            .setContentTitle("Preparing your news briefing").setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).build()
        return androidx.work.ForegroundInfo(82, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

class AndroidNewsPoster(private val context: Context) : NewsPoster {
    override fun post(notice: NewsNotice): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        NewsAlerts.channels(manager)
        if (manager.getNotificationChannel(notice.channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, notice.tag.hashCode(), Intent(context, MainActivity::class.java).setData(Uri.parse(notice.link)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(notice.tag, if (notice.channel == NewsAlerts.BRIEFING) 80 else 81, Notification.Builder(context, notice.channel)
            .setSmallIcon(R.drawable.ic_hellfire).setContentTitle(notice.title).setContentText(notice.text)
            .setStyle(Notification.BigTextStyle().bigText(notice.text)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open).setAutoCancel(true).build())
        return true
    }
}
