package com.hellfire.ass.processing

import android.content.Context
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.sms.SmsCaptureSettings
import com.hellfire.ass.sms.SmsSyncWorker
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

object CaptureWork {
    suspend fun process(context: Context) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).enqueueUniqueWork("process-events", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ProcessEventsWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()).result.get()
        Unit
    }

    suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("capture-maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CaptureMaintenanceWorker>(15, TimeUnit.MINUTES).build()).result.get()
        process(context)
        Unit
    }
}

class ProcessEventsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        try {
            EventProcessor(app.database.events(), app.finances::collect, { id, time, text, outgoing -> com.hellfire.ass.tasks.TaskScheduler.collect(app, id, time, text, outgoing) }) { app.cipher }.drain()
            // Indexing is best-effort and bounded; maintenance runs continue any backlog.
            try { app.semantic.indexPending() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class CaptureMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            com.hellfire.ass.tasks.TaskScheduler.restore(applicationContext as HellFireApp)
            CaptureWork.process(applicationContext)
            com.hellfire.ass.communications.CommunicationWork.schedule(applicationContext)
            if (SmsCaptureSettings.enabled(applicationContext)) SmsSyncWorker.schedule(applicationContext)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
