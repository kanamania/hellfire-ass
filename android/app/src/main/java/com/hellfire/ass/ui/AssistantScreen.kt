package com.hellfire.ass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.AssistantEngine
import com.hellfire.ass.ai.ActionProposals
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.communications.ActionAudit
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

@Composable
fun AssistantScreen(app: HellFireApp, onSession: () -> Unit) {
    val engine = remember { AssistantEngine(app) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("morning brief") }
    var answer by remember { mutableStateOf(AssistantEngine.Answer("Ask a question to use saved evidence.")) }
    var busy by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    var proposal by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) { scope.launch { busy = true
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { result = "Request failed. Check dates, saved evidence and local storage. No external action was executed." }
        finally { busy = false }
    } }
    Text("Ask HellFire", style = MaterialTheme.typography.titleLarge)
    Text("Offline questions: spending today, income this month, balances, tasks tomorrow, morning brief, evening brief, or search <words>. Exact totals come from approved ledger entries. Search is a bounded literal lookup, plus ≈ meaning matches when an embedding model is imported in Local AI.")
    Text("Commands such as remind me to…, remember…, mark … done, snooze…, reschedule … to…, log cash…, email … : …, whatsapp … : … are only proposals until you confirm. Drafts still need approval in Messages before sending. Type help for the full list.")
    OutlinedTextField(query, { query = it }, label = { Text("Your question") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy, onClick = { run { answer = engine.answer(query); result = "" } }) { Text("Ask HellFire") }
    Text(answer.text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    answer.command?.let { command -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = { answer = answer.copy(command = null); run {
            answer = AssistantEngine.Answer(try { AssistantCommands.execute(app, command) }
                catch (e: CancellationException) { throw e }
                catch (e: IllegalArgumentException) { e.message ?: "Command could not be applied." }
                catch (_: Exception) { "Command could not be fully applied. Check Tasks, Messages or Finance; nothing was sent." })
        } }) { Text("Confirm") }
        OutlinedButton(onClick = { answer = AssistantEngine.Answer("Proposal discarded. Nothing changed.") }) { Text("Discard") }
    } }
    answer.sources.take(50).forEach { id -> TextButton(onClick = { run { source = engine.source(id) } }) { Text("Show source ${id.take(12)}…") } }
    if (answer.sources.size > 50) Text("Showing first 50 source links for this answer.")
    OutlinedButton(onClick = onSession) { Text("Open system voice assistant") }
    Text("Offline speech input/output depends on installed system engines. Phone gestures and hotword support need final device verification.")
    HorizontalDivider()
    Text("Personal memory", style = MaterialTheme.typography.titleMedium)
    val memories by app.database.memory().items().collectAsStateWithLifecycle(initialValue = emptyList())
    var subject by remember { mutableStateOf("") }; var text by remember { mutableStateOf("") }
    var sourceId by remember { mutableStateOf("") }; var kind by remember { mutableStateOf("OWNER_NOTE") }
    Choice("Memory type", listOf("OWNER_NOTE", "SOURCE_EXCERPT", "INFERENCE"), kind) { kind = it }
    OutlinedTextField(subject, { subject = it }, label = { Text("Person or subject") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(text, { text = it }, label = { Text("Memory text") }, modifier = Modifier.fillMaxWidth())
    if (kind != "OWNER_NOTE") OutlinedTextField(sourceId, { sourceId = it }, label = { Text("Existing source ID") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy && subject.isNotBlank() && text.isNotBlank(), onClick = { run {
        require(subject.length <= 200 && text.length <= 4000)
        val now = System.currentTimeMillis()
        val id = if (kind == "OWNER_NOTE") {
            val snapshot = NotificationSnapshot(UUID.randomUUID().toString(), "owner.memory", now, now, source = "owner_memory",
                fields = mapOf("text" to listOf(text)))
            val safe = NotificationNormalizer.normalize(snapshot, app.packageName)!!
            require(!safe.securitySensitive && !safe.contentRedacted) { "Remove authentication content" }
            app.events.capture(snapshot, app.packageName); safe.id
        } else {
            val event = requireNotNull(app.database.events().find(sourceId))
            require(!event.securitySensitive && event.contentAvailable)
            val fields = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8)).getJSONObject("fields")
            if (kind == "SOURCE_EXCERPT") require(fields.keys().asSequence().any { key -> fields.optJSONArray(key)?.let { a -> (0 until a.length()).any { a.optString(it).contains(text) } } == true })
            sourceId
        }
        app.database.memory().insert(MemoryItem(UUID.randomUUID().toString(), id, subject, text, kind,
            if (kind == "INFERENCE") 0 else 100, now, if (kind == "INFERENCE") null else now))
        result = "Memory saved with source. Confidence describes provenance, not guaranteed truth."; text = ""
    } }) { Text("Save memory with provenance") }
    Text("Notes are owner assertions. Excerpts preserve source wording; inference confidence starts at 0 until review. Contact identities are not merged automatically.")
    memories.take(50).forEach { memory -> key(memory.id, memory.confirmedAt) {
        var correction by remember { mutableStateOf(memory.text) }
        Text("${memory.subject} · ${memory.kind} · ${memory.confidence}% · ${memory.confirmedAt?.let(com.hellfire.ass.tasks.TaskTime::display) ?: "unconfirmed"}")
        OutlinedTextField(correction, { correction = it }, label = { Text("Correct memory text") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { run {
            require(correction.isNotBlank() && correction.length <= 4000)
            app.database.memory().correct(memory.id, memory.subject, correction, System.currentTimeMillis())
            app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), memory.id, "OWNER_MEMORY_CORRECTION", "CONFIRMED", System.currentTimeMillis()))
            result = "Owner correction saved; original source remains available."
        } }) { Text("Confirm correction") }
        TextButton(onClick = { run { source = engine.source(memory.sourceId) } }) { Text("Show source") }
        TextButton(onClick = { run { app.database.memory().forget(memory.id); result = "Memory index item removed. Original source remains in capture history and can still appear in source search." } }) { Text("Forget memory index item") }
    } }
    HorizontalDivider()
    Text("Review a typed action proposal", style = MaterialTheme.typography.titleMedium)
    Text("Only create_task and prepare_reply JSON are accepted. Existing source ID and verbatim evidence are required. Saving creates a review item; scheduling and sending require separate owner approval in Tasks or Messages.")
    OutlinedTextField(proposal, { proposal = it }, label = { Text("Proposal JSON · schema_version 1") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy && proposal.isNotBlank(), onClick = { pending = true }) { Text("Validate and retain for review…") }
    Text(result)
    if (pending) AlertDialog(onDismissRequest = { pending = false }, title = { Text("Retain proposal for review?") }, text = { Text(proposal.take(4000)) },
        confirmButton = { TextButton(onClick = { pending = false; run { result = ActionProposals.retainForReview(app, proposal) } }) { Text("Validate and retain") } },
        dismissButton = { TextButton(onClick = { pending = false }) { Text("Cancel") } })
    source?.let { evidence -> AlertDialog(onDismissRequest = { source = null }, title = { Text("Saved source evidence") },
        text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(evidence.take(16000)) } },
        confirmButton = { TextButton(onClick = { source = null }) { Text("Close") } }) }
}
