package com.hellfire.ass.ui.tasks

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

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
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.contacts.ContactChoice
import com.hellfire.ass.contacts.ContactLinks
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.ui.money.ChipPicker

/** A task the sheet should edit: an existing proposal or task, or a new one typed by the owner. */
data class TaskDraft(val id: String?, val title: String, val dueAt: Long, val party: String, val direction: String = "NONE",
    val amount: Long? = null, val kind: String = "TASK", val repeatMonthly: Boolean = false, val personKey: String? = null, val sourceId: String? = null, val repeatRule: String = "NONE", val alertProfile: String = "PERSISTENT", val snoozeMinutes: Int = 10) {
    companion object { fun of(t: AssistantTask) = TaskDraft(t.id, t.title, t.dueAt, t.party, t.direction, t.amount, t.kind, t.repeatMonthly, t.personKey, t.sourceId, t.repeatRule, t.alertProfile, t.snoozeMinutes) }
}

@Composable
fun TasksTab() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val scope = rememberCoroutineScope()
    val tasks by remember { app.database.tasks().tasks() }.collectAsStateWithLifecycle(emptyList())
    var view by rememberSaveable { mutableStateOf("Today") }
    var editing by rememberSaveable(saver = TaskDraftStateSaver) { mutableStateOf<TaskDraft?>(null) }
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
            ScreenHeader(stringResource(R.string.ui_tasks)) { IconCircleButton(HfIcons.Bolt, "Templates", { nav.push(Screen.Templates) }) }
            SegmentedControl(listOf("Today", "Upcoming", "Done"), view, { view = it }, label = { when (it) { "Today" -> stringResource(R.string.ui_today); "Upcoming" -> stringResource(R.string.ui_upcoming); "Done" -> stringResource(R.string.ui_done); else -> it } })
            banner?.let { Banner(it, modifier = Modifier.clickableRow { banner = null }) }
            undo?.let { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Hf.colors.inverse).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Done: ${t.title.take(40)}", Modifier.weight(1f), style = Hf.type.body.copy(fontSize = 14.sp), color = Hf.colors.onInverse, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    QuietButton(stringResource(R.string.ui_undo), { undo = null; scope.launch { runCatching { withContext(Dispatchers.IO) { TaskScheduler.reopen(app, t.id) } } } })
                }
            }
            if (view != "Done") proposals.forEach { t ->
                val src = sources[t.sourceId]
                ActionCard(
                    context = "Found in ${src?.let { EventText.sourceLabel(it) } ?: "a message"}" + (src?.let { " · ${Fmt.relativeDay(it.occurredAt)}" } ?: ""),
                    contextIcon = HfIcons.Chat, quote = t.evidence.take(220),
                    actions = {
                        PrimaryButton(stringResource(R.string.ui_add_task), { scope.launch {
                            banner = runCatching { withContext(Dispatchers.IO) { TaskScheduler.activate(app, t.id, t.title, t.dueAt, t.party) } }
                                .fold({ "Task added. Reminder set for ${Fmt.weekdayDayTime(t.dueAt)}." }, { "Not added. Check the date and reminder access." })
                        } })
                        SecondaryButton(stringResource(R.string.ui_edit), { editing = TaskDraft.of(t) })
                        Note("Why suggested: time/commitment wording was found in the source. Check who promised, and the date.")
                        QuietButton(stringResource(R.string.ui_not_a_task), { scope.launch { withContext(Dispatchers.IO) { TaskScheduler.action(app, t.id, "REJECTED"); app.skills.feedbackFor(app, "IGNORE_TASK", t.sourceId, "You marked this proposal as not a task. Review this exact-match rule before using it.") } } })
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
                    SectionHeader(stringResource(R.string.ui_today), trailing = "${finished.size} of ${due.size + finished.size} done")
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
                    SectionHeader(stringResource(R.string.ui_done), trailing = "${finished.size} shown")
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
    var title by rememberSaveable { mutableStateOf(draft.title) }
    var person by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable(saver = ContactChoiceStateSaver) { mutableStateOf<ContactChoice?>(null) }
    var personLoaded by rememberSaveable { mutableStateOf(draft.personKey == null) }
    LaunchedEffect(draft.personKey) {
        if (!personLoaded) {
            person = withContext(Dispatchers.IO) { draft.personKey?.let { app.database.contacts().find(it)?.displayName } }.orEmpty()
            personLoaded = true
        }
    }
    var due by rememberSaveable { mutableStateOf(TaskTime.display(draft.dueAt)) }
    var party by rememberSaveable { mutableStateOf(draft.party) }
    var direction by rememberSaveable { mutableStateOf(draft.direction) }
    var amount by rememberSaveable { mutableStateOf(draft.amount?.toString().orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(draft.kind) }
    var repeatRule by rememberSaveable { mutableStateOf(if (draft.repeatRule != "NONE") draft.repeatRule else if (draft.kind == "BILL" && draft.repeatMonthly) "MONTHLY" else "NONE") }
    var profile by rememberSaveable { mutableStateOf(draft.alertProfile) }
    var snooze by rememberSaveable { mutableStateOf(draft.snoozeMinutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    HfBottomSheet({ onDone(null) }) {
        Text(if (draft.id == null) "New task" else "Edit task", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        HfTextField(title, { title = it }, stringResource(R.string.ui_task), singleLine = false)
        if (personLoaded) ContactPersonField(person, { person = it; picked = null }, stringResource(R.string.ui_person_optional), onPick = { picked = it; person = it.displayName })
        SavedContactStatus(draft.personKey)
        DateTimeField(due, { due = it }, stringResource(R.string.ui_due_east_africa_time))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val base = Fmt.today()
            listOf("Today 17:00" to base.atTime(17, 0), "Tomorrow 09:00" to base.plusDays(1).atTime(9, 0), "Next week" to base.plusWeeks(1).atTime(9, 0)).forEach { (label, at) ->
                HfChip(label, { due = TaskTime.display(at.atZone(TaskTime.zone).toInstant().toEpochMilli()) })
            }
        }
        GroupLabel(stringResource(R.string.ui_who_does_it))
        ChipPicker(listOf("OWNER", "OTHER", "UNKNOWN"), party, { when (it) { "OWNER" -> stringResource(R.string.ui_me); "OTHER" -> stringResource(R.string.ui_someone_else); else -> stringResource(R.string.ui_not_sure) } }) { party = it }
        GroupLabel(stringResource(R.string.ui_kind))
        ChipPicker(listOf("TASK", "BILL"), kind, { if (it == "BILL") stringResource(R.string.ui_bill_to_pay) else stringResource(R.string.ui_task) }) { kind = it }
        GroupLabel(stringResource(R.string.ui_promise))
        ChipPicker(listOf("I_OWE", "OWED_TO_ME", "NONE"), direction, { when (it) { "I_OWE" -> stringResource(R.string.ui_i_owe); "OWED_TO_ME" -> stringResource(R.string.ui_owed_to_me); else -> stringResource(R.string.ui_neither) } }) { direction = it }
        if (kind == "BILL" || direction != "NONE") HfTextField(amount, { amount = it.filter(Char::isDigit) }, stringResource(R.string.ui_amount_tzs_optional_not_added_to_money), number = true)
        GroupLabel(stringResource(R.string.ui_repeat))
        ChipPicker(listOf("NONE", "DAILY", "WEEKLY", "MONTHLY"), repeatRule, { when (it) { "NONE" -> stringResource(R.string.ui_none); "DAILY" -> stringResource(R.string.ui_daily); "WEEKLY" -> stringResource(R.string.ui_weekly); "MONTHLY" -> stringResource(R.string.ui_monthly); else -> it } }) { repeatRule = it }
        GroupLabel(stringResource(R.string.ui_overdue_reminder_intensity))
        ChipPicker(listOf("GENTLE", "NORMAL", "PERSISTENT"), profile, { when (it) { "GENTLE" -> stringResource(R.string.ui_daily); "NORMAL" -> stringResource(R.string.ui_hourly); else -> stringResource(R.string.ui_keep_reminding) } }) { profile = it }
        HfTextField(snooze, { snooze = it.filter(Char::isDigit) }, stringResource(R.string.ui_snooze_minutes_5_1440), number = true)
        Note(stringResource(R.string.ui_repeating_tasks_schedule_the_next))
        error?.let { Banner(it, Tone.Danger, null) }
        PrimaryButton(if (draft.id == null) "Add task" else "Save and schedule", {
            scope.launch {
                try {
                    val at = TaskTime.parse(due)
                    val snoozeMinutes = snooze.toInt().also { require(it in 5..1440) }
                    withContext(Dispatchers.IO) {
                        val id = draft.id ?: run {
                            val now = System.currentTimeMillis()
                            val raw = NotificationSnapshot(java.util.UUID.randomUUID().toString(), "owner.task", now, now, source = "owner_entry",
                                fields = mapOf("text" to listOf(title)))
                            val safe = NotificationNormalizer.normalize(raw, app.packageName)!!
                            require(!safe.securitySensitive) { "Authentication content is not suitable for a reminder" }
                            val sourceId = draft.sourceId ?: safe.id.also { app.events.capture(raw, app.packageName) }
                            require(app.database.events().find(sourceId)?.let { it.contentAvailable && !it.securitySensitive } == true)
                            java.util.UUID.randomUUID().toString().also { id ->
                                app.database.tasks().insert(AssistantTask(id, sourceId, title, at, party = party, evidence = title, createdAt = now, updatedAt = now))
                            }
                        }
                        TaskScheduler.activate(app, id, title.trim(), at, party)
                        TaskScheduler.updateDetails(app, id, direction, amount.toLongOrNull()?.takeIf { it > 0 }, kind, repeatRule == "MONTHLY" && kind == "BILL")
                        TaskScheduler.updateReminderOptions(app, id, repeatRule, profile, snoozeMinutes)
                        val links = ContactLinks(app.database)
                        app.database.withTransaction {
                            links.task(id, if (person.isBlank()) null else links.save(picked ?: ContactChoice(person, personKey = draft.personKey?.takeIf { app.database.contacts().find(it)?.displayName == person })))
                        }
                    }
                    onDone("Task scheduled for ${Fmt.weekdayDayTime(at)}.")
                } catch (caught: Exception) { Diagnostics.warn(caught); error = "Not saved. Use yyyy-MM-dd HH:mm and remove any security codes." }
            }
        }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && personLoaded, height = 52.dp)
    }
}
