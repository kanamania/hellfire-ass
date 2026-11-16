package com.hellfire.ass.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.media.MediaWork
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.money.ChipPicker
import com.hellfire.ass.ui.tasks.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.*
import java.util.UUID

object SharedContent {
    var intent by mutableStateOf<Intent?>(null)
    fun text(intent: Intent): String = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() }.getOrDefault("")
    @Suppress("DEPRECATION")
    fun files(intent: Intent): List<Uri> {
        val files = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        require(files.size <= 20) { "Share at most 20 files at a time." }
        require(files.all { it.scheme == "content" }) { "Only content shared through Android's file provider is accepted." }
        return files.distinct()
    }
}

@Composable
fun SharePage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val incoming = SharedContent.intent
    var text by rememberSaveable(incoming) { mutableStateOf(incoming?.let(SharedContent::text)?.take(60000).orEmpty()) }
    var subject by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("Save note") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var task by remember { mutableStateOf<TaskDraft?>(null) }
    val parsed = remember(incoming) { runCatching { incoming?.let(SharedContent::files).orEmpty() } }
    val files = parsed.getOrDefault(emptyList())
    PushedPage(com.hellfire.ass.ui.tr("Shared with HellFire")) {
        message?.let { Banner(it) }
        if (incoming == null) { Note(com.hellfire.ass.ui.tr("Share text, a screenshot, document or audio file to HellFire from another app.")); return@PushedPage }
        parsed.exceptionOrNull()?.let { Note(it.message ?: "Unsupported shared content") }
        Note(com.hellfire.ass.ui.tr("Review this share before saving. Nothing is sent or posted to your ledger."))
        HfTextField(text, { text = it.take(60000) }, "Shared text", singleLine = false, minLines = 3)
        if (files.isNotEmpty()) Note("${files.size} shared file(s). Originals will be encrypted on this phone; local analysis may wait for charging.")
        if (SharedContent.text(incoming).length > 60000) Note(com.hellfire.ass.ui.tr("Only the first 60,000 characters are shown and saved."))
        ChipPicker(listOf("Save note", "Create reminder", "Review expense"), action) { action = it }
        if (action == "Save note" && text.length > 4000) Note("The memory note keeps the first 4,000 characters; the shared source retains up to 60,000.")
        if (action == "Save note") HfTextField(subject, { subject = it.take(200) }, "Person or subject")
        message?.let { Banner(it) }
        PrimaryButton(if (busy) "Saving…" else "Save shared content", {
            busy = true
            scope.launch {
                try {
                    val captured = withContext(Dispatchers.IO) {
                        val id = UUID.randomUUID().toString()
                        val now = System.currentTimeMillis()
                        val raw = NotificationSnapshot(id, "owner.share", now, now, source = "owner_share", fields = mapOf("text" to listOf(text), "title" to listOf(subject)))
                        val safe = requireNotNull(NotificationNormalizer.normalize(raw, app.packageName))
                        require(!safe.securitySensitive) { "Security codes cannot be saved as general memory." }
                        val sanitized = safe.snapshot.fields["text"].orEmpty().joinToString("\n")
                        app.events.capture(raw, app.packageName)
                        if (text.isNotBlank()) when (action) {
                            "Save note" -> app.database.memory().insert(MemoryItem(UUID.randomUUID().toString(), safe.id, safe.snapshot.fields["title"]?.firstOrNull()?.trim()?.ifEmpty { "Shared note" } ?: "Shared note",
                                sanitized.take(4000), "OWNER_NOTE", 100, now, now))
                            "Review expense" -> app.finances.collect(safe.id, now, "owner.share", sanitized)
                        }
                        for (uri in files) {
                            val mime = app.contentResolver.getType(uri).orEmpty()
                            require(mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") || mime == "application/pdf") { "Unsupported file type. Share an image, PDF, audio or video." }
                            val kind = when { mime.startsWith("image/") -> "IMAGE"; mime.startsWith("audio/") -> "VOICE_NOTE"; mime.startsWith("video/") -> "VIDEO"; else -> "DOCUMENT" }
                            val item = app.media.import(uri, kind)
                            MediaWork.queue(app, item.id)
                        }
                        safe.id to sanitized
                    }
                    if (action == "Create reminder" && text.isNotBlank()) task = TaskDraft(null, captured.second.take(300), System.currentTimeMillis() + 3_600_000, "OWNER", sourceId = captured.first)
                    message = if (action == "Review expense") "Saved for review. Open Money to confirm recognized payments, or open the imported document after analysis. Nothing was posted."
                        else "Shared content saved locally."
                    SharedContent.intent = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = "Could not save the complete share. Some items may already be saved; check Inbox before retrying. ${if (e is IllegalArgumentException) e.message.orEmpty() else "Check file access and storage."}" }
                finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !busy && parsed.isSuccess && (text.isNotBlank() || files.isNotEmpty()))
    }
    task?.let { TaskEditSheet(it) { msg -> task = null; message = msg } }
}
