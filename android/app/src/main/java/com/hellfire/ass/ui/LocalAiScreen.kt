package com.hellfire.ass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.*

@Composable
fun LocalAiScreen(app: HellFireApp) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("Import a licensed .litertlm model to enable local inference. No model is downloaded automatically.") }
    var backend by remember { mutableStateOf("CPU") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            result = try { app.localModel.import(uri); "Local model imported. Compatibility and speed require physical verification." }
                catch (_: Exception) { "Model import failed. Select a compatible .litertlm file with sufficient free storage." }
            finally { busy = false }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            result = try { com.hellfire.ass.media.LocalOcr.read(app, uri).ifBlank { "No readable text found." } }
                catch (_: Exception) { "Local OCR could not read this image." }
            finally { busy = false }
        }
    }
    OutlinedButton(enabled = !busy, onClick = { imageLauncher.launch(arrayOf("image/*")) }) { Text("Read selected image offline") }
    Text("Local AI", style = MaterialTheme.typography.titleLarge)
    Text("Capture, ledger calculations and reminders do not require a model. Model output is untrusted text and cannot execute actions.")
    Button(enabled = !busy, onClick = { launcher.launch(arrayOf("*/*")) }) { Text("Import local model") }
    Choice("Backend", listOf("CPU", "GPU"), backend) { backend = it }
    Text("NPU capability is unverified. CPU/GPU compatibility depends on the selected model and this device.")
    OutlinedTextField(prompt, { prompt = it }, label = { Text("Local question or extraction prompt") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy && prompt.isNotBlank(), onClick = {
        scope.launch {
            busy = true
            result = try {
                val answer = app.localModel.generate(prompt, backend == "GPU")
                "${answer.text}\n\nMeasured ${answer.elapsedMs} ms · ${answer.backend} · process PSS ${answer.pssKb} KB. Generated answer; verify source evidence."
            } catch (_: Exception) { "Local inference unavailable. Check model compatibility, backend and free memory. No cloud fallback was used." }
            finally { busy = false }
        }
    }) { Text(if (busy) "Working locally…" else "Run local question") }
    Text(result)
    TextButton(enabled = !busy, onClick = { scope.launch { app.localModel.remove(); result = "Local model removed; core features remain available." } }) { Text("Remove imported model") }
    HorizontalDivider()
    Text("Meaning search", style = MaterialTheme.typography.titleMedium)
    Text("Import a MediaPipe text-embedder .tflite model (for example Universal Sentence Encoder) to add meaning-based matches to assistant search. Vectors stay in the encrypted database, skip security-sensitive sources and are rebuilt rather than backed up. Requires an arm64-v8a device.")
    var semanticStatus by remember { mutableStateOf("") }
    LaunchedEffect(busy) { if (!busy) semanticStatus = try { app.semantic.status() } catch (_: Exception) { "Index status unavailable." } }
    Text(semanticStatus)
    Text("Automatic indexing waits for charging and adequate battery. Use Index now to index on battery; new sources remain available to literal search.")
    val embedderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            result = try { app.semantic.import(uri); "Embedding model imported. Tap Index now or let automatic indexing run while charging." }
                catch (e: IllegalArgumentException) { e.message ?: "Embedding model import failed." }
                catch (_: Exception) { "Embedding model import failed. Check free storage." }
            finally { busy = false }
        }
    }
    Button(enabled = !busy, onClick = { embedderLauncher.launch(arrayOf("*/*")) }) { Text("Import embedding model") }
    OutlinedButton(enabled = !busy && app.semantic.configured, onClick = { scope.launch {
        busy = true
        result = try { "Indexed ${app.semantic.indexPending(2000)} sources in this run." }
            catch (_: Exception) { "Indexing stopped. Check model compatibility, device temperature and free memory." }
        finally { busy = false }
    } }) { Text("Index now (up to 2,000 sources)") }
    TextButton(enabled = !busy && app.semantic.configured, onClick = { scope.launch { busy = true; try { app.semantic.remove(); result = "Embedding model and vectors removed; literal search remains." } finally { busy = false } } }) { Text("Remove embedding model and index") }
}
