package com.hellfire.ass.ui.news

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.news.NewsSource
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.launch

@Composable
fun NewsSourcesPage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var failed by remember { mutableStateOf<Throwable?>(null) }
    val sources by remember { safeFlow(emptyList(), { failed = it }) { app.news.sources() } }.collectAsStateWithLifecycle(null)
    var input by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("CUSTOM") }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var removing by remember { mutableStateOf<NewsSource?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasToken = newsCall { app.news.hasXToken() }.getOrDefault(false) }
    fun act(what: String, done: String? = null, block: suspend () -> Unit) = scope.launch {
        val r = newsCall { block() }
        message = r.exceptionOrNull()?.let { failText(it, what) to Tone.Danger } ?: done?.let { it to Tone.Success }
    }

    PushedPage(stringResource(R.string.ui_sources), breadcrumb = "News settings") {
        message?.let { (text, tone) -> Banner(text, tone, HfIcons.Info, Modifier.clickableRow { message = null }) }
        failed?.let { Banner(failText(it, "Could not read sources"), Tone.Danger, null) }

        SectionCard("Add source") {
            HfTextField(input, { input = it.take(500) }, "Feed URL, website, @handle or x:query",
                supporting = "RSS, Atom or JSON feed; a website (its feed is found); an X account (@name) or X search (x:query)")
            Dropdown(stringResource(R.string.ui_category), category, NewsUi.categories, { NewsUi.categoryLabel(it) }, { category = it })
            PrimaryButton(if (adding) "Adding…" else "Add source", {
                adding = true
                scope.launch {
                    val r = newsCall { app.news.addSource(input.trim(), category) }
                    adding = false
                    message = r.getOrNull()?.let { s -> "Added ${s.name} · ${NewsUi.sourceStateLabel(s.state)}" + (s.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") to
                        (if (s.state == "ACTIVE") Tone.Success else Tone.Warning) } ?: (failText(r.exceptionOrNull(), "Could not add this source") to Tone.Danger)
                    if (r.isSuccess) input = ""
                }
            }, Modifier.fillMaxWidth(), enabled = input.isNotBlank() && !adding)
        }

        SectionCard("X (Twitter) API") {
            Note("X API access is paid and tiered. Add your own bearer token to follow X accounts or searches. Posts are shown as unverified social posts and are never treated as proof.")
            Text(if (hasToken) "A bearer token is saved (encrypted on this phone)." else "No bearer token saved.", style = Hf.type.body, color = Hf.colors.onSurface)
            HfTextField(token, { token = it.take(400) }, "Bearer token", password = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Save token", {
                    val t = token.trim(); token = ""
                    act("Could not save the token", "X token saved.") { app.news.setXToken(t); hasToken = true }
                }, enabled = token.isNotBlank())
                if (hasToken) SecondaryButton("Remove token", { act("Could not remove the token", "X token removed.") { app.news.setXToken(""); hasToken = false } })
            }
        }

        val all = sources
        when {
            all == null -> Note("Loading…")
            all.isEmpty() -> Note("No sources yet. Built-in sources are added the first time news refreshes.")
            else -> all.groupBy { it.category }.toSortedMap(compareBy { NewsUi.categories.indexOf(it).let { i -> if (i < 0) 99 else i } }).forEach { (cat, list) ->
                SectionHeader(NewsUi.categoryLabel(cat), trailing = list.size.toString())
                list.forEach { s -> SourceCard(s, onRemove = { removing = s }, act = ::act) }
            }
        }
        Note("Reliability is your own estimate of reporting quality; it affects ranking, never what counts as confirmed. Licensed sources are listed but never fetched without a licence.")
    }
    removing?.let { s ->
        ConfirmDialog("Remove source?", "${s.name} " + "will no longer be fetched. Stories already saved stay.", stringResource(R.string.ui_remove),
            { removing = null; act("Could not remove ${s.name}", "${s.name} removed.") { app.news.setSourceState(s.id, "REMOVED") } }, { removing = null }, destructive = true)
    }
}

@Composable
private fun SourceCard(s: NewsSource, onRemove: () -> Unit, act: (String, String?, suspend () -> Unit) -> Unit) {
    val app = LocalEnv.current.app
    HfCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.name, Modifier.weight(1f), style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                StatusPill(NewsUi.sourceStateLabel(s.state), NewsUi.sourceStateTone(s.state))
            }
            Text(listOfNotNull(s.kind, s.language, if (s.official) "Official" else null, s.agency).joinToString(" · "), style = Hf.type.rowSubtitle, color = Hf.colors.muted)
            Text(listOf("Last success" + ": " + (s.lastSuccessAt?.let { Fmt.dayTime(it) } ?: "never"),
                "${s.failures} " + "failures").joinToString(" · "), style = Hf.type.rowSubtitle, color = Hf.colors.muted)
            s.lastError?.let { Text("Last error" + ": " + it, style = Hf.type.rowSubtitle, color = Hf.colors.danger, maxLines = 3) }
            if (s.note.isNotBlank()) Note(s.note)
            Stepper("Reliability", s.reliability.toString(),
                { act("Could not update ${s.name}", null) { app.news.setSourceReliability(s.id, (s.reliability - 5).coerceAtLeast(0)) } },
                { act("Could not update ${s.name}", null) { app.news.setSourceReliability(s.id, (s.reliability + 5).coerceAtMost(100)) } },
                minusEnabled = s.reliability > 0, plusEnabled = s.reliability < 100)
            Stepper("Priority", (if (s.priority > 0) "+" else "") + s.priority,
                { act("Could not update ${s.name}", null) { app.news.setSourcePriority(s.id, s.priority - 1) } },
                { act("Could not update ${s.name}", null) { app.news.setSourcePriority(s.id, s.priority + 1) } },
                minusEnabled = s.priority > -2, plusEnabled = s.priority < 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s.state) {
                    "ACTIVE" -> SecondaryButton("Mute", { act("Could not mute ${s.name}", null) { app.news.setSourceState(s.id, "MUTED") } })
                    "MUTED" -> SecondaryButton("Unmute", { act("Could not unmute ${s.name}", null) { app.news.setSourceState(s.id, "ACTIVE") } })
                }
                QuietButton(stringResource(R.string.ui_remove), onRemove)
            }
        }
    }
}
