package com.hellfire.ass.ui.settings

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.PermissionStatus
import com.hellfire.ass.data.NotificationPolicy
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.notifications.NotificationCaptureStatus
import com.hellfire.ass.processing.CaptureWork
import com.hellfire.ass.sms.SmsSyncWorker
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One Setup row: plain-language name, what it is for, live state from Android, and how to turn it on. */
data class SetupStep(val title: String, val detail: String, val icon: ImageVector, val on: Boolean, val turnOn: () -> Unit)

fun setupSteps(p: PermissionStatus, a: PermissionActions): List<SetupStep> = listOf(
    SetupStep("Notifications", "Read payment and message alerts. Security codes are hidden.", HfIcons.Bell, p.notificationAccess,
        { a.onSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
    SetupStep("SMS", "Track M-Pesa and bank messages. Never sends SMS.", HfIcons.Chat, p.smsEnabled && p.smsRead, a.onSmsEnable),
    SetupStep("Microphone", "Ask by voice and record when you choose. Always shows a notification.", HfIcons.Mic, p.microphone && p.notificationPosting, a.onMicrophone),
    SetupStep("On-time reminders", "Ring at the exact minute you set", HfIcons.Alarm, p.exactAlarm != false && p.notificationPosting,
        { if (!p.notificationPosting) a.onNotifications() else if (Build.VERSION.SDK_INT >= 31) a.onSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }),
    SetupStep("Phone assistant", "Open HellFire with your assistant gesture", HfIcons.Spark, p.voiceServiceActive, a.onRole),
    SetupStep("Run in background", "Stop the phone pausing capture overnight. On OPPO/ColorOS also allow auto-launch and background activity in phone settings; check it on the phone.",
        HfIcons.Battery, p.batteryExempt, { a.onSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }),
)

@Composable
private fun SetupRows(steps: List<SetupStep>) {
    val on = steps.count { it.on }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("$on of ${steps.size} turned on", Modifier.weight(1f), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.onSurface)
            Text(com.hellfire.ass.ui.tr("about 1 minute"), style = Hf.type.label.copy(fontWeight = FontWeight.Normal), color = Hf.colors.muted)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Hf.colors.outline)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(on.toFloat() / steps.size).clip(CircleShape).background(Hf.colors.primary))
        }
    }
    HfCard(radius = 22.dp) {
        steps.forEachIndexed { i, s ->
            if (i > 0) ListDivider(66.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(icon = s.icon, tone = if (s.on) Tone.Success else Tone.Neutral)
                Column(Modifier.weight(1f)) {
                    Text(tr(s.title), style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                    Text(tr(s.detail), style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                }
                if (s.on) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(HfIcons.Check, null, Modifier.size(16.dp), tint = Hf.colors.success)
                    Text(com.hellfire.ass.ui.tr("On"), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.success)
                } else Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).background(Hf.colors.primaryContainer).clickableRow(s.turnOn).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(com.hellfire.ass.ui.tr("Turn on"), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.primaryPressed)
                }
            }
        }
    }
}

/** First-run setup (Setup.dc.html). State is read live from Android on every resume. */
@Composable
fun SetupScreen() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    fun finish() { env.app.getSharedPreferences("ui", 0).edit().putBoolean("setup_done", true).apply(); nav.pop() }
    Column(Modifier.fillMaxSize()) {
        ScrollPage(Modifier.weight(1f)) {
            PushedTopBar("", onBack = { finish() })
            Text(com.hellfire.ass.ui.tr("Let's set up HellFire"), style = Hf.type.screenTitle.copy(fontSize = 28.sp), color = Hf.colors.onSurface)
            Text("Turn on only what you want. Everything stays on this phone, and you can change it anytime.", style = Hf.type.body, color = Hf.colors.muted)
            SetupRows(setupSteps(env.permissions, env.actions))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrimaryButton(com.hellfire.ass.ui.tr("Continue"), { finish() }, Modifier.fillMaxWidth(), height = 54.dp)
            QuietButton(com.hellfire.ass.ui.tr("Skip for now"), { finish() }, Modifier.fillMaxWidth())
        }
    }
}

/** Settings hub (Settings.dc.html) with live subtitles. */
@Composable
fun SettingsHub() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val memories by remember { app.database.memory().count() }.collectAsStateWithLifecycle(0)
    val media by remember { app.database.media().count() }.collectAsStateWithLifecycle(0)
    val states by remember { app.database.communications().integrations() }.collectAsStateWithLifecycle(emptyList())
    var ai by remember { mutableStateOf<com.hellfire.ass.ai.AiState?>(null) }
    var mail by remember { mutableStateOf(0) }
    var gateways by remember { mutableStateOf<List<String>>(emptyList()) }
    var webAccess by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) {
        ai = runCatching { app.ai.store.read() }.getOrNull()
        mail = runCatching { com.hellfire.ass.communications.Accounts.list(app.secrets, com.hellfire.ass.communications.Accounts.MAIL).size }.getOrDefault(0)
        gateways = runCatching { com.hellfire.ass.communications.Accounts.gateways(app.secrets).map(com.hellfire.ass.communications.Accounts::transport) }.getOrDefault(emptyList())
        webAccess = runCatching { app.secrets.read(com.hellfire.ass.sync.WebSync.CONFIG) != null }.getOrDefault(false)
    } }
    val steps = setupSteps(env.permissions, env.actions)
    val lastBackup = maxOf(app.getSharedPreferences("ui", 0).getLong("last_backup_at", 0),
        app.getSharedPreferences(com.hellfire.ass.security.DriveBackupWork.CONFIG, 0).getLong("last-success", 0))
    PushedPage(com.hellfire.ass.ui.tr("Settings")) {
        SectionCard(tr("Everyday help")) {
            ListRow(tr("Guided setup"), subtitle = tr("Choose what you want HellFire to help with"), chevron = true, onClick = { nav.push(Screen.GuidedSetup) })
            ListRow(tr("Needs attention"), subtitle = tr("Capture, connections, models and backups"), chevron = true, onClick = { nav.push(Screen.Recovery) })
            ListRow(tr("AI skills"), subtitle = tr("Learned rules, reasons and usage history"), chevron = true, onClick = { nav.push(Screen.Skills) })
            ListRow(tr("Privacy and storage"), subtitle = tr("Where your data lives"), chevron = true, onClick = { nav.push(Screen.Privacy) })
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Hf.colors.surfaceDark).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Hf.colors.surfaceDarkInner), contentAlignment = Alignment.Center) {
                Icon(HfIcons.Lock, null, Modifier.size(20.dp), tint = androidx.compose.ui.graphics.Color(0xFF6EE7A0))
            }
            Column(Modifier.weight(1f)) {
                Text(com.hellfire.ass.ui.tr("Private by default"), style = Hf.type.rowTitle, color = Hf.colors.onSurfaceDark)
                Text((if (ai?.cloudEnabled == true) "Everything is encrypted on this phone. Cloud AI is on for text you send from Ask."
                    else "Personal records are encrypted on this phone. Review optional copies and destinations in Privacy and storage.") +
                    if (webAccess) " Web access copies selected records to your own server." else "", style = Hf.type.rowSubtitle, color = Hf.colors.mutedOnDark)
            }
        }
        SectionCard(com.hellfire.ass.ui.tr("Fingerprint app lock")) {
            Note(if (env.permissions.appLockEnabled) "Enabled · fingerprint or device screen lock is required when reopening HellFire Ass." else "Disabled · enable to protect the app when you leave it.")
            Note("Enroll fingerprints in Android Settings. Background capture and scheduled backups continue while locked. Widget visibility is controlled separately in Home screen widgets.")
            SecondaryButton(if (env.permissions.appLockEnabled) "Disable app lock…" else "Enable fingerprint app lock…",
                { env.actions.onAppLockChange(!env.permissions.appLockEnabled) }, Modifier.fillMaxWidth())
        }
        ListCard { ListRow(tr("Language"), subtitle = if (UiLanguage.code == "sw") "Kiswahili" else "English", chevron = true, onClick = { nav.push(Screen.Language) }) }
        ListCard { ListRow(tr("News"), subtitle = tr("Briefings, sources and interests"), chevron = true, onClick = { nav.push(Screen.News) }) }
        Group("Access") {
            ListRow(com.hellfire.ass.ui.tr("Permissions and capture"), subtitle = "${steps.count { it.on }} of ${steps.size} turned on", icon = HfIcons.Lock, chevron = true, onClick = { nav.push(Screen.Permissions) })
            ListDivider()
            ListRow(com.hellfire.ass.ui.tr("Reminders"), subtitle = when (states.firstOrNull { it.adapter == "REMINDERS" }?.state) {
                "EXACT_ALLOWED" -> "On time"; "BEST_EFFORT" -> "Best effort"; "PERMISSION_MISSING" -> "Notifications blocked"; else -> "Nightly review, overdue repeats" },
                icon = HfIcons.Alarm, chevron = true, onClick = { nav.push(Screen.Reminders) })
            ListDivider()
            ListRow(com.hellfire.ass.ui.tr("Home screen widgets"), subtitle = if (com.hellfire.ass.widgets.WidgetPrefs.hideAll(app)) "All amounts hidden" else "Amounts hidden unless you allow them",
                icon = HfIcons.Home, chevron = true, onClick = { nav.push(Screen.Widgets) })
        }
        Group("AI") {
            val providers = ai?.providers?.count { it.value.enabled && it.value.apiKey.isNotBlank() } ?: 0
            ListRow(com.hellfire.ass.ui.tr("Cloud AI"), subtitle = (if (ai?.cloudEnabled == true) "On" else "Off") + " · " + if (providers == 0) "no providers set up" else "$providers provider${if (providers > 1) "s" else ""}",
                icon = HfIcons.Cloud, tone = Tone.Info, chevron = true, onClick = { nav.push(Screen.CloudAi) })
            ListDivider()
            ListRow(com.hellfire.ass.ui.tr("On-device models"), subtitle = listOfNotNull("language".takeIf { app.localModel.configured }, "meaning search".takeIf { app.semantic.configured },
                "speakers".takeIf { app.speakers.configured }).joinToString(", ").ifEmpty { "None installed" }.replaceFirstChar { it.uppercase() } +
                    (if (com.hellfire.ass.ai.ModelUpdates.pendingUpdate(com.hellfire.ass.ai.ModelUpdates.state(app)) != null) " · update available" else ""),
                icon = HfIcons.Chip, tone = Tone.Success, chevron = true, onClick = { nav.push(Screen.LocalModels) })
            ListDivider()
            val spent = ai?.let { s -> s.usage.filterKeys { it.startsWith("${app.ai.month()}/") }.values.sumOf { it.charged } } ?: 0
            ListRow(com.hellfire.ass.ui.tr("Monthly AI budget"), subtitle = "USD ${usd(spent)} of ${usd(ai?.monthlyCap ?: 0)} used", icon = HfIcons.Reports, tone = Tone.Primary,
                chevron = true, onClick = { nav.push(Screen.CloudAi, "budget") })
        }
        Group("Connected accounts") {
            val mailFailing = states.count { it.adapter.startsWith("EMAIL_IMAP:") && it.state == "FAILED" }
            ListRow(com.hellfire.ass.ui.tr("Email"), subtitle = when (mail) { 0 -> "Not connected"; 1 -> "1 inbox"; else -> "$mail inboxes" } + if (mailFailing > 0) " · $mailFailing failing" else "",
                icon = HfIcons.Mail, tone = Tone.Info, chevron = true, onClick = { nav.push(Screen.Email) })
            ListDivider()
            ListRow(com.hellfire.ass.ui.tr("WhatsApp"), subtitle = when {
                    gateways.isEmpty() -> "Not connected"
                    gateways.size == 1 -> if (gateways[0] == "web") "WhatsApp Web · capture only" else "Business API"
                    else -> "${gateways.size} accounts"
                },
                icon = HfIcons.Chat, tone = Tone.Success, chevron = true, onClick = { nav.push(Screen.WhatsApp) })
        }
        Group("Your data") {
            ListRow(com.hellfire.ass.ui.tr("Memory and proposals"), subtitle = if (memories == 0) "Nothing saved yet" else "$memories saved", icon = HfIcons.Key, tone = Tone.Neutral, chevron = true,
                onClick = { nav.push(Screen.Memory) })
            ListDivider()
            ListRow(com.hellfire.ass.ui.tr("Recordings"), subtitle = "$media originals kept · import, exclusions, monitoring", icon = HfIcons.Waveform, tone = Tone.Neutral, chevron = true,
                onClick = { nav.push(Screen.Recordings) })
            ListDivider()
            ListRow(com.hellfire.ass.ui.tr("Encrypted backup"), subtitle = if (lastBackup == 0L) "Never backed up" else "Last backup ${Fmt.dayTime(lastBackup)}", icon = HfIcons.Download, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(Screen.Backup) })
            ListDivider()
            val webLast = com.hellfire.ass.sync.WebSync.lastSuccess(app)
            ListRow(com.hellfire.ass.ui.tr("Web access"), subtitle = if (!webAccess) "Off · read your data from a browser" else if (webLast == 0L) "On · not synced yet" else "On · synced ${Fmt.dayTime(webLast)}",
                icon = HfIcons.Cloud, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.WebAccess) })
            ListRow(com.hellfire.ass.ui.tr("This device"), subtitle = "Android, assistant role, timezone", icon = HfIcons.Phone, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.Device) })
        }
        Group("Library and status") {
            ListRow(tr("Backup dashboard"), subtitle = "Storage, per-item backup state, retention, selective restore", icon = HfIcons.Download, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(com.hellfire.ass.library.LibraryRoutes.backupDashboard()) })
            ListDivider()
            ListRow(tr("Status dashboard"), subtitle = "Analysis, sync, backup, models, errors", icon = HfIcons.Reports, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(com.hellfire.ass.library.LibraryRoutes.statusDashboard()) })
            ListDivider()
            ListRow(tr("Processing queue"), subtitle = "On-device analysis jobs", icon = HfIcons.Chip, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(com.hellfire.ass.library.LibraryRoutes.processingQueue()) })
            ListDivider()
            ListRow(tr("Media settings"), subtitle = "Analysis, transcription and categories", icon = HfIcons.Settings, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(com.hellfire.ass.library.LibraryRoutes.mediaSettings()) })
        }
    }
}

@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupLabel(label)
        HfCard(radius = 22.dp, content = content)
    }
}

/** Settings › Permissions and capture: the Setup rows plus capture status and maintenance. */
@Composable
fun PermissionsPage() {
    val env = LocalEnv.current
    val app = env.app
    val p = env.permissions
    val scope = rememberCoroutineScope()
    val dao = remember { app.database.events() }
    val queues by remember { dao.queueSummary() }.collectAsStateWithLifecycle(emptyList())
    val audits by remember { dao.audits() }.collectAsStateWithLifecycle(emptyList())
    val policies by remember { dao.appPolicies() }.collectAsStateWithLifecycle(emptyList())
    val count by app.events.count.collectAsStateWithLifecycle(0)
    val connected by NotificationCaptureStatus.connected.collectAsStateWithLifecycle()
    val error by NotificationCaptureStatus.error.collectAsStateWithLifecycle()
    val dropped by NotificationCaptureStatus.dropped.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    PushedPage(com.hellfire.ass.ui.tr("Permissions and capture"), breadcrumb = "Settings") {
        message?.let { Banner(it, Tone.Info, null) }
        SetupRows(setupSteps(p, env.actions))
        ContactsPermissionRow()
        if (p.smsEnabled) QuietButton(com.hellfire.ass.ui.tr("Turn off SMS capture"), env.actions.onSmsDisable)
        SectionHeader(com.hellfire.ass.ui.tr("Capture status"))
        KvList(listOfNotNull(
            "Notification listener" to when { connected && p.notificationAccess -> "Connected"; p.notificationAccess -> "Access granted · disconnected"; else -> "Access missing" },
            "SMS" to when { !p.smsEnabled -> "Off"; !p.smsRead -> "Read permission missing"; !p.smsReceive -> "History sync on · live SMS permission missing"; else -> "On · check delivery on the phone" },
            "Stored events" to "$count · $dropped dropped this session",
            "Processing" to queues.joinToString { "${it.state.lowercase()} ${it.count}" }.ifEmpty { "Nothing saved yet" },
            error?.let { "Listener error" to it },
        ))
        audits.forEach { a ->
            Note("${a.source}: ${a.captured} captured · ${a.failures} failures · ${a.dropped} drops" + (a.lastCapturedAt?.let { " · last ${Fmt.dayTime(it)}" } ?: "") +
                (a.lastError?.let { " · last issue $it" } ?: ""))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(com.hellfire.ass.ui.tr("Resume processing"), { scope.launch {
                message = try { withContext(Dispatchers.IO) { dao.retryFailed() }; CaptureWork.process(app); "Pending and failed events scheduled for processing." }
                    catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Processing could not be scheduled. Saved events stay in the database." }
            } })
            if (p.smsEnabled && p.smsRead) SecondaryButton(com.hellfire.ass.ui.tr("Sync SMS history"), { scope.launch {
                message = try { SmsSyncWorker.schedule(app, 0); "Full SMS history check scheduled. Already saved messages are skipped." } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "SMS sync could not be scheduled." }
            } })
        }
        if (policies.isNotEmpty()) {
            SectionHeader(com.hellfire.ass.ui.tr("Notification processing"))
            Note(com.hellfire.ass.ui.tr("Every accessible notification is kept. Turning an app off keeps its future notifications as capture only."))
            HfCard {
                policies.forEachIndexed { i, policy ->
                    if (i > 0) ListDivider(16.dp)
                    SwitchRow(policy.packageName, policy.processEnabled, { enabled -> scope.launch {
                        message = try { withContext(Dispatchers.IO) { dao.setPolicy(NotificationPolicy(policy.packageName, enabled)) }
                            if (enabled) "Processing on for ${policy.packageName}." else "Capture only for ${policy.packageName}." }
                            catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Setting could not be saved." }
                    } })
                }
            }
        }
        SectionCard(com.hellfire.ass.ui.tr("Testing and cleanup")) {
            SecondaryButton(com.hellfire.ass.ui.tr("Insert a synthetic test event"), { scope.launch {
                message = try {
                    val inserted = withContext(Dispatchers.IO) { app.events.capture(NotificationSnapshot(
                        key = "synthetic-phase-zero-v1", packageName = "com.hellfire.fixture", postedAt = 1, receivedAt = System.currentTimeMillis(),
                        source = "synthetic_notification", fields = mapOf("title" to listOf("Synthetic Phase 0 fixture"), "text" to listOf("Offline capture test"))), app.packageName) }
                    CaptureWork.process(app)
                    if (inserted) "Synthetic event stored." else "Synthetic event already stored; duplicate ignored."
                } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Local storage test failed. Check storage and encryption." }
            } }, Modifier.fillMaxWidth())
            Note("This tests normalising, encryption and the database. It does not prove Android delivers notifications.")
            QuietButton(com.hellfire.ass.ui.tr("Delete captured events…"), { confirmClear = true }, Modifier.fillMaxWidth(), enabled = !p.notificationAccess && !p.smsEnabled)
            Note("Turn off notification access and SMS capture first. Records linked to Money, Tasks or Media stay as evidence.")
        }
    }
    if (confirmClear) ConfirmDialog("Delete all captured events?", "This removes stored captured events and their processing jobs from this phone.", "Delete events",
        onConfirm = { confirmClear = false; scope.launch {
            message = try { withContext(Dispatchers.IO) { dao.deleteAll() }; "Captured events deleted." } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Could not delete: some events are evidence for Money, Tasks or Media." }
        } }, onDismiss = { confirmClear = false }, destructive = true)
}

@Composable
fun DevicePage() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val p = env.permissions
    PushedPage(com.hellfire.ass.ui.tr("This device"), breadcrumb = "Settings") {
        KvList(listOf("Manufacturer / model" to "${Build.MANUFACTURER} / ${Build.MODEL}", "Android / API" to "${Build.VERSION.RELEASE} / ${Build.VERSION.SDK_INT}",
            "Build" to Build.DISPLAY, "CPU ABIs" to Build.SUPPORTED_ABIS.joinToString(), "Security patch" to Build.VERSION.SECURITY_PATCH,
            "Assistant role" to if (p.roleHeld) "Held" else "Not held", "Voice service" to if (p.voiceServiceActive) "Selected" else "Not selected",
            "Owner timezone" to "Africa/Dar_es_Salaam"))
        SectionCard(com.hellfire.ass.ui.tr("Assistant")) {
            SecondaryButton(com.hellfire.ass.ui.tr("Choose HellFire as the digital assistant"), env.actions.onRole, Modifier.fillMaxWidth())
            SecondaryButton(com.hellfire.ass.ui.tr("Open assistant settings"), { env.actions.onSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) }, Modifier.fillMaxWidth())
            SecondaryButton(com.hellfire.ass.ui.tr("Test the assistant session"), env.actions.onSession, Modifier.fillMaxWidth())
            Note("Hotword, lock-screen and OEM gesture behaviour need testing on the phone.")
        }
        SecondaryButton("Performance (developer)", { nav.push(Screen.Performance) }, Modifier.fillMaxWidth())
        Note("Counters only: no message text, amounts, transcripts or prompts. Device speed is not claimed until measured on this phone.")
        Note("The model name comes from Android. ColorOS version, OEM gestures, lock-screen invocation, microphone interruptions and overnight behaviour must be checked on the physical phone. No physical verification is claimed here.")
    }
}
