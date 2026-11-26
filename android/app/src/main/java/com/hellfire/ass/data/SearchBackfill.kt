package com.hellfire.ass.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.*
import org.json.JSONObject
import com.hellfire.ass.Diagnostics

/**
 * Builds the full-text index for events captured before it existed (or restored from a backup). New captures are
 * indexed as they are stored. Progress is saved after every batch, so an interrupted run continues where it stopped.
 */
object SearchBackfill {
    private const val PREFS = "search-index"
    private const val VERSION = 1
    private const val NAME = "search-backfill"

    fun schedule(context: Context) {
        if (context.getSharedPreferences(PREFS, 0).getInt("version", 0) == VERSION) return
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SearchBackfillWorker>().setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
    }

    /** After a restore the copied events have no index rows; rebuild from scratch. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().clear().commit()
        schedule(context)
    }

    suspend fun run(app: HellFireApp) = withContext(Dispatchers.IO) {
        val prefs = app.getSharedPreferences(PREFS, 0)
        if (prefs.getInt("version", 0) == VERSION) return@withContext
        val dao = app.database.events()
        var started = prefs.getLong("started", 0)
        if (started == 0L) {
            started = System.currentTimeMillis()
            dao.clearSearch()
            check(prefs.edit().putLong("started", started).putLong("at", -1).putString("id", "").commit())
        }
        var at = prefs.getLong("at", -1)
        var id = prefs.getString("id", "").orEmpty()
        while (true) {
            ensureActive()
            val batch = dao.olderThan(started, at, id, 200)
            if (batch.isEmpty()) break
            app.database.withTransaction {
                for (event in batch) {
                    if (!event.contentAvailable || event.securitySensitive || event.source == "owner_skill_use") continue
                    val text = runCatching {
                        SearchText.of(JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8)).getJSONObject("fields"))
                    }.onFailure { Diagnostics.warn(it); dao.failure("search_index", "EVENT_UNREADABLE") }.getOrNull() ?: continue
                    dao.insertSearch(SourceSearch(event.id, text))
                }
            }
            at = batch.last().receivedAt; id = batch.last().id
            prefs.edit().putLong("at", at).putString("id", id).apply()
        }
        check(prefs.edit().putInt("version", VERSION).commit())
    }
}

class SearchBackfillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SearchBackfill.run(applicationContext as HellFireApp); Result.success()
    } catch (e: CancellationException) { throw e }
    catch (caught: Exception) { Diagnostics.warn(caught); Result.retry() }
}
