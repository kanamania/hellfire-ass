package com.hellfire.ass.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskExtractor
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A task the sheet should edit: an existing proposal or task, or a new one typed by the owner. */
data class TaskDraft(val id: String?, val title: String, val dueAt: Long, val party: String, val direction: String = "NONE",
    val amount: Long? = null, val kind: String = "TASK", val repeatMonthly: Boolean = false, val personKey: String? = null) {
    companion object { fun of(t: AssistantTask) = TaskDraft(t.id, t.title, t.dueAt, t.party, t.direction, t.amount, t.kind, t.repeatMonthly, t.personKey) }
}

@Composable
fun TasksTab() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val scope = rememberCoroutineScope()
    val tasks by remember { app.database.tasks().tasks() }.collectAsStateWithLifecycle(emptyList())
    var view by rememberSaveable { mutableStateOf("Today") }
    var editing by remember { mutableStateOf<TaskDraft?>(null) }
    var undo by remember { mutableStateOf<AssistantTask?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf<Map<String, SourceEvent>>(emptyMap()) }
    val proposals = tasks.filter { it.state == "REVIEW" }
    LaunchedEffect(proposals.map { it.sourceId }) {
        sources = withContext(Dispatchers.IO) { proposals.mapNotNull { t -> app.database.events().find(t.sourceId)?.let { t.sourceId to it } }.toMap() }
    }
    LaunchedEffect(undo) { if (undo != null) { delay(6000); undo = null } }
    val today = Fmt.today()
    val endToday = Fmt.startOf(today.plusDays(1)); val startToday = Fmt.startOf(today)
    val now = System.currentTimeMillis()
    fun done(t: AssistantTask) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { TaskScheduler.action(app, t.id, "DONE") } }
            .onSuccess { undo = t }.onFailure { banner = "Could not mark done. Check storage." }
    }

    Box(Modifier.fillMaxSize()) {
        ScrollPage(bottomInset = 92.dp) {
            ScreenHeader("Tasks") { IconCircleButton(HfIcons.Bolt, "Templates", { nav.push(Screen.Templates) }) }
            SegmentedControl(listOf("Today", "Upcoming", "Done"), view, { view = it })
            banner?.let { Banner(it, modifier = Modifier.clickableRow { banner = null }) }
            undo?.let { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Hf.colors.inverse).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Done: ${t.title.take(40)}", Modifier.weight(1f), style = Hf.type.body.copy(fontSize = 14.sp), color = Hf.colors.onInverse, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    QuietButton("Undo", { undo = null; scope.launch { runCatching { withContext(Dispatchers.IO) { TaskScheduler.reopen(app, t.id) } } } })
                }
            }
            if (view != "Done") proposals.forEach { t ->
                val src = sources[t.sourceId]
                ActionCard(
                    context = "Found in ${src?.let { EventText.sourceLabel(it) } ?: "a message"}" + (src?.let { " · ${Fmt.relativeDay(it.occurredAt)}" } ?: ""),
                    contextIcon = HfIcons.Chat, quote = t.evidence.take(220),
                    actions = {
                        PrimaryButton("Add task", { scope.launch {
                            banner = runCatching { withContext(Dispatchers.IO) { TaskScheduler.activate(app, t.id, t.title, t.dueAt, t.party) } }
                                .fold({ "Task added. Reminder set for ${Fmt.weekdayDayTime(t.dueAt)}." }, { "Not added. Check the date and reminder access." })
                        } })
                        SecondaryButton("Edit", { editing = TaskDraft.of(t) })
                        QuietButton("Not a task", { scope.launch { withContext(Dispatchers.IO) { TaskScheduler.action(app, t.id, "REJECTED") } } })
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.title.take(120), style = Hf.type.cardTitle.copy(fontSize = 15.sp), color = Hf.colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${Fmt.weekdayDayTime(t.dueAt)} · suggested time · ${partyLabel(t.party)}", style = Hf.type.label.copy(fontWeight = FontWeight.Normal), color = Hf.colors.muted)
                    }
                }
            }
            val active = tasks.filter { it.state == "ACTIVE" }
            when (view) {
                "Today" -> {
                    val due = active.filter { it.dueAt < endToday }.sortedBy { it.dueAt }
                    val finished = tasks.filter { it.state == "DONE" && it.updatedAt >= startToday }
                    SectionHeader("Today", trailing = "${finished.size} of ${due.size + finished.size} done")
                    TaskList(due + finished, now, onToggle = { t -> if (t.state == "ACTIVE") done(t) else scope.launch { runCatching { withContext(Dispatchers.IO) { TaskScheduler.reopen(app, t.id) } } } },
                        onOpen = { nav.push(Screen.Task, it.id) }, empty = "Nothing due today.")
                }
                "Upcoming" -> {
                    val later = active.filter { it.dueAt >= endToday }.sortedBy { it.dueAt }
                    if (later.isEmpty()) TaskList(emptyList(), now, {}, {}, "No upcoming tasks.")
                    later.groupBy { Fmt.zoned(it.dueAt).toLocalDate() }.forEach { (day, items) ->
                        SectionHeader(if (day == today.plusDays(1)) "Tomorrow" else java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM", java.util.Locale.ENGLISH).format(day))
                        TaskList(items, now, { done(it) }, { nav.push(Screen.Task, it.id) }, "")
                    }
                }
                else -> {
                    val finished = tasks.filter { it.state == "DONE" }.sortedByDescending { it.updatedAt }.take(60)
                    SectionHeader("Done", trailing = "${finished.size} shown")
                    TaskList(finished, now, { t -> scope.launch { runCatching { withContext(Dispatchers.IO) { TaskScheduler.reopen(app, t.id) } } } },
                        { nav.push(Screen.Task, it.id) }, "Nothing done yet.")
                }
            }
            Note("Reminder sound, exact alarms and the nightly review are in Settings › Reminders.")
        }
        QuickAdd(quick, { quick = it }, Modifier.align(Alignment.BottomCenter)) {
            val text = quick.trim()
            val parsed = TaskExtractor.extract(text, System.currentTimeMillis(), outgoing = true, requireCommitment = false)
            val fallback = java.time.ZonedDateTime.now(TaskTime.zone).plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
            editing = TaskDraft(null, text, parsed?.dueAt ?: fallback, "OWNER")
            quick = ""
        }
    }
    editing?.let { d -> TaskEditSheet(d) { msg -> editing = null; if (msg != null) banner = msg } }
}

fun partyLabel(party: String) = when (party) { "OWNER" -> "you do it"; "OTHER" -> "someone else does it"; else -> "who does it is unclear" }

@Composable
private fun TaskList(items: List<AssistantTask>, now: Long, onToggle: (AssistantTask) -> Unit, onOpen: (AssistantTask) -> Unit, empty: String) {
    if (items.isEmpty()) { if (empty.isNotEmpty()) HfCard(Modifier.offset(y = (-6).dp), padding = PaddingValues(16.dp)) { Text(empty, style = Hf.type.body, color = Hf.colors.muted) }; return }
    HfCard(Modifier.offset(y = (-6).dp)) {
        items.forEachIndexed { i, t ->
            if (i > 0) ListDivider(52.dp)
            TaskRow(t, now, { onToggle(t) }, { onOpen(t) })
        }
    }
}

@Composable
private fun TaskRow(t: AssistantTask, now: Long, onToggle: () -> Unit, onOpen: () -> Unit) {
    val done = t.state == "DONE"
    val overdue = !done && t.dueAt < now
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 60.dp).toggleable(done, role = Role.Checkbox, onValueChange = { onToggle() }).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(if (done) Hf.colors.success else Hf.colors.surface)
                .border(2.dp, if (done) Hf.colors.success else Hf.colors.faint, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (done) Icon(HfIcons.Check, null, Modifier.size(15.dp), tint = Hf.colors.surface)
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(t.title, style = if (done) Hf.type.body.copy(fontWeight = FontWeight.Medium, textDecoration = TextDecoration.LineThrough) else Hf.type.rowTitle,
                    color = if (done) Hf.colors.faint else Hf.colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when {
                    done -> "Done · ${Fmt.relativeDay(t.updatedAt)}"
                    overdue -> "Overdue since ${Fmt.relativeDay(t.dueAt)}${if (t.dueAt >= Fmt.startOf(Fmt.today())) "" else " " + Fmt.time(t.dueAt)}"
                    else -> "${Fmt.time(t.dueAt)} · ${if (t.party == "OWNER") "Reminder" else partyLabel(t.party)}"
                }, style = Hf.type.rowSubtitle.copy(fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (overdue) Hf.colors.warning else Hf.colors.muted)
            }
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickableRow(onOpen).semantics { contentDescription = "Open ${t.title}" }, contentAlignment = Alignment.Center) {
            Icon(HfIcons.ChevronRight, null, Modifier.size(18.dp), tint = Hf.colors.faint)
        }
    }
}

@Composable
private fun QuickAdd(value: String, onChange: (String) -> Unit, modifier: Modifier, onAdd: () -> Unit) {
    val c = Hf.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(52.dp).shadow(8.dp, CircleShape, ambientColor = c.inverse.copy(alpha = 0.25f), spotColor = c.inverse.copy(alpha = 0.25f))
            .clip(CircleShape).background(c.surface).border(1.dp, c.outline, CircleShape).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Add a task — 'call Neema tomorrow 15:00'", style = Hf.type.body, color = c.muted, maxLines = 1)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = "New task" }, singleLine = true,
                textStyle = Hf.type.body.copy(color = c.onSurface), cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onAdd() }))
        }
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.inverse).clickableRow { if (value.isNotBlank()) onAdd() }.semantics { contentDescription = "Add task" },
            contentAlignment = Alignment.Center) { Icon(HfIcons.Plus, null, Modifier.size(22.dp), tint = c.onInverse) }
    }
}

/** Edit and schedule a task. For a new task typed by the owner, nothing is stored until "Add task". */
@Composable
fun TaskEditSheet(draft: TaskDraft, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(draft.title) }
    var person by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<com.hellfire.ass.contacts.ContactChoice?>(null) }
    var personLoaded by remember { mutableStateOf(draft.personKey == null) }
    LaunchedEffect(draft.personKey) {
        person = withContext(Dispatchers.IO) { draft.personKey?.let { app.database.contacts().find(it)?.displayName } }.orEmpty()
        personLoaded = true
    }
    var due by remember { mutableStateOf(TaskTime.display(draft.dueAt)) }
    var party by remember { mutableStateOf(draft.party) }
    var direction by remember { mutableStateOf(draft.direction) }
    var amount by remember { mutableStateOf(draft.amount?.toString().orEmpty()) }
    var kind by remember { mutableStateOf(draft.kind) }
    var repeat by remember { mutableStateOf(draft.repeatMonthly) }
    var error by remember { mutableStateOf<String?>(null) }
    HfBottomSheet({ onDone(null) }) {
        Text(if (draft.id == null) "New task" else "Edit task", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        HfTextField(title, { title = it }, "Task", singleLine = false)
        if (personLoaded) ContactPersonField(person, { person = it; picked = null }, "Person (optional)", onPick = { picked = it; person = it.displayName })
        SavedContactStatus(draft.personKey)
        HfTextField(due, { due = it }, "Due · yyyy-MM-dd HH:mm · East Africa Time")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val base = Fmt.today()
            listOf("Today 17:00" to base.atTime(17, 0), "Tomorrow 09:00" to base.plusDays(1).atTime(9, 0), "Next week" to base.plusWeeks(1).atTime(9, 0)).forEach { (label, at) ->
                HfChip(label, { due = TaskTime.display(at.atZone(TaskTime.zone).toInstant().toEpochMilli()) })
            }
        }
        GroupLabel("Who does it")
        com.hellfire.ass.ui.money.ChipPicker(listOf("OWNER", "OTHER", "UNKNOWN"), party, { when (it) { "OWNER" -> "Me"; "OTHER" -> "Someone else"; else -> "Not sure" } }) { party = it }
        GroupLabel("Kind")
        com.hellfire.ass.ui.money.ChipPicker(listOf("TASK", "BILL"), kind, { if (it == "BILL") "Bill to pay" else "Task" }) { kind = it }
        GroupLabel("Promise")
        com.hellfire.ass.ui.money.ChipPicker(listOf("I_OWE", "OWED_TO_ME", "NONE"), direction, { when (it) { "I_OWE" -> "I owe"; "OWED_TO_ME" -> "Owed to me"; else -> "Neither" } }) { direction = it }
        if (kind == "BILL" || direction != "NONE") HfTextField(amount, { amount = it.filter(Char::isDigit) }, "Amount TZS (optional, not added to Money)", number = true)
        if (kind == "BILL") CheckRow("Repeats every month", repeat, onChange = { repeat = it })
        error?.let { Banner(it, Tone.Danger, null) }
        PrimaryButton(if (draft.id == null) "Add task" else "Save and schedule", {
            scope.launch {
                try {
                    val at = TaskTime.parse(due)
                    withContext(Dispatchers.IO) {
                        val id = draft.id ?: run {
                            val now = System.currentTimeMillis()
                            val raw = com.hellfire.ass.events.NotificationSnapshot(java.util.UUID.randomUUID().toString(), "owner.task", now, now, source = "owner_entry",
                                fields = mapOf("text" to listOf(title)))
                            val safe = com.hellfire.ass.events.NotificationNormalizer.normalize(raw, app.packageName)!!
                            require(!safe.securitySensitive) { "Authentication content is not suitable for a reminder" }
                            app.events.capture(raw, app.packageName)
                            java.util.UUID.randomUUID().toString().also { id ->
                                app.database.tasks().insert(AssistantTask(id, safe.id, title, at, party = party, evidence = title, createdAt = now, updatedAt = now))
                            }
                        }
                        TaskScheduler.activate(app, id, title.trim(), at, party)
                        TaskScheduler.updateDetails(app, id, direction, amount.toLongOrNull()?.takeIf { it > 0 }, kind, repeat)
                        val links = com.hellfire.ass.contacts.ContactLinks(app.database)
                        app.database.withTransaction {
                            links.task(id, if (person.isBlank()) null else links.save(picked ?: com.hellfire.ass.contacts.ContactChoice(person)))
                        }
                    }
                    onDone("Task scheduled for ${Fmt.weekdayDayTime(at)}.")
                } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); error = "Not saved. Use yyyy-MM-dd HH:mm and remove any security codes." }
            }
        }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && personLoaded, height = 52.dp)
    }
}
