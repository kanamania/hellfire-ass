package com.hellfire.ass.ui.tasks

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TaskPage(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val task by remember(id) { app.database.tasks().observe(id) }.collectAsStateWithLifecycle(null)
    var editing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun act(label: String, block: suspend () -> Unit) = scope.launch {
        message = runCatching { withContext(Dispatchers.IO) { block() } }.fold({ label }, { "Action failed. Check the date, permissions and storage." })
    }
    val t = task
    PushedPage(stringResource(R.string.ui_task), breadcrumb = "Tasks") {
        if (t == null) { Note("This task is no longer available."); return@PushedPage }
        message?.let { Banner(it) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.title, style = Hf.type.reportTitle, color = Hf.colors.onSurface)
            Text("${Fmt.weekdayDayTime(t.dueAt)} · ${partyLabel(t.party)}", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        }
        KvList(listOf(
            "Status" to when (t.state) { "REVIEW" -> "Waiting for you"; "ACTIVE" -> if (t.dueAt < System.currentTimeMillis()) "Overdue" else "Scheduled"
                "DONE" -> "Done"; "REJECTED" -> "Not a task"; else -> t.state },
            "Due" to Fmt.full(t.dueAt) + " EAT",
            "Reminders sent" to t.alertCount.toString(),
            "Next reminder" to (t.nextAlertAt?.let(Fmt::dayTime) ?: "None"),
        ))
        if (t.evidence != t.title) EvidenceCard("Found in", t.evidence.take(1500))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (t.state) {
                "REVIEW" -> {
                    PrimaryButton(stringResource(R.string.ui_add_task), { act("Task added and reminder set.") { TaskScheduler.activate(app, t.id, t.title, t.dueAt, t.party) } })
                    SecondaryButton(stringResource(R.string.ui_edit), { editing = true })
                    QuietButton(stringResource(R.string.ui_not_a_task), { act("Marked as not a task.") { TaskScheduler.action(app, t.id, "REJECTED") } })
                }
                "ACTIVE" -> {
                    PrimaryButton(stringResource(R.string.ui_done), { act("Marked done.") { TaskScheduler.action(app, t.id, "DONE") } })
                    SecondaryButton("Snooze ${t.snoozeMinutes} min", { act("Snoozed for ${t.snoozeMinutes} minutes.") { TaskScheduler.action(app, t.id, "SNOOZE") } })
                    SecondaryButton(stringResource(R.string.ui_reschedule), { editing = true })
                }
                "DONE" -> SecondaryButton(stringResource(R.string.ui_not_done_yet), { act("Task is active again.") { TaskScheduler.reopen(app, t.id) } })
            }
        }
        com.hellfire.ass.calendar.TaskCalendarControls(t)
        QuietButton(stringResource(R.string.ui_show_source), { nav.push(Screen.Source, t.sourceId) })
    }
    if (editing && t != null) TaskEditSheet(TaskDraft.of(t)) { msg -> editing = false; if (msg != null) message = msg }
}

/** Settings › Reminders (not drawn): the reminder controls that used to sit on the Tasks tab. */
@Composable
fun RemindersPage() {
    val env = LocalEnv.current
    val app = env.app
    val scope = rememberCoroutineScope()
    val prefs = remember { app.getSharedPreferences("capture", 0) }
    val states by remember { app.database.communications().integrations() }.collectAsStateWithLifecycle(emptyList())
    var nightly by remember { mutableStateOf(prefs.getBoolean("nightly_review", true)) }
    var morning by remember { mutableStateOf(prefs.getBoolean("morning_brief", true)) }
    var briefHour by remember { mutableStateOf(prefs.getInt("brief_hour", 7).coerceIn(4, 11)) }
    var gapAlerts by remember { mutableStateOf(prefs.getBoolean("gap_alerts", true)) }
    var repeat by remember { mutableStateOf(prefs.getInt("overdue_minutes", 10).toString()) }
    var status by remember { mutableStateOf<String?>(null) }
    PushedPage(stringResource(R.string.ui_reminders), breadcrumb = "Settings") {
        status?.let { Banner(it, Tone.Info, null) }
        val reminder = states.firstOrNull { it.adapter == "REMINDERS" }
        KvList(listOf("Delivery" to when (reminder?.state) {
            "EXACT_ALLOWED" -> "On time (exact alarms)"; "BEST_EFFORT" -> "Best effort"; "PERMISSION_MISSING" -> "Notifications blocked"
            "FAILED" -> "Last alarm failed"; null -> "Not checked yet"; else -> reminder.state }))
        SectionCard(stringResource(R.string.ui_access)) {
            SecondaryButton(stringResource(R.string.ui_allow_reminder_notifications), env.actions.onNotifications, Modifier.fillMaxWidth())
            if (Build.VERSION.SDK_INT >= 31) SecondaryButton(stringResource(R.string.ui_manage_exact_alarm_access), { env.actions.onSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }, Modifier.fillMaxWidth())
            Note("Without exact access reminders use best-effort Android alarms. Doze, Do Not Disturb, battery restrictions and force-stop can still delay or block delivery.")
        }
        HfCard {
            SwitchRow(stringResource(R.string.ui_nightly_cash_review), nightly, { enabled ->
                nightly = enabled
                prefs.edit().putBoolean("nightly_review", enabled).apply()
                scope.launch { runCatching { TaskScheduler.restore(app) } }
            }, subtitle = "21:00 East Africa Time")
            ListDivider(16.dp)
            SwitchRow(stringResource(R.string.ui_morning_brief), morning, { enabled ->
                morning = enabled
                prefs.edit().putBoolean("morning_brief", enabled).apply()
                scope.launch { runCatching { TaskScheduler.restore(app) } }
            }, subtitle = "A quiet notification with today's tasks, bills and replies")
            if (morning) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                com.hellfire.ass.ui.money.ChipPicker((5..9).map { it.toString() }, briefHour.toString(), { "%02d:00".format(it.toInt()) }) { picked ->
                    briefHour = picked.toInt()
                    prefs.edit().putInt("brief_hour", briefHour).apply()
                    scope.launch { runCatching { TaskScheduler.restore(app) } }
                }
            }
            ListDivider(16.dp)
            SwitchRow(stringResource(R.string.ui_capture_problem_alerts), gapAlerts, { enabled ->
                gapAlerts = enabled
                prefs.edit().putBoolean("gap_alerts", enabled).apply()
            }, subtitle = "Tell me when notifications, SMS, email or WhatsApp stop being saved")
        }
        SectionCard(stringResource(R.string.ui_overdue_reminders)) {
            HfTextField(repeat, { repeat = it }, "Repeat every · 5–1440 minutes", number = true)
            PrimaryButton(stringResource(R.string.ui_save_interval), {
                val minutes = repeat.toIntOrNull()
                status = if (minutes == null || minutes !in 5..1440) "Choose a whole interval from 5 to 1440 minutes."
                else { prefs.edit().putInt("overdue_minutes", minutes).apply(); "Saved. Applies after the next reminder. Android delivery limits still apply." }
            }, Modifier.fillMaxWidth())
        }
    }
}
