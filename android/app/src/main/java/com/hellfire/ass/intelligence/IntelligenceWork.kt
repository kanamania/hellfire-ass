package com.hellfire.ass.intelligence

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.ContentKind
import com.hellfire.ass.library.JobDao
import com.hellfire.ass.library.ProcessingJob
import com.hellfire.ass.library.StorageMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

object QueueStep {
    suspend fun run(jobs: JobDao, job: ProcessingJob, token: String, now: Long, work: suspend () -> Unit): String = try {
        work()
        "completed"
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        jobs.fail(job.id, (error.message ?: "failed").take(200), now + 60_000, 3, now)
        "failed"
    }
}

object IntelligenceWork {
    const val NAME = "intelligence-queue"

    fun constraints(prefs: IntelligencePrefs): Constraints {
        val builder = Constraints.Builder().setRequiresBatteryNotLow(true)
        if (prefs.storageNotLow) builder.setRequiresStorageNotLow(true)
        if (prefs.chargingOnly) builder.setRequiresCharging(true)
        if (prefs.idlePreference) builder.setRequiresDeviceIdle(true)
        return builder.build()
    }

    suspend fun schedule(context: Context) = withContext(Dispatchers.IO) {
        val prefs = IntelligencePrefs(context)
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request(prefs)).result.get()
        Unit
    }

    suspend fun recover(app: HellFireApp) {
        app.database.jobs().recoverType(ContentEngine.JOB)
        enqueueMissing(app, 10)
        schedule(app)
    }

    suspend fun enqueueMissing(app: HellFireApp, limit: Int) {
        val now = System.currentTimeMillis()
        val items = app.database.library().page(ContentKind.entries.map { it.name }, StorageMode.entries.map { it.name }, false, Long.MAX_VALUE, "", 40)
        var added = 0
        for (item in items) {
            if (app.database.jobs().find(ContentEngine.JOB, item.id) != null) continue
            app.database.jobs().enqueue(ContentEngine.newJob(item.id, now))
            added++
            if (added >= limit) break
        }
    }

    private fun request(prefs: IntelligencePrefs) = OneTimeWorkRequestBuilder<IntelligenceWorker>()
        .setConstraints(constraints(prefs))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    fun snapshot(context: Context): DeviceSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
        return DeviceSnapshot(
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            percent, thermal, context.filesDir.usableSpace < 256L * 1024 * 1024,
        )
    }
}

class IntelligenceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        val prefs = IntelligencePrefs(app)
        val blocked = DeviceGate.blockReason(prefs.policy(), IntelligenceWork.snapshot(app))
        if (blocked != null) return@withContext Result.retry()
        app.database.jobs().recoverType(ContentEngine.JOB)
        IntelligenceWork.enqueueMissing(app, 5)
        val engine = ContentEngine(app)
        var ran = 0
        var pending = false
        while (ran < prefs.maxConcurrent) {
            val token = UUID.randomUUID().toString()
            val job = app.database.jobs().claim(token, System.currentTimeMillis(), ContentEngine.JOB) ?: break
            val item = job.itemId?.let { app.database.library().item(it) }
            if (item == null) {
                app.database.jobs().complete(job.id, token, System.currentTimeMillis())
                continue
            }
            val outcome = QueueStep.run(app.database.jobs(), job, token, System.currentTimeMillis()) {
                val checkpoint = AnalysisCheckpoint.decode(job.checkpoint)
                val (finished, next) = engine.step(item, checkpoint)
                val now = System.currentTimeMillis()
                if (finished) app.database.jobs().complete(job.id, token, now)
                else {
                    app.database.jobs().checkpoint(job.id, token, com.hellfire.ass.library.JobState.PROCESSING.name, (next.doneMs.coerceAtLeast(1)).toDouble() / 1000.0, next.encode(), now)
                    app.database.jobs().requeue(job.id, job.priority, now)
                    pending = true
                }
            }
            if (outcome == "failed") pending = true
            ran++
        }
        if (pending) {
            WorkManager.getInstance(app).enqueueUniqueWork(IntelligenceWork.NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<IntelligenceWorker>().setConstraints(IntelligenceWork.constraints(prefs))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        Result.success()
    }
}
