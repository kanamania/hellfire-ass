package com.hellfire.ass.ui.news

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.news.*
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Safe Flow from the news service: a failing call or stream becomes [fallback] and [onError]. */
internal fun <T> safeFlow(fallback: T, onError: (Throwable) -> Unit, block: () -> Flow<T>): Flow<T> =
    runCatching(block).getOrElse { onError(it); flowOf(fallback) }.catch { onError(it); emit(fallback) }

/** News hub: Morning / Evening briefings, Breaking, Saved and Archive. */
@Composable
fun NewsScreen() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableStateOf(if (java.time.LocalTime.now().hour < 12) "Morning" else "Evening") }
    var settings by remember { mutableStateOf(NewsSettings()) }
    LaunchedEffect(Unit) { newsCall { app.news.settings() }.getOrNull()?.let { settings = it } }
    val zone = zoneOf(settings)
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var refreshErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    PushedPage("News", breadcrumb = stringResource(R.string.ui_home), actions = {
        IconCircleButton(HfIcons.Settings, "News settings", { nav.push(Screen.NewsSettings) })
    }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SegmentedControl(listOf("Morning", "Evening", "Breaking", "Saved", "Archive"), tab, { tab = it }, compact = true,
                label = { when (it) { "Saved" -> stringResource(R.string.ui_saved); "Archive" -> stringResource(R.string.drawer_archive); else -> it } })
        }
        message?.let { (text, tone) -> Banner(text, tone, HfIcons.Info, Modifier.clickableRow { message = null; refreshErrors = emptyList() }) }
        if (refreshErrors.isNotEmpty()) HfCard(padding = PaddingValues(14.dp)) {
            Text("Sources that failed", style = Hf.type.cardTitle, color = Hf.colors.onSurface)
            refreshErrors.take(12).forEach { Text(it, style = Hf.type.rowSubtitle, color = Hf.colors.muted) }
            if (refreshErrors.size > 12) Note("+${refreshErrors.size - 12} " + "more")
        }
        when (tab) {
            "Morning", "Evening" -> Briefings(tab == "Morning", zone, busy, onBriefNow = {
                busy = true
                scope.launch {
                    val r = newsCall { app.news.briefing("ON_DEMAND") }
                    busy = false
                    r.getOrNull()?.let { nav.push(Screen.NewsReader, it.id) }
                        ?: run { message = failText(r.exceptionOrNull(), "Could not build a briefing") to Tone.Danger }
                }
            }, onRefresh = {
                busy = true
                scope.launch {
                    val r = newsCall { app.news.refresh(force = true) }
                    busy = false
                    val x = r.getOrNull()
                    message = if (x == null) failText(r.exceptionOrNull(), "Could not refresh sources") to Tone.Danger
                        else ("Checked ${x.sourcesTried} sources · ${x.sourcesFailed} failed · ${x.articlesNew} new reports · " +
                            "${x.eventsNew} new stories · ${x.eventsUpdated} updated") to (if (x.sourcesFailed > 0) Tone.Warning else Tone.Success)
                    refreshErrors = x?.errors.orEmpty()
                }
            })
            "Breaking" -> Loaded("Breaking", { app.news.breaking() }) { list ->
                Note("Critical and high-importance stories from the last 24 hours.")
                StoryList(list, "No breaking stories right now.", zone) { nav.push(Screen.NewsStory, it.event.id) }
            }
            "Saved" -> Loaded("Saved", { app.news.saved() }) { list ->
                StoryList(list, "Bookmark a story or choose Read later to keep it here.", zone) { nav.push(Screen.NewsStory, it.event.id) }
            }
            else -> Archive(zone)
        }
    }
}

/** Loads a story list once per [key] and shows an error note instead of crashing. */
@Composable
private fun Loaded(key: String, load: suspend () -> List<StoryView>, content: @Composable (List<StoryView>?) -> Unit) {
    var result by remember(key) { mutableStateOf<Result<List<StoryView>>?>(null) }
    LaunchedEffect(key) { result = newsCall { load() } }
    val r = result
    if (r?.isFailure == true) Banner(failText(r.exceptionOrNull(), "Could not load $key"), Tone.Danger, null)
    else content(r?.getOrNull())
}

@Composable
private fun Briefings(morning: Boolean, zone: java.time.ZoneId, busy: Boolean, onBriefNow: () -> Unit, onRefresh: () -> Unit) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var failed by remember { mutableStateOf<Throwable?>(null) }
    val all by remember { safeFlow(emptyList(), { failed = it }) { app.news.briefings(NewsUi.allSlots - "BREAKING", 60) } }
        .collectAsStateWithLifecycle(null)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(if (busy) stringResource(R.string.ui_working) else "Brief me now", onBriefNow, Modifier.weight(1f), enabled = !busy)
        SecondaryButton("Refresh sources", onRefresh, Modifier.weight(1f), enabled = !busy)
    }
    failed?.let { Banner(failText(it, "Could not read briefings"), Tone.Danger, null) }
    val list = all?.filter { NewsUi.isMorning(it, zone) == morning }
    when {
        list == null -> Note("Loading…")
        list.isEmpty() -> HfCard(padding = PaddingValues(16.dp)) {
            Text(if (morning) "No morning briefings yet." else "No evening briefings yet.", style = Hf.type.body, color = Hf.colors.muted)
        }
        else -> ListCard(title = if (morning) "Morning briefings" else "Evening briefings", count = list.size) {
            list.forEachIndexed { i, b ->
                if (i > 0) ListDivider(16.dp)
                ListRow(b.title, subtitle = listOf(NewsUi.stamp(b.generatedAt, zone), "${NewsUi.itemCount(b.itemsJson)} " + "stories", b.note)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                    trailing = { QuietButton(stringResource(R.string.ui_open), { nav.push(Screen.NewsReader, b.id) }) },
                    onClick = { nav.push(Screen.NewsReader, b.id) })
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun Archive(zone: java.time.ZoneId) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf("This week") }
    var result by remember { mutableStateOf<Result<List<StoryView>>?>(null) }
    fun search() {
        val (from, to) = NewsUi.range(range, System.currentTimeMillis(), zone)
        result = null
        scope.launch { result = newsCall { app.news.archive(query.trim(), from, to) } }
    }
    LaunchedEffect(range) { search() }
    HfTextField(query, { query = it.take(200) }, "Search past stories")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Today", "This week", "30 days").forEach { chip ->
            HfChip(if (chip == "Today") stringResource(R.string.ui_today) else chip, { range = chip }, style = if (range == chip) ChipStyle.Selected else ChipStyle.Outline)
        }
        PrimaryButton(stringResource(R.string.ui_intel_search), { search() })
    }
    val r = result
    if (r?.isFailure == true) Banner(failText(r.exceptionOrNull(), "Could not search the archive"), Tone.Danger, null)
    else StoryList(r?.getOrNull(), "No stories match.", zone) { nav.push(Screen.NewsStory, it.event.id) }
}
