package com.hellfire.ass.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hellfire.ass.drawer.DrawerController
import com.hellfire.ass.drawer.DrawerPipeline
import com.hellfire.ass.drawer.DropPart
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
    val requestId = incoming?.getStringExtra("hellfire-share-id") ?: incoming?.let { com.hellfire.ass.memory.AiSkills.key(SharedContent.text(it)) }.orEmpty()
    var text by rememberPrivateDraft("share-$requestId-text", incoming?.let(SharedContent::text)?.take(60000).orEmpty())
    var subject by rememberPrivateDraft("share-$requestId-subject")
    var action by rememberPrivateDraft("share-$requestId-action", "Save note")
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var task by rememberSaveable(saver = TaskDraftStateSaver) { mutableStateOf<TaskDraft?>(null) }
    val parsed = remember(incoming) { runCatching { incoming?.let(SharedContent::files).orEmpty() } }
    val files = parsed.getOrDefault(emptyList())
    PushedPage(com.hellfire.ass.ui.tr("Shared with HellFire")) {
        message?.let { Banner(it) }
        if (incoming == null) { Note(com.hellfire.ass.ui.tr("Share text, a screenshot, document or audio file to HellFire from another app.")); return@PushedPage }
        parsed.exceptionOrNull()?.let { Note(it.message ?: "Unsupported shared content") }
        Note(com.hellfire.ass.ui.tr("Review this share before saving. Nothing is sent or posted to your ledger."))
        HfTextField(text, { text = it.take(60000) }, "Shared text", singleLine = false, minLines = 3, enabled = !busy)
        if (files.isNotEmpty()) Note("${files.size} shared file(s). Originals will be encrypted on this phone; local analysis may wait for charging.")
        if (SharedContent.text(incoming).length > 60000) Note(com.hellfire.ass.ui.tr("Only the first 60,000 characters are shown and saved."))
        if (!busy) ChipPicker(listOf("Save note", "Create reminder", "Review expense"), action) { action = it } else Note(action)
        if (action == "Save note" && text.length > 4000) Note("The memory note keeps the first 4,000 characters; the shared source retains up to 60,000.")
        if (action == "Save note") HfTextField(subject, { subject = it.take(200) }, "Person or subject", enabled = !busy)
        message?.let { Banner(it) }
        SecondaryButton(com.hellfire.ass.ui.tr("Save to drawer"), {
            busy = true
            app.captureScope.launch {
                try {
                    val parts = buildList {
                        if (text.isNotBlank()) add(DropPart.Text(text, name = subject.ifBlank { "Shared text" }))
                        files.forEach { add(DropPart.UriPart(it)) }
                    }
                    val outcome = withContext(Dispatchers.IO) { DrawerPipeline(app).ingest(parts, com.hellfire.ass.library.CaptureOrigin.SHARE, incoming.getPackage().orEmpty()) }
                    DrawerController.show(outcome.inboxId)
                    message = if (outcome.captureState == "COMPLETE") "Saved to the drawer." else "Incomplete capture. ${outcome.captureError.orEmpty()}"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "Could not save to the drawer." }
                finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !busy && parsed.isSuccess && (text.isNotBlank() || files.isNotEmpty()))
        PrimaryButton(if (busy) "Saving…" else "Save shared content", {
            busy = true
            app.captureScope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) { SharedSaver.save(app, requestId, action, text, subject, files) }
                    task = saved.taskId?.let { withContext(Dispatchers.IO) { app.database.tasks().task(it)?.let { row -> TaskDraft.of(row) } } }
                    listOf("text", "subject", "action").forEach { PrivateDrafts.write(app, "share-$requestId-$it", "") }
                    message = if (action == "Review expense") "Saved for review. Open Money to confirm recognized payments, or open the imported document after analysis. Nothing was posted."
                        else "Shared content saved locally."
                    SharedContent.intent = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = UiLanguage.text(app, "Could not save the complete share. Already saved items are retained; retry resumes this share and skips completed files. Re-share files if Android access expired.") + " " + UiLanguage.text(app, if (e is IllegalArgumentException) e.message.orEmpty() else "Check file access and storage.") }
                finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !busy && parsed.isSuccess && (text.isNotBlank() || files.isNotEmpty()))
    }
    task?.let { TaskEditSheet(it) { msg -> task = null; message = msg } }
}
