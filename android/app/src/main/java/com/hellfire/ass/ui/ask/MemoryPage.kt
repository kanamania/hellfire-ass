package com.hellfire.ass.ui.ask

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.layout.*
import androidx.room.withTransaction
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(50) }
    val memories by remember(search, filter, limit) { app.database.memory().browse(search, filter, limit) }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(search, filter) { limit = 50 }
    var subject by rememberPrivateDraft("memory-subject")
    var text by rememberPrivateDraft("memory-text")
    var sourceId by rememberPrivateDraft("memory-sourceid")
    var advanced by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("OWNER_NOTE") }
    var proposal by rememberPrivateDraft("memory-proposal")
    var confirmProposal by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun run(block: suspend () -> String) { scope.launch {
        busy = true
        result = try { withContext(Dispatchers.IO) { block() } } catch (e: CancellationException) { throw e }
            catch (e: com.hellfire.ass.OwnerError) { context.getString(e.text) } catch (e: IllegalArgumentException) { e.message ?: "Not saved." } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Not saved. Check the source ID and remove security codes." }
        busy = false
    } }
    PushedPage(if (arg == "new") "New note" else "Memory", breadcrumb = "Settings") {
        result?.let { Banner(it, Tone.Info, null) }
        SectionCard(stringResource(R.string.ui_add_to_memory)) {
            ChipPicker(if (advanced) listOf("OWNER_NOTE", "SOURCE_EXCERPT", "INFERENCE") else listOf("OWNER_NOTE"), kind, { when (it) { "OWNER_NOTE" -> stringResource(R.string.ui_my_note); "SOURCE_EXCERPT" -> stringResource(R.string.ui_quote_from_a_record); else -> stringResource(R.string.ui_guess_to_check) } }) { kind = it }
            HfTextField(subject, { subject = it }, stringResource(R.string.ui_person_or_subject), enabled = !busy)
            HfTextField(text, { text = it }, stringResource(R.string.ui_what_to_remember), singleLine = false, minLines = 2, enabled = !busy)
            if (kind != "OWNER_NOTE") HfTextField(sourceId, { sourceId = it }, "Source record ID", enabled = !busy)
            PrimaryButton(stringResource(R.string.ui_save_with_provenance), { run {
                require(subject.length in 1..200 && text.length in 1..4000)
                val now = System.currentTimeMillis()
                val id = if (kind == "OWNER_NOTE") {
                    val snapshot = NotificationSnapshot(UUID.randomUUID().toString(), "owner.memory", now, now, source = "owner_memory", fields = mapOf("text" to listOf(subject, text)))
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
        if (memories.isNotEmpty()) SectionHeader(stringResource(R.string.ui_saved), trailing = "${memories.size}")
        HfTextField(search, { search = it }, stringResource(R.string.ui_search_memory))
        ChipPicker(listOf("", "OWNER_NOTE", "OWNER_CORRECTION", "SOURCE_EXCERPT", "INFERENCE"), filter, { if (it.isEmpty()) stringResource(R.string.ui_all) else it.lowercase().replace('_', ' ') }) { filter = it }
        memories.forEach { m -> key(m.id, m.confirmedAt) {
            var correction by rememberPrivateDraft("memory-edit-${m.id}", m.text)
            val history by remember(m.id) { app.database.memory().revisions(m.id) }.collectAsStateWithLifecycle(emptyList())
            var expandedHistory by rememberSaveable { mutableStateOf(false) }
            SectionCard(m.subject) {
                Text("${when (m.kind) { "OWNER_NOTE" -> "Your note"; "SOURCE_EXCERPT" -> "Quote"; else -> "Guess" }} · ${m.confidence}% · ${m.confirmedAt?.let(Fmt::dayTime) ?: "not confirmed"}",
                    style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                if (history.isNotEmpty()) {
                    QuietButton(stringResource(R.string.ui_earlier_versions) + " (${history.size})", { expandedHistory = !expandedHistory })
                    if (expandedHistory) history.forEach { Note("${Fmt.full(it.changedAt)} · ${it.text}") }
                }
                HfTextField(correction, { correction = it }, stringResource(R.string.ui_text), singleLine = false, enabled = !busy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.ui_save_correction), { run {
                        require(correction.isNotBlank() && correction.length <= 4000)
                        val safe = NotificationNormalizer.normalize(NotificationSnapshot("correction", "owner.memory", 0, 0, fields = mapOf("text" to listOf(correction))), app.packageName)!!
                        require(!safe.securitySensitive && !safe.contentRedacted) { "Remove authentication content" }
                        app.database.withTransaction {
                        app.database.memory().revise(m.id, m.subject, correction, System.currentTimeMillis())
                        if (m.subject.trim().length >= 2) app.skills.suggest("Remember: ${m.subject}", "GUIDANCE", m.subject, correction, "You corrected this memory. Review whether the correction should guide future related answers.", m.sourceId)
                        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), m.id, "OWNER_MEMORY_CORRECTION", "CONFIRMED", System.currentTimeMillis()))
                        }
                        PrivateDrafts.write(app, "memory-edit-${m.id}", "")
                        context.getString(R.string.ui_correction_saved_the_original_source)
                    } }, enabled = !busy && correction != m.text)
                    SecondaryButton(stringResource(R.string.ui_source), { nav.push(Screen.Source, m.sourceId) })
                    QuietButton(stringResource(R.string.ui_forget), { run { app.database.memory().forget(m.id); "Removed from memory. The original record stays in capture history." } })
                }
            }
        } }
        if (memories.size >= limit) SecondaryButton(stringResource(R.string.ui_load_older_memories), { limit += 50 })
        QuietButton("Advanced source/proposal tools", { advanced = !advanced; if (!advanced) kind = "OWNER_NOTE" })
        if (advanced) SectionCard("Review a typed proposal") {
            Note("Only create_task and prepare_reply JSON (schema_version 1) with an existing source ID and word-for-word evidence are accepted. Saving creates a review item; scheduling and sending still need your approval.")
            HfTextField(proposal, { proposal = it }, "Proposal JSON", singleLine = false, minLines = 3)
            SecondaryButton("Validate and keep for review…", { confirmProposal = true }, Modifier.fillMaxWidth(), enabled = !busy && proposal.isNotBlank())
        }
    }
    if (confirmProposal) ConfirmDialog("Keep this proposal for review?", proposal.take(2000), "Validate and keep",
        onConfirm = { confirmProposal = false; run { ActionProposals.retainForReview(app, proposal) } }, onDismiss = { confirmProposal = false })
}
