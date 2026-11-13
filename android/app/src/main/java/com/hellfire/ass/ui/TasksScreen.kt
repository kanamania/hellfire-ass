package com.hellfire.ass.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.tasks.*
import kotlinx.coroutines.*
import java.util.UUID

@Composable
fun TasksScreen(app: HellFireApp, onSettings: (String) -> Unit, onNotifications: () -> Unit) {
    val dao = remember { app.database.tasks() }
    val taskFlow = remember { dao.tasks() }
    val reviewFlow = remember { dao.reviews() }
    val tasks by taskFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val reviews by reviewFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(TaskTime.display(System.currentTimeMillis() + 3600_000)) }
    var status by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var nightly by remember { mutableStateOf(app.getSharedPreferences("capture", 0).getBoolean("nightly_review", true)) }
    Text("Tasks and reviews", style = MaterialTheme.typography.titleLarge)
    Text("Extracted commitments require review before scheduling. Suggested times default to 09:00 when no supported time is present; verify the date, time and responsible party.")
    Text(status)
    OutlinedButton(onClick = onNotifications) { Text("Allow reminder notifications") }
    if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { onSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }) { Text("Manage exact-alarm access") }
    Text("Without exact access reminders use best-effort Android alarms. Doze, DND, battery restrictions and force-stop can still delay or block delivery.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Nightly cash review · 21:00 East Africa Time", modifier = Modifier.weight(1f))
        Switch(checked = nightly, onCheckedChange = { enabled ->
            nightly = enabled
            app.getSharedPreferences("capture", 0).edit().putBoolean("nightly_review", enabled).apply()
            scope.launch { runCatching { TaskScheduler.restore(app) } }
        })
    }
    var repeatMinutes by remember { mutableStateOf(app.getSharedPreferences("capture", 0).getInt("overdue_minutes", 10).toString()) }
    OutlinedTextField(repeatMinutes, { repeatMinutes = it }, label = { Text("Overdue repeat interval · 5–1440 minutes") }, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = {
        val minutes = repeatMinutes.toIntOrNull()
        if (minutes == null || minutes !in 5..1440) status = "Choose a whole interval from 5 to 1440 minutes."
        else {
            app.getSharedPreferences("capture", 0).edit().putInt("overdue_minutes", minutes).apply()
            status = "Overdue repeat interval saved; applies after the next alert. Android delivery restrictions still apply."
        }
    }) { Text("Save overdue interval") }
    reviews.forEach { review ->
        Text("Unresolved expense review: ${review.day}", style = MaterialTheme.typography.titleSmall)
        Text("Review your ledger, unknown merchants and unlogged cash expenses in Finance. Skipped reviews remain here.")
        TextButton(onClick = { confirm = "Mark ${review.day} cash review complete?" to { dao.completeReview(review.day, System.currentTimeMillis()) } }) { Text("I reviewed this day") }
    }
    HorizontalDivider()
    OutlinedTextField(title, { title = it }, label = { Text("New task title") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(due, { due = it }, label = { Text("Due · yyyy-MM-dd HH:mm · East Africa Time") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = title.isNotBlank(), onClick = {
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    val at = TaskTime.parse(due)
                    val now = System.currentTimeMillis()
                    val raw = NotificationSnapshot(UUID.randomUUID().toString(), "owner.task", now, now, source = "owner_entry", fields = mapOf("text" to listOf(title)))
                    val safe = NotificationNormalizer.normalize(raw, app.packageName)!!
                    require(!safe.securitySensitive) { "Authentication content is not suitable for a reminder" }
                    app.events.capture(raw, app.packageName)
                    dao.insert(AssistantTask(UUID.randomUUID().toString(), safe.id, title, at, party = "OWNER", evidence = title, createdAt = now, updatedAt = now))
                }
                title = ""; "Task saved for approval below."
            } catch (_: Exception) { "Task not saved. Check the date/time and remove authentication codes." }
        }
    }) { Text("Preview new task") }
    tasks.take(100).forEach { task ->
        var editedTitle by remember(task.id, task.updatedAt) { mutableStateOf(task.title) }
        var editedDue by remember(task.id, task.updatedAt) { mutableStateOf(TaskTime.display(task.dueAt)) }
        var party by remember(task.id, task.updatedAt) { mutableStateOf(task.party) }
        HorizontalDivider()
        Text("${task.state} · responsible: ${task.party} · alerts: ${task.alertCount}", style = MaterialTheme.typography.titleSmall)
        Text("Source: ${task.sourceId}")
        Text(task.evidence.take(1000))
        if (task.state in setOf("REVIEW", "ACTIVE")) {
            OutlinedTextField(editedTitle, { editedTitle = it }, label = { Text("Task") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(editedDue, { editedDue = it }, label = { Text("Due · yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth())
            Choice("Responsible party", listOf("OWNER", "OTHER", "UNKNOWN"), party) { party = it }
            Button(onClick = { confirm = "Approve this task and reminder at $editedDue East Africa Time?" to { TaskScheduler.activate(app, task.id, editedTitle, TaskTime.parse(editedDue), party) } }) {
                Text(if (task.state == "REVIEW") "Approve and schedule" else "Confirm reschedule")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirm = "Mark this task done?" to { TaskScheduler.action(app, task.id, "DONE") } }) { Text("Done") }
                if (task.state == "ACTIVE") TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { TaskScheduler.action(app, task.id, "SNOOZE") }; status = "Snoozed for 10 minutes." } }) { Text("Snooze 10 min") }
                else TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { TaskScheduler.action(app, task.id, "REJECTED") } } }) { Text("Reject") }
            }
        } else Text("${task.title} · ${TaskTime.display(task.dueAt)}")
    }
    confirm?.let { pending ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Confirm task action") }, text = { Text(pending.first) },
            confirmButton = { TextButton(onClick = {
                confirm = null
                scope.launch { status = try { withContext(Dispatchers.IO) { pending.second() }; "Task action saved. Check reminder access status." }
                    catch (_: Exception) { "Action failed. Check date/time, permissions and storage." } }
            }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}
