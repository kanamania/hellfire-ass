package com.hellfire.ass.ui.news

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.news.*
import com.hellfire.ass.ui.LocalEnv
import com.hellfire.ass.ui.LocalNav
import com.hellfire.ass.ui.PushedPage
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Sequential immersive reader for one briefing: one story per page, resumable, with spoken playback. */
@Composable
fun NewsReaderPage(briefingId: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var settings by remember { mutableStateOf(NewsSettings()) }
    var stories by remember { mutableStateOf<Result<List<StoryView>>?>(null) }
    var progress by remember { mutableStateOf<NewsProgress?>(null) }
    var title by remember { mutableStateOf("Briefing") }
    LaunchedEffect(briefingId) {
        newsCall { app.news.settings() }.getOrNull()?.let { settings = it }
        progress = newsCall { app.news.position(briefingId) }.getOrNull()
        newsCall { app.news.briefings(NewsUi.allSlots, 60).first().firstOrNull { it.id == briefingId } }.getOrNull()?.let { title = it.title }
        stories = newsCall { app.news.stories(briefingId) }
    }
    Column(Modifier.fillMaxSize().padding(start = ScreenPadding, end = ScreenPadding, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PushedTopBar(title, onBack = { nav.pop() }, breadcrumb = "News")
        val r = stories
        val list = r?.getOrNull()
        when {
            r == null -> Note("Loading…")
            r.isFailure -> Banner(failText(r.exceptionOrNull(), "Could not open this briefing"), Tone.Danger, null)
            list.isNullOrEmpty() -> Note("This briefing has no stories.")
            else -> Reader(briefingId, list, progress, settings)
        }
    }
}

private fun sectionTitle(section: String) = when (section) { "TOP" -> "Top stories"; "CHANGED" -> "What Changed"; else -> "Stories" }

@Composable
private fun ColumnScope.Reader(briefingId: String, list: List<StoryView>, progress: NewsProgress?, settings: NewsSettings) {
    val app = LocalEnv.current.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audio by NewsAudio.state.collectAsStateWithLifecycle()
    val mine = audio.active && audio.briefingId == briefingId
    val saved = NewsUi.startPage(progress, list.size)
    val pager = rememberPagerState(initialPage = if (mine) audio.index.coerceIn(0, list.lastIndex) else saved) { list.size }
    val read = remember { mutableStateListOf<String>() }
    val language = list.first().summary.language.ifBlank { settings.language }
    fun listen(page: Int, continuous: Boolean) = NewsAudio.play(context, briefingId, list.map(::spoken), start = page,
        startMs = if (page == saved) progress?.audioMs ?: 0 else 0, rate = settings.speechRate, language = language, continuous = continuous)

    // Audio leads the pager when autoplay is on.
    LaunchedEffect(audio.index, mine) {
        if (mine && settings.autoplay && audio.index in list.indices && pager.currentPage != audio.index) pager.animateScrollToPage(audio.index)
    }
    // Page settled: sync audio, save the position, then mark the story read once it has been visible for a moment.
    LaunchedEffect(pager.settledPage) {
        val page = pager.settledPage
        val playing = NewsAudio.state.value.let { it.active && it.briefingId == briefingId }
        if (playing && settings.autoplay) NewsAudio.jumpTo(page)
        newsCall { app.news.savePosition(briefingId, page, if (playing) NewsAudio.audioMs() else 0) }
        val id = list[page].event.id
        if (id !in read) {
            delay(NewsUi.READ_AFTER_MS)
            if (newsCall { app.news.markRead(id) }.isSuccess) read += id
        }
    }

    val page = pager.currentPage
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(sectionTitle(list[page].section).uppercase(), style = Hf.type.caption.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.primary)
            Text("${page + 1} " + "of" + " ${list.size}", style = Hf.type.label, color = Hf.colors.muted)
        }
        QuietButton(stringResource(R.string.ui_previous), { scope.launch { pager.animateScrollToPage(page - 1) } }, enabled = page > 0)
        QuietButton(stringResource(R.string.ui_next), { scope.launch { pager.animateScrollToPage(page + 1) } }, enabled = page < list.lastIndex)
    }
    if (mine) HfCard(padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Listening" + " · ${audio.index + 1} " + "of" + " ${audio.count} · ${audio.title}", style = Hf.type.rowSubtitle, color = Hf.colors.muted, maxLines = 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            QuietButton(stringResource(R.string.ui_previous), { NewsAudio.previous() })
            QuietButton(if (audio.playing) stringResource(R.string.ui_pause) else stringResource(R.string.ui_intel_resume), { if (audio.playing) NewsAudio.pause() else NewsAudio.resume() })
            QuietButton(stringResource(R.string.ui_next), { NewsAudio.next() })
            QuietButton("Stop", { NewsAudio.stop() })
            Spacer(Modifier.weight(1f))
            HfChip("${audio.rate}×", { NewsAudio.setRate(NewsUi.nextSpeed(audio.rate, NewsAudio.speedSteps)) })
        }
    } else PrimaryButton("Listen to briefing", { listen(page, continuous = true) }, Modifier.fillMaxWidth(), icon = com.hellfire.ass.ui.theme.HfIcons.Waveform)
    audio.note?.let { Note(it) }

    HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp, key = { list[it].event.id }) { i ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            StoryCard(list[i], settings, read = list[i].event.id in read,
                onListen = { listen(i, continuous = settings.autoplay) },
                onSkip = {
                    val id = list[i].event.id
                    scope.launch {
                        if (newsCall { app.news.markRead(id) }.isSuccess) read += id
                        if (i < list.lastIndex) pager.animateScrollToPage(i + 1)
                    }
                })
        }
    }
}

/** One story outside a briefing (Breaking, Saved, Archive, notifications, share results). */
@Composable
fun NewsStoryPage(eventId: String) {
    val app = LocalEnv.current.app
    val context = LocalContext.current
    var settings by remember { mutableStateOf(NewsSettings()) }
    var story by remember { mutableStateOf<Result<StoryView?>?>(null) }
    var read by remember { mutableStateOf(false) }
    LaunchedEffect(eventId) {
        newsCall { app.news.settings() }.getOrNull()?.let { settings = it }
        story = newsCall { app.news.story(eventId) }
        if (story?.getOrNull() != null) { delay(NewsUi.READ_AFTER_MS); read = newsCall { app.news.markRead(eventId) }.isSuccess }
    }
    val audio by NewsAudio.state.collectAsStateWithLifecycle()
    PushedPage("Story", breadcrumb = "News") {
        val r = story
        val v = r?.getOrNull()
        when {
            r == null -> Note("Loading…")
            r.isFailure -> Banner(failText(r.exceptionOrNull(), "Could not open this story"), Tone.Danger, null)
            v == null -> Note("This story is no longer available.")
            else -> {
                if (audio.active && audio.briefingId == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Listening" + " · ${audio.title}", Modifier.weight(1f), style = Hf.type.rowSubtitle, color = Hf.colors.muted, maxLines = 1)
                    QuietButton(if (audio.playing) stringResource(R.string.ui_pause) else stringResource(R.string.ui_intel_resume), { if (audio.playing) NewsAudio.pause() else NewsAudio.resume() })
                    QuietButton("Stop", { NewsAudio.stop() })
                }
                audio.note?.let { Note(it) }
                StoryCard(v, settings, read = read, onListen = {
                    NewsAudio.play(context, null, listOf(spoken(v)), rate = settings.speechRate, language = v.summary.language.ifBlank { settings.language }, continuous = false)
                })
            }
        }
    }
}
