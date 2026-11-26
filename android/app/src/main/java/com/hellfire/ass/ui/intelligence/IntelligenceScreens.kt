package com.hellfire.ass.ui.intelligence

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.intelligence.AdultClassifier
import com.hellfire.ass.intelligence.DiagnosticBenchmark
import com.hellfire.ass.intelligence.IntelligencePrefs
import com.hellfire.ass.intelligence.IntelligenceSearch
import com.hellfire.ass.intelligence.IntelligenceWork
import com.hellfire.ass.intelligence.QueryParser
import com.hellfire.ass.intelligence.SampleMode
import com.hellfire.ass.intelligence.SearchConstraints
import com.hellfire.ass.intelligence.SearchHit
import com.hellfire.ass.library.LibraryRoutes
import com.hellfire.ass.ui.LocalEnv
import com.hellfire.ass.ui.LocalNav
import com.hellfire.ass.ui.PushedPage
import com.hellfire.ass.ui.Route
import com.hellfire.ass.ui.Screen
import com.hellfire.ass.ui.components.Note
import com.hellfire.ass.ui.components.OptionCard
import com.hellfire.ass.ui.components.PrimaryButton
import com.hellfire.ass.ui.components.SecondaryButton
import com.hellfire.ass.ui.components.SectionCard
import com.hellfire.ass.ui.components.SwitchRow
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(route: Route) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(LibraryRoutes.args(route.arg)["q"].orEmpty()) }
    var constraints by remember { mutableStateOf<SearchConstraints?>(null) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(route.arg) {
        if (query.isNotBlank()) {
            val parsed = QueryParser.parse(query, System.currentTimeMillis())
            constraints = parsed
            hits = IntelligenceSearch(app).search(parsed)
            status = if (hits.isEmpty()) "No matching saved items." else "${hits.size} results."
        }
    }
    PushedPage(stringResource(R.string.ui_intel_search), breadcrumb = stringResource(R.string.ui_intel_settings)) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_intel_search)) }, singleLine = true)
        PrimaryButton(stringResource(R.string.ui_intel_search), {
            scope.launch {
                val now = System.currentTimeMillis()
                val next = constraints?.let { QueryParser.refine(it, query, now) } ?: QueryParser.parse(query, now)
                constraints = next
                hits = runCatching { IntelligenceSearch(app).search(next) }.getOrElse {
                    status = it.message ?: "Search failed"
                    emptyList()
                }
                status = if (hits.isEmpty()) "No matching saved items." else "${hits.size} results. Constraints stay until you start with Find."
            }
        })
        if (status.isNotBlank()) Note(status)
        hits.forEach { hit ->
            Column(Modifier.fillMaxWidth().clickable {
                nav.push(when (hit.open) {
                    "player" -> LibraryRoutes.player(hit.itemId, hit.startMs ?: 0)
                    "inbox" -> LibraryRoutes.inboxItem(hit.itemId)
                    else -> LibraryRoutes.mediaDetail(hit.itemId)
                })
            }.padding(vertical = 8.dp)) {
                com.hellfire.ass.ui.library.SceneThumbnail(hit.thumbnailPath)
                Text(hit.title, style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                Text("${hit.contentKind} · ${hit.matchSource} · ${"%.2f".format(hit.relevance)}", style = Hf.type.caption, color = Hf.colors.muted)
                if (hit.startMs != null) Text("At ${hit.startMs} ms", style = Hf.type.caption, color = Hf.colors.muted)
                if (hit.snippet.isNotBlank()) Text(hit.snippet, style = Hf.type.rowSubtitle, color = Hf.colors.bodySecondary)
            }
        }
    }
}

@Composable
fun ProcessingQueueScreen() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val jobs by remember { app.database.jobs().queue(50) }.collectAsStateWithLifecycle(emptyList())
    PushedPage(stringResource(R.string.ui_intel_queue), breadcrumb = stringResource(R.string.ui_intel_settings)) {
        if (jobs.isEmpty()) Note("No analysis jobs. Use Media intelligence settings to queue the library.")
        jobs.forEach { job ->
            SectionCard("${job.type} · ${job.state}") {
                Text(job.itemId ?: "No item", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                Text("Progress ${job.progress} · attempts ${job.attempts}", style = Hf.type.caption, color = Hf.colors.muted)
                if (!job.error.isNullOrBlank()) Text(job.error, style = Hf.type.caption, color = Hf.colors.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.ui_pause), { scope.launch { app.database.jobs().pause(job.id, System.currentTimeMillis()) } })
                    SecondaryButton(stringResource(R.string.ui_intel_resume), { scope.launch { app.database.jobs().resume(job.id, System.currentTimeMillis()); IntelligenceWork.schedule(app) } })
                    SecondaryButton(stringResource(R.string.ui_retry), { scope.launch { app.database.jobs().retryFailed(job.id, System.currentTimeMillis()); IntelligenceWork.schedule(app) } })
                }
            }
        }
    }
}

@Composable
fun MediaSettingsScreen() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { IntelligencePrefs(app) }
    var charging by remember { mutableStateOf(prefs.chargingOnly) }
    var idle by remember { mutableStateOf(prefs.idlePreference) }
    var thermal by remember { mutableStateOf(prefs.pauseAtModerate) }
    var storage by remember { mutableStateOf(prefs.storageNotLow) }
    var wifi by remember { mutableStateOf(prefs.wifiRequired) }
    var transcribe by remember { mutableStateOf(prefs.transcribe) }
    var mode by remember { mutableStateOf(prefs.sampleMode) }
    var battery by remember { mutableStateOf(prefs.minBattery) }
    var concurrency by remember { mutableStateOf(prefs.maxConcurrent) }
    var genre by remember { mutableStateOf("") }
    var genres by remember { mutableStateOf(prefs.genres()) }
    var status by remember { mutableStateOf(AdultClassifier(app).status()) }
    var note by remember { mutableStateOf("Not measured. Run the diagnostic to record timings on this device.") }
    val categories by remember { app.database.library().categories() }.collectAsStateWithLifecycle(emptyList())
    val model = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            note = runCatching { AdultClassifier(app).importModel(uri); status = AdultClassifier(app).status(); "Classifier file imported. It is not verified." }
                .getOrElse { it.message ?: "Import failed" }
        }
    }
    val labels = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            note = runCatching { AdultClassifier(app).importLabels(uri); "Labels imported." }.getOrElse { it.message ?: "Label import failed" }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    PushedPage(stringResource(R.string.ui_intel_settings), breadcrumb = stringResource(R.string.ui_settings)) {
        SectionCard(stringResource(R.string.ui_intel_search)) {
            Note(mediaPermissionText(context))
            SecondaryButton("Request media read", {
                permission.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            })
            SecondaryButton(stringResource(R.string.ui_intel_search), { nav.push(LibraryRoutes.search()) })
            SecondaryButton(stringResource(R.string.ui_intel_queue), { nav.push(LibraryRoutes.processingQueue()) })
        }
        SectionCard("When analysis may run") {
            SwitchRow(stringResource(R.string.ui_intel_charging), charging, { charging = it; prefs.chargingOnly = it })
            SwitchRow("Prefer idle device", idle, { idle = it; prefs.idlePreference = it })
            SwitchRow("Pause when warm", thermal, { thermal = it; prefs.pauseAtModerate = it })
            SwitchRow("Pause when storage is low", storage, { storage = it; prefs.storageNotLow = it })
            SwitchRow("Wi-Fi for network transfers", wifi, { wifi = it; prefs.wifiRequired = it }, subtitle = "Local analysis does not use the network, so this does not block it.")
            SwitchRow("Transcribe audio", transcribe, { transcribe = it; prefs.transcribe = it })
            Text("Minimum battery $battery%", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("-", { battery = (battery - 5).coerceAtLeast(5); prefs.minBattery = battery })
                SecondaryButton("+", { battery = (battery + 5).coerceAtMost(90); prefs.minBattery = battery })
            }
            Text("Jobs per run $concurrency", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("1", { concurrency = 1; prefs.maxConcurrent = 1 })
                SecondaryButton("2", { concurrency = 2; prefs.maxConcurrent = 2 })
                SecondaryButton("3", { concurrency = 3; prefs.maxConcurrent = 3 })
            }
        }
        SectionCard("Sampling") {
            SampleMode.entries.forEach { entry ->
                val label = when (entry) { SampleMode.FAST -> stringResource(R.string.ui_intel_fast); SampleMode.BALANCED -> stringResource(R.string.ui_intel_balanced); SampleMode.DEEP -> stringResource(R.string.ui_intel_deep) }
                OptionCard(label, entry.name, mode == entry.name, {
                    mode = entry.name
                    prefs.sampleMode = entry.name
                })
            }
        }
        SectionCard("Models") {
            Note("Language: ${if (app.localModel.configured) "installed" else "not installed"}. Speech: ${if (app.speech.model.isFile) "installed" else "not installed"}. Embeddings: ${if (app.semantic.configured) "installed" else "not installed"}.")
            Note(status)
            Note("Adult labels are uncertain and never treated as verified. No age, consent, or identity is inferred. Nothing is downloaded automatically.")
            SecondaryButton("Open on-device models", { nav.push(Screen.LocalModels) })
            SecondaryButton("Import adult classifier", { model.launch(arrayOf("*/*")) })
            SecondaryButton("Import classifier labels", { labels.launch(arrayOf("text/plain", "text/*")) })
        }
        SectionCard("User genres") {
            OutlinedTextField(genre, { genre = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Genre name") }, singleLine = true)
            PrimaryButton("Add genre", { prefs.addGenre(genre); genres = prefs.genres(); genre = "" })
            genres.forEach { name ->
                Text(name, Modifier.clickable { prefs.removeGenre(name); genres = prefs.genres() }.padding(vertical = 6.dp), color = Hf.colors.onSurface)
            }
            Note("Genres are matched to container tags only. Song recognition is not implemented.")
        }
        SectionCard("Category policies") {
            if (categories.isEmpty()) Note("No categories yet.")
            categories.take(30).forEach { category ->
                Text("${category.name}: ${category.policy}", style = Hf.type.rowSubtitle, color = Hf.colors.onSurface)
                SecondaryButton("Next policy", {
                    scope.launch {
                        val order = com.hellfire.ass.library.CategoryPolicy.entries
                        val current = runCatching { com.hellfire.ass.library.CategoryPolicy.valueOf(category.policy) }.getOrDefault(com.hellfire.ass.library.CategoryPolicy.DEFAULT)
                        val next = order[(order.indexOf(current) + 1) % order.size]
                        app.database.library().updateCategory(category.copy(policy = next.name, updatedAt = System.currentTimeMillis()))
                    }
                })
            }
        }
        PrimaryButton(stringResource(R.string.ui_intel_analyze), {
            scope.launch {
                IntelligenceWork.enqueueMissing(app, 30)
                IntelligenceWork.schedule(app)
                note = "Queued. Local analysis runs when charging, battery, thermal and storage constraints allow."
            }
        })
        SecondaryButton("Reanalyse with installed models", {
            scope.launch {
                val ids = app.database.library().analysedWithoutModel(listOf("transcript", "description", "adult"), 5000)
                ids.forEach { app.database.jobs().enqueue(com.hellfire.ass.intelligence.ContentEngine.newJob(it, System.currentTimeMillis())) }
                IntelligenceWork.schedule(app)
                note = "Queued ${ids.size} items that previously lacked a model. Jobs run sequentially."
            }
        })
        SecondaryButton("Run diagnostic", {
            scope.launch { note = "Running…"; note = DiagnosticBenchmark(app).run() }
        })
        Note(note)
    }
}

private fun mediaPermissionText(context: android.content.Context): String {
    val needed = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    return if (missing.isEmpty()) "Media read permission granted for indexed files. Managed originals do not need it."
    else "Media read permission not granted: ${missing.joinToString { it.substringAfterLast('.') }}"
}
