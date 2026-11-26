package com.hellfire.ass.ui.settings

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import kotlinx.coroutines.*

@Composable
fun GuidedSetupPage() {
    val env = LocalEnv.current
    val app = env.app
    val nav = LocalNav.current
    val prefs = remember { app.getSharedPreferences("ui", 0) }
    var goal by rememberSaveable { mutableStateOf(prefs.getString("setup-goal", "spending")!!) }
    val names = mapOf("spending" to stringResource(R.string.ui_track_spending), "conversations" to stringResource(R.string.ui_remember_conversations), "commitments" to stringResource(R.string.ui_manage_commitments))
    val titles = when (goal) {
        "spending" -> setOf(R.string.ui_notifications, R.string.ui_sms, R.string.ui_on_time_reminders)
        "conversations" -> setOf(R.string.ui_microphone, R.string.ui_run_in_background)
        else -> setOf(R.string.ui_notifications, R.string.ui_on_time_reminders, R.string.ui_phone_assistant)
    }
    val steps = setupSteps(env.permissions, env.actions).filter { it.title in titles }
    PushedPage(stringResource(R.string.ui_guided_setup), breadcrumb = "Settings") {
        Note(stringResource(R.string.ui_choose_a_starting_goal_you_can_add_the))
        names.forEach { (id, label) -> OptionCard(label, "", goal == id, { goal = id; prefs.edit().putString("setup-goal", id).apply() }) }
        Text("${steps.count { it.on }} / ${steps.size} " + stringResource(R.string.ui_permissions_enabled))
        steps.forEach { step -> SectionCard(stringResource(step.title)) {
            Note(stringResource(step.detail))
            Note(if (step.on) stringResource(R.string.ui_enabled) else stringResource(R.string.ui_needs_your_permission))
            if (!step.on) SecondaryButton(stringResource(R.string.ui_open_setup), step.turnOn)
        } }
        if (goal == "spending") SecondaryButton(stringResource(R.string.ui_add_an_account), { nav.push(Screen.Accounts, "add") })
        if (goal == "conversations") SecondaryButton(stringResource(R.string.ui_choose_speech_and_language_models), { nav.push(Screen.LocalModels) })
        SectionCard(stringResource(R.string.ui_check_it_works)) {
            Note(stringResource(R.string.ui_permission_enabled_is_not_proof_of))
            SecondaryButton(stringResource(R.string.ui_open_capture_checks), { nav.push(Screen.Permissions) })
            SecondaryButton(stringResource(R.string.ui_connections_and_recovery), { nav.push(Screen.Recovery) })
        }
        PrimaryButton(stringResource(R.string.ui_continue_to_hellfire), { prefs.edit().putBoolean("setup_done", true).apply(); nav.tab(MainTab.Home) })
        QuietButton(stringResource(R.string.ui_finish_setup_later), { prefs.edit().putBoolean("setup_done", true).apply(); nav.tab(MainTab.Home) })
        Note(stringResource(R.string.ui_your_goal_is_saved_resume_from_settings))
    }
}

@Composable
fun RecoveryPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val env = LocalEnv.current
    val app = env.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val queues by remember { app.database.events().queueSummary() }.collectAsStateWithLifecycle(emptyList())
    val audits by remember { app.database.events().audits() }.collectAsStateWithLifecycle(emptyList())
    val connections by remember { app.database.communications().integrations() }.collectAsStateWithLifecycle(emptyList())
    val media by remember { app.database.media().items() }.collectAsStateWithLifecycle(emptyList())
    var refresh by remember { mutableIntStateOf(0) }
    val snapshot = remember(refresh) { Triple(com.hellfire.ass.ai.ModelUpdates.state(app).status, com.hellfire.ass.sync.WebSync.readStatus(app), com.hellfire.ass.security.DriveBackupWork.readStatus(app)) }
    var message by remember { mutableStateOf<String?>(null) }
    PushedPage(stringResource(R.string.ui_needs_attention), breadcrumb = "Settings") {
        message?.let { Banner(it) }
        SecondaryButton(stringResource(R.string.ui_refresh_status), { refresh++ })
        Note(stringResource(R.string.ui_saved_records_stay_on_the_phone_retry))
        SectionCard(stringResource(R.string.ui_capture_and_permissions)) {
            setupSteps(env.permissions, env.actions).forEach { Note(stringResource(it.title) + ": " + if (it.on) stringResource(R.string.ui_enabled) else stringResource(R.string.ui_not_enabled)) }
            audits.forEach { Note("${it.source} · ${it.captured} saved · ${it.failures} failures · " + (it.lastCapturedAt?.let(Fmt::full) ?: stringResource(R.string.ui_no_delivery_observed_yet))) }
            Note(queues.joinToString { "${it.state}: ${it.count}" }.ifEmpty { stringResource(R.string.ui_no_queued_events) })
            SecondaryButton(stringResource(R.string.ui_open_permissions_and_capture), { nav.push(Screen.Permissions) })
            SecondaryButton(stringResource(R.string.ui_retry_failed_processing), { scope.launch {
                message = try { withContext(Dispatchers.IO) { app.database.events().retryFailed(); com.hellfire.ass.processing.CaptureWork.process(app) }; context.getString(R.string.ui_processing_scheduled_check_the_updated) }
                catch (e: Exception) { context.getString(R.string.ui_could_not_schedule_processing_saved) }
            } })
        }
        SectionCard(stringResource(R.string.ui_connections)) {
            if (connections.isEmpty()) Note(stringResource(R.string.ui_no_connection_results_recorded_optional))
            connections.forEach { state -> ListRow(state.adapter, subtitle = "${state.state} · ${Fmt.full(state.updatedAt)}\n${state.error.orEmpty()}", chevron = true,
                onClick = { nav.push(if (state.adapter.startsWith("EMAIL") || state.adapter.startsWith("IMAP")) Screen.Email else Screen.WhatsApp) }) }
            SecondaryButton("Email", { nav.push(Screen.Email) }); SecondaryButton("WhatsApp", { nav.push(Screen.WhatsApp) })
            Note(snapshot.second)
            SecondaryButton(stringResource(R.string.ui_web_access), { nav.push(Screen.WebAccess) })
        }
        SectionCard(stringResource(R.string.ui_models_and_recordings)) {
            Note(snapshot.first.ifEmpty { stringResource(R.string.ui_no_model_download_status_recorded) })
            Note("${media.count { it.state.contains("FAIL") }} " + stringResource(R.string.ui_recordings_need_attention))
            media.filter { it.state.contains("FAIL") || it.state == "QUEUED" || it.state == "WAITING" }.take(20).forEach { item ->
                ListRow(item.name, subtitle = mediaStatus(item.state, item.error), chevron = true, onClick = { nav.push(Screen.MediaItem, item.id) })
            }
            SecondaryButton(stringResource(R.string.ui_choose_models), { nav.push(Screen.LocalModels) })
        }
        SectionCard(stringResource(R.string.ui_backup_and_storage)) {
            Note(snapshot.third)
            Note("${app.filesDir.usableSpace / (1024 * 1024)} MiB " + stringResource(R.string.ui_free_on_this_device))
            SecondaryButton(stringResource(R.string.ui_open_backup_and_verification), { nav.push(Screen.Backup) })
            SecondaryButton(stringResource(R.string.ui_privacy_and_storage), { nav.push(Screen.Privacy) })
        }
    }
}

@Composable
fun PrivacyPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var cloud by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf(false) }
    var drive by remember { mutableStateOf(false) }
    var mediaBytes by remember { mutableLongStateOf(0) }
    var databaseBytes by remember { mutableLongStateOf(0) }
    LaunchedEffect(refresh) { try { withContext(Dispatchers.IO) {
        cloud = app.ai.store.read().cloudEnabled
        web = app.secrets.read(com.hellfire.ass.sync.WebSync.CONFIG) != null
        drive = app.secrets.read(com.hellfire.ass.security.DriveBackupWork.CONFIG)?.optBoolean("enabled") == true
        mediaBytes = app.database.media().all().sumOf { it.size }
        databaseBytes = app.getDatabasePath("hellfire.db").length()
        loaded = true; failure = null
    } } catch (e: Exception) { failure = context.getString(R.string.ui_privacy_settings_could_not_be_read) } }
    PushedPage(stringResource(R.string.ui_privacy_and_storage), breadcrumb = "Settings") {
        if (!loaded) {
            failure?.let { Banner(it, Tone.Warning) }
            Note(stringResource(R.string.ui_loading_privacy_settings))
            if (failure != null) SecondaryButton(stringResource(R.string.ui_retry), { refresh++ })
            return@PushedPage
        }
        SectionCard(stringResource(R.string.ui_on_this_phone)) {
            Note(stringResource(R.string.ui_captured_sources_memories_skills_and))
            KvList(listOf(stringResource(R.string.ui_original_media) to "${mediaBytes / (1024 * 1024)} MiB", stringResource(R.string.ui_database) to "${databaseBytes / (1024 * 1024)} MiB", stringResource(R.string.ui_free_space) to "${app.filesDir.usableSpace / (1024 * 1024)} MiB"))
            Note(stringResource(R.string.ui_model_weights_and_app_code_are_separate))
            SecondaryButton(stringResource(R.string.ui_manage_recordings), { nav.push(Screen.Recordings) })
            SecondaryButton(stringResource(R.string.ui_manage_memory), { nav.push(Screen.Memory) })
            SecondaryButton(stringResource(R.string.ui_manage_ai_skills), { nav.push(Screen.Skills) })
        }
        SectionCard(stringResource(R.string.ui_outside_this_phone)) {
            Note(stringResource(R.string.ui_web_copy) + ": " + if (web) stringResource(R.string.ui_enabled_selected_records_are_readable_on) else stringResource(R.string.ui_sync_is_off_an_earlier_server_copy_may))
            SecondaryButton(stringResource(R.string.ui_review_or_delete_web_copy), { nav.push(Screen.WebAccess) })
            Note(stringResource(R.string.ui_drive_backup) + ": " + if (drive) stringResource(R.string.ui_enabled_passphrase_encrypted_archives) else stringResource(R.string.ui_automatic_drive_backup_is_off_previously))
            SecondaryButton(stringResource(R.string.ui_manage_backup_archives), { nav.push(Screen.Backup) })
            Note(stringResource(R.string.ui_cloud_ai) + ": " + if (cloud) stringResource(R.string.ui_enabled_for_text_you_explicitly_submit) else stringResource(R.string.ui_off_local_answers_do_not_use_a_cloud))
            SecondaryButton(stringResource(R.string.ui_manage_cloud_ai), { nav.push(Screen.CloudAi) })
        }
        Note(stringResource(R.string.ui_turning_a_connection_off_does_not_erase))
    }
}
