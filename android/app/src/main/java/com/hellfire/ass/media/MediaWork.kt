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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object MediaWork {
    const val ANALYSIS = "analyze-media"
    private const val TAG = ANALYSIS
    private val pumpLock = Mutex()
    const val OCR_CHECKPOINT = 1
    suspend fun schedule(app: HellFireApp, id: String, followUp: Boolean = false, delaySeconds: Long = 0) = withContext(Dispatchers.IO) {
        if (app.getSharedPreferences("media", 0).getBoolean("analysis_paused", false)) return@withContext
        WorkManager.getInstance(app).enqueueUniqueWork("analyze-media:$id", if (followUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaWorker>().setInputData(workDataOf("id" to id)).addTag(TAG)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(
                    app.getSharedPreferences("media", 0).getBoolean("charging", true)).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()).result.get()
        Unit
    }
    /** Schedules at most [JobBudget.MEDIA_PENDING_CAP] analysis jobs. The rest stay queued for the next pass. */
    suspend fun pump(app: HellFireApp) = withContext(Dispatchers.IO) { pumpLock.withLock {
        val manager = WorkManager.getInstance(app)
        val pending = manager.getWorkInfos(WorkQuery.Builder.fromTags(listOf(TAG))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)).build()).get().size
        var slots = JobBudget.slots(pending)
        for (item in app.database.media().unfinished().sortedBy { it.importedAt }) {
            if (slots <= 0) break
            val active = manager.getWorkInfosForUniqueWork("analyze-media:${item.id}").get()
                .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
            if (active) continue
            schedule(app, item.id)
            slots--
        }
    } }
    suspend fun queue(app: HellFireApp, id: String) {
        require(app.database.media().item(id)?.state != "DELETING")
        app.database.media().state(id, "QUEUED", null)
        pump(app)
    }
    suspend fun recover(app: HellFireApp) {
        app.media.recover()
        pump(app)
    }
    suspend fun text(app: HellFireApp, item: MediaItem, start: Long, end: Long, text: String, suffix: String): Unit = app.database.withTransaction {
        if (app.database.media().item(item.id)?.state == "DELETING") return@withTransaction
        val now = System.currentTimeMillis()
        val raw = NotificationSnapshot("${item.id}:$suffix:$start", "owner.media", (item.recordedAt ?: item.importedAt) + start, now,
            source = when (suffix) { "speech" -> "media_transcript"; "image_labels" -> "media_labels"; else -> "media_ocr" },
            fields = mapOf("text" to listOf(text.take(60000))), provenance = mapOf("media_id" to item.id,
                "start_ms" to start.toString(), "end_ms" to end.toString(), "index_kind" to suffix,
                "recorded_time_known" to (item.recordedAt != null).toString()))
        val safe = NotificationNormalizer.normalize(raw, app.packageName) ?: return@withTransaction
        app.events.capture(raw, app.packageName)
        val sanitized = safe.snapshot.fields["text"].orEmpty().joinToString("\n")
        app.database.media().segment(TranscriptSegment("${item.id}:$suffix:$start", item.id, safe.id, start, end, text = sanitized))
        // Scanned pages may carry an expiry date (passport, licence, insurance): propose a renewal task for the owner to accept.
        if (suffix == "image" || suffix == "pdf_page_zero_based") com.hellfire.ass.insights.ExpiryFinder.collect(app.database, safe.id, item.id, item.name, text, now)
    }
}

class MediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val item = app.database.media().item(id) ?: return@withContext Result.success()
        if (item.state == "DELETING") return@withContext Result.success()
        val signals = DeviceProbe.signals(app)
        val profile = OperatingProfile.of(signals)
        if (signals.thermal >= OperatingProfile.THERMAL_SEVERE || signals.lowMemory) {
            app.localModel.releaseCached(); app.speech.releaseCached(); app.semantic.releaseCached(); app.speakers.releaseCached()
            app.database.media().state(id, "QUEUED", if (signals.lowMemory) "WAITING_FOR_MEMORY" else "WAITING_FOR_DEVICE_TO_COOL")
            PerfLog.detail("media_wait", if (signals.lowMemory) "WAITING_FOR_MEMORY" else "WAITING_FOR_DEVICE_TO_COOL")
            return@withContext Result.retry()
        }
        try {
            app.database.media().state(id, "PROCESSING", null)
            var complete = true
            var yielded = false
            var progress = item.processedMs
            when {
                item.mime.startsWith("image/") -> VisualReader.image(app, item).let { read ->
                    MediaWork.text(app, item, 0, 0, read.ocr, "image")
                    read.labels?.let { MediaWork.text(app, item, 0, 0, it, "image_labels") }
                }
                item.mime == "application/pdf" -> {
                    complete = VisualReader.pdf(app, item, progress.toInt(), profile.pdfPages) { page, text ->
                        MediaWork.text(app, item, page.toLong(), page.toLong(), text, "pdf_page_zero_based")
                        progress = page + 1L
                    }
                }
                item.mime.startsWith("audio/") || item.mime.startsWith("video/") -> {
                    if (item.mime.startsWith("video/") && progress == 0L && !ProcessingCheckpoint.videoDone(app, id, requireNotNull(item.contentHash) { "MANAGED_CONTENT_HASH_REQUIRED" }, MediaWork.OCR_CHECKPOINT)) {
                        VisualReader.video(app, item) { at, text ->
                            MediaWork.text(app, item, at, at, text, "sampled_video_frame")
                        }
                        ProcessingCheckpoint.markVideo(app, id, requireNotNull(item.contentHash) { "MANAGED_CONTENT_HASH_REQUIRED" }, MediaWork.OCR_CHECKPOINT)
                    }
                    if (!app.speech.model.isFile) error("TRANSCRIPTION_MODEL_REQUIRED")
                    if (ExecutionScheduler.shouldYield(WorkPriority.DEFERRED)) {
                        complete = false
                        yielded = true
                        PerfLog.detail("media_wait", "YIELDED_FOR_INTERACTIVE")
                    } else {
                        val chunk = AudioDecoder.chunk(EncryptedMediaSource(app.media.reader(item)), progress, profile.audioChunkSeconds)
                        if (chunk.samples.isNotEmpty() && !SpeechPlan.needsTranscription(chunk.samples)) {
                            PerfLog.detail("audio_chunk", "SILENCE_SKIPPED")
                            progress += chunk.samples.size * 1000L / 16000
                            chunk.samples.fill(0f)
                        } else if (chunk.samples.isNotEmpty()) {
                            val result = try { app.speech.transcribe(chunk.samples, app.getSharedPreferences("media", 0).getString("language", "auto")!!) }
                                finally { chunk.samples.fill(0f) }
                            val segments = result.getJSONArray("segments")
                            for (index in 0 until segments.length()) {
                                val part = segments.getJSONObject(index)
                                MediaWork.text(app, item, progress + part.getLong("start_ms"), progress + part.getLong("end_ms"), part.getString("text"), "speech")
                            }
                            progress += chunk.samples.size * 1000L / 16000
                        }
                        complete = chunk.end || chunk.samples.isEmpty()
                    }
                }
                else -> error("UNSUPPORTED_MEDIA_FORMAT")
            }
            app.database.media().progress(id, progress)
            app.database.media().state(id, if (complete) "INDEXED" else "QUEUED", null)
            CaptureWork.process(app)
            if (!complete) MediaWork.schedule(app, id, true, if (yielded) 30 else 0) else MediaWork.pump(app)
            Result.success()
        } catch (e: CancellationException) {
            PerfLog.record("work_stop_reason", stopReason.toLong())
            withContext(NonCancellable) { if (app.database.media().item(id)?.state != "DELETING") app.database.media().state(id, "QUEUED", "PROCESSING_INTERRUPTED") }
            throw e
        } catch (error: Exception) {
            app.database.media().state(id, "ANALYSIS_FAILED", when (error.message) {
                "TRANSCRIPTION_MODEL_REQUIRED", "UNSUPPORTED_MEDIA_FORMAT", "NO_ACCESSIBLE_AUDIO_TRACK", "PDF_EXCEEDS_1000_PAGES" -> error.message
                else -> "LOCAL_DECODING_OR_MODEL_FAILED"
            })
            Result.failure()
        }
    }
}
