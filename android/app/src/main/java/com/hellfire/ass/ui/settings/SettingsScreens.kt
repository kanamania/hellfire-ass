package com.hellfire.ass.ui.settings

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

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
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.ai.AiState
import com.hellfire.ass.ai.ModelUpdates
import com.hellfire.ass.calendar.CalendarPermissionRow
import com.hellfire.ass.communications.Accounts
import com.hellfire.ass.library.LibraryRoutes
import com.hellfire.ass.security.DriveBackupWork
import com.hellfire.ass.sync.WebSync
import com.hellfire.ass.widgets.WidgetPrefs

/** One Setup row: plain-language name, what it is for, live state from Android, and how to turn it on. */
data class SetupStep(@androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val detail: Int, val icon: ImageVector, val on: Boolean, val turnOn: () -> Unit)

fun setupSteps(p: PermissionStatus, a: PermissionActions): List<SetupStep> = listOf(
    SetupStep(R.string.ui_notifications, R.string.ui_read_payment_and_message_alerts_security, HfIcons.Bell, p.notificationAccess,
        { a.onSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
    SetupStep(R.string.ui_sms, R.string.ui_track_m_pesa_and_bank_messages_never, HfIcons.Chat, p.smsEnabled && p.smsRead, a.onSmsEnable),
    SetupStep(R.string.ui_microphone, R.string.ui_ask_by_voice_and_record_when_you_choose, HfIcons.Mic, p.microphone && p.notificationPosting, a.onMicrophone),
    SetupStep(R.string.ui_on_time_reminders, R.string.ui_ring_at_the_exact_minute_you_set, HfIcons.Alarm, p.exactAlarm != false && p.notificationPosting,
        { if (!p.notificationPosting) a.onNotifications() else if (Build.VERSION.SDK_INT >= 31) a.onSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }),
    SetupStep(R.string.ui_phone_assistant, R.string.ui_open_hellfire_with_your_assistant, HfIcons.Spark, p.voiceServiceActive, a.onRole),
    SetupStep(R.string.ui_run_in_background, R.string.ui_run_in_background_detail,
        HfIcons.Battery, p.batteryExempt, { a.onSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }),
)

@Composable
private fun SetupRows(steps: List<SetupStep>) {
    val on = steps.count { it.on }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("$on of ${steps.size} turned on", Modifier.weight(1f), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.onSurface)
            Text(stringResource(R.string.ui_about_1_minute), style = Hf.type.label.copy(fontWeight = FontWeight.Normal), color = Hf.colors.muted)
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
                    Text(stringResource(s.title), style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                    Text(stringResource(s.detail), style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                }
                if (s.on) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(HfIcons.Check, null, Modifier.size(16.dp), tint = Hf.colors.success)
                    Text(stringResource(R.string.ui_on), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.success)
                } else Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).background(Hf.colors.primaryContainer).clickableRow(s.turnOn).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ui_turn_on), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.primaryPressed)
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
            Text(stringResource(R.string.ui_lets_set_up_hellfire), style = Hf.type.screenTitle.copy(fontSize = 28.sp), color = Hf.colors.onSurface)
            Text("Turn on only what you want. Everything stays on this phone, and you can change it anytime.", style = Hf.type.body, color = Hf.colors.muted)
            SetupRows(setupSteps(env.permissions, env.actions))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrimaryButton(stringResource(R.string.ui_continue), { finish() }, Modifier.fillMaxWidth(), height = 54.dp)
            QuietButton(stringResource(R.string.ui_skip_for_now), { finish() }, Modifier.fillMaxWidth())
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
    var ai by remember { mutableStateOf<AiState?>(null) }
    var mail by remember { mutableStateOf(0) }
    var gateways by remember { mutableStateOf<List<String>>(emptyList()) }
    var webAccess by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) {
        ai = runCatching { app.ai.store.read() }.getOrNull()
        mail = runCatching { Accounts.list(app.secrets, Accounts.MAIL).size }.getOrDefault(0)
        gateways = runCatching { Accounts.gateways(app.secrets).map(Accounts::transport) }.getOrDefault(emptyList())
        webAccess = runCatching { app.secrets.read(WebSync.CONFIG) != null }.getOrDefault(false)
    } }
    val steps = setupSteps(env.permissions, env.actions)
    val lastBackup = maxOf(app.getSharedPreferences("ui", 0).getLong("last_backup_at", 0),
        app.getSharedPreferences(DriveBackupWork.CONFIG, 0).getLong("last-success", 0))
    PushedPage(stringResource(R.string.ui_settings)) {
        SectionCard(stringResource(R.string.ui_everyday_help)) {
            ListRow(stringResource(R.string.ui_guided_setup), subtitle = stringResource(R.string.ui_choose_what_you_want_hellfire_to_help), chevron = true, onClick = { nav.push(Screen.GuidedSetup) })
            ListRow(stringResource(R.string.ui_needs_attention), subtitle = stringResource(R.string.ui_capture_connections_models_and_backups), chevron = true, onClick = { nav.push(Screen.Recovery) })
            ListRow(stringResource(R.string.ui_ai_skills), subtitle = stringResource(R.string.ui_learned_rules_reasons_and_usage_history), chevron = true, onClick = { nav.push(Screen.Skills) })
            ListRow(stringResource(R.string.ui_privacy_and_storage), subtitle = stringResource(R.string.ui_where_your_data_lives), chevron = true, onClick = { nav.push(Screen.Privacy) })
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Hf.colors.surfaceDark).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Hf.colors.surfaceDarkInner), contentAlignment = Alignment.Center) {
                Icon(HfIcons.Lock, null, Modifier.size(20.dp), tint = androidx.compose.ui.graphics.Color(0xFF6EE7A0))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_private_by_default), style = Hf.type.rowTitle, color = Hf.colors.onSurfaceDark)
                Text((if (ai?.cloudEnabled == true) "Everything is encrypted on this phone. Cloud AI is on for text you send from Ask."
                    else "Personal records are encrypted on this phone. Review optional copies and destinations in Privacy and storage.") +
                    if (webAccess) " Web access copies selected records to your own server." else "", style = Hf.type.rowSubtitle, color = Hf.colors.mutedOnDark)
            }
        }
        SectionCard(stringResource(R.string.ui_fingerprint_app_lock)) {
            Note(if (env.permissions.appLockEnabled) "Enabled · fingerprint or device screen lock is required when reopening HellFire Ass." else "Disabled · enable to protect the app when you leave it.")
            Note("Enroll fingerprints in Android Settings. Background capture and scheduled backups continue while locked. Widget visibility is controlled separately in Home screen widgets.")
            SecondaryButton(if (env.permissions.appLockEnabled) "Disable app lock…" else "Enable fingerprint app lock…",
                { env.actions.onAppLockChange(!env.permissions.appLockEnabled) }, Modifier.fillMaxWidth())
        }
        ListCard { ListRow(stringResource(R.string.ui_language), subtitle = if (UiLanguage.code == "sw") "Kiswahili" else "English", chevron = true, onClick = { nav.push(Screen.Language) }) }
        ListCard { ListRow("News", subtitle = "Briefings, sources and interests", chevron = true, onClick = { nav.push(Screen.News) }) }
        ListCard { ListRow("TheftGuard", subtitle = "Biometric arming, encrypted evidence and owner alerts", chevron = true, onClick = { nav.push(Screen.Guard) }) }
        Group(stringResource(R.string.ui_access)) {
            ListRow(stringResource(R.string.ui_permissions_and_capture), subtitle = "${steps.count { it.on }} of ${steps.size} turned on", icon = HfIcons.Lock, chevron = true, onClick = { nav.push(Screen.Permissions) })
            ListDivider()
            ListRow(stringResource(R.string.ui_reminders), subtitle = when (states.firstOrNull { it.adapter == "REMINDERS" }?.state) {
                "EXACT_ALLOWED" -> "On time"; "BEST_EFFORT" -> "Best effort"; "PERMISSION_MISSING" -> "Notifications blocked"; else -> "Nightly review, overdue repeats" },
                icon = HfIcons.Alarm, chevron = true, onClick = { nav.push(Screen.Reminders) })
            ListDivider()
            ListRow(stringResource(R.string.ui_home_screen_widgets), subtitle = if (WidgetPrefs.hideAll(app)) "All amounts hidden" else "Amounts hidden unless you allow them",
                icon = HfIcons.Home, chevron = true, onClick = { nav.push(Screen.Widgets) })
        }
        Group(stringResource(R.string.ui_ai)) {
            val providers = ai?.providers?.count { it.value.enabled && it.value.apiKey.isNotBlank() } ?: 0
            ListRow(stringResource(R.string.ui_cloud_ai), subtitle = (if (ai?.cloudEnabled == true) "On" else "Off") + " · " + if (providers == 0) "no providers set up" else "$providers provider${if (providers > 1) "s" else ""}",
                icon = HfIcons.Cloud, tone = Tone.Info, chevron = true, onClick = { nav.push(Screen.CloudAi) })
            ListDivider()
            ListRow(stringResource(R.string.ui_on_device_models), subtitle = listOfNotNull("language".takeIf { app.localModel.configured }, "meaning search".takeIf { app.semantic.configured },
                "speakers".takeIf { app.speakers.configured }).joinToString(", ").ifEmpty { "None installed" }.replaceFirstChar { it.uppercase() } +
                    (if (ModelUpdates.pendingUpdate(ModelUpdates.state(app)) != null) " · update available" else ""),
                icon = HfIcons.Chip, tone = Tone.Success, chevron = true, onClick = { nav.push(Screen.LocalModels) })
            ListDivider()
            val spent = ai?.let { s -> s.usage.filterKeys { it.startsWith("${app.ai.month()}/") }.values.sumOf { it.charged } } ?: 0
            ListRow(stringResource(R.string.ui_monthly_ai_budget), subtitle = "USD ${usd(spent)} of ${usd(ai?.monthlyCap ?: 0)} used", icon = HfIcons.Reports, tone = Tone.Primary,
                chevron = true, onClick = { nav.push(Screen.CloudAi, "budget") })
        }
        Group(stringResource(R.string.ui_connected_accounts)) {
            val mailFailing = states.count { it.adapter.startsWith("EMAIL_IMAP:") && it.state == "FAILED" }
            ListRow(stringResource(R.string.ui_email), subtitle = when (mail) { 0 -> "Not connected"; 1 -> "1 inbox"; else -> "$mail inboxes" } + if (mailFailing > 0) " · $mailFailing failing" else "",
                icon = HfIcons.Mail, tone = Tone.Info, chevron = true, onClick = { nav.push(Screen.Email) })
            ListDivider()
            ListRow(stringResource(R.string.ui_whatsapp), subtitle = when {
                    gateways.isEmpty() -> "Not connected"
                    gateways.size == 1 -> if (gateways[0] == "web") "WhatsApp Web · capture only" else "Business API"
                    else -> "${gateways.size} accounts"
                },
                icon = HfIcons.Chat, tone = Tone.Success, chevron = true, onClick = { nav.push(Screen.WhatsApp) })
        }
        Group(stringResource(R.string.ui_your_data)) {
            ListRow(stringResource(R.string.ui_memory_and_proposals), subtitle = if (memories == 0) "Nothing saved yet" else "$memories saved", icon = HfIcons.Key, tone = Tone.Neutral, chevron = true,
                onClick = { nav.push(Screen.Memory) })
            ListDivider()
            ListRow(stringResource(R.string.ui_recordings), subtitle = "$media originals kept · import, exclusions, monitoring", icon = HfIcons.Waveform, tone = Tone.Neutral, chevron = true,
                onClick = { nav.push(Screen.Recordings) })
            ListDivider()
            ListRow(stringResource(R.string.ui_encrypted_backup), subtitle = if (lastBackup == 0L) "Never backed up" else "Last backup ${Fmt.dayTime(lastBackup)}", icon = HfIcons.Download, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(Screen.Backup) })
            ListDivider()
            val webLast = WebSync.lastSuccess(app)
            ListRow(stringResource(R.string.ui_web_access), subtitle = if (!webAccess) "Off · read your data from a browser" else if (webLast == 0L) "On · not synced yet" else "On · synced ${Fmt.dayTime(webLast)}",
                icon = HfIcons.Cloud, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.WebAccess) })
            ListRow(stringResource(R.string.ui_this_device), subtitle = "Android, assistant role, timezone", icon = HfIcons.Phone, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.Device) })
        }
        Group("Library and status") {
            ListRow(stringResource(R.string.ui_backup_dashboard), subtitle = "Storage, per-item backup state, retention, selective restore", icon = HfIcons.Download, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(LibraryRoutes.backupDashboard()) })
            ListDivider()
            ListRow(stringResource(R.string.ui_status_dashboard), subtitle = "Analysis, sync, backup, models, errors", icon = HfIcons.Reports, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(LibraryRoutes.statusDashboard()) })
            ListDivider()
            ListRow(stringResource(R.string.ui_intel_queue), subtitle = "On-device analysis jobs", icon = HfIcons.Chip, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(LibraryRoutes.processingQueue()) })
            ListDivider()
            ListRow("Media settings", subtitle = "Analysis, transcription and categories", icon = HfIcons.Settings, tone = Tone.Neutral,
                chevron = true, onClick = { nav.push(LibraryRoutes.mediaSettings()) })
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
    PushedPage(stringResource(R.string.ui_permissions_and_capture), breadcrumb = "Settings") {
        message?.let { Banner(it, Tone.Info, null) }
        SetupRows(setupSteps(p, env.actions))
        ContactsPermissionRow()
        CalendarPermissionRow()
        if (p.smsEnabled) QuietButton(stringResource(R.string.ui_turn_off_sms_capture), env.actions.onSmsDisable)
        SectionHeader(stringResource(R.string.ui_capture_status))
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
            SecondaryButton(stringResource(R.string.ui_resume_processing), { scope.launch {
                message = try { withContext(Dispatchers.IO) { dao.retryFailed() }; CaptureWork.process(app); "Pending and failed events scheduled for processing." }
                    catch (caught: Exception) { Diagnostics.warn(caught); "Processing could not be scheduled. Saved events stay in the database." }
            } })
            if (p.smsEnabled && p.smsRead) SecondaryButton(stringResource(R.string.ui_sync_sms_history), { scope.launch {
                message = try { SmsSyncWorker.schedule(app, 0); "Full SMS history check scheduled. Already saved messages are skipped." } catch (caught: Exception) { Diagnostics.warn(caught); "SMS sync could not be scheduled." }
            } })
        }
        if (policies.isNotEmpty()) {
            SectionHeader(stringResource(R.string.ui_notification_processing))
            Note(stringResource(R.string.ui_every_accessible_notification_is_kept))
            HfCard {
                policies.forEachIndexed { i, policy ->
                    if (i > 0) ListDivider(16.dp)
                    SwitchRow(policy.packageName, policy.processEnabled, { enabled -> scope.launch {
                        message = try { withContext(Dispatchers.IO) { dao.setPolicy(NotificationPolicy(policy.packageName, enabled)) }
                            if (enabled) "Processing on for ${policy.packageName}." else "Capture only for ${policy.packageName}." }
                            catch (caught: Exception) { Diagnostics.warn(caught); "Setting could not be saved." }
                    } })
                }
            }
        }
        SectionCard(stringResource(R.string.ui_testing_and_cleanup)) {
            SecondaryButton(stringResource(R.string.ui_insert_a_synthetic_test_event), { scope.launch {
                message = try {
                    val inserted = withContext(Dispatchers.IO) { app.events.capture(NotificationSnapshot(
                        key = "synthetic-phase-zero-v1", packageName = "com.hellfire.fixture", postedAt = 1, receivedAt = System.currentTimeMillis(),
                        source = "synthetic_notification", fields = mapOf("title" to listOf("Synthetic Phase 0 fixture"), "text" to listOf("Offline capture test"))), app.packageName) }
                    CaptureWork.process(app)
                    if (inserted) "Synthetic event stored." else "Synthetic event already stored; duplicate ignored."
                } catch (caught: Exception) { Diagnostics.warn(caught); "Local storage test failed. Check storage and encryption." }
            } }, Modifier.fillMaxWidth())
            Note("This tests normalising, encryption and the database. It does not prove Android delivers notifications.")
            QuietButton(stringResource(R.string.ui_delete_captured_events), { confirmClear = true }, Modifier.fillMaxWidth(), enabled = !p.notificationAccess && !p.smsEnabled)
            Note("Turn off notification access and SMS capture first. Records linked to Money, Tasks or Media stay as evidence.")
        }
    }
    if (confirmClear) ConfirmDialog("Delete all captured events?", "This removes stored captured events and their processing jobs from this phone.", "Delete events",
        onConfirm = { confirmClear = false; scope.launch {
            message = try { withContext(Dispatchers.IO) { dao.deleteAll() }; "Captured events deleted." } catch (caught: Exception) { Diagnostics.warn(caught); "Could not delete: some events are evidence for Money, Tasks or Media." }
        } }, onDismiss = { confirmClear = false }, destructive = true)
}

@Composable
fun DevicePage() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val p = env.permissions
    PushedPage(stringResource(R.string.ui_this_device), breadcrumb = "Settings") {
        KvList(listOf("Manufacturer / model" to "${Build.MANUFACTURER} / ${Build.MODEL}", "Android / API" to "${Build.VERSION.RELEASE} / ${Build.VERSION.SDK_INT}",
            "Build" to Build.DISPLAY, "CPU ABIs" to Build.SUPPORTED_ABIS.joinToString(), "Security patch" to Build.VERSION.SECURITY_PATCH,
            "Assistant role" to if (p.roleHeld) "Held" else "Not held", "Voice service" to if (p.voiceServiceActive) "Selected" else "Not selected",
            "Owner timezone" to "Africa/Dar_es_Salaam"))
        SectionCard(stringResource(R.string.ui_assistant)) {
            SecondaryButton(stringResource(R.string.ui_choose_hellfire_as_the_digital_assistant), env.actions.onRole, Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.ui_open_assistant_settings), { env.actions.onSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) }, Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.ui_test_the_assistant_session), env.actions.onSession, Modifier.fillMaxWidth())
            val prefs = remember { env.app.getSharedPreferences("capture", 0) }
            var screen by remember { mutableStateOf(prefs.getBoolean("assist_screen", false)) }
            SwitchRow("Read the screen when I call HellFire", screen, { on -> screen = on; prefs.edit().putBoolean("assist_screen", on).apply() },
                subtitle = "Off by default. Only when you call HellFire, and only if Android's \"Use text from screen\" is on. Apps that protect their screens share nothing. Nothing is saved unless you tap Save.")
            Note("Hotword, lock-screen and OEM gesture behaviour need testing on the phone.")
        }
        SecondaryButton("Performance (developer)", { nav.push(Screen.Performance) }, Modifier.fillMaxWidth())
        Note("Counters only: no message text, amounts, transcripts or prompts. Device speed is not claimed until measured on this phone.")
        Note("The model name comes from Android. ColorOS version, OEM gestures, lock-screen invocation, microphone interruptions and overnight behaviour must be checked on the physical phone. No physical verification is claimed here.")
    }
}
