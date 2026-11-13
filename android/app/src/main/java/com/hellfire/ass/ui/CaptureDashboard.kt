package com.hellfire.ass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.PermissionStatus
import com.hellfire.ass.data.NotificationPolicy
import com.hellfire.ass.processing.CaptureWork
import com.hellfire.ass.sms.SmsSyncWorker
import kotlinx.coroutines.*

@Composable
fun CaptureDashboard(app: HellFireApp, permissions: PermissionStatus) {
    val dao = remember { app.database.events() }
    val queueFlow = remember(dao) { dao.queueSummary() }
    val auditFlow = remember(dao) { dao.audits() }
    val policyFlow = remember(dao) { dao.appPolicies() }
    val queues by queueFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val audits by auditFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val policies by policyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    Text("Source status", style = MaterialTheme.typography.titleLarge)
    Text("Notifications: " + if (permissions.notificationAccess) "access granted" else "access missing")
    Text("SMS: " + when {
        !permissions.smsEnabled -> "capture disabled"
        !permissions.smsRead -> "read permission missing"
        !permissions.smsReceive -> "provider sync enabled; incoming broadcast permission missing"
        else -> "capture enabled; provider delivery still needs device verification"
    })
    Text("Processing: " + (queues.joinToString { "${it.state}: ${it.count}" }.ifEmpty { "no saved work" }))
    audits.forEach { audit ->
        Text("${audit.source}: ${audit.captured} captured · ${audit.failures} recorded failures · ${audit.dropped} recorded drops")
        audit.lastCapturedAt?.let { Text("Last capture: ${formatCaptureTime(it)}") }
        audit.lastError?.let { Text("Last recorded issue: $it", color = MaterialTheme.colorScheme.error) }
    }
    OutlinedButton(onClick = {
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) { dao.retryFailed() }
                CaptureWork.process(app)
                "Pending and failed events scheduled for processing."
            } catch (_: Exception) { "Processing could not be scheduled. Saved events remain in Room." }
        }
    }) { Text("Resume saved processing") }
    if (permissions.smsEnabled && permissions.smsRead) OutlinedButton(onClick = {
        scope.launch {
            message = try { SmsSyncWorker.schedule(app); "SMS reconciliation scheduled." }
                catch (_: Exception) { "SMS reconciliation could not be scheduled." }
        }
    }) { Text("Sync authorized SMS history") }
    message?.let { Text(it) }
    if (policies.isNotEmpty()) {
        HorizontalDivider()
        Text("Notification processing", style = MaterialTheme.typography.titleMedium)
        Text("All accessible notifications are retained. Turn processing off to keep future/pending events as capture only. Existing results remain unchanged.")
        policies.forEach { policy ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(policy.packageName, modifier = Modifier.weight(1f).padding(top = 12.dp))
                Switch(checked = policy.processEnabled, onCheckedChange = { enabled ->
                    scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) { dao.setPolicy(NotificationPolicy(policy.packageName, enabled)) }
                            if (enabled) "Processing enabled for ${policy.packageName}." else "Capture only for ${policy.packageName}."
                        } catch (_: Exception) { "Processing policy could not be saved." }
                    }
                }, modifier = Modifier.semantics { contentDescription = "Process notifications from ${policy.packageName}" })
            }
        }
    }
    HorizontalDivider()
}

private fun formatCaptureTime(time: Long): String = java.time.format.DateTimeFormatter
    .ofPattern("d MMM, HH:mm:ss").withZone(java.time.ZoneId.of("Africa/Dar_es_Salaam"))
    .format(java.time.Instant.ofEpochMilli(time))
