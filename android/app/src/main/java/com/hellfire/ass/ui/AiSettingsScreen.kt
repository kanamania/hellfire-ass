package com.hellfire.ass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ai.*
import kotlinx.coroutines.*
import java.math.BigDecimal

internal fun usd(micro: Long): String = BigDecimal.valueOf(micro, 6).stripTrailingZeros().toPlainString()
private fun microUsd(text: String): Long = text.toBigDecimal().movePointRight(6).longValueExact().also { require(it >= 0) }

@Composable
internal fun <T> AiChoice(label: String, value: String, choices: List<Pair<T, String>>, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { open = true }) { Text("$label: $value") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(id) }) }
        }
    }
}
internal fun ModelRef.label() = "${provider.label} / $model"
internal fun AiState.modelChoices(): List<ModelRef> = listOf(OfflineProvider.ref) + providers.flatMap { (id, config) ->
    if (!config.enabled || id == ProviderId.LOCAL) emptyList()
    else (config.models.map { ModelRef(id, it.id) } + listOfNotNull(config.defaultModel.takeIf(String::isNotBlank)?.let { ModelRef(id, it) })).distinct()
}

@Composable
fun AiSettingsScreen(app: HellFireApp, budgetOnly: Boolean = false) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AiState?>(null) }
    var message by remember { mutableStateOf("Loading encrypted settings…") }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var cap by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(ProviderId.OPENAI) }
    var task by remember { mutableStateOf(AiTask.CHAT) }
    fun runOperation(success: String, action: suspend () -> Unit) {
        operation = scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { action(); state = app.ai.store.read() }
                message = success
            } catch (e: CancellationException) { message = AiError.CANCELLED.message; throw e }
            catch (e: Exception) {
                message = (e as? AiException)?.error?.message ?: "Could not save settings. Check the entered values."
                runCatching { state = withContext(Dispatchers.IO) { app.ai.store.read() } }
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try {
            state = withContext(Dispatchers.IO) { app.ai.store.read() }
            cap = usd(state!!.monthlyCap)
            message = "Cloud is off by default. Keys stay encrypted on this device."
        } catch (_: Exception) { message = AiError.STORAGE.message }
    }
    val current = state
    if (current == null) { Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }); return }
    val month = app.ai.month()
    val monthly = current.usage.filterKeys { it.startsWith("$month/") }
    Banner(message, Tone.Info, null, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (!budgetOnly) {
        HfCard {
            SwitchRow("Allow cloud AI", current.cloudEnabled, { enabled ->
                runOperation("Cloud ${if (enabled) "enabled for text you submit in Ask" else "disabled"}.") { app.ai.store.update { it.copy(cloudEnabled = enabled) } }
            }, subtitle = "Only for text you type in Ask with a cloud model chosen", enabled = !busy)
        }
        Note("Captured notifications, SMS, finance, recordings and media stay local. Cloud requests redact security content, emails and long numbers. Review your text before submitting; names and other sensitive prose may remain.")
    }
    SectionCard("Monthly budget") {
        Text("$month · USD ${usd(monthly.values.sumOf { it.charged })} of ${usd(current.monthlyCap)} estimated or reserved", style = Hf.type.body, color = Hf.colors.onSurface)
        HfTextField(cap, { cap = it }, "Monthly estimated spending cap (USD)", number = true)
        PrimaryButton("Save spending cap", { runOperation("Monthly cap saved.") {
            val amount = microUsd(cap)
            require(amount <= 1_000_000_000_000)
            app.ai.store.update { it.copy(monthlyCap = amount) }
        } }, Modifier.fillMaxWidth(), enabled = !busy)
        Note("Set input and output prices per model before cloud use. Estimates use your prices and a cautious input reservation. Errors and cancellations keep the reservation. Your provider's bill is the real figure.")
    }
    if (!budgetOnly) {
        SectionCard("Providers") {
            ProviderId.entries.filter { it != ProviderId.LOCAL }.forEach { id ->
                val config = current.config(id)
                Text("${id.label}: ${if (config.enabled) "on" else "off"} · ${if (config.apiKey.isBlank()) "no key" else "key saved"} · ${config.status}", style = Hf.type.rowSubtitle, color = Hf.colors.bodySecondary)
            }
            AiChoice("Configure provider", provider.label, ProviderId.entries.filter { it != ProviderId.LOCAL }.map { it to it.label }, enabled = !busy) { provider = it }
            key(provider) {
                ProviderEditor(provider, current.config(provider), busy,
                    onSave = { enabled, key, model, price, clear -> runOperation("Provider settings saved.") {
                        require(model.length <= 200 && model.all { it.isLetterOrDigit() || it in "-._:/" })
                        require(key.length <= 4096 && key.none { it.isWhitespace() || it.isISOControl() })
                        app.ai.store.update { latest ->
                            val old = latest.config(provider)
                            val credential = if (clear) "" else key.ifBlank { old.apiKey }
                            latest.copy(providers = latest.providers + (provider to old.copy(enabled = enabled, apiKey = credential,
                                defaultModel = model, prices = if (price == null) old.prices - model else old.prices + (model to price),
                                models = if (credential == old.apiKey) old.models else emptyList(),
                                status = if (credential == old.apiKey) old.status else "Credentials changed; test connection")))
                        }
                    } },
                    onTest = { runOperation("Model discovery passed. Select a model and set its prices; inference is tested by asking a question.") { app.ai.testConnection(provider) } })
            }
        }
        SectionCard("Task routing") {
            Note("The first entry is preferred; later entries are tried once in order when a provider is limited, down, unsupported, off or over budget. The offline test can be a fallback for basic chat only.")
            AiChoice("Task", task.label, AiTask.entries.map { it to it.label }, enabled = !busy) { task = it }
            val route = current.route(task)
            val choices = current.modelChoices()
            route.forEachIndexed { index, ref ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${index + 1}. ${ref.label()}", Modifier.weight(1f), style = Hf.type.body, color = Hf.colors.onSurface)
                    if (index > 0) QuietButton("Up", { runOperation("Route order saved.") {
                        val updated = route.toMutableList().apply { add(index - 1, removeAt(index)) }
                        app.ai.store.update { it.copy(routes = it.routes + (task to updated)) }
                    } }, enabled = !busy)
                    QuietButton("Remove", { runOperation("Route saved.") {
                        app.ai.store.update { it.copy(routes = it.routes + (task to route.filterIndexed { i, _ -> i != index })) }
                    } }, enabled = !busy)
                }
            }
            if (!busy) AiChoice("Add to route", "Select provider / model", choices.filter { it !in route }.map { it to it.label() }) { ref ->
                runOperation("Route saved.") { app.ai.store.update { it.copy(routes = it.routes + (task to (route + ref))) } }
            }
        }
    }
    SectionCard("Usage this month") {
        if (monthly.isEmpty()) Text("No requests yet.", style = Hf.type.body, color = Hf.colors.muted)
        monthly.forEach { (key, total) ->
            Text("${key.substringAfter('/')} · ${total.requests} requests · ${total.input} in / ${total.output} out tokens · USD ${usd(total.charged)}", style = Hf.type.rowSubtitle, color = Hf.colors.bodySecondary)
        }
        current.audit.takeLast(10).reversed().forEach { a -> Text("${a.provider.label} / ${a.model} · ${a.result} · ${a.task.label} · ${a.requestId.take(8)}", style = Hf.type.rowSubtitle, color = Hf.colors.muted) }
        Note("Records hold task, time, provider, model, result and usage only. Prompts, answers and keys are never stored. Full history is in Reports › AI usage.")
    }
    if (busy) SecondaryButton("Cancel operation", { operation?.cancel() })
    if (!budgetOnly) Note("Official API keys only. No account sign-in or proxy exists. The on-device provider is the deterministic offline test, not a loaded language model. Only text is exchanged; tools, images and streaming are not supported.")
}

@Composable
private fun ProviderEditor(
    id: ProviderId, config: ProviderConfig, busy: Boolean,
    onSave: (Boolean, String, String, ModelPrice?, Boolean) -> Unit, onTest: () -> Unit,
) {
    var enabled by remember(config.enabled) { mutableStateOf(config.enabled) }
    // Never rememberSaveable: credentials must not enter activity saved-instance state.
    var credential by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    var model by remember(config.defaultModel) { mutableStateOf(config.defaultModel) }
    var inputPrice by remember(model, config.prices) { mutableStateOf(config.prices[model]?.let { usd(it.input) }.orEmpty()) }
    var outputPrice by remember(model, config.prices) { mutableStateOf(config.prices[model]?.let { usd(it.output) }.orEmpty()) }
    var error by remember { mutableStateOf("") }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(enabled, { enabled = it }, modifier = Modifier.semantics { contentDescription = "Enable ${id.label}" }); Text("Enable ${id.label}")
    }
    OutlinedTextField(credential, { credential = it }, label = { Text("API key (blank keeps saved key)") },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(clear, { clear = it }, modifier = Modifier.semantics { contentDescription = "Delete saved API key on save" }); Text("Delete saved API key on save")
    }
    if (config.models.isNotEmpty()) AiChoice("Discovered model", model.ifBlank { "Select" }, config.models.map { it.id to it.id }) { model = it }
    OutlinedTextField(model, { model = it }, label = { Text("Default model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(inputPrice, { inputPrice = it }, label = { Text("Input USD per million tokens") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(outputPrice, { outputPrice = it }, label = { Text("Output USD per million tokens") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Use the highest applicable uncached/context/tier prices. Leave both blank to block generation for this model. Prices are not fetched automatically.")
    Button(enabled = !busy, onClick = {
        try {
            val price = if (inputPrice.isBlank() && outputPrice.isBlank()) null else ModelPrice(microUsd(inputPrice), microUsd(outputPrice))
            onSave(enabled, credential.trim(), model.trim(), price, clear)
            credential = ""; clear = false; error = ""
        } catch (_: Exception) { error = "Enter nonnegative USD prices with at most six decimal places." }
    }) { Text("Save provider") }
    TextButton(enabled = !busy, onClick = onTest) { Text("Test saved connection and discover models") }
    if (error.isNotEmpty()) Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Text("Save changes before testing. Discovery verifies credentials and model listing; it does not incur a generation request or prove inference permissions.")
}
