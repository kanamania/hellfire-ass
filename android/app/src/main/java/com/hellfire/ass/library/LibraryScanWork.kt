package com.hellfire.ass.library

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.media.MediaItem
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import com.hellfire.ass.Diagnostics

data class ItemStatus(val processing: String?, val sync: String, val backup: String)

object LibraryStatus {
    const val NOT_SELECTED = "Not selected for sync"
    const val SELECTED = "Selected for sync"
    const val INDEXED_ONLY = "Indexed only"
    const val NOT_BACKED_UP = "Not backed up"
    const val BACKED_UP = "Backed up"

    suspend fun load(app: HellFireApp, items: List<MediaItem>): Map<String, ItemStatus> {
        if (items.isEmpty()) return emptyMap()
        val ids = items.map { it.id }
        val jobs = app.database.jobs().forItems(ids).filter { it.state != JobState.COMPLETED.name }
            .groupBy { it.itemId }.mapValues { (_, rows) -> rows.minBy { it.priority }.state }
        val sync = app.database.sync().records("media_item", ids).associateBy { it.entityId }
        val backups = app.database.sync().verifiedBackups(ids).associateBy { it.itemId }
        return items.associate { item ->
            val syncLabel = when {
                !item.syncEnabled -> NOT_SELECTED
                sync[item.id] != null -> sync[item.id]!!.state
                else -> SELECTED
            }
            val backup = when {
                item.storage != StorageMode.MANAGED.name -> INDEXED_ONLY
                backups[item.id] != null -> BACKED_UP
                else -> NOT_BACKED_UP
            }
            item.id to ItemStatus(jobs[item.id], syncLabel, backup)
        }
    }
}

object LibraryScanWork {
    private const val PERIODIC = "library-scan"
    private const val NOW = "library-scan-now"
    private var watching = false
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LibraryScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun watch(app: HellFireApp) {
        if (watching) return
        watching = true
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { scheduleSoon(app) }
        }
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            .forEach { runCatching { app.contentResolver.registerContentObserver(it, true, observer) } }
    }

    private fun scheduleSoon(app: HellFireApp) {
        pending?.let { handler.removeCallbacks(it) }
        val task = Runnable { runCatching { runNow(app) } }
        pending = task
        handler.postDelayed(task, 2_000)
    }
}

class LibraryScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HellFireApp
        return try {
            LibraryIndex.scan(app)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (caught: Exception) {
            Diagnostics.warn(caught)
            Result.retry()
        }
    }
}
