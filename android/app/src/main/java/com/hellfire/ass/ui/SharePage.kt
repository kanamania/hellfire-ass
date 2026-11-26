package com.hellfire.ass.ui

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

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
    val context = androidx.compose.ui.platform.LocalContext.current
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
    PushedPage(stringResource(R.string.ui_shared_with_hellfire)) {
        message?.let { Banner(it) }
        if (incoming == null) { Note(stringResource(R.string.ui_share_text_a_screenshot_document_or)); return@PushedPage }
        parsed.exceptionOrNull()?.let { Note(it.message ?: "Unsupported shared content") }
        Note(stringResource(R.string.ui_review_this_share_before_saving_nothing))
        HfTextField(text, { text = it.take(60000) }, stringResource(R.string.ui_shared_text), singleLine = false, minLines = 3, enabled = !busy)
        if (files.isNotEmpty()) Note("${files.size} shared file(s). Originals will be encrypted on this phone; local analysis may wait for charging.")
        if (SharedContent.text(incoming).length > 60000) Note(stringResource(R.string.ui_only_the_first_60_000_characters_are))
        if (!busy) ChipPicker(listOf("Save note", "Create reminder", "Review expense"), action, { when (it) { "Save note" -> stringResource(R.string.ui_save_note); "Create reminder" -> stringResource(R.string.ui_create_reminder); "Review expense" -> stringResource(R.string.ui_review_expense); else -> it } }) { action = it } else Note(action)
        if (action == "Save note" && text.length > 4000) Note("The memory note keeps the first 4,000 characters; the shared source retains up to 60,000.")
        if (action == "Save note") HfTextField(subject, { subject = it.take(200) }, stringResource(R.string.ui_person_or_subject), enabled = !busy)
        message?.let { Banner(it) }
        SecondaryButton(stringResource(R.string.drawer_save), {
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
                        else context.getString(R.string.ui_shared_content_saved_locally)
                    SharedContent.intent = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = context.getString(R.string.ui_could_not_save_the_complete_share) + " " + when (e) {
                    is com.hellfire.ass.OwnerError -> context.getString(e.text)
                    is IllegalArgumentException -> e.message.orEmpty()
                    else -> context.getString(R.string.ui_check_file_access_and_storage)
                } }
                finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !busy && parsed.isSuccess && (text.isNotBlank() || files.isNotEmpty()))
        NewsCheck(text, busy)
    }
    task?.let { TaskEditSheet(it) { msg -> task = null; message = msg } }
}

@Composable
private fun NewsCheck(text: String, busy: Boolean) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<com.hellfire.ass.news.DropResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    SecondaryButton(if (checking) "Checking…" else "Check against news", {
        checking = true; error = null
        scope.launch {
            val r = com.hellfire.ass.ui.news.newsCall { app.news.dropIn(text.take(4000), com.hellfire.ass.ui.news.NewsUi.firstUrl(text)) }
            checking = false
            result = r.getOrNull()
            error = r.exceptionOrNull()?.let { com.hellfire.ass.ui.news.failText(it, "Could not check against news") }
        }
    }, Modifier.fillMaxWidth(), enabled = !busy && !checking && text.isNotBlank())
    error?.let { Banner(it, Tone.Danger, null) }
    result?.let { r ->
        Banner(r.message, Tone.Info, com.hellfire.ass.ui.theme.HfIcons.Info)
        r.eventId?.let { id -> QuietButton("Open story", { nav.push(Screen.NewsStory, id) }) }
    }
}
