package com.hellfire.ass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.*

@Composable
fun LocalAiScreen(app: HellFireApp) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var backend by remember { mutableStateOf("CPU") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            result = try { app.localModel.import(uri); "Local model imported. Compatibility and speed require physical verification." }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Model import failed. Select a compatible .litertlm file with sufficient free storage." }
            finally { busy = false }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            result = try { com.hellfire.ass.media.LocalOcr.read(app, uri).ifBlank { "No readable text found." } }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Local OCR could not read this image." }
            finally { busy = false }
        }
    }
    GemmaCard(app)
    HorizontalDivider()
    OutlinedButton(enabled = !busy, onClick = { imageLauncher.launch(arrayOf("image/*")) }) { Text("Read selected image offline") }
    Text("Local AI", style = MaterialTheme.typography.titleLarge)
    Text("Capture, ledger calculations and reminders do not require a model. Model output is untrusted text and cannot execute actions.")
    OutlinedButton(enabled = !busy, onClick = { launcher.launch(arrayOf("*/*")) }) { Text("Use my own .litertlm model instead") }
    Choice("Backend", listOf("CPU", "GPU"), backend) { backend = it }
    Text("NPU capability is unverified. CPU/GPU compatibility depends on the selected model and this device.")
    OutlinedTextField(prompt, { prompt = it }, label = { Text("Local question or extraction prompt") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy && prompt.isNotBlank(), onClick = {
        scope.launch {
            busy = true
            result = try {
                val answer = app.localModel.generate(prompt, backend == "GPU")
                "${answer.text}\n\nMeasured ${answer.elapsedMs} ms · ${answer.backend} · process PSS ${answer.pssKb} KB. Generated answer; verify source evidence."
            } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Local inference unavailable. Check model compatibility, backend and free memory. No cloud fallback was used." }
            finally { busy = false }
        }
    }) { Text(if (busy) "Working locally…" else "Run local question") }
    Text(result)
    TextButton(enabled = !busy, onClick = { scope.launch { app.localModel.remove(); result = "Language model removed and will not download again until you choose Download. Core features remain available." } }) { Text("Remove language model") }
    HorizontalDivider()
    Text("Meaning search", style = MaterialTheme.typography.titleMedium)
    Text("Import a MediaPipe text-embedder .tflite model (for example Universal Sentence Encoder) to add meaning-based matches to assistant search. Vectors stay in the encrypted database, skip security-sensitive sources and are rebuilt rather than backed up. Requires an arm64-v8a device.")
    var semanticStatus by remember { mutableStateOf("") }
    LaunchedEffect(busy) { if (!busy) semanticStatus = try { app.semantic.status() } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Index status unavailable." } }
    Text(semanticStatus)
    Text("Automatic indexing waits for charging and adequate battery. Use Index now to index on battery; new sources remain available to literal search.")
    val embedderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            result = try { app.semantic.import(uri); "Embedding model imported. Tap Index now or let automatic indexing run while charging." }
                catch (e: IllegalArgumentException) { e.message ?: "Embedding model import failed." }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Embedding model import failed. Check free storage." }
            finally { busy = false }
        }
    }
    Button(enabled = !busy, onClick = { embedderLauncher.launch(arrayOf("*/*")) }) { Text("Import embedding model") }
    OutlinedButton(enabled = !busy && app.semantic.configured, onClick = { scope.launch {
        busy = true
        result = try { "Indexed ${app.semantic.indexPending(2000)} sources in this run." }
            catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Indexing stopped. Check model compatibility, device temperature and free memory." }
        finally { busy = false }
    } }) { Text("Index now (up to 2,000 sources)") }
    TextButton(enabled = !busy && app.semantic.configured, onClick = { scope.launch { busy = true; try { app.semantic.remove(); result = "Embedding model and vectors removed; literal search remains." } finally { busy = false } } }) { Text("Remove embedding model and index") }
}

/** Gemma 4 E2B: first-launch download, daily update check, owner-approved updates. */
@Composable
private fun GemmaCard(app: HellFireApp) {
    val work by remember { androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(com.hellfire.ass.ai.ModelUpdates.DOWNLOAD) }
        .collectAsState(initial = emptyList())
    var state by remember { mutableStateOf(com.hellfire.ass.ai.ModelUpdates.state(app)) }
    val running = work.any { !it.state.isFinished }
    // Progress is written to preferences by the worker; refresh while this screen is open.
    LaunchedEffect(running) { while (true) { state = com.hellfire.ass.ai.ModelUpdates.state(app); if (!running) break; delay(2000) } }
    val update = com.hellfire.ass.ai.ModelUpdates.pendingUpdate(state)
    fun gb(bytes: Long) = String.format(java.util.Locale.ENGLISH, "%.2f GB", bytes / 1e9)
    Text(com.hellfire.ass.ai.GemmaModel.NAME, style = MaterialTheme.typography.titleMedium)
    Text(when {
        state.source == "managed" -> "Installed · version ${state.installed?.sha256?.take(8)} · ${gb(state.installed?.size ?: 0)}"
        state.source == "imported" -> "Your own model file is in use."
        running -> "Not installed yet"
        state.declined -> "Not installed. You removed it; it downloads only when you choose."
        else -> "Not installed. It downloads automatically on Wi-Fi."
    })
    Text("Official Google file from Hugging Face (litert-community, Apache-2.0). Downloads only on Wi-Fi with enough battery and storage, resumes after interruptions, and is used only after its SHA-256 matches and it loads on this phone.")
    if (running && state.total > 0) LinearProgressIndicator(progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    if (state.status.isNotBlank()) Text(state.status)
    if (update != null && !running) Text("Update available: version ${update.sha256.take(8)} · ${gb(update.size)}. The current model stays in use until the new one is verified.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            running -> OutlinedButton(onClick = { com.hellfire.ass.ai.ModelUpdates.cancel(app) }) { Text("Cancel download") }
            update != null -> Button(onClick = { com.hellfire.ass.ai.ModelUpdates.download(app, update) }) { Text("Update") }
            state.source != "managed" -> Button(onClick = {
                com.hellfire.ass.ai.ModelUpdates.download(app, state.available ?: com.hellfire.ass.ai.GemmaModel.PINNED)
            }) { Text(if (state.source == "imported") "Switch to ${com.hellfire.ass.ai.GemmaModel.NAME}" else "Download now") }
        }
        if (!running) OutlinedButton(onClick = { com.hellfire.ass.ai.ModelUpdates.checkNow(app) }) { Text("Check for update") }
    }
}
