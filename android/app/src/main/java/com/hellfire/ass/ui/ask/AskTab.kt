package com.hellfire.ass.ui.ask

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hellfire.ass.ai.*
import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.AssistantEngine
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.*

/** One chat entry. Kept in memory for the app session only; never written to disk. */
sealed interface ChatItem {
    data class Question(val text: String) : ChatItem
    data class Reply(val answer: AssistantEngine.Answer, val via: String) : ChatItem
    data class Proposal(val command: OwnerCommand, val state: String = "OPEN") : ChatItem
    data class Status(val text: String) : ChatItem
}

/** Selected route: null means "On this phone" (offline evidence answers and commands). */
data class AskRoute(val task: AiTask, val model: ModelRef?)

object AskSession {
    val items = mutableStateListOf<ChatItem>()
    var route by mutableStateOf<AskRoute?>(null)
}

@Composable
fun AskTab() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val scope = rememberCoroutineScope()
    val engine = remember { AssistantEngine(app) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<Job?>(null) }
    var picker by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<String>?>(null) }
    val list = rememberLazyListState()
    val items = AskSession.items
    LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(items.size - 1) }

    fun send(text: String) {
        val q = text.trim(); if (q.isEmpty() || running != null) return
        items += ChatItem.Question(q); input = ""
        val route = AskSession.route
        running = scope.launch {
            try {
                if (route == null) {
                    val answer = withContext(Dispatchers.IO) { engine.answer(q) }
                    items += answer.command?.let { ChatItem.Proposal(it) } ?: ChatItem.Reply(answer, "On this phone")
                    rememberLast(app, q, answer.text)
                } else {
                    val result = withContext(Dispatchers.IO) { app.ai.generate(route.task, q, route.model) }
                    items += ChatItem.Reply(AssistantEngine.Answer(result.generation.text),
                        "${result.provider.label} · ${result.generation.model} · suggestion only · USD ${usd(result.estimatedMicroUsd)} est." +
                            result.attempts.joinToString("") { "\n${it.ref.provider.label}: ${it.error.message}" })
                    rememberLast(app, q, result.generation.text)
                }
            } catch (e: CancellationException) { items += ChatItem.Status(AiError.CANCELLED.message); throw e }
            catch (e: AiException) { items += ChatItem.Status(e.error.message) }
            catch (_: Exception) { items += ChatItem.Status("That request failed. Offline answers still work. Nothing was changed or sent.") }
            finally { running = null }
        }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = it }
    }
    fun listen() {
        try {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true).putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask HellFire"))
        } catch (_: Exception) { items += ChatItem.Status("No speech recogniser is available on this phone. Type your question instead.") }
    }
    LaunchedEffect(Unit) { if (nav.consume("voice")) listen() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ask", Modifier.weight(1f), style = Hf.type.screenTitle, color = Hf.colors.onSurface)
            Row(
                Modifier.height(36.dp).clip(CircleShape).background(Hf.colors.surface).border(1.dp, Hf.colors.outline, CircleShape)
                    .clickableRow { picker = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val r = AskSession.route
                Icon(if (r == null) HfIcons.Chip else HfIcons.Cloud, null, Modifier.size(16.dp), tint = if (r == null) Hf.colors.success else Hf.colors.info)
                Text(r?.let { it.model?.label() ?: it.task.label } ?: "On this phone", style = Hf.type.label, color = Hf.colors.onSurface, maxLines = 1)
                Icon(HfIcons.ChevronDown, null, Modifier.size(14.dp), tint = Hf.colors.muted)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (items.isEmpty()) item {
                HfCard(padding = PaddingValues(16.dp)) {
                    Text("Ask about your money, tasks and messages", style = Hf.type.cardTitle, color = Hf.colors.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("Answers come from what's saved on this phone. Commands like \"remind me to pay rent on the 1st at 9:00\" or \"log cash chakula 7000\" are proposals until you confirm. Type help for the full list.",
                        style = Hf.type.rowSubtitle.copy(fontSize = 13.sp), color = Hf.colors.muted)
                }
            }
            items(items) { item ->
                when (item) {
                    is ChatItem.Question -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(item.text, Modifier.fillMaxWidth(0.78f).wrapContentWidth(Alignment.End)
                            .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(Hf.colors.inverse).padding(horizontal = 15.dp, vertical = 11.dp),
                            style = Hf.type.body.copy(lineHeight = 20.sp), color = Hf.colors.onInverse)
                    }
                    is ChatItem.Reply -> AnswerCard(item, onSources = { sources = it }, onSeeAll = { nav.push(Screen.ReportMoney, "table") })
                    is ChatItem.Proposal -> ProposalCard(item, onConfirm = {
                        items[items.indexOf(item)] = item.copy(state = "DONE")
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                try { AssistantCommands.execute(app, item.command) }
                                catch (e: IllegalArgumentException) { e.message ?: "Command could not be applied." }
                                catch (_: Exception) { "Command could not be fully applied. Check Tasks, Inbox or Money; nothing was sent." }
                            }
                            items += ChatItem.Reply(AssistantEngine.Answer(text), "On this phone")
                        }
                    }, onEdit = { input = item.command.utterance }, onDiscard = {
                        items[items.indexOf(item)] = item.copy(state = "DISCARDED")
                        items += ChatItem.Status("Proposal discarded. Nothing changed.")
                    })
                    is ChatItem.Status -> Text(item.text, Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                }
            }
            if (running != null) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Working…", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                    QuietButton("Cancel", { running?.cancel() })
                }
            }
        }
        Box(Modifier.fillMaxWidth().background(Hf.colors.background).border(width = 0.dp, color = Hf.colors.background)) {
            Composer(input, { input = it }, { send(input) }, placeholder = "Ask or tell HellFire…",
                suggestions = listOf("Morning brief", "Spent today", "Tasks tomorrow", "Log cash"),
                onSuggestion = { s -> if (s == "Log cash") input = "log cash " else send(s.lowercase()) },
                onMic = { listen() }, enabled = running == null)
        }
    }
    if (picker) RoutePicker { picker = false }
    sources?.let { ids -> HfBottomSheet({ sources = null }) {
        Text("Sources", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Note("The saved records this answer used. Tap one to see the original.")
        ListCard {
            ids.take(50).forEachIndexed { i, id -> if (i > 0) ListDivider(16.dp)
                ListRow("Record ${id.take(10)}…", chevron = true, onClick = { sources = null; nav.push(Screen.Source, id) }) }
        }
        if (ids.size > 50) Note("Showing the first 50 of ${ids.size}.")
    } }
}

/** The last question and answer feed the Ask widget. Stored encrypted; previews start hidden on the widget. */
private fun rememberLast(app: com.hellfire.ass.HellFireApp, question: String, answer: String) {
    app.captureScope.launch {
        runCatching { app.secrets.save("ask-last", org.json.JSONObject().put("q", question.take(200)).put("a", answer.take(400)).put("at", System.currentTimeMillis())) }
    }
}

@Composable
private fun AnswerCard(item: ChatItem.Reply, onSources: (List<String>) -> Unit, onSeeAll: () -> Unit) {
    val a = item.answer
    Column(
        Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)).background(Hf.colors.surface)
            .border(1.dp, Hf.colors.outline, RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (a.figure != null) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(a.figureLabel.orEmpty(), style = Hf.type.label.copy(fontWeight = FontWeight.Normal), color = Hf.colors.muted)
            Text("TZS " + Fmt.amount(a.figure), style = Hf.type.bigFigure.copy(fontSize = 28.sp), color = Hf.colors.onSurface)
        }
        if (a.breakdown.isNotEmpty()) {
            HorizontalBars(a.breakdown.take(3), Fmt::amount, labelWidth = 76.dp)
            Row(Modifier.clickableRow(onSeeAll), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Top ${minOf(3, a.breakdown.size)} categories · see all", style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.primary)
                Icon(HfIcons.ChevronRight, null, Modifier.size(14.dp), tint = Hf.colors.primary)
            }
            Text("Only confirmed payments are counted. Own transfers are excluded.", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        } else Text(a.text, style = Hf.type.body.copy(lineHeight = 21.sp), color = Hf.colors.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.via, Modifier.weight(1f), style = Hf.type.caption.copy(fontWeight = FontWeight.Normal), color = Hf.colors.muted)
            if (a.sources.isNotEmpty()) Text("Sources (${a.sources.size})", Modifier.clip(CircleShape).clickableRow { onSources(a.sources) }.padding(horizontal = 6.dp, vertical = 8.dp),
                style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.primary)
        }
    }
}

private fun proposalRows(c: OwnerCommand): Pair<String, List<Pair<String, String>>> = when (c) {
    is OwnerCommand.Remind -> "Create this reminder?" to listOf("What" to c.title, "When" to Fmt.weekdayDayTime(c.dueAt), "Alert" to "Repeats until done")
    is OwnerCommand.Remember -> "Save this note?" to listOf("About" to c.subject, "Note" to c.text)
    is OwnerCommand.TaskAction -> "Update this task?" to listOfNotNull("Task" to (c.title ?: c.query),
        "Change" to when (c.action) { "DONE" -> "Mark done"; "SNOOZE" -> "Snooze 10 minutes"; else -> "Move" }, c.dueAt?.let { "New time" to Fmt.weekdayDayTime(it) })
    is OwnerCommand.Draft -> "Save this draft?" to listOfNotNull("Channel" to Fmt.channel(c.channel), "To" to c.recipient,
        c.subject.takeIf { it.isNotEmpty() }?.let { "Subject" to it }, "Message" to c.body.take(300))
    is OwnerCommand.Cash -> "Add this cash for review?" to (c.items.map { it.first to "TZS " + Fmt.amount(it.second) } + ("When" to Fmt.dayTime(c.occurredAt)))
}

@Composable
private fun ProposalCard(item: ChatItem.Proposal, onConfirm: () -> Unit, onEdit: () -> Unit, onDiscard: () -> Unit) {
    val (title, rows) = proposalRows(item.command)
    Column(
        Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)).background(Hf.colors.surface)
            .border(1.5.dp, if (item.state == "OPEN") Hf.colors.primary else Hf.colors.outline, RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(icon = when (item.command) { is OwnerCommand.Remind, is OwnerCommand.TaskAction -> HfIcons.Alarm; is OwnerCommand.Cash -> HfIcons.Money
                is OwnerCommand.Draft -> HfIcons.Chat; is OwnerCommand.Remember -> HfIcons.Key }, size = 30.dp)
            Text(title, style = Hf.type.cardTitle.copy(fontSize = 15.sp), color = Hf.colors.onSurface)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Hf.colors.subtle).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (k, v) -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, style = Hf.type.body.copy(fontSize = 14.sp), color = Hf.colors.muted)
                Text(v, Modifier.weight(1f), style = Hf.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Hf.colors.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            } }
        }
        if (item.command is OwnerCommand.Draft) Note("Confirming saves a draft. Sending still needs Approve and send in Inbox.")
        when (item.state) {
            "OPEN" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Confirm", onConfirm); SecondaryButton("Edit", onEdit); QuietButton("Discard", onDiscard)
            }
            "DONE" -> StatusPill("Confirmed", Tone.Success)
            else -> StatusPill("Discarded", Tone.Neutral)
        }
    }
}

/** "On this phone" or an AI task route / model. Cloud models still require consent in Settings › Cloud AI. */
@Composable
private fun RoutePicker(onDone: () -> Unit) {
    val app = LocalEnv.current.app
    var state by remember { mutableStateOf<AiState?>(null) }
    LaunchedEffect(Unit) { state = runCatching { withContext(Dispatchers.IO) { app.ai.store.read() } }.getOrNull() }
    var task by remember { mutableStateOf(AskSession.route?.task ?: AiTask.CHAT) }
    HfBottomSheet(onDone) {
        Text("Who answers", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        OptionCard("On this phone", "Answers from your saved records, plus commands. Works offline.", AskSession.route == null, { AskSession.route = null; onDone() })
        val s = state
        if (s != null) {
            GroupLabel("AI task")
            com.hellfire.ass.ui.money.ChipPicker(AiTask.entries.map { it.name }, task.name, { AiTask.valueOf(it).label }) { task = AiTask.valueOf(it) }
            OptionCard("Saved route for ${task.label}", s.route(task).joinToString(" → ") { it.label() },
                AskSession.route?.let { it.task == task && it.model == null } == true, { AskSession.route = AskRoute(task, null); onDone() })
            s.modelChoices().forEach { m ->
                OptionCard(m.label(), if (m.provider == ProviderId.LOCAL) "Offline test model" else if (s.cloudEnabled) "Cloud · sends only the text you type, after redaction" else "Cloud AI is off in Settings",
                    AskSession.route?.model == m, { AskSession.route = AskRoute(task, m); onDone() })
            }
            if (!s.cloudEnabled) Note("Cloud AI is off. Turn it on and add a provider in Settings › Cloud AI.")
        }
    }
}
