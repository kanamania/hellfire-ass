package com.hellfire.ass.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ai.*
import com.hellfire.ass.ui.components.*
import kotlinx.coroutines.delay

@Composable
fun ModelCatalogSection(app: HellFireApp) {
    val work by remember { androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ModelCatalog.WORK) }.collectAsState(initial = emptyList())
    val managedWork by remember { androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ModelUpdates.DOWNLOAD) }.collectAsState(initial = emptyList())
    val managedRunning = managedWork.any { !it.state.isFinished }
    val busy = work.any { !it.state.isFinished }
    var selected by rememberSaveable { mutableStateOf("gemma4-e2b") }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(busy) { do { status = app.getSharedPreferences("model-catalog", 0).getString("status", "").orEmpty(); if (busy) delay(1500) } while (busy) }
    var managed by remember { mutableStateOf(ModelUpdates.state(app)) }
    LaunchedEffect(managedRunning, busy) { do { managed = ModelUpdates.state(app); if (managedRunning || busy) delay(1500) } while (managedRunning || busy) }
    val update = ModelUpdates.pendingUpdate(managed)
    val roles = mapOf("LANGUAGE" to tr("Language model"), "SPEECH" to tr("Speech model"), "EMBEDDING" to tr("Meaning search"))
    val model = ModelCatalog.entry(selected)
    SectionCard(tr("Model catalog")) {
        Note(tr("Choose a compatible format from a trusted publisher. No model name or download URL needs typing."))
        ChoiceField("Model", model, ModelCatalog.entries, { "${it.name} · ${roles[it.role]}" }, { selected = it.id })
        Text(model.name)
        Note("${model.publisher} · ${model.license} · ${model.release.size / 1_000_000} MB\n${model.description}")
        Note("SHA-256: ${model.release.sha256}\nRevision: ${model.release.commit}")
        SecondaryButton(tr("Publisher and model details"), { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.source)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        PrimaryButton(tr("Download and use selected model"), { ModelCatalog.download(app, model.id) }, enabled = !busy && !(managedRunning && selected == "gemma4-e2b"))
        if (managedRunning) SecondaryButton(tr("Pause Gemma download"), { ModelUpdates.cancel(app); ModelUpdates.suppressAutomaticDownload(app) })
        if (busy) SecondaryButton(tr("Pause download"), { androidx.work.WorkManager.getInstance(app).cancelUniqueWork(ModelCatalog.WORK) })
        if (status.isNotBlank()) Banner(status)
        if (managed.source == "managed") {
            Note("Gemma 4 E2B installed revision: ${managed.installed?.commit} · SHA-256 ${managed.installed?.sha256}")
            if (update != null) SecondaryButton(tr("Install published Gemma update"), { ModelUpdates.download(app, update) }, enabled = !busy)
        }
        if (managed.status.isNotBlank()) Note(managed.status)
        SecondaryButton(tr("Check Gemma updates"), { ModelUpdates.checkNow(app) }, enabled = !busy)
        listOf("LANGUAGE", "SPEECH", "EMBEDDING").forEach { role ->
            val installed = if (role == "LANGUAGE" && managed.source == "managed") "gemma4-e2b" else app.getSharedPreferences("model-catalog", 0).getString("installed-$role", null)
            val fileExists = when (role) { "LANGUAGE" -> app.localModel.configured; "SPEECH" -> app.speech.model.isFile; else -> app.semantic.configured }
            Note("${roles[role]}: " + if (!fileExists) tr("Not installed") else ModelCatalog.entries.firstOrNull { it.id == installed }?.name ?: tr("Imported model"))
        }
        Note(tr("Downloads wait for Wi-Fi and adequate battery/storage. Size and SHA-256 are checked, then the runtime loads the file before installation. Existing models remain until replacement succeeds."))
    }
}
