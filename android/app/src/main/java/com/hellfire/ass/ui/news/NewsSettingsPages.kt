package com.hellfire.ass.ui.news

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.news.*
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.launch

/** "−  value  +" control used for counts, rates, priorities and weights. */
@Composable
internal fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean = true, plusEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr(label), Modifier.weight(1f), style = Hf.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = Hf.colors.onSurface)
        QuietButton("−", onMinus, enabled = minusEnabled)
        Text(value, Modifier.widthIn(min = 44.dp), style = Hf.type.figure, color = Hf.colors.onSurface, maxLines = 1)
        QuietButton("+", onPlus, enabled = plusEnabled)
    }
}

@Composable
fun NewsSettingsPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<Result<NewsSettings>?>(null) }
    var s by remember { mutableStateOf(NewsSettings()) }
    var weights by remember { mutableStateOf(List(6) { "" }) }
    var extra by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    fun weightText(w: ScoreWeights) = listOf(w.impact, w.relevance, w.recency, w.corroboration, w.reliability, w.novelty)
        .map { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    LaunchedEffect(Unit) {
        val r = newsCall { app.news.settings() }
        r.getOrNull()?.let { s = it; weights = weightText(it.weights) }
        loaded = r
    }
    PushedPage(tr("News settings"), breadcrumb = tr("News")) {
        val r = loaded
        if (r == null) { Note(tr("Loading…")); return@PushedPage }
        r.exceptionOrNull()?.let { Banner(failText(it, "Could not read news settings"), Tone.Danger, null); return@PushedPage }
        message?.let { (text, tone) -> Banner(text, tone, HfIcons.Info) }

        SectionCard(tr("Briefings")) {
            SwitchRow(tr("News briefings"), s.enabled, { s = s.copy(enabled = it) }, subtitle = tr("Fetch public news and build scheduled briefings"))
            HfTextField(s.morning, { s = s.copy(morning = it.take(5)) }, "Morning briefing (HH:mm)",
                supporting = if (NewsUi.validTime(s.morning)) null else tr("Use 24-hour time, e.g. 07:00"))
            HfTextField(s.evening, { s = s.copy(evening = it.take(5)) }, "Evening briefing (HH:mm)",
                supporting = if (NewsUi.validTime(s.evening)) null else tr("Use 24-hour time, e.g. 19:00"))
            HfTextField(s.zone, { s = s.copy(zone = it.take(60)) }, "Time zone",
                supporting = if (NewsUi.validZone(s.zone)) null else tr("Unknown time zone, e.g. Africa/Dar_es_Salaam"))
            if (s.extra.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.extra.forEach { t -> HfChip(t, { s = s.copy(extra = s.extra - t) }, style = ChipStyle.Filter) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HfTextField(extra, { extra = it.take(5) }, "Extra briefing (HH:mm)", Modifier.weight(1f))
                SecondaryButton(tr("Add"), { if (NewsUi.validTime(extra) && extra !in s.extra) { s = s.copy(extra = (s.extra + extra).sorted()); extra = "" } },
                    enabled = NewsUi.validTime(extra))
            }
            GroupLabel("Length")
            SegmentedControl(NewsUi.lengths.map { it.second }, NewsUi.lengths.firstOrNull { it.first == s.length }?.second ?: "Standard",
                { label -> s = s.copy(length = NewsUi.lengths.first { it.second == label }.first) })
            Stepper("Maximum stories", s.maxStories.toString(), { s = s.copy(maxStories = s.maxStories - 1) }, { s = s.copy(maxStories = s.maxStories + 1) },
                minusEnabled = s.maxStories > 3, plusEnabled = s.maxStories < 30)
        }
        SectionCard(tr("Alerts")) {
            SwitchRow(tr("Breaking news alerts"), s.breakingAlerts, { s = s.copy(breakingAlerts = it) })
            SwitchRow(tr("Followed story updates"), s.updateAlerts, { s = s.copy(updateAlerts = it) }, subtitle = tr("A \"What Changed\" alert when a story you follow changes materially"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HfTextField(s.quietStart, { s = s.copy(quietStart = it.take(5)) }, "Quiet from", Modifier.weight(1f))
                HfTextField(s.quietEnd, { s = s.copy(quietEnd = it.take(5)) }, "Quiet until", Modifier.weight(1f))
            }
            SwitchRow(tr("Critical alerts during quiet hours"), s.criticalOverridesQuiet, { s = s.copy(criticalOverridesQuiet = it) },
                subtitle = tr("Emergencies and public-safety news may still notify"))
        }
        SectionCard(tr("Listening and reading")) {
            SwitchRow(tr("Autoplay"), s.autoplay, { s = s.copy(autoplay = it) }, subtitle = tr("Keep reading the next story aloud and turn the page with it"))
            Stepper("Speech rate", "${s.speechRate}×", { s = s.copy(speechRate = (s.speechRate - 0.25f).coerceAtLeast(0.75f)) },
                { s = s.copy(speechRate = (s.speechRate + 0.25f).coerceAtMost(2f)) }, minusEnabled = s.speechRate > 0.75f, plusEnabled = s.speechRate < 2f)
            GroupLabel("Summary language")
            SegmentedControl(listOf("English", "Kiswahili"), if (s.language == "sw") "Kiswahili" else "English", { s = s.copy(language = if (it == "Kiswahili") "sw" else "en") })
            SwitchRow(tr("AI summaries"), s.aiSummaries, { s = s.copy(aiSummaries = it) },
                subtitle = tr("Uses your AI routing; cloud only if Cloud AI is on; spending counts toward the monthly cap"))
            if (s.language == "sw" && !s.aiSummaries) Note(tr("Kiswahili summaries of English reports need AI summaries; otherwise the original language is shown."))
            SwitchRow(tr("Wi-Fi only"), s.wifiOnly, { s = s.copy(wifiOnly = it) }, subtitle = tr("Fetch news only on unmetered networks"))
        }
        SectionCard(tr("Importance weights")) {
            Note(tr("How much each factor counts toward a story's rank. Weights are relative; the percentages show their share."))
            val parsed = weights.map { it.toDoubleOrNull() }
            val shares = NewsUi.percentages(ScoreWeights(parsed[0] ?: 0.0, parsed[1] ?: 0.0, parsed[2] ?: 0.0, parsed[3] ?: 0.0, parsed[4] ?: 0.0, parsed[5] ?: 0.0))
            NewsUi.weightNames.forEachIndexed { i, name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HfTextField(weights[i], { v -> weights = weights.toMutableList().also { it[i] = v.take(6) } }, name, Modifier.weight(1f), number = true,
                        supporting = if (parsed[i] == null || parsed[i]!! < 0) tr("Enter a number, 0 or more") else null)
                    Text("${shares[i]}%", Modifier.widthIn(min = 48.dp), style = Hf.type.figure, color = Hf.colors.muted)
                }
            }
            QuietButton(tr("Reset to defaults"), { weights = weightText(ScoreWeights()) })
        }
        ListCard {
            ListRow(tr("Sources"), subtitle = tr("Feeds, X accounts, health and reliability"), icon = HfIcons.Inbox, chevron = true, onClick = { nav.push(Screen.NewsSources) })
            ListDivider()
            ListRow(tr("Interests"), subtitle = tr("Topics you follow, learned preferences and hidden subjects"), icon = HfIcons.Spark, chevron = true, onClick = { nav.push(Screen.NewsInterests) })
            ListDivider()
            ListRow(tr("News health"), subtitle = tr("Fetch errors, AI cost and corrections"), icon = HfIcons.Reports, chevron = true, onClick = { nav.push(Screen.NewsHealth) })
        }
        PrimaryButton(tr("Save news settings"), {
            val parsed = weights.map { it.toDoubleOrNull() }
            val error = when {
                !NewsUi.validTime(s.morning) || !NewsUi.validTime(s.evening) -> "Briefing times must be HH:mm."
                !NewsUi.validTime(s.quietStart) || !NewsUi.validTime(s.quietEnd) -> "Quiet hours must be HH:mm."
                !NewsUi.validZone(s.zone) -> "Unknown time zone."
                parsed.any { it == null || it < 0 } || parsed.sumOf { it ?: 0.0 } <= 0 -> "Importance weights must be numbers, 0 or more, and not all 0."
                else -> null
            }
            if (error != null) { message = error to Tone.Danger; return@PrimaryButton }
            val next = s.copy(zone = s.zone.trim(), weights = ScoreWeights(parsed[0]!!, parsed[1]!!, parsed[2]!!, parsed[3]!!, parsed[4]!!, parsed[5]!!))
            scope.launch {
                val saved = newsCall { app.news.saveSettings(next) }
                message = if (saved.isSuccess) "News settings saved." to Tone.Success else failText(saved.exceptionOrNull(), "Could not save news settings") to Tone.Danger
            }
        }, Modifier.fillMaxWidth())
    }
}

@Composable
fun NewsInterestsPage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf<Throwable?>(null) }
    val prefs by remember { safeFlow(emptyList(), { failed = it }) { app.news.preferences() } }.collectAsStateWithLifecycle(null)
    var term by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    fun act(what: String, block: suspend () -> Unit) = scope.launch {
        newsCall { block() }.exceptionOrNull()?.let { message = failText(it, what) }
    }
    PushedPage(tr("Interests"), breadcrumb = tr("News settings")) {
        failed?.let { Banner(failText(it, "Could not read your interests"), Tone.Danger, null) }
        message?.let { Banner(it, Tone.Info, HfIcons.Info, Modifier.clickableRow { message = null }) }
        val all = prefs
        if (all == null) { Note(tr("Loading…")); return@PushedPage }
        val explicit = all.filter { it.kind == "EXPLICIT" }.associateBy { it.key }
        SectionCard(tr("Topics you follow")) {
            Note(tr("Tap a topic to follow it, then set how much it counts. Public-interest news still shows for topics you don't follow."))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NewsUi.topics.forEach { (key, label) ->
                    val on = key in explicit
                    HfChip(tr(label), { act("Could not update $label") { if (on) app.news.removeInterest(key, "EXPLICIT") else app.news.setInterest(key, label, 1.0) } },
                        style = if (on) ChipStyle.Selected else ChipStyle.Outline)
                }
            }
            val custom = explicit.values.filter { p -> NewsUi.topics.none { it.first == p.key } }
            (NewsUi.topics.mapNotNull { explicit[it.first] } + custom).forEach { p ->
                Stepper(p.label, String.format(java.util.Locale.ROOT, "%.1f", p.weight),
                    { act("Could not update ${p.label}") { app.news.setInterest(p.key, p.label, (p.weight - 0.5).coerceAtLeast(0.5)) } },
                    { act("Could not update ${p.label}") { app.news.setInterest(p.key, p.label, (p.weight + 0.5).coerceAtMost(3.0)) } },
                    minusEnabled = p.weight > 0.5, plusEnabled = p.weight < 3.0)
            }
            custom.forEach { p -> QuietButton(tr("Remove") + " " + p.label, { act("Could not remove ${p.label}") { app.news.removeInterest(p.key, "EXPLICIT") } }) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HfTextField(term, { term = it.take(60) }, "Custom term, e.g. TANESCO", Modifier.weight(1f))
                SecondaryButton(tr("Add"), {
                    val t = term.trim()
                    act("Could not add $t") { app.news.setInterest("term:" + t.lowercase(), t, 1.0) }; term = ""
                }, enabled = term.isNotBlank())
            }
        }
        val learned = all.filter { it.kind == "INFERRED" }
        SectionCard(tr("Learned from your reading")) {
            Note(tr("HellFire learns from what you open, finish, skip and rate. These nudge ranking a little; they never hide critical public news."))
            if (learned.isEmpty()) Note(tr("Nothing learned yet."))
            learned.forEach { p -> ListRow(p.label.ifBlank { p.key }, subtitle = String.format(java.util.Locale.ROOT, "%+.2f", p.weight)) }
            if (learned.isNotEmpty()) SecondaryButton(tr("Reset learned preferences"), { confirmReset = true }, Modifier.fillMaxWidth())
        }
        val hidden = all.filter { it.kind == "HIDDEN" }
        SectionCard(tr("Hidden subjects")) {
            if (hidden.isEmpty()) Note(tr("No hidden subjects. Hide one from a story card."))
            hidden.forEach { p ->
                ListRow(p.label.ifBlank { p.key }, trailing = { QuietButton(tr("Unhide"), { act("Could not unhide ${p.label}") { app.news.removeInterest(p.key, "HIDDEN") } }) })
            }
        }
    }
    if (confirmReset) ConfirmDialog(tr("Reset learned preferences?"), tr("Topics you chose and hidden subjects stay. Only what HellFire learned from your reading is cleared."),
        tr("Reset"), { confirmReset = false; act("Could not reset") { app.news.resetLearned() } }, { confirmReset = false }, destructive = true)
}

@Composable
fun NewsHealthPage() {
    val app = LocalEnv.current.app
    var health by remember { mutableStateOf<Result<List<NewsStageCount>>?>(null) }
    var corrections by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf<Throwable?>(null) }
    val sources by remember { safeFlow(emptyList(), { failed = it }) { app.news.sources() } }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(Unit) {
        health = newsCall { app.news.health(7) }
        corrections = newsCall { app.database.news().clusterCorrections() }.getOrNull()
    }
    PushedPage(tr("News health"), breadcrumb = tr("News settings")) {
        Note(tr("The last 7 days on this phone."))
        val r = health
        val rows = r?.getOrNull()
        when {
            r == null -> Note(tr("Loading…"))
            rows == null -> Banner(failText(r.exceptionOrNull(), "Could not read news health"), Tone.Danger, null)
            else -> {
                fun runs(stage: String, ok: Boolean) = rows.filter { it.stage == stage && it.ok == ok }.sumOf { it.runs }
                fun items(stage: String) = rows.filter { it.stage == stage && it.ok }.sumOf { it.items }
                KvList(listOf(
                    tr("Source fetches OK") to "${runs("INGEST", true)} · ${items("INGEST")} " + tr("reports"),
                    tr("Source fetches failed") to runs("INGEST", false).toString(),
                    tr("Grouping errors") to runs("CLUSTER", false).toString(),
                    tr("Summary errors") to runs("SUMMARY", false).toString(),
                    tr("Briefings built") to runs("BRIEFING", true).toString(),
                    tr("Estimated AI cost") to NewsUi.money(rows.sumOf { it.microUsd }),
                    tr("Grouping corrections") to (corrections?.toString() ?: "—"),
                ))
            }
        }
        failed?.let { Banner(failText(it, "Could not read sources"), Tone.Danger, null) }
        val failing = sources.filter { it.failures > 0 || it.lastError != null }
        SectionHeader(tr("Sources with failures"), trailing = failing.size.toString())
        if (failing.isEmpty()) Note(tr("No source is failing."))
        else ListCard {
            failing.forEachIndexed { i, src ->
                if (i > 0) ListDivider(16.dp)
                ListRow(src.name, subtitle = "${src.failures} " + tr("failures") + (src.lastError?.let { " · $it" } ?: ""),
                    trailing = { StatusPill(tr(NewsUi.sourceStateLabel(src.state)), NewsUi.sourceStateTone(src.state)) })
            }
        }
        Note(tr("AI cost is an estimate from your AI routing and counts toward the monthly cap."))
    }
}
