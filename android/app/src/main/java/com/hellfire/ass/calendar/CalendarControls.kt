package com.hellfire.ass.calendar

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import kotlinx.coroutines.*

@Composable
fun CalendarPermissionRow() {
    val app = LocalEnv.current.app
    val provider = remember { PhoneCalendar(app) }
    var read by remember { mutableStateOf(provider.canRead()) }
    var write by remember { mutableStateOf(provider.canWrite()) }
    fun refresh() { read = provider.canRead(); write = provider.canWrite() }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    ListCard {
        ListRow("Device calendar", subtitle = "Read: ${if (read) "granted" else "missing"} · Write: ${if (write) "granted" else "missing"}",
            chevron = true, onClick = { request.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) })
    }
    Note("Reads appointments from calendars synced on this phone. Every create, edit and cancellation needs your approval. Manage revoked access in Android App settings › Permissions › Calendar.")
}

@Composable
fun TaskCalendarControls(task: AssistantTask) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val nav = LocalNav.current
    val link by remember(task.id) { app.database.calendar().observeLink(task.id) }.collectAsStateWithLifecycle(null)
    var choices by remember(task.id) { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var chosen by remember(task.id) { mutableStateOf<Long?>(null) }
    var expanded by remember(task.id) { mutableStateOf(false) }
    var title by remember(task.id, task.updatedAt) { mutableStateOf(task.title) }
    var start by remember(task.id, task.updatedAt) { mutableStateOf(TaskTime.display(task.dueAt)) }
    var end by remember(task.id, task.updatedAt) { mutableStateOf(TaskTime.display(task.dueAt + 3_600_000)) }
    var zone by remember(task.id) { mutableStateOf(task.timezone) }
    var message by remember(task.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<CalendarChange?>(null) }
    var warnings by remember { mutableStateOf("") }
    fun run(block: suspend () -> Unit) { scope.launch {
        busy = true
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message?.takeIf { e is IllegalArgumentException || e is IllegalStateException } ?: "Calendar unavailable. Check permissions and calendar settings." }
        finally { busy = false }
    } }
    fun review(operation: String) = run {
        val selected = requireNotNull(if (operation != "CREATE") link?.calendarId else chosen) { "Choose a calendar" }
        val change = app.calendar.prepare(task.id, operation, selected, if (operation == "CANCEL") task.title else title,
            if (operation == "CANCEL") task.dueAt else TaskTime.parse(start), if (operation == "CANCEL") task.dueAt + 3_600_000 else TaskTime.parse(end), zone)
        val agenda = app.calendar.agenda(change.start, change.end)
        val overlaps = agenda.events.filter { it.id != change.eventId && it.busy && !it.allDay }
        warnings = buildString {
            if (operation != "CANCEL" && overlaps.isNotEmpty()) append("\nOverlaps: ${overlaps.take(5).joinToString { it.title.take(80) }}${if (overlaps.size > 5) " and ${overlaps.size - 5} more" else ""}.")
            if (agenda.status != "AVAILABLE" || agenda.truncated) append("\nOverlap check incomplete: ${agenda.statusLabel}.")
            if (link?.state == "RESTORED") append("\nRestored link cannot be reused on this phone. Check your calendar for an existing event before creating another.")
        }
        proposal = change
    }
    SectionCard("Device calendar") {
        Note(when (link?.state) { "LINKED" -> "Linked event. Task changes need a separate calendar approval below."; "CANCELED" -> "Calendar event canceled.";
            "RESTORED" -> "Restored history. Calendar IDs and approvals from the old installation are disabled."; else -> "Confirm a task first, then choose a calendar and review its event." })
        message?.let { androidx.compose.material3.Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        SecondaryButton("Calendar permissions", { nav.push(Screen.Permissions) })
        SecondaryButton("Check calendar result", { run { message = app.calendar.reconcile(task.id) } }, enabled = !busy)
        SecondaryButton(if (expanded) "Refresh writable calendars" else "Choose calendar…", { run {
            choices = app.calendar.calendars(); chosen = link?.takeIf { it.state == "LINKED" }?.calendarId
            expanded = true
            if (choices.isEmpty()) message = "No visible writable calendar. Add or enable one in your calendar app."
        } }, enabled = !busy)
        if (expanded) {
            choices.forEach { choice -> SecondaryButton((if (chosen == choice.id) "Selected: " else "") + choice.label, { chosen = choice.id }, enabled = !busy && link?.state != "LINKED") }
            HfTextField(title, { title = it }, "Event title")
            HfTextField(start, { start = it }, "Start · yyyy-MM-dd HH:mm EAT")
            HfTextField(end, { end = it }, "End · yyyy-MM-dd HH:mm EAT")
            HfTextField(zone, { zone = it }, "Event timezone · e.g. Africa/Dar_es_Salaam")
            Note("Times above are entered in EAT; the event timezone controls calendar display. A synced calendar may upload approved changes to its account. HellFire reminders remain separate.")
            if (task.state == "ACTIVE") PrimaryButton(if (link?.state == "LINKED") "Review calendar edit" else "Review calendar event", {
                review(if (link?.state == "LINKED") "UPDATE" else "CREATE")
            }, enabled = !busy)
            if (link?.state == "LINKED") QuietButton("Review calendar cancellation", { review("CANCEL") }, enabled = !busy)
        }
    }
    proposal?.let { change ->
        ConfirmDialog("Approve calendar ${change.operation.lowercase()}?",
            "${change.calendarLabel}\n${change.title}\n${TaskTime.display(change.start)} – ${TaskTime.display(change.end)} EAT\nTimezone: ${change.timezone}\nLinked to this task's original source.\nThis may sync to the selected calendar account.$warnings",
            "Approve ${change.operation.lowercase()}", onConfirm = {
                proposal = null
                run { val token = app.calendar.approve(change.id); message = when (app.calendar.applyApproved(change.id, token).substringBefore(" ·")) {
                    "APPLIED" -> "Calendar updated."
                    "NOT_APPLIED" -> "Calendar change not applied. Check permissions, task and destination, then review again."
                    else -> "Calendar result unconfirmed. Use Check calendar result before trying again."
                } }
            }, onDismiss = { proposal = null })
    }
}
