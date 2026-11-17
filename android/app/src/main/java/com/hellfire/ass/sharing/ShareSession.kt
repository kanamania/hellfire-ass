package com.hellfire.ass.sharing

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.ExportState
import com.hellfire.ass.library.ExportVerification
import com.hellfire.ass.library.FindingStatus
import com.hellfire.ass.library.LinkState
import com.hellfire.ass.library.PrivacyFinding
import com.hellfire.ass.library.ShareExport
import com.hellfire.ass.library.ShareProfile
import com.hellfire.ass.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PrepareResult(val ok: Boolean, val message: String, val text: String, val files: List<File>, val mime: String, val exportIds: List<String>)

private data class Step(val ok: Boolean, val message: String, val findings: List<MarkerHit>, val mime: String?)

object ShareSession {
    const val CONTENT_NOTE = "Visible or audible content can still identify people, places and devices. This copy is not anonymous."
    const val DETECTORS = "Face, license-plate, QR and identity-document detection are not available. No face, plate or barcode model is installed. Suggestions come only from text that matches a phone, email or address pattern."
    const val TRACKING = "Video region tracking is not implemented. A selected video region is a static cover for the whole clip."

    suspend fun load(app: HellFireApp, itemId: String, startMs: Long?, endMs: Long?): ShareFacts? = withContext(Dispatchers.IO) {
        val item = app.database.library().item(itemId) ?: return@withContext null
        val range = ClipRanges.valid(startMs, endMs, item.durationMs)
        val analyses = app.database.library().analyses(itemId).first().map { row ->
            val raw = ShareTemplates.fieldsFrom(row.rawJson)
            val edited = row.editedJson?.let { ShareTemplates.fieldsFrom(it) }.orEmpty()
            AnalysisFact(row.analyzer, row.text, edited.isNotEmpty(), raw + edited)
        }
        val segments = app.database.library().segments(itemId).first().map { SegmentFact(it.startMs, it.endMs, it.title, it.description) }
        val transcript = app.database.media().transcript(itemId).map { TranscriptFact(it.startMs, it.endMs, it.text) }
        val links = app.database.library().itemCategories(itemId).first()
        val categories = links.filter { !it.category.isPrivate && it.linkState == LinkState.ASSIGNED.name }.map { it.category.name }
        val tags = app.database.library().tags(itemId).first().filter { it.state == LinkState.ASSIGNED.name }.map { it.tag }
        val bookmarks = app.database.playback().bookmarks(itemId, false).first().map { BookmarkFact(it.startMs, it.endMs, it.title) }
        ShareFacts(item.contentKind, item.durationMs, item.width, item.height, range, analyses, segments, transcript, categories, tags, bookmarks,
            item.summary.takeIf { range == null }.orEmpty(), links.count { it.category.isPrivate && it.linkState == LinkState.ASSIGNED.name })
    }

    suspend fun suggest(app: HellFireApp, facts: ShareFacts, style: CaptionStyle): String = withContext(Dispatchers.IO) {
        if (!app.localModel.configured) return@withContext "Local model is not installed. Captions stay on the stored-fact template."
        val prompt = "Draft a ${style.name.lowercase()} caption using only the stored facts below. Do not add people, places, times or events that are not listed. If a fact is missing, omit it.\n" +
            ShareTemplates.fromFacts(facts, CaptionStyle.FACTUAL).caption
        runCatching { app.localModel.generate(prompt.take(8000)).text.take(2000) }.getOrElse { "Local model did not answer. The stored-fact template is unchanged." }
    }

    suspend fun textBoxes(bitmap: Bitmap): List<SensitiveText.Suggestion> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            val width = bitmap.width.coerceAtLeast(1)
            val height = bitmap.height.coerceAtLeast(1)
            return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val kind = SensitiveText.kind(line.text) ?: return@mapNotNull null
                SensitiveText.Suggestion(kind, line.text.take(80),
                    (box.left * 1000 / width).coerceIn(0, 999),
                    (box.top * 1000 / height).coerceIn(0, 999),
                    (box.right * 1000 / width).coerceIn(1, 1000),
                    (box.bottom * 1000 / height).coerceIn(1, 1000))
            }
        } finally {
            recognizer.close()
        }
    }

    fun rect(suggestion: SensitiveText.Suggestion) =
        RedactRect(suggestion.left / 1000f, suggestion.top / 1000f, suggestion.right / 1000f, suggestion.bottom / 1000f)

    suspend fun prepare(
        app: HellFireApp,
        context: Context,
        primaryId: String,
        extraIds: List<String>,
        approved: ApprovedShare,
        rects: List<RedactRect>,
        mode: RedactMode,
        muteRangesMs: List<Pair<Long, Long>>,
    ): PrepareResult = withContext(Dispatchers.IO) {
        val assembled = MetadataExport.assemble(approved)
        val ids = (listOf(primaryId) + extraIds).distinct()
        val items = ids.mapNotNull { app.database.library().item(it) }
        if (items.size != ids.size) return@withContext PrepareResult(false, "A selected item is no longer available. Nothing was shared.", assembled.text, emptyList(), "", emptyList())
        cleanup(app)
        val dir = File(context.cacheDir, "share/${UUID.randomUUID()}").apply { mkdirs() }
        val files = mutableListOf<File>()
        val exportIds = mutableListOf<String>()
        var mime = ""
        items.forEachIndexed { index, item ->
            val plan = ShareFormats.plan(item.mime, item.contentKind)
            if (!plan.supported) {
                discard(app, exportIds, files)
                record(app, item, approved, assembled, null, plan.mime, false, plan.note, emptyList())
                return@withContext PrepareResult(false, plan.note + " Nothing was shared.", assembled.text, emptyList(), "", emptyList())
            }
            if (index > 0 && plan.kind != "image") {
                discard(app, exportIds, files)
                return@withContext PrepareResult(false, "Only extra images can be added to a multi-file share. Nothing was shared.", assembled.text, emptyList(), "", emptyList())
            }
            val output = File(dir, neutralName(plan, index, items.size))
            val step = try {
                app.media.withPlainCopy(item) { plain -> exportOne(context, plain, output, plan, index, approved, rects, mode, muteRangesMs) }
            } catch (error: Exception) {
                com.hellfire.ass.Diagnostics.warn(error)
                output.delete()
                discard(app, exportIds, files)
                val message = when (error) {
                    is SecurityException -> "Media read permission is not granted. Nothing was shared."
                    is java.io.FileNotFoundException -> "The original could not be opened. Nothing was shared."
                    else -> "Export failed. The original was not changed. Nothing was shared."
                }
                record(app, item, approved, assembled, null, plan.mime, false, message, emptyList())
                return@withContext PrepareResult(false, message, assembled.text, emptyList(), "", emptyList())
            }
            if (!step.ok || !output.isFile) {
                output.delete()
                discard(app, exportIds, files)
                record(app, item, approved, assembled, null, plan.mime, false, step.message, step.findings)
                return@withContext PrepareResult(false, step.message, assembled.text, emptyList(), "", emptyList())
            }
            val writtenMime = step.mime ?: plan.mime
            exportIds += record(app, item, approved, assembled, output, writtenMime, true, step.message, step.findings)
            files += output
            mime = when {
                mime.isEmpty() -> writtenMime
                mime == writtenMime -> mime
                mime.startsWith("image/") && writtenMime.startsWith("image/") -> "image/*"
                else -> "*/*"
            }
        }
        if (approved.profile == ShareProfile.MEDIA_JSON_SIDECAR && assembled.sidecarJson != null) {
            val sidecar = File(dir, "metadata.json")
            sidecar.writeText(assembled.sidecarJson)
            files += sidecar
            mime = "*/*"
        }
        PrepareResult(true, "Sanitized copy is ready to review. $CONTENT_NOTE HellFire Ass has not published anything.", assembled.text, files, mime.ifBlank { "*/*" }, exportIds)
    }

    /** Only expired export sessions are removed; recent recipients may still be reading their copy. */
    suspend fun cleanup(app: HellFireApp, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val root = File(app.cacheDir, "share")
        root.listFiles()?.filter { now - it.lastModified() > 24 * 60 * 60_000L }?.forEach { it.deleteRecursively() }
        app.database.shares().staleOutputs(now - 24 * 60 * 60_000L).forEach { row ->
            app.database.shares().saveExport(row.copy(outputPath = null, updatedAt = now))
        }
    }

    suspend fun markHandedOff(app: HellFireApp, exportIds: List<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        exportIds.forEach { id ->
            val row = app.database.shares().export(id) ?: return@forEach
            app.database.shares().saveExport(row.copy(state = ExportState.HANDED_OFF.name, handedOffAt = now, updatedAt = now))
        }
    }

    private suspend fun exportOne(context: Context, plain: File, output: File, plan: ShareFormats.Plan, index: Int, approved: ApprovedShare, rects: List<RedactRect>, mode: RedactMode, muteRangesMs: List<Pair<Long, Long>>): Step {
        check(plain.absolutePath != output.absolutePath)
        return if (plan.kind == "image") {
            val result = ImageSanitizer.sanitize(plain, output, plan.mime, if (index == 0) rects else emptyList(), mode)
            Step(result.ok, result.message, result.findings, result.mime)
        } else {
            val result = ContainerExport.export(context, plain, output, plan.kind == "audio", approved.range?.startMs, approved.range?.endMs,
                if (index == 0) muteRangesMs else emptyList(), if (index == 0 && plan.kind == "video") rects else emptyList())
            Step(result.ok, result.message, result.findings, if (result.ok) plan.mime else null)
        }
    }

    private suspend fun discard(app: HellFireApp, exportIds: List<String>, files: List<File>) {
        files.forEach { it.delete() }
        val now = System.currentTimeMillis()
        exportIds.forEach { id ->
            val row = app.database.shares().export(id) ?: return@forEach
            row.outputPath?.let { File(it).delete() }
            app.database.shares().saveExport(row.copy(state = ExportState.FAILED.name, verification = ExportVerification.FAILED.name,
                outputPath = null, error = "Another file in this share failed verification", updatedAt = now))
        }
    }

    private suspend fun record(app: HellFireApp, item: MediaItem, approved: ApprovedShare, assembled: AssembledExport, output: File?, mime: String, ok: Boolean, message: String, hits: List<MarkerHit>): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        app.database.shares().saveExport(ShareExport(
            id, item.id, approved.range?.startMs, approved.range?.endMs, approved.profile.name, approved.style.name,
            assembled.text, if (approved.profile == ShareProfile.MEDIA_JSON_SIDECAR) assembled.sidecarJson else null,
            if (ok) output?.absolutePath else null, if (ok) mime else null, if (ok) output?.length() else null,
            if (ok && output != null) sha256(output) else null, if (ok) ExportState.READY.name else ExportState.FAILED.name,
            if (ok) ExportVerification.VERIFIED.name else ExportVerification.FAILED.name, if (ok) null else message.take(300), now, now,
        ))
        val findings = hits.map { hit ->
            PrivacyFinding(UUID.randomUUID().toString(), id, hit.field.take(80), hit.container.take(40),
                if (ok) FindingStatus.REMOVED.name else FindingStatus.STILL_PRESENT.name, hit.detail.take(120))
        } + PrivacyFinding(UUID.randomUUID().toString(), id, "visible-or-audible-content", "PIXELS", FindingStatus.UNSUPPORTED.name, CONTENT_NOTE)
        app.database.shares().saveFindings(findings)
        return id
    }

    private fun neutralName(plan: ShareFormats.Plan, index: Int, count: Int): String {
        val stem = when (plan.kind) { "image" -> "image"; "audio" -> "audio"; "video" -> "video"; else -> "file" }
        return "$stem${if (count > 1 && plan.kind == "image") "-${index + 1}" else ""}.${plan.extension}"
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
