package com.hellfire.ass.ui.settings

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
    val names = mapOf("spending" to tr("Track spending"), "conversations" to tr("Remember conversations"), "commitments" to tr("Manage commitments"))
    val titles = when (goal) {
        "spending" -> setOf("Notifications", "SMS", "On-time reminders")
        "conversations" -> setOf("Microphone", "Run in background")
        else -> setOf("Notifications", "On-time reminders", "Phone assistant")
    }
    val steps = setupSteps(env.permissions, env.actions).filter { it.title in titles }
    PushedPage(tr("Guided setup"), breadcrumb = "Settings") {
        Note(tr("Choose a starting goal. You can add the other features later."))
        names.forEach { (id, label) -> OptionCard(label, "", goal == id, { goal = id; prefs.edit().putString("setup-goal", id).apply() }) }
        Text("${steps.count { it.on }} / ${steps.size} " + tr("permissions enabled"))
        steps.forEach { step -> SectionCard(tr(step.title)) {
            Note(tr(step.detail))
            Note(if (step.on) tr("Enabled") else tr("Needs your permission"))
            if (!step.on) SecondaryButton(tr("Open setup"), step.turnOn)
        } }
        if (goal == "spending") SecondaryButton(tr("Add an account"), { nav.push(Screen.Accounts, "add") })
        if (goal == "conversations") SecondaryButton(tr("Choose speech and language models"), { nav.push(Screen.LocalModels) })
        SectionCard(tr("Check it works")) {
            Note(tr("Permission enabled is not proof of delivery. Receive a real notification or SMS, then check Capture status and its saved source. Test recording and reminder delivery on this phone."))
            SecondaryButton(tr("Open capture checks"), { nav.push(Screen.Permissions) })
            SecondaryButton(tr("Connections and recovery"), { nav.push(Screen.Recovery) })
        }
        PrimaryButton(tr("Continue to HellFire"), { prefs.edit().putBoolean("setup_done", true).apply(); nav.tab(MainTab.Home) })
        QuietButton(tr("Finish setup later"), { prefs.edit().putBoolean("setup_done", true).apply(); nav.tab(MainTab.Home) })
        Note(tr("Your goal is saved. Resume from Settings › Guided setup."))
    }
}

@Composable
fun RecoveryPage() {
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
    PushedPage(tr("Needs attention"), breadcrumb = "Settings") {
        message?.let { Banner(it) }
        SecondaryButton(tr("Refresh status"), { refresh++ })
        Note(tr("Saved records stay on the phone. Retry capture or analysis here; sending always requires its separate approval."))
        SectionCard(tr("Capture and permissions")) {
            setupSteps(env.permissions, env.actions).forEach { Note(tr(it.title) + ": " + if (it.on) tr("Enabled") else tr("Not enabled")) }
            audits.forEach { Note("${it.source} · ${it.captured} saved · ${it.failures} failures · " + (it.lastCapturedAt?.let(Fmt::full) ?: tr("No delivery observed yet"))) }
            Note(queues.joinToString { "${it.state}: ${it.count}" }.ifEmpty { tr("No queued events") })
            SecondaryButton(tr("Open permissions and capture"), { nav.push(Screen.Permissions) })
            SecondaryButton(tr("Retry failed processing"), { scope.launch {
                message = try { withContext(Dispatchers.IO) { app.database.events().retryFailed(); com.hellfire.ass.processing.CaptureWork.process(app) }; "Processing scheduled; check the updated queue. No messages were sent." }
                catch (e: Exception) { "Could not schedule processing. Saved records are retained." }
            } })
        }
        SectionCard(tr("Connections")) {
            if (connections.isEmpty()) Note(tr("No connection results recorded. Optional accounts can be configured below."))
            connections.forEach { state -> ListRow(state.adapter, subtitle = "${state.state} · ${Fmt.full(state.updatedAt)}\n${state.error.orEmpty()}", chevron = true,
                onClick = { nav.push(if (state.adapter.startsWith("EMAIL") || state.adapter.startsWith("IMAP")) Screen.Email else Screen.WhatsApp) }) }
            SecondaryButton("Email", { nav.push(Screen.Email) }); SecondaryButton("WhatsApp", { nav.push(Screen.WhatsApp) })
            Note(snapshot.second)
            SecondaryButton(tr("Web access"), { nav.push(Screen.WebAccess) })
        }
        SectionCard(tr("Models and recordings")) {
            Note(snapshot.first.ifEmpty { tr("No model download status recorded") })
            Note("${media.count { it.state.contains("FAIL") }} " + tr("recordings need attention"))
            media.filter { it.state.contains("FAIL") || it.state == "QUEUED" || it.state == "WAITING" }.take(20).forEach { item ->
                ListRow(item.name, subtitle = mediaStatus(item.state, item.error), chevron = true, onClick = { nav.push(Screen.MediaItem, item.id) })
            }
            SecondaryButton(tr("Choose models"), { nav.push(Screen.LocalModels) })
        }
        SectionCard(tr("Backup and storage")) {
            Note(snapshot.third)
            Note("${app.filesDir.usableSpace / (1024 * 1024)} MiB " + tr("free on this device"))
            SecondaryButton(tr("Open backup and verification"), { nav.push(Screen.Backup) })
            SecondaryButton(tr("Privacy and storage"), { nav.push(Screen.Privacy) })
        }
    }
}

@Composable
fun PrivacyPage() {
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
    } } catch (e: Exception) { failure = "Privacy settings could not be read. Nothing has changed." } }
    PushedPage(tr("Privacy and storage"), breadcrumb = "Settings") {
        if (!loaded) {
            failure?.let { Banner(it, Tone.Warning) }
            Note(tr("Loading privacy settings…"))
            if (failure != null) SecondaryButton(tr("Retry"), { refresh++ })
            return@PushedPage
        }
        SectionCard(tr("On this phone")) {
            Note(tr("Captured sources, memories, skills and usage history are stored in the encrypted database. Originals and private drafts are encrypted locally."))
            KvList(listOf(tr("Original media") to "${mediaBytes / (1024 * 1024)} MiB", tr("Database") to "${databaseBytes / (1024 * 1024)} MiB", tr("Free space") to "${app.filesDir.usableSpace / (1024 * 1024)} MiB"))
            Note(tr("Model weights and app code are separate from personal records. Original recordings remain until you explicitly delete them."))
            SecondaryButton(tr("Manage recordings"), { nav.push(Screen.Recordings) })
            SecondaryButton(tr("Manage memory"), { nav.push(Screen.Memory) })
            SecondaryButton(tr("Manage AI skills"), { nav.push(Screen.Skills) })
        }
        SectionCard(tr("Outside this phone")) {
            Note(tr("Web copy") + ": " + if (web) tr("Enabled. Selected records are readable on your server.") else tr("Sync is off. An earlier server copy may still exist until you delete it."))
            SecondaryButton(tr("Review or delete web copy"), { nav.push(Screen.WebAccess) })
            Note(tr("Drive backup") + ": " + if (drive) tr("Enabled. Passphrase-encrypted archives are uploaded to your Google Drive.") else tr("Automatic Drive backup is off. Previously exported or uploaded archives may still exist."))
            SecondaryButton(tr("Manage backup archives"), { nav.push(Screen.Backup) })
            Note(tr("Cloud AI") + ": " + if (cloud) tr("Enabled for text you explicitly submit with a cloud model. Inspect your text before sending.") else tr("Off. Local answers do not use a cloud fallback."))
            SecondaryButton(tr("Manage cloud AI"), { nav.push(Screen.CloudAi) })
        }
        Note(tr("Turning a connection off does not erase copies already exported. Deletion must be requested in the relevant destination."))
    }
}
