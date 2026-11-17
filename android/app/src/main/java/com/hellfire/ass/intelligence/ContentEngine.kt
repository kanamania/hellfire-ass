package com.hellfire.ass.intelligence

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.CategoryPolicy
import com.hellfire.ass.library.ContentKind
import com.hellfire.ass.library.ItemTag
import com.hellfire.ass.library.JobPriority
import com.hellfire.ass.library.LinkState
import com.hellfire.ass.library.MediaAnalysis
import com.hellfire.ass.library.MediaEmbedding
import com.hellfire.ass.library.MediaSegment
import com.hellfire.ass.library.Origin
import com.hellfire.ass.library.SegmentKind
import com.hellfire.ass.library.StorageMode
import com.hellfire.ass.media.AudioDecoder
import com.hellfire.ass.media.EncryptedMediaSource
import com.hellfire.ass.media.LocalImageLabels
import com.hellfire.ass.media.LocalOcr
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.MediaWork
import com.hellfire.ass.memory.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ContentEngine(private val app: HellFireApp) {
    private val adult = AdultClassifier(app)
    private val dao get() = app.database.library()

    suspend fun step(item: MediaItem, checkpoint: AnalysisCheckpoint): Pair<Boolean, AnalysisCheckpoint> = withContext(Dispatchers.IO) {
        val policy = strictestPolicy(item.id)
        if (policy == CategoryPolicy.SKIP_ANALYSIS) {
            save(item.id, "policy", "none", "1", "COMPLETED", JSONObject().put("policy", policy.name), "Analysis skipped by category policy.", null)
            return@withContext true to checkpoint.copy(phase = "done")
        }
        when (item.contentKind) {
            ContentKind.IMAGE.name -> true to picture(item)
            ContentKind.VIDEO.name -> video(item, checkpoint, policy)
            ContentKind.AUDIO.name -> audio(item, checkpoint, policy)
            ContentKind.TEXT.name, ContentKind.CODE.name, ContentKind.URL.name, ContentKind.DOCUMENT.name -> true to text(item)
            else -> {
                save(item.id, "route", "none", "1", "FAILED", JSONObject().put("kind", item.contentKind), "Unsupported content kind. Nothing was inferred.", null)
                true to checkpoint.copy(phase = "done")
            }
        }
    }

    private suspend fun picture(item: MediaItem): AnalysisCheckpoint {
        val bitmap = decode(item) ?: run {
            save(item.id, "image", "none", "1", "FAILED", JSONObject(), "Original image could not be decoded.", null)
            return AnalysisCheckpoint(phase = "done")
        }
        val observed = observe(bitmap)
        val now = System.currentTimeMillis()
        saveLabels(item, observed, now)
        adultResult(item, bitmap, now)
        bitmap.recycle()
        return AnalysisCheckpoint(phase = "done")
    }

    private suspend fun video(item: MediaItem, checkpoint: AnalysisCheckpoint, policy: CategoryPolicy): Pair<Boolean, AnalysisCheckpoint> {
        if (checkpoint.phase == "audio" || checkpoint.phase == "summary") return audio(item, checkpoint, policy)
        val duration = duration(item) ?: 0L
        val times = Sampling.schedule(duration, IntelligencePrefs(app).mode(), checkpoint.sceneChangesMs).filter { !AnalysisCheckpoint.skipFrame(checkpoint, it) }
        val batch = times.take(6)
        if (batch.isEmpty()) return audio(item, checkpoint.copy(phase = "audio", doneMs = duration), policy)
        var last = checkpoint.doneMs
        val frames = ArrayList<RgbFrame>()
        batch.forEach { at ->
            last = at + 1
            val bitmap = frame(item, at) ?: return@forEach
            val pixels = pixels(bitmap)
            frames += RgbFrame(at, pixels)
            val observed = observe(bitmap)
            val segmentId = UUID.randomUUID().toString()
            val thumb = saveThumb(segmentId, bitmap)
            val description = observedText(observed)
            dao.saveSegment(MediaSegment(segmentId, item.id, null, SegmentKind.SCENE.name, at, at, title = titleFrom(observed.labels, item.name),
                description = description, labels = JSONArray(observed.labels).toString(), thumbnailPath = thumb, confidence = observed.labels.firstOrNull()?.let { 0.6 }, createdAt = System.currentTimeMillis()))
            adultResult(item, bitmap, System.currentTimeMillis(), at)
            bitmap.recycle()
            last = at + 1
        }
        val boundaries = SceneMath.boundaries(frames)
        if (boundaries.isNotEmpty()) {
            save(item.id, "scenes", "histogram-luma", "1", "PARTIAL", JSONObject().put("boundariesMs", JSONArray(boundaries)),
                "Scene boundaries from colour-histogram and luma difference. ${boundaries.size} change(s) in this batch.", null)
        }
        val next = checkpoint.copy(doneMs = last, sceneChangesMs = (checkpoint.sceneChangesMs + boundaries).distinct().sorted())
        val more = Sampling.schedule(duration, IntelligencePrefs(app).mode(), next.sceneChangesMs).any { !AnalysisCheckpoint.skipFrame(next, it) }
        return if (more) false to next else audio(item, next.copy(phase = "audio"), policy)
    }

    private suspend fun audio(item: MediaItem, checkpoint: AnalysisCheckpoint, policy: CategoryPolicy): Pair<Boolean, AnalysisCheckpoint> {
        if (checkpoint.audioDoneMs == 0L) tagsAndTempo(item)
        if (policy == CategoryPolicy.SKIP_TRANSCRIPTION || !IntelligencePrefs(app).transcribe) {
            save(item.id, "transcript", "none", "1", "PARTIAL", JSONObject().put("skipped", true), "Transcription skipped by settings or category policy.", null)
            return true to checkpoint.copy(phase = "done")
        }
        if (!app.speech.model.isFile) {
            save(item.id, "transcript", "whisper.cpp", "absent", "PARTIAL", JSONObject(), "Transcription model not installed. Audio was not transcribed.", null)
            return true to checkpoint.copy(phase = "done")
        }
        val source = audioSource(item) ?: run {
            save(item.id, "transcript", "whisper.cpp", "1", "FAILED", JSONObject(), "No accessible audio track.", null)
            return true to checkpoint.copy(phase = "done")
        }
        val chunk = AudioDecoder.chunk(source, checkpoint.audioDoneMs, 30)
        if (chunk.samples.isEmpty()) return true to checkpoint.copy(phase = "done")
        val language = IntelligencePrefs(app).speechLanguage
        val result = try { app.speech.transcribe(chunk.samples, language) } finally { chunk.samples.fill(0f) }
        val segments = result.optJSONArray("segments") ?: JSONArray()
        val spoken = StringBuilder()
        for (index in 0 until segments.length()) {
            val part = segments.getJSONObject(index)
            val text = part.optString("text")
            if (text.isBlank()) continue
            val start = checkpoint.audioDoneMs + part.optLong("start_ms")
            val end = checkpoint.audioDoneMs + part.optLong("end_ms")
            MediaWork.text(app, item, start, end, text, "speech")
            spoken.append(text).append(' ')
        }
        val detected = listOf("language", "lang", "detected_language").firstNotNullOfOrNull { result.optString(it).takeIf { value -> value.isNotBlank() } }
            ?: "language not reported by the installed model"
        val evidence = spoken.toString().trim()
        val summary = if (evidence.isBlank()) null else draft(evidence, "List only action items and decisions that are explicit in the evidence. One line each.")
        save(item.id, "transcript_summary", languageModelId(), languageModelVersion(), if (summary == null) "PARTIAL" else "COMPLETED",
            JSONObject().put("language", detected).put("draftUsed", summary != null).put("imageInput", false),
            listOf("Language: $detected", evidence.take(1000), summary?.let { "Unverified draft from the transcript: $it" }.orEmpty(),
                "No words were added when the transcript was empty.").filter { it.isNotBlank() }.joinToString("\n"),
            null)
        val advanced = checkpoint.audioDoneMs + chunk.samples.size * 1000L / 16000
        val done = chunk.end
        return done to checkpoint.copy(audioDoneMs = advanced, phase = if (done) "done" else "audio")
    }

    private suspend fun text(item: MediaItem): AnalysisCheckpoint {
        val body = runCatching { app.media.readText(item, 200_000) }.getOrElse {
            save(item.id, "text", "none", "1", "FAILED", JSONObject(), "Text could not be read. Nothing was inferred.", null)
            return AnalysisCheckpoint(phase = "done")
        }
        val clipped = body.take(8_000)
        save(item.id, "text", "deterministic", "1", "COMPLETED", JSONObject().put("chars", body.length), clipped, null)
        embed(item.id, clipped, null, null)
        return AnalysisCheckpoint(phase = "done")
    }

    private suspend fun tagsAndTempo(item: MediaItem) {
        val tags = containerTags(item)
        val genre = tags.optString("genre")
        IntelligencePrefs(app).genres().filter { genre.contains(it, ignoreCase = true) }.forEach { name ->
            dao.suggestTag(ItemTag(item.id, name.lowercase(), Origin.AI.name, updatedAt = System.currentTimeMillis()))
        }
        save(item.id, "music_tags", "container-metadata", "1", "COMPLETED", tags,
            "Tags are from the file container, not from song recognition. Title, artist and album are blank when the file has no such tags.", null)
        val source = audioSource(item) ?: return
        val chunk = runCatching { AudioDecoder.chunk(source, 0, 8) }.getOrNull() ?: return
        val bpm = BpmEstimator.estimate(chunk.samples)
        chunk.samples.fill(0f)
        save(item.id, "bpm", "onset-autocorr", "1", if (bpm == null) "PARTIAL" else "COMPLETED",
            JSONObject().put("bpm", bpm ?: JSONObject.NULL),
            if (bpm == null) "BPM could not be estimated from the decoded audio." else "BPM estimate $bpm from onset-energy autocorrelation. This is not song recognition.",
            null)
    }

    private suspend fun saveLabels(item: MediaItem, observed: Observed, now: Long) {
        val evidence = observedText(observed)
        val draft = draft(evidence, "Reply with one short title using only words from the evidence.")
        val title = draft?.takeIf { QueryGrounding.accepts(it, evidence) } ?: titleFrom(observed.labels, item.name)
        save(item.id, "image_labels", "mlkit-image-labeler", "bundled", "COMPLETED",
            JSONObject().put("labels", JSONArray(observed.labels)).put("imageInputToLanguageModel", false),
            evidence, null)
        if (observed.ocr.isNotBlank()) save(item.id, "ocr", "mlkit-text-latin", "bundled", "COMPLETED", JSONObject().put("chars", observed.ocr.length), observed.ocr.take(8_000), null)
        save(item.id, "description", languageModelId(), languageModelVersion(), "COMPLETED",
            JSONObject().put("title", title).put("draftUsed", draft != null).put("limitation", "LocalModel.generate accepts a string prompt only. The image was not sent."),
            "$title\n$evidence", null)
        CategoryApply.suggest(dao, item.id, CategoryMap.from(observed.labels, observed.ocr), now)
        observed.labels.take(8).forEach { label -> dao.suggestTag(ItemTag(item.id, label.lowercase().take(40), Origin.AI.name, updatedAt = now)) }
        embed(item.id, evidence, null, null)
    }

    private suspend fun adultResult(item: MediaItem, bitmap: Bitmap, now: Long, atMs: Long? = null) {
        val verdict = adult.classify(bitmap)
        save(item.id, "adult", if (verdict.installed) "adult.onnx" else "none", if (verdict.installed) "user-imported" else "absent",
            "PARTIAL", JSONObject().put("label", verdict.label ?: JSONObject.NULL).put("verified", false).put("message", verdict.message).put("atMs", atMs ?: JSONObject.NULL),
            verdict.message, verdict.confidence)
        if (verdict.applied && verdict.label != null && (verdict.confidence ?: 0.0) >= 0.7) {
            CategoryApply.suggest(dao, item.id, listOf(CategorySuggestion("Adult", verdict.label, verdict.confidence ?: 0.7)), now)
        }
    }

    private suspend fun observe(bitmap: Bitmap): Observed {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val labels = runCatching { LocalImageLabels.read(software).map { it.first } }.getOrDefault(emptyList())
        val copy = software.copy(software.config ?: Bitmap.Config.ARGB_8888, false)
        val ocr = runCatching { LocalOcr.read(copy) }.getOrDefault("")
        if (software !== bitmap) software.recycle()
        return Observed(labels, ocr)
    }

    private fun observedText(observed: Observed): String {
        val parts = mutableListOf<String>()
        if (observed.labels.isNotEmpty()) parts += "Labels: ${observed.labels.take(10).joinToString(", ")}"
        if (observed.ocr.isNotBlank()) parts += "Visible text: ${observed.ocr.take(500)}"
        parts += "Participant count is not available from the installed labeler. No face identification was attempted."
        parts += "Description is from on-device labels and OCR. The installed language runtime accepts text only, so the image was not sent to it."
        return parts.joinToString("\n")
    }

    private suspend fun draft(evidence: String, ask: String): String? {
        if (!app.localModel.configured || evidence.isBlank()) return null
        val prompt = "Use only the evidence. Do not invent people, identities, locations, ages, consent, or events. If the evidence is insufficient, reply INSUFFICIENT.\n$ask\nEvidence:\n${evidence.take(3000)}"
        val text = runCatching { app.localModel.generate(prompt).text.trim() }.getOrNull() ?: return null
        if (text.equals("INSUFFICIENT", true) || !QueryGrounding.accepts(text, evidence)) return null
        return text.take(400)
    }

    private suspend fun save(mediaId: String, analyzer: String, modelId: String, modelVersion: String, state: String, raw: JSONObject, text: String, confidence: Double?) {
        val now = System.currentTimeMillis()
        dao.saveAnalysis(MediaAnalysis(UUID.randomUUID().toString(), mediaId, analyzer, modelId, modelVersion, PIPELINE, state, raw.toString(), text = text.take(8_000), confidence = confidence, createdAt = now))
    }

    private suspend fun embed(mediaId: String, text: String, startMs: Long?, endMs: Long?) {
        val model = app.semantic.embeddingModel() ?: return
        val vector = app.semantic.embedText(text) ?: return
        dao.insertEmbedding(MediaEmbedding(UUID.randomUUID().toString(), mediaId, model, startMs, endMs, VectorMath.encode(vector), System.currentTimeMillis()))
    }

    private suspend fun strictestPolicy(itemId: String): CategoryPolicy {
        val links = dao.itemCategories(itemId).first().filter { it.linkState == LinkState.ASSIGNED.name }
        val policies = links.mapNotNull { runCatching { CategoryPolicy.valueOf(it.category.policy) }.getOrNull() }
        return when {
            CategoryPolicy.SKIP_ANALYSIS in policies -> CategoryPolicy.SKIP_ANALYSIS
            CategoryPolicy.SKIP_TRANSCRIPTION in policies -> CategoryPolicy.SKIP_TRANSCRIPTION
            CategoryPolicy.LOCAL_ONLY in policies -> CategoryPolicy.LOCAL_ONLY
            else -> CategoryPolicy.DEFAULT
        }
    }

    private fun decode(item: MediaItem): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(item).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        open(item).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    private fun open(item: MediaItem) = if (item.storage == StorageMode.EXTERNAL.name)
        app.contentResolver.openInputStream(Uri.parse(item.externalUri)) ?: error("Original unavailable")
    else app.media.openStream(item)

    private fun frame(item: MediaItem, atMs: Long): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        val source = managedSource(item)
        try {
            if (source != null) retriever.setDataSource(source) else retriever.setDataSource(app, Uri.parse(item.externalUri))
            retriever.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                ?: retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            runCatching { retriever.release() }
            runCatching { source?.close() }
        }
    }.getOrNull()

    private fun duration(item: MediaItem): Long? = item.durationMs ?: runCatching {
        val retriever = MediaMetadataRetriever()
        val source = managedSource(item)
        try {
            if (source != null) retriever.setDataSource(source) else retriever.setDataSource(app, Uri.parse(item.externalUri))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            runCatching { retriever.release() }
            runCatching { source?.close() }
        }
    }.getOrNull()

    private fun containerTags(item: MediaItem): JSONObject = runCatching {
        val retriever = MediaMetadataRetriever()
        val source = managedSource(item)
        try {
            if (source != null) retriever.setDataSource(source) else retriever.setDataSource(app, Uri.parse(item.externalUri))
            JSONObject()
                .put("title", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "")
                .put("artist", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "")
                .put("album", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "")
                .put("genre", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "")
        } finally {
            runCatching { retriever.release() }
            runCatching { source?.close() }
        }
    }.getOrDefault(JSONObject())

    private fun managedSource(item: MediaItem): MediaDataSource? =
        if (item.storage == StorageMode.MANAGED.name) app.media.dataSource(item) else null

    private fun audioSource(item: MediaItem): MediaDataSource? = if (item.storage == StorageMode.MANAGED.name)
        EncryptedMediaSource(app.media.reader(item))
    else runCatching {
        val descriptor = app.contentResolver.openFileDescriptor(Uri.parse(item.externalUri), "r") ?: return null
        FdMediaSource(descriptor)
    }.getOrNull()

    private fun pixels(bitmap: Bitmap): IntArray {
        val max = 48
        val scale = maxOf(bitmap.width, bitmap.height).toDouble() / max
        val scaled = if (scale > 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width / scale).toInt().coerceAtLeast(1), (bitmap.height / scale).toInt().coerceAtLeast(1), true) else bitmap
        val out = IntArray(scaled.width * scaled.height)
        scaled.getPixels(out, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bitmap) scaled.recycle()
        return out
    }

    private fun saveThumb(id: String, bitmap: Bitmap): String {
        val dir = File(app.filesDir, "intelligence-thumbs").apply { mkdirs() }
        val file = File(dir, "$id.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        return "intelligence-thumbs/$id.jpg"
    }

    private fun titleFrom(labels: List<String>, name: String) = labels.firstOrNull { it.length in 2..40 } ?: name.substringBeforeLast('.').take(80)
    private fun languageModelId() = app.getSharedPreferences("model-catalog", 0).getString("installed-LANGUAGE", null) ?: if (app.localModel.configured) "imported-litertlm" else "not-installed"
    private fun languageModelVersion() = if (app.localModel.configured) app.localModel.modelFile.length().toString() else "absent"

    companion object {
        const val PIPELINE = 1
        const val JOB = "intelligence"
        fun newJob(itemId: String, now: Long) = com.hellfire.ass.library.ProcessingJob(
            UUID.randomUUID().toString(), JOB, itemId, priority = JobPriority.BACKGROUND, createdAt = now, updatedAt = now)
    }
}

private data class Observed(val labels: List<String>, val ocr: String)
