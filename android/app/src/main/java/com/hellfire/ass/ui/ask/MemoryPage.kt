package com.hellfire.ass.ui.ask

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.ai.ActionProposals
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.money.ChipPicker
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Settings › Memory and proposals (not drawn): personal memory with provenance and typed JSON proposals, moved from the old Assistant tab. */
@Composable
fun MemoryPage(arg: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val memories by remember { app.database.memory().items() }.collectAsStateWithLifecycle(emptyList())
    var subject by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var sourceId by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("OWNER_NOTE") }
    var proposal by remember { mutableStateOf("") }
    var confirmProposal by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run(block: suspend () -> String) { scope.launch {
        busy = true
        result = try { withContext(Dispatchers.IO) { block() } } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { e.message ?: "Not saved." } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Not saved. Check the source ID and remove security codes." }
        busy = false
    } }
    PushedPage(if (arg == "new") "New note" else "Memory", breadcrumb = "Settings") {
        result?.let { Banner(it, Tone.Info, null) }
        SectionCard("Add to memory") {
            ChipPicker(listOf("OWNER_NOTE", "SOURCE_EXCERPT", "INFERENCE"), kind, { when (it) { "OWNER_NOTE" -> "My note"; "SOURCE_EXCERPT" -> "Quote from a record"; else -> "Guess to check" } }) { kind = it }
            HfTextField(subject, { subject = it }, "Person or subject")
            HfTextField(text, { text = it }, "What to remember", singleLine = false, minLines = 2)
            if (kind != "OWNER_NOTE") HfTextField(sourceId, { sourceId = it }, "Source record ID")
            PrimaryButton("Save with provenance", { run {
                require(subject.length in 1..200 && text.length in 1..4000)
                val now = System.currentTimeMillis()
                val id = if (kind == "OWNER_NOTE") {
                    val snapshot = NotificationSnapshot(UUID.randomUUID().toString(), "owner.memory", now, now, source = "owner_memory", fields = mapOf("text" to listOf(text)))
                    val safe = NotificationNormalizer.normalize(snapshot, app.packageName)!!
                    require(!safe.securitySensitive && !safe.contentRedacted) { "Remove authentication content" }
                    app.events.capture(snapshot, app.packageName); safe.id
                } else {
                    val event = requireNotNull(app.database.events().find(sourceId)) { "No record with that ID" }
                    require(!event.securitySensitive && event.contentAvailable)
                    val fields = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8)).getJSONObject("fields")
                    if (kind == "SOURCE_EXCERPT") require(fields.keys().asSequence().any { key -> fields.optJSONArray(key)?.let { a -> (0 until a.length()).any { a.optString(it).contains(text) } } == true }) { "The quote must appear word for word in the record" }
                    sourceId
                }
                app.database.memory().insert(MemoryItem(UUID.randomUUID().toString(), id, subject, text, kind, if (kind == "INFERENCE") 0 else 100, now, if (kind == "INFERENCE") null else now))
                text = ""; "Saved with its source. Confidence describes where it came from, not whether it is true."
            } }, Modifier.fillMaxWidth(), enabled = !busy && subject.isNotBlank() && text.isNotBlank())
        }
        if (memories.isNotEmpty()) SectionHeader("Saved", trailing = "${memories.size}")
        memories.take(50).forEach { m -> key(m.id, m.confirmedAt) {
            var correction by remember { mutableStateOf(m.text) }
            SectionCard(m.subject) {
                Text("${when (m.kind) { "OWNER_NOTE" -> "Your note"; "SOURCE_EXCERPT" -> "Quote"; else -> "Guess" }} · ${m.confidence}% · ${m.confirmedAt?.let(Fmt::dayTime) ?: "not confirmed"}",
                    style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                HfTextField(correction, { correction = it }, "Text", singleLine = false)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Save correction", { run {
                        require(correction.isNotBlank() && correction.length <= 4000)
                        app.database.memory().correct(m.id, m.subject, correction, System.currentTimeMillis())
                        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), m.id, "OWNER_MEMORY_CORRECTION", "CONFIRMED", System.currentTimeMillis()))
                        "Correction saved; the original source stays available."
                    } }, enabled = correction != m.text)
                    SecondaryButton("Source", { nav.push(Screen.Source, m.sourceId) })
                    QuietButton("Forget", { run { app.database.memory().forget(m.id); "Removed from memory. The original record stays in capture history." } })
                }
            }
        } }
        SectionCard("Review a typed proposal") {
            Note("Only create_task and prepare_reply JSON (schema_version 1) with an existing source ID and word-for-word evidence are accepted. Saving creates a review item; scheduling and sending still need your approval.")
            HfTextField(proposal, { proposal = it }, "Proposal JSON", singleLine = false, minLines = 3)
            SecondaryButton("Validate and keep for review…", { confirmProposal = true }, Modifier.fillMaxWidth(), enabled = !busy && proposal.isNotBlank())
        }
    }
    if (confirmProposal) ConfirmDialog("Keep this proposal for review?", proposal.take(2000), "Validate and keep",
        onConfirm = { confirmProposal = false; run { ActionProposals.retainForReview(app, proposal) } }, onDismiss = { confirmProposal = false })
}
