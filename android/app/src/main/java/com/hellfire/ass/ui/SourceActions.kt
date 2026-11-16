package com.hellfire.ass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.tasks.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.*
import java.util.UUID

@Composable
fun SourceActions(sourceId: String, evidence: String) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf(evidence.take(4000)) }
    var result by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(com.hellfire.ass.ui.tr("Remember this"), { note = true })
        SecondaryButton(com.hellfire.ass.ui.tr("Create task"), { task = true })
    }
    result?.let { Note(it) }
    if (note) HfBottomSheet({ note = false }) {
        Text(com.hellfire.ass.ui.tr("Remember this"), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        HfTextField(subject, { subject = it }, "Person or subject")
        HfTextField(quote, { quote = it }, "Source excerpt", singleLine = false, minLines = 3)
        Note(com.hellfire.ass.ui.tr("Choose an exact passage from the source. It stays linked to the original."))
        PrimaryButton(com.hellfire.ass.ui.tr("Save memory"), {
            scope.launch {
                result = try { withContext(Dispatchers.IO) {
                    val source = requireNotNull(app.database.events().find(sourceId))
                    require(source.contentAvailable && !source.securitySensitive)
                    require(quote.isNotBlank() && evidence.contains(quote) && subject.isNotBlank())
                    val safeSubject = com.hellfire.ass.events.NotificationNormalizer.normalize(com.hellfire.ass.events.NotificationSnapshot("subject", "owner.memory", 0, 0, fields = mapOf("text" to listOf(subject))), app.packageName)!!
                    require(!safeSubject.securitySensitive && !safeSubject.contentRedacted) { "Remove authentication codes from the subject." }
                    val now = System.currentTimeMillis()
                    app.database.memory().insert(MemoryItem(UUID.randomUUID().toString(), sourceId, subject.trim().take(200), quote, "SOURCE_EXCERPT", 100, now, now))
                }; note = false; "Memory saved with its source." }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { "Not saved. Select an exact excerpt and enter a subject." }
            }
        }, Modifier.fillMaxWidth(), enabled = subject.isNotBlank() && quote.isNotBlank())
    }
    if (task) TaskEditSheet(TaskDraft(null, evidence.take(300), System.currentTimeMillis() + 3_600_000, "OWNER", sourceId = sourceId)) { result = it; task = false }
}
