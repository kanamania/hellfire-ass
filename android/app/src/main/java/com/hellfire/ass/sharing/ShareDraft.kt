package com.hellfire.ass.sharing

import com.hellfire.ass.library.ContentKind
import com.hellfire.ass.library.ShareProfile
import org.json.JSONArray
import org.json.JSONObject

enum class CaptionStyle { SHORT, DETAILED, CASUAL, PROFESSIONAL, SOCIAL, TECHNICAL, FACTUAL }

data class ClipRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    fun contains(atMs: Long) = atMs in startMs until endMs
    fun overlaps(start: Long, end: Long) = start < endMs && end > startMs
}

data class TimestampNote(val startMs: Long, val endMs: Long, val label: String)

data class AnalysisFact(val analyzer: String, val text: String, val edited: Boolean, val fields: Map<String, List<String>>)

data class SegmentFact(val startMs: Long, val endMs: Long, val title: String, val description: String)

data class TranscriptFact(val startMs: Long, val endMs: Long, val text: String)

data class BookmarkFact(val startMs: Long, val endMs: Long?, val title: String)

data class ShareFacts(
    val contentKind: String,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val range: ClipRange?,
    val analyses: List<AnalysisFact>,
    val segments: List<SegmentFact>,
    val transcript: List<TranscriptFact>,
    val categories: List<String>,
    val tags: List<String>,
    val bookmarks: List<BookmarkFact>,
    val storedSummary: String,
    val omittedPrivateCategories: Int,
)

data class ShareDraft(
    val title: String,
    val shortSummary: String,
    val detailedSummary: String,
    val caption: String,
    val categories: List<String>,
    val tags: List<String>,
    val hashtags: List<String>,
    val timestamps: List<TimestampNote>,
    val transcriptExcerpts: List<String>,
    val notice: String,
    val source: String,
)

data class ApprovedShare(
    val title: String,
    val shortSummary: String,
    val detailedSummary: String,
    val caption: String,
    val categories: List<String>,
    val tags: List<String>,
    val hashtags: List<String>,
    val timestamps: List<TimestampNote>,
    val transcriptExcerpts: List<String>,
    val style: CaptionStyle,
    val profile: ShareProfile,
    val range: ClipRange?,
    val includeTranscript: Boolean,
    val source: String,
)

data class AssembledExport(val text: String, val sidecarJson: String?)

object ClipRanges {
    fun valid(startMs: Long?, endMs: Long?, durationMs: Long? = null): ClipRange? {
        if (startMs == null || endMs == null) return null
        if (startMs < 0 || endMs <= startMs) return null
        if (durationMs != null && startMs >= durationMs) return null
        val end = if (durationMs != null) minOf(endMs, durationMs) else endMs
        return if (end > startMs) ClipRange(startMs, end) else null
    }

    fun fromBookmark(startMs: Long, endMs: Long?, durationMs: Long? = null) = valid(startMs, endMs, durationMs)

    fun fromLoop(startMs: Long?, endMs: Long?, durationMs: Long? = null) = valid(startMs, endMs, durationMs)

    fun overlaps(range: ClipRange?, startMs: Long, endMs: Long) = range == null || range.overlaps(startMs, endMs)

    /** Mute and excerpt times relative to the exported clip. Whole-file shares stay absolute. */
    fun clipRelative(range: ClipRange?, startMs: Long, endMs: Long): Pair<Long, Long>? {
        if (endMs <= startMs) return null
        if (range == null) return startMs to endMs
        val start = maxOf(startMs, range.startMs) - range.startMs
        val end = minOf(endMs, range.endMs) - range.startMs
        return if (end > start) start to end else null
    }
}

object SensitiveText {
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phone = Regex("(?<![A-Z0-9])(?:\\+?\\d[\\d\\s().-]{7,}\\d)")
    private val address = Regex("(?i)\\b(?:street|st\\.|road|rd\\.|avenue|ave\\.|plot|p\\.o\\.? box|po box|mtaa|s\\.l\\.p)\\b")

    fun kind(text: String): String? = when {
        email.containsMatchIn(text) -> "email"
        phone.containsMatchIn(text) && text.count { it.isDigit() } >= 9 -> "phone"
        address.containsMatchIn(text) -> "address"
        else -> null
    }

    data class Suggestion(val kind: String, val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun suggest(text: String, left: Int, top: Int, right: Int, bottom: Int): Suggestion? {
        val kind = kind(text) ?: return null
        if (right <= left || bottom <= top) return null
        return Suggestion(kind, text.take(80), left, top, right, bottom)
    }
}

object ShareFormats {
    data class Plan(val mime: String, val extension: String, val kind: String, val note: String, val supported: Boolean)

    fun plan(mime: String, contentKind: String): Plan {
        val type = mime.substringBefore(';').trim().lowercase()
        return when {
            type == "image/heic" || type == "image/heif" -> Plan("image/jpeg", "jpg", "image", "HEIC/HEIF is re-encoded as JPEG.", true)
            type == "image/gif" -> Plan("image/png", "png", "image", "Only the first GIF frame is exported.", true)
            type == "image/png" -> Plan("image/png", "png", "image", "Re-encoded PNG.", true)
            type == "image/webp" -> Plan("image/webp", "webp", "image", "Re-encoded WebP, or JPEG if this Android version cannot write WebP.", true)
            type.startsWith("image/") -> Plan("image/jpeg", "jpg", "image", "Re-encoded JPEG.", true)
            contentKind == ContentKind.IMAGE.name -> Plan("image/jpeg", "jpg", "image", "Re-encoded JPEG.", true)
            type.startsWith("audio/") || contentKind == ContentKind.AUDIO.name || type == "application/ogg" ->
                Plan("audio/mp4", "m4a", "audio", "Re-encoded AAC in an M4A container. AMR, OGG and other inputs are transcoded or reported unsupported.", true)
            type.startsWith("video/") || contentKind == ContentKind.VIDEO.name ->
                Plan("video/mp4", "mp4", "video", "Re-encoded H.264/AAC MP4. MKV and other containers are transcoded or reported unsupported.", true)
            else -> Plan(type.ifBlank { "application/octet-stream" }, "bin", "unsupported", "This type cannot be sanitized for sharing.", false)
        }
    }
}

object ShareTemplates {
    const val STORED = "stored-facts"
    const val MODEL = "local-model"
    const val STORED_NOTICE = "Template built only from stored facts. It is not a new observation."
    const val EMPTY_NOTICE = "No stored analysis, categories, tags, scenes or transcript for this range. Nothing was invented."
    const val MODEL_NOTICE = "Local model suggestion. Review it before export. It is not verified."

    fun fromFacts(facts: ShareFacts, style: CaptionStyle): ShareDraft {
        val segments = facts.segments.filter { ClipRanges.overlaps(facts.range, it.startMs, it.endMs) }
        val transcript = facts.transcript.filter { ClipRanges.overlaps(facts.range, it.startMs, it.endMs) }
        val bookmarks = facts.bookmarks.filter { ClipRanges.overlaps(facts.range, it.startMs, it.endMs ?: it.startMs + 1) }
        val fields = mergedFields(facts.analyses)
        val title = first(fields["title"]) ?: segments.map { it.title }.firstOrNull { it.isNotBlank() } ?: bookmarks.map { it.title }.firstOrNull { it.isNotBlank() } ?: ""
        val shortSummary = first(fields["shortSummary"]) ?: first(fields["summary"]) ?: facts.storedSummary.takeIf { facts.range == null }.orEmpty()
            .ifBlank { segments.map { it.description }.firstOrNull { it.isNotBlank() }.orEmpty() }
        val detailed = listOfNotNull(
            first(fields["detailedSummary"]),
            first(fields["description"]),
            facts.analyses.map { it.text }.firstOrNull { it.isNotBlank() }?.takeIf { facts.range == null },
            segments.joinToString(" ") { listOf(it.title, it.description).filter { part -> part.isNotBlank() }.joinToString(" ") }.ifBlank { null },
        ).distinct().joinToString("\n")
        val timestamps = (segments.map { TimestampNote(it.startMs, it.endMs, it.title.ifBlank { it.description }.take(80)) } +
            bookmarks.mapNotNull { mark -> mark.endMs?.let { TimestampNote(mark.startMs, it, mark.title.take(80)) } })
            .distinctBy { "${it.startMs}:${it.endMs}:${it.label}" }
        val excerpts = transcript.map { it.text.trim() }.filter { it.isNotEmpty() }.map { it.take(240) }.take(8)
        val hashtags = hashtags(facts.categories, facts.tags)
        val caption = caption(style, title, shortSummary, detailed, facts, timestamps, hashtags, segments)
        val notice = if (caption.isBlank() && title.isBlank() && shortSummary.isBlank() && facts.categories.isEmpty()) EMPTY_NOTICE else STORED_NOTICE
        return ShareDraft(title, shortSummary.take(500), detailed.take(2000), caption, facts.categories, facts.tags, hashtags, timestamps, excerpts, notice, STORED)
    }

    fun hashtags(categories: List<String>, tags: List<String>) = (tags + categories).map { slug(it) }.filter { it.length >= 2 }.distinct().take(12).map { "#$it" }

    fun slug(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "").take(40)

    private fun caption(style: CaptionStyle, title: String, short: String, detailed: String, facts: ShareFacts, times: List<TimestampNote>, hashtags: List<String>, segments: List<SegmentFact>): String {
        val timeText = times.joinToString("; ") { "${format(it.startMs)}–${format(it.endMs)} ${it.label}".trim() }
        val cats = facts.categories.joinToString(", ")
        return when (style) {
            CaptionStyle.SHORT -> listOf(title, short).filter { it.isNotBlank() }.joinToString(" — ").take(160)
            CaptionStyle.DETAILED -> listOf(title, detailed.ifBlank { short }, cats, timeText).filter { it.isNotBlank() }.joinToString("\n")
            CaptionStyle.CASUAL -> listOf(title, short.ifBlank { detailed }).filter { it.isNotBlank() }.joinToString(". ").let { if (it.isBlank()) "" else "Notes on file: $it" }
            CaptionStyle.PROFESSIONAL -> listOf(title, short.ifBlank { detailed }, cats).filter { it.isNotBlank() }.joinToString("\n")
            CaptionStyle.SOCIAL -> listOf(title, short, hashtags.joinToString(" ")).filter { it.isNotBlank() }.joinToString("\n")
            CaptionStyle.TECHNICAL -> technical(facts, segments, times)
            CaptionStyle.FACTUAL -> factual(facts, title, short, detailed, times)
        }.trim()
    }

    private fun technical(facts: ShareFacts, segments: List<SegmentFact>, times: List<TimestampNote>): String {
        val lines = mutableListOf<String>()
        lines += "Kind: ${facts.contentKind}"
        facts.durationMs?.let { lines += "Duration: ${format(it)}" }
        if (facts.width != null && facts.height != null) lines += "Dimensions: ${facts.width}×${facts.height}"
        facts.range?.let { lines += "Range: ${format(it.startMs)}–${format(it.endMs)}" }
        facts.analyses.map { it.analyzer }.distinct().takeIf { it.isNotEmpty() }?.let { lines += "Stored analyzers: ${it.joinToString(", ")}" }
        if (segments.isNotEmpty()) lines += "Scenes in range: ${segments.size}"
        if (times.isNotEmpty()) lines += times.joinToString("; ") { "${format(it.startMs)}–${format(it.endMs)} ${it.label}".trim() }
        return lines.joinToString("\n")
    }

    private fun factual(facts: ShareFacts, title: String, short: String, detailed: String, times: List<TimestampNote>): String {
        val lines = mutableListOf<String>()
        if (title.isNotBlank()) lines += "Title: $title"
        if (short.isNotBlank()) lines += "Short summary: $short"
        if (detailed.isNotBlank() && detailed != short) lines += "Detailed summary: $detailed"
        if (facts.categories.isNotEmpty()) lines += "Categories: ${facts.categories.joinToString(", ")}"
        if (facts.tags.isNotEmpty()) lines += "Tags: ${facts.tags.joinToString(", ")}"
        times.forEach { lines += "Timestamp ${format(it.startMs)}–${format(it.endMs)}: ${it.label}" }
        facts.analyses.filter { it.text.isNotBlank() && (facts.range == null) }.forEach { lines += "${it.analyzer}: ${it.text.take(400)}" }
        if (facts.omittedPrivateCategories > 0) lines += "Private categories omitted: ${facts.omittedPrivateCategories}"
        return lines.joinToString("\n")
    }

    fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun first(values: List<String>?) = values?.firstOrNull { it.isNotBlank() }

    fun mergedFields(analyses: List<AnalysisFact>): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        analyses.sortedBy { it.edited }.forEach { fact ->
            fact.fields.forEach { (key, values) -> out.getOrPut(canonical(key)) { mutableListOf() }.addAll(values) }
        }
        analyses.filter { it.edited }.forEach { fact ->
            fact.fields.forEach { (key, values) -> if (values.any { it.isNotBlank() }) out[canonical(key)] = values.toMutableList() }
        }
        return out
    }

    fun canonical(key: String) = when (key) {
        "short_summary" -> "shortSummary"
        "detailed_summary" -> "detailedSummary"
        else -> key
    }

    fun fieldsFrom(json: String): Map<String, List<String>> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, List<String>>()
        val keys = listOf("title", "summary", "shortSummary", "short_summary", "detailedSummary", "detailed_summary", "description", "caption", "categories", "tags", "hashtags", "text")
        keys.forEach { key ->
            if (!root.has(key) || root.isNull(key)) return@forEach
            val values = when (val value = root.get(key)) {
                is String -> listOf(value)
                is JSONArray -> (0 until value.length()).mapNotNull { index -> value.optString(index).takeIf { it.isNotBlank() } }
                else -> emptyList()
            }.map { it.trim() }.filter { it.isNotEmpty() }
            if (values.isNotEmpty()) out[key] = values
        }
        return out
    }
}

object MetadataExport {
    fun assemble(approved: ApprovedShare): AssembledExport {
        val transcript = if (approved.includeTranscript) approved.transcriptExcerpts else emptyList()
        val sidecar = if (approved.profile == ShareProfile.MEDIA_JSON_SIDECAR) json(approved, transcript) else null
        val text = when (approved.profile) {
            ShareProfile.MEDIA_ONLY -> ""
            ShareProfile.MEDIA_CAPTION -> approved.caption
            ShareProfile.MEDIA_CATEGORIES -> join(approved.caption, labeled("Categories", approved.categories), labeled("Tags", approved.tags), approved.hashtags.joinToString(" "))
            ShareProfile.MEDIA_DESCRIPTION -> join(approved.detailedSummary.ifBlank { approved.shortSummary }, approved.caption)
            ShareProfile.MEDIA_FULL_METADATA -> join(
                labeled("Title", listOf(approved.title)),
                labeled("Short summary", listOf(approved.shortSummary)),
                labeled("Detailed summary", listOf(approved.detailedSummary)),
                approved.caption,
                labeled("Categories", approved.categories),
                labeled("Tags", approved.tags),
                approved.hashtags.joinToString(" "),
                approved.timestamps.joinToString("\n") { "${ShareTemplates.format(it.startMs)}–${ShareTemplates.format(it.endMs)} ${it.label}".trim() },
                if (transcript.isEmpty()) "" else transcript.joinToString("\n"),
            )
            ShareProfile.MEDIA_JSON_SIDECAR -> approved.caption
        }
        return AssembledExport(text.trim(), sidecar)
    }

    fun json(approved: ApprovedShare, transcript: List<String>): String {
        val root = JSONObject()
        root.put("title", approved.title)
        root.put("shortSummary", approved.shortSummary)
        root.put("detailedSummary", approved.detailedSummary)
        root.put("caption", approved.caption)
        root.put("categories", JSONArray(approved.categories))
        root.put("tags", JSONArray(approved.tags))
        root.put("hashtags", JSONArray(approved.hashtags))
        root.put("timestamps", JSONArray().apply {
            approved.timestamps.forEach { put(JSONObject().put("startMs", it.startMs).put("endMs", it.endMs).put("label", it.label)) }
        })
        root.put("transcriptExcerpts", JSONArray(transcript))
        approved.range?.let { root.put("range", JSONObject().put("startMs", it.startMs).put("endMs", it.endMs)) }
        root.put("style", approved.style.name)
        root.put("profile", approved.profile.name)
        root.put("source", approved.source)
        root.put("note", "Approved text only. Embedded file metadata is not included. Opening a share sheet does not mean a post was published.")
        return root.toString(2)
    }

    private fun labeled(name: String, values: List<String>) = values.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "$name: $it" }.orEmpty()

    private fun join(vararg parts: String) = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
