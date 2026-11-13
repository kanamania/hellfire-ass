package com.hellfire.ass.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.PermissionStatus
import com.hellfire.ass.assistant.LocalAssistant
import com.hellfire.ass.audio.*
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.notifications.NotificationCaptureStatus
import com.hellfire.ass.processing.CaptureWork
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HellFireScreen(
    app: HellFireApp,
    permissions: PermissionStatus,
    initialTab: String,
    message: String?,
    onRole: () -> Unit,
    onSettings: (String) -> Unit,
    onMicrophonePermission: () -> Unit,
    onSession: () -> Unit,
    onNotifications: () -> Unit,
    onSmsEnable: () -> Unit,
    onSmsDisable: () -> Unit,
) {
    val storage by app.storageStatus.collectAsStateWithLifecycle()
    if (storage != "READY") {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("HellFire Ass", style = MaterialTheme.typography.headlineMedium)
                    Text(if (storage == "STARTING") "Opening encrypted local storage…" else "Encrypted storage is unavailable. Check free storage and device unlock/key availability. Existing files have been retained; no plaintext fallback or automatic reset was used.")
                    if (storage != "STARTING") Button(onClick = { app.initializeStorage() }) { Text("Retry local storage") }
                }
            }
        }
        return
    }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFFFB595))
        else lightColorScheme(primary = Color(0xFF9A3412), secondary = Color(0xFF675044))
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var tab by rememberSaveable { mutableStateOf(initialTab) }
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("HellFire Ass", style = MaterialTheme.typography.headlineMedium)
                    Text("Local capture · finance · assistant", style = MaterialTheme.typography.bodyMedium)
                }
                ScrollableTabRow(selectedTabIndex = listOf("Setup", "Assistant", "Capture", "Finance", "Messages", "Tasks", "Media", "Local AI", "Backup", "Device").indexOf(tab), edgePadding = 8.dp) {
                    listOf("Setup", "Assistant", "Capture", "Finance", "Messages", "Tasks", "Media", "Local AI", "Backup", "Device").forEach { name ->
                        Tab(selected = tab == name, onClick = { tab = name }, text = { Text(name) })
                    }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (message != null) Text(message, color = MaterialTheme.colorScheme.primary)
                    when (tab) {
                        "Setup" -> Setup(permissions, onRole, onSettings, onMicrophonePermission, onSmsEnable, onSmsDisable)
                        "Assistant" -> AssistantScreen(app, onSession)
                        "Capture" -> Capture(app, permissions, onMicrophonePermission, onSettings)
                        "Finance" -> FinanceScreen(app)
                        "Messages" -> CommunicationsScreen(app)
                        "Tasks" -> TasksScreen(app, onSettings, onNotifications)
                        "Media" -> MediaScreen(app, permissions, onMicrophonePermission)
                        "Local AI" -> LocalAiScreen(app)
                        "Backup" -> BackupScreen(app)
                        "Device" -> Diagnostics(permissions)
                    }
                }
            }
        }
    }
}

@Composable
private fun Setup(status: PermissionStatus, onRole: () -> Unit, onSettings: (String) -> Unit, onMicrophone: () -> Unit, onSmsEnable: () -> Unit, onSmsDisable: () -> Unit) {
    Text("Connect Android access", style = MaterialTheme.typography.titleLarge)
    Text("Access is optional and controlled by Android. Return here after changing settings to see the current state.")
    Status("Default assistant", when {
        status.voiceServiceActive -> "HellFire voice service selected"
        status.roleHeld -> "Role held; HellFire voice service is not active"
        status.roleAvailable -> "Not selected"
        else -> "Assistant role unavailable; check system settings"
    })
    Button(onClick = onRole) { Text("Select digital assistant") }
    TextButton(onClick = { onSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) }) { Text("Open assistant settings") }
    Status("Notification access", if (status.notificationAccess) "Granted" else "Not granted")
    Text("Captures all notifications Android makes available. Authentication codes are redacted; protected content may be absent.")
    Button(onClick = { onSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("Manage notification access") }
    Status("Microphone", if (status.microphone) "Granted" else "Not granted")
    Status("Recording notifications", if (status.notificationPosting) "Allowed" else "Not allowed")
    Button(onClick = onMicrophone) { Text("Allow microphone and notifications") }
    TextButton(onClick = { onSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) { Text("Open app permission settings") }
    Status("Read SMS", if (status.smsRead) "Granted" else "Not granted")
    Status("Receive SMS", if (status.smsReceive) "Granted" else "Not granted")
    Status("SMS capture", if (status.smsEnabled) "Enabled by owner" else "Disabled")
    Text("Enabling SMS capture authorizes importing existing incoming/sent SMS and reconciling new messages locally. Android or your installer may restrict SMS permissions. This app does not send SMS.")
    if (!status.smsEnabled) Button(onClick = onSmsEnable) { Text("Enable SMS capture and history import") }
    else OutlinedButton(onClick = onSmsDisable) { Text("Disable SMS capture") }
    Status("Exact alarms", when (status.exactAlarm) {
        true -> "Android reports exact-alarm access"
        false -> "Not granted · reminders use best-effort alarms"
        null -> "Special permission not required on this OS"
    })
    Status("Media access", "Owner-selected files only · no broad library permission")
    Status("Battery optimization", if (status.batteryExempt) "Android optimization exemption reported" else "Subject to Android battery optimization")
    Text("ColorOS background controls require manual verification even if Android reports an exemption.")
    TextButton(onClick = { onSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("Open battery settings") }
}

@Composable
private fun Capture(app: HellFireApp, permissions: PermissionStatus, onPermission: () -> Unit, onSettings: (String) -> Unit) {
    val connected by NotificationCaptureStatus.connected.collectAsStateWithLifecycle()
    val error by NotificationCaptureStatus.error.collectAsStateWithLifecycle()
    val dropped by NotificationCaptureStatus.dropped.collectAsStateWithLifecycle()
    val count by app.events.count.collectAsStateWithLifecycle(initialValue = 0)
    val recent by app.events.recent.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    CaptureDashboard(app, permissions)
    Text("Captured event inbox", style = MaterialTheme.typography.titleLarge)
    Status("Listener", if (connected && permissions.notificationAccess) "Connected" else if (permissions.notificationAccess) "Access granted; listener disconnected" else "Access missing")
    Text("$count stored events · $dropped queue drops this process")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = {
        scope.launch {
            result = try {
                withContext(Dispatchers.IO) {
                    app.events.capture(NotificationSnapshot(
                        key = "synthetic-phase-zero-v1", packageName = "com.hellfire.fixture", postedAt = 1,
                        receivedAt = System.currentTimeMillis(), source = "synthetic_notification",
                        fields = mapOf("title" to listOf("Synthetic Phase 0 fixture"), "text" to listOf("Offline capture test")),
                    ), app.packageName)
                }.let { inserted ->
                    CaptureWork.process(app)
                    if (inserted) "Synthetic event stored in Room." else "Synthetic event already stored; duplicate ignored." }
            } catch (_: Exception) { "Local storage test failed. Check device storage and encryption availability." }
        }
    }) { Text("Insert synthetic test event") }
    result?.let { Text(it) }
    Text("This button tests normalization, encryption and Room. It does not prove Android listener delivery.")
    recent.forEach { event ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (event.source == "synthetic_notification") "Synthetic notification fixture" else event.packageName,
                style = MaterialTheme.typography.titleSmall)
            Text("${event.kind} · ${formatTime(event.receivedAt)} · " + when {
                !event.contentAvailable -> "content unavailable"
                event.contentRedacted -> "redacted"
                else -> "content as delivered"
            })
            Text("${event.source} · ${event.state} · ${event.label ?: "unclassified"}")
        }
        HorizontalDivider()
    }
    TextButton(onClick = { confirmClear = true }, enabled = !permissions.notificationAccess && !permissions.smsEnabled) { Text("Delete captured events") }
    Text("Disable notification access and SMS capture before deleting the inbox. Database and payloads are encrypted. Finance/task/media-linked source evidence cannot be removed through inbox clearing.")
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Delete all captured events?") },
        text = { Text("This removes stored captured events and their processing jobs from this device.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch {
                result = try { withContext(Dispatchers.IO) { app.database.events().deleteAll() }; "Captured events deleted." }
                    catch (_: Exception) { "Could not delete captured events." }
            }
        }) { Text("Delete events") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    HorizontalDivider()
    Recording(app, permissions, onPermission, onSettings)
}

@Composable
private fun Recording(app: HellFireApp, permissions: PermissionStatus, onPermission: () -> Unit, onSettings: (String) -> Unit) {
    val state by MicrophoneStatus.state.collectAsStateWithLifecycle()
    val busy by MicrophoneStatus.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var playback by remember { mutableStateOf<Job?>(null) }
    var files by remember { mutableStateOf(emptyList<java.io.File>()) }
    var result by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
            if (event == Lifecycle.Event.ON_STOP) { playback?.cancel(); playback = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); playback?.cancel() }
    }
    LaunchedEffect(busy, refresh) {
        files = withContext(Dispatchers.IO) {
            MicrophoneService.recordingDirectory(app).listFiles()?.filter { it.name.endsWith(".pcm.enc") }?.sortedByDescending { it.lastModified() }.orEmpty()
        }
    }
    Text("Microphone proof of concept", style = MaterialTheme.typography.titleLarge)
    Status("Recording", state)
    Text("Records up to 60 seconds of mono PCM audio, encrypted directly on this device. Start only when everyone involved is aware. Samples remain until you delete them.")
    if (!permissions.microphone || !permissions.notificationPosting) {
        Button(onClick = onPermission) { Text("Allow recording permissions") }
    }
    Button(enabled = !busy && playback == null && permissions.microphone && permissions.notificationPosting, onClick = {
        try {
            app.startForegroundService(Intent(app, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_START))
        } catch (_: Exception) { MicrophoneStatus.state.value = "Android blocked recording. Keep the app visible and check permissions." }
    }) { Text("Start 60-second recording") }
    OutlinedButton(enabled = busy, onClick = {
        app.startService(Intent(app, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_STOP))
    }) { Text("Stop recording") }
    TextButton(onClick = { onSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS) }) { Text("Recording notification settings") }
    result?.let { Text(it) }
    if (playback != null) OutlinedButton(onClick = { playback?.cancel(); playback = null }) { Text("Stop playback") }
    Text("${files.size} encrypted samples retained", style = MaterialTheme.typography.titleMedium)
    files.forEach { file ->
        Text("Sample · ${formatTime(file.lastModified())}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(enabled = !busy && playback == null, onClick = {
                playback = scope.launch {
                    try {
                        result = "Playing encrypted sample locally…"
                        withContext(Dispatchers.IO) { playSample(app, file) }
                        result = "Playback finished."
                    } catch (e: CancellationException) { result = "Playback stopped."; throw e }
                    catch (_: Exception) { result = "Sample could not be played. It may be incomplete or its device key is unavailable." }
                    finally { playback = null }
                }
            }) { Text("Play sample") }
            TextButton(enabled = !busy && playback == null, onClick = {
                scope.launch {
                    result = if (withContext(Dispatchers.IO) { file.delete() }) "Sample deleted." else "Sample could not be deleted."
                    refresh++
                }
            }) { Text("Delete sample") }
        }
    }
}

@Composable
private fun Diagnostics(status: PermissionStatus) {
    Text("This device", style = MaterialTheme.typography.titleLarge)
    Status("Manufacturer / model", "${Build.MANUFACTURER} / ${Build.MODEL}")
    Status("Android / API", "${Build.VERSION.RELEASE} / ${Build.VERSION.SDK_INT}")
    Status("Build", Build.DISPLAY)
    Status("CPU ABIs", Build.SUPPORTED_ABIS.joinToString())
    Status("Security patch", Build.VERSION.SECURITY_PATCH)
    Status("Assistant role", if (status.roleHeld) "Held" else "Not held")
    Status("Voice service", if (status.voiceServiceActive) "Selected" else "Not selected")
    Status("Owner timezone", "Africa/Dar_es_Salaam")
    Text("The model name comes from Android. ColorOS version, OEM gestures, lock-screen invocation, microphone interruptions and overnight behaviour must be verified on the physical phone.")
    Text("Device, model and live-account feasibility results are recorded separately; no physical verification has been claimed.")
}

@Composable
private fun Status(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatTime(millis: Long): String = DateTimeFormatter.ofPattern("d MMM, HH:mm:ss")
    .withZone(ZoneId.of("Africa/Dar_es_Salaam")).format(Instant.ofEpochMilli(millis))
