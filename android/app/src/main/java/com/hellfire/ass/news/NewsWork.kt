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
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import com.hellfire.ass.HellFireApp
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object NewsWork {
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val settings = (context.applicationContext as HellFireApp).news.settings()
        if (!settings.enabled) {
            listOf("news-ingest", "news-charge", "news-briefing").forEach(manager::cancelUniqueWork)
            return
        }
        val connected = Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
        manager.enqueueUniquePeriodicWork("news-ingest", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<NewsIngestWorker>(30, TimeUnit.MINUTES).setConstraints(connected).build())
        val charging = Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED).build()
        manager.enqueueUniquePeriodicWork("news-charge", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NewsIngestWorker>(6, TimeUnit.HOURS).setConstraints(charging).build())
        val next = nextBriefing(settings, System.currentTimeMillis())
        manager.enqueueUniqueWork("news-briefing", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NewsBriefingWorker>()
                .setInitialDelay((next.at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("slot" to next.slot, "scheduledAt" to next.at)).build())
    }

    data class Scheduled(val slot: String, val at: Long)

    fun nextBriefing(settings: NewsSettings, at: Long): Scheduled {
        val zone = runCatching { ZoneId.of(settings.zone) }.getOrDefault(ZoneId.of("Africa/Dar_es_Salaam"))
        val now = java.time.Instant.ofEpochMilli(at).atZone(zone)
        val times = listOf("MORNING" to settings.morning, "EVENING" to settings.evening) + settings.extra.map { "CUSTOM" to it }
        return times.mapNotNull { (slot, raw) ->
            runCatching { LocalTime.parse(raw) }.getOrNull()?.let { time ->
                val next = now.toLocalDate().atTime(time).atZone(zone).let { if (!it.isAfter(now)) it.plusDays(1) else it }
                Scheduled(slot, next.toInstant().toEpochMilli())
            }
        }.minByOrNull { it.at } ?: Scheduled("MORNING", at + 3_600_000)
    }

    fun nextBriefingDelay(context: Context): Long {
        val now = System.currentTimeMillis()
        return nextBriefing((context.applicationContext as HellFireApp).news.settings(), now).at - now
    }
}

class NewsIngestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val news = (applicationContext as HellFireApp).news
        if (!news.settings().enabled) return Result.success()
        return try { news.refresh(false); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class NewsBriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HellFireApp
        val settings = app.news.settings()
        if (settings.enabled) {
            val slot = inputData.getString("slot") ?: "MORNING"
            val scheduledAt = inputData.getLong("scheduledAt", System.currentTimeMillis())
            try {
                try { app.news.refresh(false) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Saved news remains available offline. */ }
                val briefing = app.news.briefing(slot, scheduledAt)
                val count = JSONArray(briefing.itemsJson).length()
                if (count > 0 && !NewsAlerts.inQuiet(settings, System.currentTimeMillis())) {
                    AndroidNewsPoster(app).post(NewsNotice(NewsAlerts.BRIEFING, briefing.id, briefing.title,
                        "$count stories ready to read or listen to", "hellfire://news/briefing/${android.net.Uri.encode(briefing.id)}"))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return Result.retry() }
        }
        NewsWork.schedule(applicationContext)
        return Result.success()
    }
}
