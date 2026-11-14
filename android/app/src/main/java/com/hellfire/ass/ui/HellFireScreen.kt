package com.hellfire.ass.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HellFireTheme
import com.hellfire.ass.ui.theme.HfIcons
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
import com.hellfire.ass.ai.*
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
    initialTab: MainTab,
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
    HellFireTheme {
        if (storage != "READY") {
            Surface(Modifier.fillMaxSize(), color = Hf.colors.background) {
                Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("HellFire Ass", style = Hf.type.screenTitle)
                    Text(if (storage == "STARTING") "Opening encrypted local storage…" else "Encrypted storage is unavailable. Check free storage and device unlock/key availability. Existing files have been retained; no plaintext fallback or automatic reset was used.",
                        style = Hf.type.body)
                    if (storage != "STARTING") PrimaryButton("Retry local storage", { app.initializeStorage() })
                }
            }
            return@HellFireTheme
        }
        var tab by rememberSaveable { mutableStateOf(initialTab) }
        val stack = rememberSaveable(saver = listSaver<MutableList<Page>, String>({ it.map(Page::name) }, { it.map(Page::valueOf).toMutableStateList() })) {
            mutableStateListOf()
        }
        val page = stack.lastOrNull()
        BackHandler(enabled = page != null) { stack.removeAt(stack.lastIndex) }
        BackHandler(enabled = page == null && tab != MainTab.Home) { tab = MainTab.Home }
        val open: (Page) -> Unit = { stack.add(it) }
        Scaffold(
            containerColor = Hf.colors.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = { if (page == null) HellFireBottomBar(tab) { tab = it } },
        ) { inner ->
            key(tab, page) {
                Column(
                    Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding()
                        .verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (page != null) PushedTopBar(page.title, onBack = { stack.removeAt(stack.lastIndex) })
                    if (message != null) Banner(message, Tone.Info, HfIcons.Info)
                    when (page) {
                        null -> when (tab) {
                            MainTab.Home -> Home(onOpen = open)
                            MainTab.Money -> { ScreenHeader("Money"); FinanceScreen(app) }
                            MainTab.Ask -> { ScreenHeader("Ask"); AssistantScreen(app, onSession); HorizontalDivider(); Assistant(app, onSession) }
                            MainTab.Tasks -> { ScreenHeader("Tasks"); TasksScreen(app, onSettings, onNotifications) }
                            MainTab.Inbox -> { ScreenHeader("Inbox"); CommunicationsScreen(app) }
                        }
                        Page.Settings -> SettingsHub(onOpen = open)
                        Page.Permissions -> {
                            Setup(permissions, onRole, onSettings, onMicrophonePermission, onSmsEnable, onSmsDisable)
                            HorizontalDivider()
                            Capture(app, permissions, onMicrophonePermission, onSettings)
                        }
                        Page.CloudAi -> AiSettingsScreen(app)
                        Page.LocalModels -> LocalAiScreen(app)
                        Page.Recordings -> MediaScreen(app, permissions, onMicrophonePermission)
                        Page.Backup -> BackupScreen(app)
                        Page.Device -> Diagnostics(permissions)
                    }
                }
            }
        }
    }
}

enum class MainTab(val label: String, val icon: ImageVector) {
    Home("Home", HfIcons.Home), Money("Money", HfIcons.Money), Ask("Ask", HfIcons.Spark),
    Tasks("Tasks", HfIcons.Task), Inbox("Inbox", HfIcons.Inbox),
}

/** Screens pushed over the tabs (back arrow, no bottom bar). */
enum class Page(val title: String) {
    Settings("Settings"), Permissions("Permissions and capture"), CloudAi("Cloud AI"), LocalModels("On-device models"),
    Recordings("Recordings"), Backup("Encrypted backup"), Device("This device"),
}

@Composable
private fun HellFireBottomBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    val c = Hf.colors
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier.fillMaxWidth().background(c.surface).drawBehind {
            drawLine(c.outline, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx())
        },
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 6.dp, end = 6.dp, top = 6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.Top,
        ) {
            MainTab.entries.forEach { t ->
                val on = t == current
                val tint = if (on) c.primary else c.muted
                Column(
                    Modifier.width(68.dp).heightIn(min = 52.dp)
                        .selectable(selected = on, role = Role.Tab, interactionSource = null, indication = null) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // The 54 dp Ask button is raised 20 dp: it occupies a 34 dp slot and overflows above the bar.
                    if (t == MainTab.Ask) Box(Modifier.size(width = 56.dp, height = 34.dp)) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).offset(y = (-10).dp).requiredSize(54.dp)
                                .shadow(14.dp, CircleShape, ambientColor = c.primary, spotColor = c.primary)
                                .clip(CircleShape).background(c.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(t.icon, null, Modifier.size(22.dp), tint = c.onPrimary) }
                    }
                    else Box(
                        Modifier.size(width = 56.dp, height = 30.dp).clip(CircleShape).background(if (on) c.primaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(t.icon, null, Modifier.size(22.dp), tint = tint) }
                    Text(t.label, style = Hf.type.caption, color = tint)
                }
            }
        }
        Spacer(Modifier.height(maxOf(navInset, 20.dp)))
    }
}

@Composable
private fun Home(onOpen: (Page) -> Unit) {
    val now = remember { java.time.ZonedDateTime.now(ZoneId.of("Africa/Dar_es_Salaam")) }
    val greeting = when (now.hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    ScreenHeader(greeting, overline = DateTimeFormatter.ofPattern("EEEE, d MMMM").format(now)) {
        IconCircleButton(HfIcons.Settings, "Settings", { onOpen(Page.Settings) })
    }
    Text("Your summary and the “Needs you” queue will appear here.", style = Hf.type.body, color = Hf.colors.muted)
}

@Composable
private fun SettingsHub(onOpen: (Page) -> Unit) {
    ListCard {
        ListRow("Permissions and capture", subtitle = "Assistant, notifications, SMS, microphone", icon = HfIcons.Lock, chevron = true,
            onClick = { onOpen(Page.Permissions) })
        ListDivider()
        ListRow("Cloud AI", subtitle = "Consent, providers, routes and budget", icon = HfIcons.Cloud, tone = Tone.Info, chevron = true,
            onClick = { onOpen(Page.CloudAi) })
        ListDivider()
        ListRow("On-device models", subtitle = "Import and test local models", icon = HfIcons.Chip, tone = Tone.Success, chevron = true,
            onClick = { onOpen(Page.LocalModels) })
        ListDivider()
        ListRow("Recordings", subtitle = "Media import, exclusions, conversations", icon = HfIcons.Waveform, tone = Tone.Neutral, chevron = true,
            onClick = { onOpen(Page.Recordings) })
    }
    ListCard {
        ListRow("Encrypted backup", subtitle = "Export and restore", icon = HfIcons.Key, tone = Tone.Neutral, chevron = true,
            onClick = { onOpen(Page.Backup) })
        ListDivider()
        ListRow("This device", subtitle = "Android, assistant role, timezone", icon = HfIcons.Phone, tone = Tone.Neutral, chevron = true,
            onClick = { onOpen(Page.Device) })
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
private fun Assistant(app: HellFireApp, onSession: () -> Unit) {
    val assistant = remember { LocalAssistant() }
    var query by rememberSaveable { mutableStateOf("hello") }
    var response by rememberSaveable { mutableStateOf(assistant.answer("help")) }
    Text("Ask HellFire", style = MaterialTheme.typography.titleLarge)
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(AiState()) }
    var task by remember { mutableStateOf(AiTask.CHAT) }
    var manual by remember { mutableStateOf<ModelRef?>(null) }
    var running by remember { mutableStateOf<Job?>(null) }
    var source by rememberSaveable { mutableStateOf("On-device offline test / ${OfflineProvider.MODEL}") }
    var resultStatus by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { state = withContext(Dispatchers.IO) { app.ai.store.read() } }
        catch (_: Exception) { resultStatus = AiError.STORAGE.message }
    }
    Text("Offline default: hello, habari, or time. No on-device language model is loaded. Cloud routes use only the text you submit here, after redaction. Configure cloud consent, models, prices and fallbacks in AI settings.")
    AiChoice("Task", task.label, AiTask.entries.map { it to it.label }, enabled = running == null) { task = it }
    AiChoice("Model", manual?.label() ?: "Use saved task route", listOf<Pair<ModelRef?, String>>(null to "Use saved task route") +
        state.modelChoices().map { it to it.label() }, enabled = running == null) { manual = it }
    Text("Route: " + (if (manual == null) state.route(task) else listOf(manual!!) + state.route(task).drop(1)).joinToString(" → ") { it.label() })
    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Your question") }, maxLines = 4)
    Button(enabled = running == null, onClick = {
        val submittedTask = task
        val submittedQuery = query
        val submittedModel = manual
        running = scope.launch {
            resultStatus = "Generating…"
            try {
                val result = withContext(Dispatchers.IO) { app.ai.generate(submittedTask, submittedQuery, submittedModel) }
                response = result.generation.text
                source = "${result.provider.label} / ${result.generation.model}"
                resultStatus = "Suggestion only · USD ${usd(result.estimatedMicroUsd)} estimated · audit ${result.requestId.take(8)}" +
                    result.attempts.joinToString("") { "\n${it.ref.provider.label}: ${it.error.message}" }
            } catch (e: CancellationException) { resultStatus = AiError.CANCELLED.message; throw e }
            catch (e: Exception) { resultStatus = (e as? AiException)?.error?.message ?: "AI request failed. Offline tests remain available." }
            finally { running = null }
        }
    }) { Text("Ask HellFire") }
    if (running != null) OutlinedButton(onClick = { running?.cancel() }) { Text("Cancel generation") }
    Text(resultStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Text(source, style = MaterialTheme.typography.labelLarge)
    Text(response, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    HorizontalDivider()
    Text("System assistant session", style = MaterialTheme.typography.titleMedium)
    Text("After selecting HellFire Ass, test the session here and with your phone’s assistant gesture. The session can read answers aloud if an offline voice is installed.")
    OutlinedButton(onClick = onSession) { Text("Open assistant session") }
    Text("Hotword, lock-screen and OEM button behaviour need physical device testing.")
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
