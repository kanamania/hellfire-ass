package com.hellfire.ass.media

import android.content.Context
import android.os.PowerManager
import androidx.room.withTransaction
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.processing.CaptureWork
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

object MediaWork {
    suspend fun schedule(app: HellFireApp, id: String, followUp: Boolean = false) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(app).enqueueUniqueWork("analyze-media:$id", if (followUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaWorker>().setInputData(workDataOf("id" to id))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(
                    app.getSharedPreferences("media", 0).getBoolean("charging", true)).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()).result.get()
        Unit
    }
    suspend fun queue(app: HellFireApp, id: String) {
        require(app.database.media().item(id)?.state != "DELETING")
        app.database.media().state(id, "QUEUED", null)
        schedule(app, id)
    }
    suspend fun recover(app: HellFireApp) {
        app.media.recover()
        for (item in app.database.media().unfinished()) schedule(app, item.id)
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
    }
}

class MediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val item = app.database.media().item(id) ?: return@withContext Result.success()
        if (item.state == "DELETING") return@withContext Result.success()
        if (app.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            app.database.media().state(id, "QUEUED", "WAITING_FOR_DEVICE_TO_COOL")
            return@withContext Result.retry()
        }
        try {
            app.database.media().state(id, "PROCESSING", null)
            var complete = true
            var progress = item.processedMs
            when {
                item.mime.startsWith("image/") -> VisualReader.image(app, item).let { read ->
                    MediaWork.text(app, item, 0, 0, read.ocr, "image")
                    read.labels?.let { MediaWork.text(app, item, 0, 0, it, "image_labels") }
                }
                item.mime == "application/pdf" -> {
                    complete = VisualReader.pdf(app, item, progress.toInt()) { page, text ->
                        MediaWork.text(app, item, page.toLong(), page.toLong(), text, "pdf_page_zero_based")
                        progress = page + 1L
                    }
                }
                item.mime.startsWith("audio/") || item.mime.startsWith("video/") -> {
                    if (item.mime.startsWith("video/") && progress == 0L) VisualReader.video(app, item) { at, text ->
                        MediaWork.text(app, item, at, at, text, "sampled_video_frame")
                    }
                    if (!app.speech.model.isFile) error("TRANSCRIPTION_MODEL_REQUIRED")
                    val chunk = AudioDecoder.chunk(EncryptedMediaSource(app.media.reader(item)), progress)
                    if (chunk.samples.isNotEmpty()) {
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
                else -> error("UNSUPPORTED_MEDIA_FORMAT")
            }
            app.database.media().progress(id, progress)
            app.database.media().state(id, if (complete) "INDEXED" else "QUEUED", null)
            CaptureWork.process(app)
            if (!complete) MediaWork.schedule(app, id, true)
            Result.success()
        } catch (e: CancellationException) {
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
