package com.hellfire.ass.media

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.performance.DeviceProbe
import com.hellfire.ass.performance.ExecutionScheduler
import com.hellfire.ass.performance.JobBudget
import com.hellfire.ass.performance.OperatingProfile
import com.hellfire.ass.performance.PerfLog
import com.hellfire.ass.performance.ProcessingCheckpoint
import com.hellfire.ass.performance.SpeechPlan
import com.hellfire.ass.performance.WorkPriority
import com.hellfire.ass.processing.CaptureWork
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

object MediaWork {
    const val ANALYSIS = "media-analysis"
    suspend fun schedule(app: HellFireApp, id: String, followUp: Boolean = false) = withContext(Dispatchers.IO) {
        if (app.getSharedPreferences("media", 0).getBoolean("analysis_paused", false)) return@withContext
        WorkManager.getInstance(app).enqueueUniqueWork("analyze-media:$id", if (followUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaWorker>().setInputData(workDataOf("id" to id)).addTag(ANALYSIS)
