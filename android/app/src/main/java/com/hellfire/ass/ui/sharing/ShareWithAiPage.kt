package com.hellfire.ass.ui.sharing

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.library.LibraryRoutes
import com.hellfire.ass.library.ShareProfile
import com.hellfire.ass.library.StorageMode
import com.hellfire.ass.sharing.ApprovedShare
import com.hellfire.ass.sharing.CaptionStyle
import com.hellfire.ass.sharing.ClipRanges
import com.hellfire.ass.sharing.PrepareResult
import com.hellfire.ass.sharing.RedactMode
import com.hellfire.ass.sharing.RedactRect
import com.hellfire.ass.sharing.ShareDraft
import com.hellfire.ass.sharing.ShareFacts
import com.hellfire.ass.sharing.ShareFormats
import com.hellfire.ass.sharing.ShareHandoff
import com.hellfire.ass.sharing.ShareSession
import com.hellfire.ass.sharing.ShareTemplates
import com.hellfire.ass.ui.LocalEnv
import com.hellfire.ass.ui.PushedPage
import com.hellfire.ass.ui.Route
import com.hellfire.ass.ui.components.HfTextField
import com.hellfire.ass.ui.components.Note
import com.hellfire.ass.ui.components.PrimaryButton
import com.hellfire.ass.ui.components.SecondaryButton
import com.hellfire.ass.ui.components.SectionCard
import com.hellfire.ass.ui.money.ChipPicker
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShareWithAiPage(route: Route) {
    val app = LocalEnv.current.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val itemId = LibraryRoutes.id(route)
    var startMs by remember { mutableStateOf(LibraryRoutes.startMs(route)) }
    var endMs by remember { mutableStateOf(LibraryRoutes.endMs(route)) }
    var facts by remember { mutableStateOf<ShareFacts?>(null) }
    var missing by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(CaptionStyle.FACTUAL) }
    var profile by remember { mutableStateOf(ShareProfile.MEDIA_CAPTION) }
    var title by remember { mutableStateOf("") }
    var shortSummary by remember { mutableStateOf("") }
    var detailed by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var hashtags by remember { mutableStateOf("") }
    var timestamps by remember { mutableStateOf("") }
    var excerpts by remember { mutableStateOf("") }
    var timestampNotes by remember { mutableStateOf(listOf<com.hellfire.ass.sharing.TimestampNote>()) }
    var includeTranscript by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(ShareTemplates.STORED_NOTICE) }
    var suggestion by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<PrepareResult?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var mode by remember { mutableStateOf(RedactMode.COVER) }
    var rects by remember { mutableStateOf(listOf<RedactRect>()) }
    var suggestions by remember { mutableStateOf(listOf<com.hellfire.ass.sharing.SensitiveText.Suggestion>()) }
    var left by remember { mutableStateOf("0") }
    var top by remember { mutableStateOf("0") }
    var right by remember { mutableStateOf("1000") }
    var bottom by remember { mutableStateOf("1000") }
    var muteStart by remember { mutableStateOf("") }
    var muteEnd by remember { mutableStateOf("") }
    var muteRanges by remember { mutableStateOf(listOf<Pair<Long, Long>>()) }
    var extraIds by remember { mutableStateOf(setOf<String>()) }
    var extras by remember { mutableStateOf(listOf<String>()) }
    val history by app.database.shares().exports(itemId).collectAsStateWithLifecycle(emptyList())
    val segments by app.database.library().segments(itemId).collectAsStateWithLifecycle(emptyList())
    val bookmarks by app.database.playback().bookmarks(itemId, false).collectAsStateWithLifecycle(emptyList())

    fun apply(draft: ShareDraft) {
        title = draft.title
        shortSummary = draft.shortSummary
        detailed = draft.detailedSummary
        caption = draft.caption
        categories = draft.categories.joinToString(", ")
        tags = draft.tags.joinToString(", ")
        hashtags = draft.hashtags.joinToString(" ")
        timestamps = draft.timestamps.joinToString("\n") { "${ShareTemplates.format(it.startMs)}–${ShareTemplates.format(it.endMs)} ${it.label}".trim() }
        timestampNotes = draft.timestamps
        excerpts = draft.transcriptExcerpts.joinToString("\n")
        notice = draft.notice
        prepared = null
    }

    LaunchedEffect(itemId, startMs, endMs) {
        val loaded = ShareSession.load(app, itemId, startMs, endMs)
        facts = loaded
        missing = loaded == null
        if (loaded != null && title.isEmpty() && caption.isEmpty()) apply(ShareTemplates.fromFacts(loaded, style))
        extras = app.database.library().page(listOf("IMAGE"), listOf(StorageMode.MANAGED.name, StorageMode.EXTERNAL.name), false, Long.MAX_VALUE, "", 12)
            .map { it.id }.filter { it != itemId }
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        status = if (granted.values.all { it }) "Media read permission granted. Prepare the copy again." else "Media read permission is not granted. Nothing was shared."
    }

    fun approved(): ApprovedShare? {
        val current = facts ?: return null
        return ApprovedShare(title.trim(), shortSummary.trim(), detailed.trim(), caption.trim(), split(categories), split(tags), split(hashtags),
            timestampNotes, excerpts.lines().map { it.trim() }.filter { it.isNotEmpty() }, style, profile, current.range, includeTranscript, notice)
    }

    fun prepare() {
        val current = approved() ?: return
        scope.launch {
            busy = true
            status = ""
            val item = withContext(Dispatchers.IO) { app.database.library().item(itemId) }
            if (item?.storage == StorageMode.EXTERNAL.name && !canReadMedia(context)) {
                busy = false
                askPermission.launch(mediaPermissions())
                return@launch
            }
            val result = ShareSession.prepare(app, context, itemId, extraIds.toList(), current, rects, mode, muteRanges)
            prepared = result.takeIf { it.ok }
            preview = result.files.firstOrNull { it.extension in setOf("jpg", "png", "webp") }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            status = result.message
            busy = false
        }
    }

    PushedPage(tr("Share with AI"), breadcrumb = "Inbox") {
        if (missing) { Note("This item is no longer available."); return@PushedPage }
        if (facts == null) { Note("Reading stored facts…"); return@PushedPage }
        val plan = ShareFormats.plan("", facts!!.contentKind)
        SectionCard(tr("Sanitized copy")) {
            Note("This prepares a new copy. The original file was not changed. Embedded metadata is removed only when verification succeeds. Visible or audible content can still identify people and places. HellFire Ass does not publish the post.")
            Note(plan.note)
            Note("The sanitized copy is kept in app cache and is not the encrypted original.")
        }
        SectionCard("Range") {
            val choices = buildList {
                add("Whole file")
                if (LibraryRoutes.startMs(route) != null) add("Opened range")
                segments.take(8).forEachIndexed { index, _ -> add("Scene ${index + 1}") }
                bookmarks.filter { it.endMs != null }.take(6).forEachIndexed { index, _ -> add("Bookmark ${index + 1}") }
            }
            val selected = when {
                startMs == null && endMs == null -> "Whole file"
                startMs == LibraryRoutes.startMs(route) && endMs == LibraryRoutes.endMs(route) && startMs != null -> "Opened range"
                else -> "Custom"
            }
            ChipPicker(choices, selected) { choice ->
                when {
                    choice == "Whole file" -> { startMs = null; endMs = null }
                    choice == "Opened range" -> { startMs = LibraryRoutes.startMs(route); endMs = LibraryRoutes.endMs(route) }
                    choice.startsWith("Scene") -> segments.getOrNull(choice.removePrefix("Scene ").toInt() - 1)?.let { startMs = it.startMs; endMs = it.endMs }
                    choice.startsWith("Bookmark") -> bookmarks.filter { it.endMs != null }.getOrNull(choice.removePrefix("Bookmark ").toInt() - 1)?.let { startMs = it.startMs; endMs = it.endMs }
                }
                prepared = null
            }
            Note(facts!!.range?.let { "Segment ${ShareTemplates.format(it.startMs)}–${ShareTemplates.format(it.endMs)}. Description uses only stored facts inside this range." } ?: "Whole file. A bookmark without an end is not a clip.")
        }
        SectionCard("Caption") {
            Note(notice)
            ChipPicker(CaptionStyle.entries.map { it.name }, style.name) { style = CaptionStyle.valueOf(it); prepared = null }
            SecondaryButton("Rebuild from stored facts", { facts?.let { apply(ShareTemplates.fromFacts(it, style)) } }, enabled = !busy)
            HfTextField(title, { title = it.take(200); prepared = null }, "Title")
            HfTextField(shortSummary, { shortSummary = it.take(500); prepared = null }, "Short summary", singleLine = false, minLines = 2)
            HfTextField(detailed, { detailed = it.take(2000); prepared = null }, "Detailed summary", singleLine = false, minLines = 3)
            HfTextField(caption, { caption = it.take(2000); prepared = null }, "Caption", singleLine = false, minLines = 3)
            HfTextField(categories, { categories = it.take(300); prepared = null }, "Categories")
            HfTextField(tags, { tags = it.take(300); prepared = null }, "Tags")
            HfTextField(hashtags, { hashtags = it.take(300); prepared = null }, "Hashtags")
            HfTextField(timestamps, { timestamps = it.take(1000); prepared = null }, "Timestamps", singleLine = false, minLines = 2)
            HfTextField(excerpts, { excerpts = it.take(2000); prepared = null }, "Transcript excerpts", singleLine = false, minLines = 2)
            ChipPicker(listOf("Omit transcript", "Include transcript"), if (includeTranscript) "Include transcript" else "Omit transcript") {
                includeTranscript = it == "Include transcript"
                prepared = null
            }
            if (suggestion.isNotBlank()) {
                Note(suggestion)
                SecondaryButton("Use suggestion", { caption = suggestion.take(2000); notice = ShareTemplates.MODEL_NOTICE; prepared = null })
            }
            SecondaryButton("Suggest with local model", {
                val current = facts ?: return@SecondaryButton
                scope.launch {
                    busy = true
                    suggestion = ShareSession.suggest(app, current.copy(range = ClipRanges.valid(startMs, endMs, current.durationMs)), style)
                    status = suggestion
                    busy = false
                }
            }, enabled = !busy)
            Note("Local model text is a suggestion. It is not applied until you use it, and it is not verified.")
        }
        SectionCard("What is attached") {
            ChipPicker(ShareProfile.entries.map { it.name }, profile.name) { profile = ShareProfile.valueOf(it); prepared = null }
            Note(profileLabel(profile))
            Note("Some apps ignore captions or reject a JSON sidecar. That is not a published post.")
            approved()?.let { Note(com.hellfire.ass.sharing.MetadataExport.assemble(it).text.ifBlank { "No text will be attached." }) }
        }
        SectionCard("Redaction") {
            Note(ShareSession.DETECTORS)
            Note(ShareSession.TRACKING)
            ChipPicker(RedactMode.entries.map { it.name }, mode.name) { mode = RedactMode.valueOf(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Scan text", {
                    scope.launch {
                        busy = true
                        suggestions = withContext(Dispatchers.IO) {
                            val item = app.database.library().item(itemId) ?: return@withContext emptyList()
                            app.media.withPlainCopy(item) { file ->
                                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withPlainCopy emptyList()
                                try { ShareSession.textBoxes(bitmap) } finally { bitmap.recycle() }
                            }
                        }
                        status = if (suggestions.isEmpty()) "No phone, email or address text was found. Nothing was changed." else "Review the suggested regions before adding them."
                        busy = false
                    }
                }, enabled = !busy)
            }
            suggestions.forEach { hit ->
                SecondaryButton("${hit.kind}: ${hit.text.take(24)}", {
                    rects = rects + ShareSession.rect(hit)
                    prepared = null
                })
            }
            HfTextField(left, { left = it.filter { ch -> ch.isDigit() }.take(4) }, "Left 0–1000")
            HfTextField(top, { top = it.filter { ch -> ch.isDigit() }.take(4) }, "Top 0–1000")
            HfTextField(right, { right = it.filter { ch -> ch.isDigit() }.take(4) }, "Right 0–1000")
            HfTextField(bottom, { bottom = it.filter { ch -> ch.isDigit() }.take(4) }, "Bottom 0–1000")
            SecondaryButton("Add region", {
                val rect = RedactRect(left.toFloatOrNull()?.div(1000f) ?: -1f, top.toFloatOrNull()?.div(1000f) ?: -1f, right.toFloatOrNull()?.div(1000f) ?: -1f, bottom.toFloatOrNull()?.div(1000f) ?: -1f)
                if (rect.valid()) { rects = rects + rect; prepared = null } else status = "Region must be inside the picture."
            })
            if (rects.isNotEmpty()) Note("${rects.size} region(s) will be applied only to the copy.")
            if (facts!!.contentKind == "AUDIO" || facts!!.contentKind == "VIDEO") {
                facts!!.transcriptExcerpts().forEach { excerpt ->
                    SecondaryButton("Mute ${ShareTemplates.format(excerpt.first)}", {
                        muteRanges = muteRanges + excerpt
                        prepared = null
                    })
                }
                HfTextField(muteStart, { muteStart = it.filter { ch -> ch.isDigit() }.take(9) }, "Mute from ms")
                HfTextField(muteEnd, { muteEnd = it.filter { ch -> ch.isDigit() }.take(9) }, "Mute until ms")
                SecondaryButton("Add mute range", {
                    val range = ClipRanges.clipRelative(facts!!.range, muteStart.toLongOrNull() ?: -1, muteEnd.toLongOrNull() ?: -1)
                    if (range == null) status = "Mute range is not inside the selected clip." else { muteRanges = muteRanges + range; prepared = null }
                })
                if (muteRanges.isNotEmpty()) Note("${muteRanges.size} mute range(s) on the exported copy only.")
                Note("Voice transformation is not implemented.")
            }
        }
        if (extras.isNotEmpty() && facts!!.contentKind == "IMAGE") SectionCard("More images") {
            Note("Each extra image is sanitized on its own. Original filenames are not used.")
            extras.forEach { id ->
                val on = id in extraIds
                SecondaryButton(if (on) "Remove image ${id.take(4)}" else "Add image ${id.take(4)}", {
                    extraIds = if (on) extraIds - id else extraIds + id
                    prepared = null
                })
            }
        }
        if (status.isNotBlank()) Note(status)
        prepared?.let { ready ->
            preview?.let { Image(it.asImageBitmap(), tr("Sanitized preview"), Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
            Note(ready.text.ifBlank { "No caption will be attached." })
        }
        PrimaryButton(tr("Prepare sanitized copy"), { prepare() }, Modifier.fillMaxWidth(), enabled = !busy)
        SecondaryButton(tr("Copy caption"), { copy(context, caption); status = "Copied. Nothing was shared." }, enabled = caption.isNotBlank())
        SecondaryButton(tr("Copy summary"), { copy(context, shortSummary); status = "Copied. Nothing was shared." }, enabled = shortSummary.isNotBlank())
        SecondaryButton(tr("Export JSON"), {
            val json = approved()?.let { com.hellfire.ass.sharing.MetadataExport.assemble(it.copy(profile = ShareProfile.MEDIA_JSON_SIDECAR)).sidecarJson }.orEmpty()
            copy(context, json)
            status = "JSON copied. Nothing was published."
        })
        PrimaryButton(tr("Open share sheet"), {
            val ready = prepared ?: return@PrimaryButton
            val uris = ready.files.map { ShareHandoff.uri(context, it) }
            context.startActivity(ShareHandoff.chooser(uris, ready.mime, ready.text))
            scope.launch { ShareSession.markHandedOff(app, ready.exportIds) }
            status = "Share sheet opened. HellFire Ass did not publish this."
        }, Modifier.fillMaxWidth(), enabled = prepared?.ok == true && !busy)
        if (history.isNotEmpty()) SectionCard("Earlier copies") {
            history.take(5).forEach { row ->
                Text("${row.state} · ${row.verification} · ${row.profile}", style = Hf.type.body, color = Hf.colors.onSurface)
                Note(if (row.handedOffAt != null) "Share sheet opened. That is not a published post." else row.error ?: "Not handed to another app.")
            }
        }
    }
}

private fun ShareFacts.transcriptExcerpts(): List<Pair<Long, Long>> = transcript.mapNotNull { ClipRanges.clipRelative(range, it.startMs, it.endMs) }

private fun split(value: String) = value.split(',', ' ', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()

private fun profileLabel(profile: ShareProfile) = when (profile) {
    ShareProfile.MEDIA_ONLY -> "Media only"
    ShareProfile.MEDIA_CAPTION -> "Media + caption"
    ShareProfile.MEDIA_CATEGORIES -> "Media + categories"
    ShareProfile.MEDIA_DESCRIPTION -> "Media + description"
    ShareProfile.MEDIA_FULL_METADATA -> "Media + approved metadata"
    ShareProfile.MEDIA_JSON_SIDECAR -> "Media + JSON sidecar"
}

private fun copy(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("share", text))
}

private fun mediaPermissions() = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun canReadMedia(context: android.content.Context) = mediaPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
