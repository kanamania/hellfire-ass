package com.hellfire.ass.sms

import android.content.Context
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.processing.CaptureWork
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class SmsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        if (!SmsCaptureSettings.enabled(app)) return@withContext Result.success()
        try {
            if (!SmsCaptureSettings.canRead(app)) {
                app.database.events().failure("android_sms", "SMS_READ_PERMISSION_MISSING")
                return@withContext Result.success()
            }
            val requested = inputData.getLong("after_id", FRESH)
            val start = if (requested == FRESH) startAfter(SmsCaptureSettings.lastRow(app)) else requested
            val rows = SmsProviderReader.readPage(app.contentResolver, start)
            var captured = false
            for (row in rows) {
                ensureActive()
                if (!SmsCaptureSettings.enabled(app) || !SmsCaptureSettings.canRead(app)) return@withContext Result.success()
                captured = app.events.capture(row.snapshot(System.currentTimeMillis()), app.packageName) || captured
            }
            if (captured) CaptureWork.process(app)
            rows.lastOrNull()?.let { SmsCaptureSettings.saveLastRow(app, it.rowId) }
            // A continuation's input is persisted by WorkManager before this page completes.
            if (rows.size == SmsProviderReader.PAGE_SIZE && SmsCaptureSettings.enabled(app)) schedule(app, rows.last().rowId)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) {
            app.database.events().failure("android_sms", "SMS_PROVIDER_ACCESS_DENIED")
            Result.success()
        } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught);
            runCatching { app.database.events().failure("android_sms", "SMS_IMPORT_FAILED") }
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "sms-reconciliation"
        private const val FRESH = -1L
        /**
         * Rows re-read before the last imported one. An outbox message keeps its provider row ID when it becomes
         * sent, so a fresh trigger looks back this far; captured rows are deduplicated by their stable identity.
         */
        const val LOOK_BACK = 200L
        fun startAfter(lastRow: Long): Long = (lastRow - LOOK_BACK).coerceAtLeast(0)

        suspend fun schedule(context: Context, afterId: Long = FRESH) = withContext(Dispatchers.IO) {
            // Coalesce fresh triggers; append only page continuations. Periodic reconciliation
            // recovers provider changes that race the final page's completion.
            val policy = if (afterId == FRESH) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy,
                OneTimeWorkRequestBuilder<SmsSyncWorker>().setInputData(workDataOf("after_id" to afterId))
                    .setInitialDelay(2, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()).result.get()
            Unit
        }
    }
}
