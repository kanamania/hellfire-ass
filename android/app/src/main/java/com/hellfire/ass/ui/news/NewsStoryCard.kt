package com.hellfire.ass.ui.news

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.news.*
import com.hellfire.ass.ui.LocalEnv
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import com.hellfire.ass.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.ZoneId

/** Runs a NewsService call off the main thread. Failures (including the not-yet-implemented stub) become a Result for an on-screen note. */
internal suspend fun <T> newsCall(block: suspend () -> T): Result<T> = try { Result.success(withContext(Dispatchers.IO) { block() }) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { Diagnostics.warn(e); Result.failure(e) }
    catch (e: NotImplementedError) { Result.failure(e) }

internal fun failText(e: Throwable?, what: String): String = when (e) {
    is NotImplementedError -> "$what: news is not available in this build yet."
    is IllegalArgumentException, is IllegalStateException -> "$what: ${e.message ?: "invalid input"}"
    else -> "$what. Check the connection and try again."
}

/** Opens an original article, video or audio page in the browser or publisher app. */
internal fun openLink(context: Context, url: String): Boolean {
    if (!NewsUi.openable(url)) return false
    return try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
    catch (_: ActivityNotFoundException) { false }
}

internal fun shareStory(context: Context, view: StoryView, summary: StorySummary) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, NewsUi.shareText(summary, view.event.headline))
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Text to read aloud; falls back to headline + quick summary if the engine cannot provide it. */
internal fun spokenText(summary: StorySummary, headline: String): String =
    runCatching { summary.spoken() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: (listOf(summary.headline.ifBlank { headline }) + summary.quick).joinToString(". ") { it.trim().trimEnd('.') } + "."

internal fun spoken(view: StoryView): SpokenStory = SpokenStory(view.event.id, view.summary.headline.ifBlank { view.event.headline }, spokenText(view.summary, view.event.headline))

internal fun zoneOf(settings: NewsSettings): ZoneId = runCatching { ZoneId.of(settings.zone) }.getOrDefault(ZoneId.of("Africa/Dar_es_Salaam"))

internal fun publisherOf(view: StoryView, article: NewsArticle): String = view.sources.firstOrNull { it.id == article.sourceId }?.name ?: "Unknown publisher"

/** Compact row for Breaking, Saved, Archive and Home lists. */
@Composable
fun StoryRow(view: StoryView, zone: ZoneId, onClick: () -> Unit) {
    val s = view.summary
    ListRow(s.headline.ifBlank { view.event.headline },
        subtitle = listOf(tr(NewsUi.levelLabel(view.event.level)), "${view.event.independentSources} " + tr("independent sources"),
            NewsUi.stamp(view.event.lastArticleAt, zone)).joinToString(" · "),
        trailing = { StatusPill(tr(NewsUi.levelLabel(view.event.level)), NewsUi.levelTone(view.event.level)) },
        chevron = true, onClick = onClick)
}

@Composable
fun StoryList(views: List<StoryView>?, empty: String, zone: ZoneId, onOpen: (StoryView) -> Unit) {
    when {
        views == null -> Note(tr("Loading…"))
        views.isEmpty() -> HfCard(padding = PaddingValues(16.dp)) { Text(tr(empty), style = Hf.type.body, color = Hf.colors.muted) }
        else -> ListCard {
            views.forEachIndexed { i, v -> if (i > 0) ListDivider(16.dp); StoryRow(v, zone) { onOpen(v) } }
        }
    }
}

/** Collapsible section of a story card. */
@Composable
private fun Expandable(title: String, open: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(open) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickableRow { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(tr(title), Modifier.weight(1f), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
            Icon(HfIcons.ChevronDown, if (expanded) tr("Collapse") else tr("Expand"), Modifier.size(18.dp).rotate(if (expanded) 180f else 0f), tint = Hf.colors.muted)
        }
        if (expanded) content()
    }
}

@Composable
private fun Bullets(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.filter { it.isNotBlank() }.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = Hf.type.body, color = Hf.colors.primary)
                Text(line, style = Hf.type.body, color = Hf.colors.onSurface)
            }
        }
    }
}

/** "What Changed" header for an update item: previously / what's new / why it matters / evidence / when. */
@Composable
fun WhatChangedCard(view: StoryView, zone: ZoneId) {
    val delta = view.delta ?: view.versions.lastOrNull() ?: return
    val evidence = remember(delta) {
        runCatching { JSONArray(delta.evidenceJson).let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrDefault(emptyList())
            .mapNotNull { id -> view.articles.firstOrNull { it.id == id } }.map { publisherOf(view, it) }.distinct()
    }
    val correction = delta.classification == "CORRECTION"
    val (bg, fg) = (if (correction) Tone.Warning else Tone.Primary).colors()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("What Changed"), Modifier.weight(1f), style = Hf.type.cardTitle, color = fg)
            StatusPill(tr(NewsUi.classificationLabel(delta.classification)), if (correction) Tone.Warning else Tone.Primary)
        }
        @Composable fun part(label: String, text: String) {
            if (text.isBlank()) return
            Text(tr(label).uppercase(), style = Hf.type.caption.copy(fontWeight = FontWeight.SemiBold), color = fg)
            Text(text, style = Hf.type.body, color = Hf.colors.onSurface)
        }
        part("Previously", delta.previously)
        part("What's New", delta.whatsNew)
        part("Why It Matters", delta.whyItMatters)
        part("Source Evidence", evidence.joinToString(", ").ifBlank { "${view.event.independentSources} " + tr("independent sources") })
        part("Updated", NewsUi.stamp(delta.at, zone))
    }
}

/** Factor bars and reasons behind a story's rank. */
@Composable
private fun RankSheet(eventId: String, onDismiss: () -> Unit) {
    val app = LocalEnv.current.app
    var result by remember { mutableStateOf<Result<NewsScore?>?>(null) }
    LaunchedEffect(eventId) { result = newsCall { app.news.explain(eventId) } }
    HfBottomSheet(onDismiss) {
        Text(tr("Why this rank?"), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        val r = result
        val score = r?.getOrNull()
        when {
            r == null -> Note(tr("Loading…"))
            r.isFailure -> Banner(failText(r.exceptionOrNull(), "Could not explain this rank"), Tone.Danger, HfIcons.Info)
            score == null -> Note(tr("No score has been recorded for this story yet."))
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${score.total}/100", style = Hf.type.figure, color = Hf.colors.onSurface)
                    StatusPill(tr(NewsUi.levelLabel(score.level)), NewsUi.levelTone(score.level))
                }
                score.factors.entries.sortedByDescending { it.value }.forEach { (name, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row { Text(name.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), style = Hf.type.label, color = Hf.colors.onSurface)
                            Text("$value", style = Hf.type.label, color = Hf.colors.muted) }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Hf.colors.track)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0, 100) / 100f).clip(CircleShape).background(Hf.colors.primary))
                        }
                    }
                }
                if (score.reasons.isNotEmpty()) Bullets(score.reasons)
                if (score.protectedPublic) Note(tr("Public-interest story: it is never personalized away, even for hidden subjects."))
            }
        }
    }
}

/** "Wrong grouping": choose the article that is about a different event. */
@Composable
private fun GroupingSheet(view: StoryView, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<NewsArticle?>(null) }
    HfBottomSheet(onDismiss) {
        Text(tr("Wrong grouping"), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        Note(tr("Choose the report that is about a different event. It will be split out and kept apart from this story."))
        view.articles.forEach { a ->
            OptionCard(a.title, publisherOf(view, a) + " · " + NewsUi.contentTypeLabel(a.contentType), chosen?.id == a.id, { chosen = a })
        }
        PrimaryButton(tr("Split this report out"), {
            val a = chosen ?: return@PrimaryButton
            scope.launch {
                val r = newsCall { app.news.feedback("NOT_SAME_EVENT", view.event.id, articleId = a.id) }
                onDone(if (r.isSuccess) "Thanks. That report will be kept apart from this story." else failText(r.exceptionOrNull(), "Could not save the correction"))
            }
        }, Modifier.fillMaxWidth(), enabled = chosen != null && view.articles.size > 1)
        if (view.articles.size <= 1) Note(tr("This story has only one report, so there is nothing to split."))
    }
}

@Composable
private fun HideSheet(view: StoryView, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val subjects = remember(view.event) {
        view.event.topics.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { "topic:$it" to NewsUi.topicLabel(it) } +
            listOfNotNull(view.event.place?.takeIf { it.isNotBlank() }?.let { "place:${it.lowercase()}" to it.replaceFirstChar { c -> c.uppercase() } })
    }
    HfBottomSheet(onDismiss) {
        Text(tr("Hide a subject"), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        Note(tr("Stories about this subject leave your ordinary briefings. Critical public-interest news still shows. Unhide it in News › Interests."))
        if (subjects.isEmpty()) Note(tr("No subjects were recognized for this story."))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            subjects.forEach { (key, label) ->
                HfChip(label, {
                    scope.launch {
                        val r = newsCall { app.news.hideSubject(key, label) }
                        onDone(if (r.isSuccess) "Hidden: $label. Critical public-interest news still shows." else failText(r.exceptionOrNull(), "Could not hide $label"))
                    }
                })
            }
        }
    }
}

/** Version history of a developing story; entries newer than what the owner has read are highlighted. */
@Composable
private fun TimelineSheet(view: StoryView, settings: NewsSettings, onDismiss: () -> Unit) {
    val app = LocalEnv.current.app
    val context = LocalContext.current
    val zone = zoneOf(settings)
    val seen = view.state?.seenVersion ?: 0
    var updates by remember { mutableStateOf<Result<List<NewsEventVersion>>?>(null) }
    LaunchedEffect(view.event.id) { updates = newsCall { app.news.timeline(view.event.id, sinceVersion = seen) } }
    val fresh = updates?.getOrNull() ?: view.versions.filter { it.version > seen }
    HfBottomSheet(onDismiss) {
        Text(tr("What changed"), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        updates?.exceptionOrNull()?.let { Note(failText(it, "Could not load the latest updates") + " " + tr("Showing the versions saved with this story.")) }
        Note(if (fresh.isEmpty()) tr("Nothing new since you last read this story.") else "${fresh.size} " + tr("updates since you last read this story"))
        if (fresh.isNotEmpty()) SecondaryButton(tr("Listen to updates only"), {
            NewsAudio.play(context, null, fresh.sortedBy { it.version }.map {
                SpokenStory("${view.event.id}#${it.version}", view.event.headline, it.whatsNew.ifBlank { it.previously })
            }, rate = settings.speechRate, language = view.summary.language, continuous = true)
        }, Modifier.fillMaxWidth())
        (view.versions + fresh).distinctBy { it.version }.sortedByDescending { it.version }.forEach { v ->
            val correction = v.classification == "CORRECTION"
            val new = v.version > seen
            HfCard(highlighted = new, padding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("v${v.version} · " + NewsUi.stamp(v.at, zone), Modifier.weight(1f), style = Hf.type.label, color = Hf.colors.muted)
                        if (new) StatusPill(tr("New"), Tone.Primary)
                        StatusPill(tr(NewsUi.classificationLabel(v.classification)), if (correction) Tone.Warning else Tone.Neutral)
                    }
                    if (v.whatsNew.isNotBlank()) Text(v.whatsNew, style = Hf.type.body, color = if (correction) Hf.colors.warning else Hf.colors.onSurface)
                    if (v.previously.isNotBlank()) Text(tr("Previously") + ": " + v.previously, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                    if (v.whyItMatters.isNotBlank()) Text(tr("Why It Matters") + ": " + v.whyItMatters, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                }
            }
        }
    }
}

/**
 * Full story card used by the reader pager and the single-story page. [read]: the reader already marked it read.
 * [onSkip] is only offered inside a briefing.
 */
@Composable
fun StoryCard(view: StoryView, settings: NewsSettings, read: Boolean, onListen: () -> Unit, onSkip: (() -> Unit)? = null) {
    val app = LocalEnv.current.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = zoneOf(settings)
    val event = view.event
    var summary by remember(event.id) { mutableStateOf(view.summary) }
    var length by remember(event.id) { mutableStateOf(settings.length) }
    var language by remember(event.id) { mutableStateOf(view.summary.language.ifBlank { settings.language }) }
    var summaryNote by remember(event.id) { mutableStateOf<String?>(null) }
    var message by remember(event.id) { mutableStateOf<String?>(null) }
    var bookmarked by remember(event.id) { mutableStateOf(view.state?.bookmarked == true) }
    var readLater by remember(event.id) { mutableStateOf(view.state?.readLater == true) }
    var followed by remember(event.id) { mutableStateOf(view.state?.followed == true) }
    var sheet by remember(event.id) { mutableStateOf<String?>(null) }

    fun reload(newLength: String, newLanguage: String) {
        length = newLength; language = newLanguage
        scope.launch {
            summaryNote = "Loading…"
            val r = newsCall { app.news.summary(event.id, newLength, newLanguage) }
            r.getOrNull()?.let { summary = it }
            summaryNote = r.exceptionOrNull()?.let { failText(it, "Could not change the summary") }
                ?: r.getOrNull()?.let { NewsUi.translationNote(newLanguage, it.language) }
        }
    }
    fun flag(field: String, value: Boolean, revert: () -> Unit) = scope.launch {
        val r = newsCall { app.news.setFlag(event.id, field, value) }
        if (r.isFailure) { revert(); message = failText(r.exceptionOrNull(), "Could not save") }
    }
    fun feedback(kind: String, thanks: String) = scope.launch {
        val r = newsCall { app.news.feedback(kind, event.id) }
        message = if (r.isSuccess) thanks else failText(r.exceptionOrNull(), "Could not save your feedback")
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        message?.let { Banner(it, Tone.Info, HfIcons.Info, Modifier.clickableRow { message = null }) }
        if (view.section == "CHANGED") WhatChangedCard(view, zone)

        val media = remember(view.articles) { view.articles.flatMap { a -> NewsImages.media(a.mediaJson).map { a to it } } }
        media.firstOrNull { it.second.type == "IMAGE" && NewsImages.accept(it.second.url) }?.let { (a, m) -> NewsImage(m.url, publisherOf(view, a)) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(tr(NewsUi.levelLabel(event.level)), NewsUi.levelTone(event.level))
            NewsUi.labels(summary.labels).forEach { l -> NewsUi.labelText(l)?.let { StatusPill(tr(it), NewsUi.labelTone(l)) } }
        }
        Text(summary.headline.ifBlank { event.headline }, style = Hf.type.reportTitle, color = Hf.colors.onSurface)
        val status = when {
            read || (view.state?.readAt != null && (view.state?.seenVersion ?: 0) >= event.version) -> "Read"
            view.state?.readAt != null -> "Updated since you read"
            else -> "Unread"
        }
        Text(listOf("${view.articles.size} " + tr("reports"), "${event.independentSources} " + tr("independent sources"), tr(status),
            tr("Updated") + " " + NewsUi.stamp(event.updatedAt, zone)).joinToString(" · "), style = Hf.type.rowSubtitle, color = Hf.colors.muted)

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedControl(NewsUi.lengths.map { it.second }, NewsUi.lengths.firstOrNull { it.first == length }?.second ?: "Standard",
                { label -> reload(NewsUi.lengths.first { it.second == label }.first, language) }, compact = true)
            SegmentedControl(listOf("English", "Kiswahili"), if (language == "sw") "Kiswahili" else "English",
                { reload(length, if (it == "Kiswahili") "sw" else "en") }, compact = true)
        }
        summaryNote?.let { Note(it) }
        if ("TRANSLATED" in summary.labels) Note(tr("Translated by AI") + " · " + summary.generatedBy.removePrefix("AI:"))

        if (summary.quick.isNotEmpty()) HfCard(padding = PaddingValues(16.dp)) { Bullets(summary.quick) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(tr("Listen"), onListen, icon = HfIcons.Waveform)
            if (onSkip != null) SecondaryButton(tr("Skip"), onSkip)
            if (view.versions.size > 1) SecondaryButton(tr("What changed"), { sheet = "timeline" })
        }

        HfCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            if (summary.details.isNotEmpty()) Expandable("Key details", open = true) {
                summary.details.forEach { line ->
                    val who = line.sources.map { s -> view.articles.firstOrNull { it.id == s }?.let { publisherOf(view, it) } ?: s }.distinct()
                    Text(line.text, style = Hf.type.body, color = Hf.colors.onSurface)
                    if (who.isNotEmpty()) Text(who.joinToString(", "), style = Hf.type.caption, color = Hf.colors.muted)
                }
            }
            if (summary.whyItMatters.isNotBlank()) Expandable("Why it matters") { Text(summary.whyItMatters, style = Hf.type.body, color = Hf.colors.onSurface) }
            if (summary.localImpact.isNotBlank()) Expandable("Local impact") { Text(summary.localImpact, style = Hf.type.body, color = Hf.colors.onSurface) }
            if (summary.background.isNotBlank()) Expandable("Background") { Text(summary.background, style = Hf.type.body, color = Hf.colors.onSurface) }
            if (summary.whatsNext.isNotEmpty()) Expandable("What's next") {
                StatusPill(tr("Expected, not confirmed"), Tone.Warning)
                Bullets(summary.whatsNext)
            }
            if (summary.conflicts.isNotEmpty()) Expandable("Conflicting reports", open = true) { Bullets(summary.conflicts) }
        }

        val playable = media.filter { it.second.type in setOf("VIDEO", "AUDIO", "EMBED") && NewsUi.openable(it.second.url) }.distinctBy { it.second.url }
        if (playable.isNotEmpty()) ListCard(title = tr("Video and audio")) {
            playable.forEachIndexed { i, (a, m) ->
                if (i > 0) ListDivider(16.dp)
                ListRow((if (m.type == "AUDIO") tr("Listen at") else tr("Watch at")) + " " + publisherOf(view, a),
                    subtitle = tr("Plays on the publisher's site/app"), icon = HfIcons.Waveform, chevron = true,
                    onClick = { if (!openLink(context, m.url)) message = "No app can open this link." })
            }
        }

        if (summary.sources.isNotEmpty()) ListCard(title = tr("Sources"), count = summary.sources.size) {
            summary.sources.forEachIndexed { i, s ->
                if (i > 0) ListDivider(16.dp)
                ListRow(s.title.ifBlank { s.publisher }, subtitle = listOfNotNull(s.publisher, NewsUi.stamp(s.publishedAt, zone), tr(NewsUi.contentTypeLabel(s.contentType)),
                    s.agency?.let { "via $it" }).joinToString(" · "), chevron = true,
                    onClick = { if (!openLink(context, s.url)) message = "No app can open this link." })
            }
            Spacer(Modifier.height(6.dp))
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HfChip(tr(if (bookmarked) "Bookmarked" else "Bookmark"), { bookmarked = !bookmarked; val v = bookmarked; flag("bookmarked", v) { bookmarked = !v } },
                style = if (bookmarked) ChipStyle.Selected else ChipStyle.Outline)
            HfChip(tr("Read later"), { readLater = !readLater; val v = readLater; flag("readLater", v) { readLater = !v } },
                style = if (readLater) ChipStyle.Selected else ChipStyle.Outline)
            HfChip(tr(if (followed) "Following" else "Follow story"), { followed = !followed; val v = followed; flag("followed", v) { followed = !v } },
                style = if (followed) ChipStyle.Selected else ChipStyle.Outline)
            HfChip(tr("Share"), { shareStory(context, view, summary) })
            HfChip(tr("More like this"), { feedback("MORE_IMPORTANT", "Thanks. Similar stories will rank higher.") })
            HfChip(tr("Less like this"), { feedback("LESS_IMPORTANT", "Thanks. Similar stories will rank lower.") })
            HfChip(tr("Hide subject"), { sheet = "hide" })
            HfChip(tr("Wrong grouping"), { sheet = "grouping" })
            HfChip(tr("Why this rank?"), { sheet = "rank" })
        }
        Note(tr("Summaries come only from the listed reports. Open a source to read the original."))
    }

    when (sheet) {
        "rank" -> RankSheet(event.id) { sheet = null }
        "grouping" -> GroupingSheet(view, { message = it; sheet = null }) { sheet = null }
        "hide" -> HideSheet(view, { message = it; sheet = null }) { sheet = null }
        "timeline" -> TimelineSheet(view, settings) { sheet = null }
    }
}

