package com.hellfire.ass.ui

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

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
    val roles = mapOf("LANGUAGE" to stringResource(R.string.ui_language_model), "SPEECH" to stringResource(R.string.ui_speech_model), "EMBEDDING" to stringResource(R.string.ui_meaning_search))
    val model = ModelCatalog.entry(selected)
    SectionCard(stringResource(R.string.ui_model_catalog)) {
        Note(stringResource(R.string.ui_choose_a_compatible_format_from_a))
        ChoiceField(stringResource(R.string.ui_model), model, ModelCatalog.entries, { "${it.name} · ${roles[it.role]}" }, { selected = it.id })
        Text(model.name)
        Note("${model.publisher} · ${model.license} · ${model.release.size / 1_000_000} MB\n${model.description}")
        Note("SHA-256: ${model.release.sha256}\nRevision: ${model.release.commit}")
        SecondaryButton(stringResource(R.string.ui_publisher_and_model_details), { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.source)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        PrimaryButton(stringResource(R.string.ui_download_and_use_selected_model), { ModelCatalog.download(app, model.id) }, enabled = !busy && !(managedRunning && selected == "gemma4-e2b"))
        if (managedRunning) SecondaryButton(stringResource(R.string.ui_pause_gemma_download), { ModelUpdates.cancel(app); ModelUpdates.suppressAutomaticDownload(app) })
        if (busy) SecondaryButton(stringResource(R.string.ui_pause_download), { androidx.work.WorkManager.getInstance(app).cancelUniqueWork(ModelCatalog.WORK) })
        if (status.isNotBlank()) Banner(status)
        if (managed.source == "managed") {
            Note("Gemma 4 E2B installed revision: ${managed.installed?.commit} · SHA-256 ${managed.installed?.sha256}")
            if (update != null) SecondaryButton(stringResource(R.string.ui_install_published_gemma_update), { ModelUpdates.download(app, update) }, enabled = !busy)
        }
        if (managed.status.isNotBlank()) Note(managed.status)
        SecondaryButton(stringResource(R.string.ui_check_gemma_updates), { ModelUpdates.checkNow(app) }, enabled = !busy)
        listOf("LANGUAGE", "SPEECH", "EMBEDDING").forEach { role ->
            val installed = if (role == "LANGUAGE" && managed.source == "managed") "gemma4-e2b" else app.getSharedPreferences("model-catalog", 0).getString("installed-$role", null)
            val fileExists = when (role) { "LANGUAGE" -> app.localModel.configured; "SPEECH" -> app.speech.model.isFile; else -> app.semantic.configured }
            Note("${roles[role]}: " + if (!fileExists) stringResource(R.string.ui_not_installed) else ModelCatalog.entries.firstOrNull { it.id == installed }?.name ?: stringResource(R.string.ui_imported_model))
        }
        Note(stringResource(R.string.ui_downloads_wait_for_wi_fi_and_adequate))
    }
}
