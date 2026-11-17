package com.hellfire.ass.news

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellfire.ass.HellFireApp
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object NewsWork {
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        manager.enqueueUniquePeriodicWork("news-ingest", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<NewsIngestWorker>(30, TimeUnit.MINUTES).setConstraints(connected).build())
        val charging = Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED).build()
        manager.enqueueUniquePeriodicWork("news-charge", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NewsIngestWorker>(6, TimeUnit.HOURS).setConstraints(charging).build())
        val delay = nextBriefingDelay(context)
        manager.enqueueUniqueWork("news-briefing", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NewsBriefingWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setConstraints(connected).build())
    }

    fun nextBriefingDelay(context: Context): Long {
        val settings = (context.applicationContext as HellFireApp).news.settings()
        val zone = runCatching { ZoneId.of(settings.zone) }.getOrDefault(ZoneId.of("Africa/Dar_es_Salaam"))
        val now = ZonedDateTime.now(zone)
        val times = listOf(settings.morning, settings.evening) + settings.extra
        val next = times.mapNotNull { raw -> runCatching { LocalTime.parse(raw) }.getOrNull() }
            .map { time -> now.toLocalDate().atTime(time).atZone(zone).let { if (!it.isAfter(now)) it.plusDays(1) else it } }
            .minByOrNull { it.toInstant() }
        val millis = next?.let { Duration.between(now, it).toMillis() } ?: 3_600_000L
        return millis.coerceIn(60_000L, 36 * 3_600_000L)
    }
}

class NewsIngestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val news = (applicationContext as HellFireApp).news
        if (!news.settings().enabled) return Result.success()
        return try { news.refresh(false); Result.success() } catch (_: Exception) { Result.retry() }
    }
}

class NewsBriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HellFireApp
        val settings = app.news.settings()
        if (settings.enabled) {
            val zone = runCatching { ZoneId.of(settings.zone) }.getOrDefault(ZoneId.of("Africa/Dar_es_Salaam"))
            val hour = ZonedDateTime.now(zone).hour
            val slot = if (hour < 12) "MORNING" else "EVENING"
            runCatching { app.news.refresh(false) }
            runCatching { app.news.briefing(slot) }
        }
        NewsWork.schedule(applicationContext)
        return Result.success()
    }
}
