package com.hellfire.ass.processing

import android.content.Context
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.sms.SmsCaptureSettings
import com.hellfire.ass.sms.SmsSyncWorker
import com.hellfire.ass.memory.SemanticIndexWork
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.communications.CommunicationWork
import com.hellfire.ass.insights.CaptureGaps
import com.hellfire.ass.media.MediaWork
import com.hellfire.ass.tasks.TaskScheduler

object CaptureWork {
    private val scheduling = Mutex()

    suspend fun process(context: Context) = withContext(Dispatchers.IO) {
        scheduling.withLock {
            val manager = WorkManager.getInstance(context)
            val queued = WorkQuery.Builder.fromUniqueWorkNames(listOf("process-events"))
                .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)).build()
            // Keep one queued drain. A running drain needs a successor in case it
            // has already read its last empty batch when a new event commits.
            if (manager.getWorkInfos(queued).get().isEmpty()) {
                manager.enqueueUniqueWork("process-events", ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<ProcessEventsWorker>()
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()).result.get()
            }
        }
        Unit
    }

    suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("capture-maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CaptureMaintenanceWorker>(15, TimeUnit.MINUTES).build()).result.get()
        process(context)
        if ((context.applicationContext as HellFireApp).semantic.configured) SemanticIndexWork.schedule(context)
        Unit
    }
}

class ProcessEventsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        try {
            EventProcessor(app.database.events(), app.finances::collect, { id, time, text, outgoing -> TaskScheduler.collect(app, id, time, text, outgoing) }) { app.cipher }.drain()
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (caught: Exception) { Diagnostics.warn(caught); Result.retry() }
    }
}

class CaptureMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            TaskScheduler.restore(applicationContext as HellFireApp)
            CaptureWork.process(applicationContext)
            CommunicationWork.schedule(applicationContext)
            if (SmsCaptureSettings.enabled(applicationContext)) SmsSyncWorker.schedule(applicationContext)
            // A failed health check must not stop the maintenance above from counting as done.
            runCatching { MediaWork.pump(applicationContext as HellFireApp) }
                .onFailure { if (it is CancellationException) throw it; Diagnostics.warn(it) }
            runCatching { (applicationContext as HellFireApp).let { app -> CaptureGaps.report(app, CaptureGaps.check(app)) } }
                .onFailure { if (it is CancellationException) throw it; Diagnostics.warn(it) }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (caught: Exception) { Diagnostics.warn(caught); Result.retry() }
    }
}
