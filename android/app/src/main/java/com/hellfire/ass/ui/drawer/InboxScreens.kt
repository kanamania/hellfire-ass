package com.hellfire.ass.ui.drawer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.data.SearchText
import com.hellfire.ass.drawer.DrawerAi
import com.hellfire.ass.drawer.DrawerContent
import com.hellfire.ass.drawer.DrawerController
import com.hellfire.ass.drawer.DrawerInbox
import com.hellfire.ass.drawer.DrawerTalk
import com.hellfire.ass.library.InboxItem
import com.hellfire.ass.library.InboxLifecycle
import com.hellfire.ass.library.LibraryRoutes
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UniversalInboxPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var filter by rememberSaveable { mutableStateOf("Recent") }
    var sort by rememberSaveable { mutableStateOf("Newest") }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var collection by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<InboxItem>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val categories by app.database.library().categories().collectAsStateWithLifecycle(emptyList())
    val collections by app.database.library().collections(listOf("COLLECTION", "ALBUM")).collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(filter, query, category, collection, refresh) {
        items = withContext(Dispatchers.IO) {
            val lifecycle = when (filter) {
                "Archived" -> InboxLifecycle.ARCHIVED.name
                "Trash" -> InboxLifecycle.TRASHED.name
                else -> InboxLifecycle.ACTIVE.name
            }
            val page = if (query.isBlank()) app.database.inbox().page(lifecycle, filter == "Favorites", Long.MAX_VALUE, "", 80)
            else {
                val match = SearchText.query(query)
                val hits = if (match == null) emptyList() else app.database.library().search(match, 40).filter { it.kind == "INBOX" }.map { it.itemId }
                hits.mapNotNull { app.database.inbox().item(it) }.filter { it.lifecycle == lifecycle }
            }
            val categorized = if (category.isBlank()) page else page.filter { row ->
                app.database.inbox().contentsNow(row.id).any { media ->
                    app.database.library().inCategory(category, 200, 0).any { it.id == media.id }
                }
            }
            if (collection.isBlank()) categorized else categorized.filter { row ->
                val ids = app.database.library().members(collection).first().map { it.id }.toSet()
                app.database.inbox().contentsNow(row.id).any { it.id in ids }
            }
        }
    }
    val shown = when (sort) {
        "Oldest" -> items.sortedBy { it.capturedAt }
        "Title" -> items.sortedBy { it.title.lowercase() }
        else -> items.sortedByDescending { it.capturedAt }
    }
    PushedPage(tr("Universal inbox"), breadcrumb = tr("Inbox")) {
        HfTextField(query, { query = it }, tr("Search drawer"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Recent", "Favorites", "Archived", "Trash").forEach { name ->
                SecondaryButton(tr(name), { filter = name })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Newest", "Oldest", "Title").forEach { name -> SecondaryButton(name, { sort = name }) }
        }
        if (categories.isNotEmpty()) {
            Text(tr("Categories"), style = Hf.type.label, color = Hf.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Recent"), { category = "" })
                categories.take(12).forEach { cat -> SecondaryButton(cat.name, { category = cat.id }) }
            }
        }
        if (collections.isNotEmpty()) {
            Text(tr("Collections"), style = Hf.type.label, color = Hf.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Recent"), { collection = "" })
                collections.take(12).forEach { col -> SecondaryButton(col.name, { collection = col.id }) }
            }
        }
        message?.let { Banner(it) }
        if (filter == "Trash" && shown.isNotEmpty()) {
            if (!confirmEmpty) SecondaryButton(tr("Empty trash"), { confirmEmpty = true })
            else {
                Note("Permanently delete every item in the trash? Their conversations, action history and saved places are removed. Originals still used by another inbox item, a collection or the library are kept. This cannot be undone.")
                PrimaryButton(tr("Empty trash"), {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { DrawerInbox.emptyTrash(app) } }
                        message = result.exceptionOrNull()?.let { "Some items could not be deleted: ${it.message.orEmpty()}" } ?: "Deleted ${result.getOrNull()} items."
                        confirmEmpty = false; refresh++
                    }
                })
                QuietButton(tr("Cancel"), { confirmEmpty = false })
            }
        }
        if (shown.isEmpty()) Note(tr("Saved drops, files and shares"))
        shown.forEach { row ->
            ListRow((if (row.favorite) "★ " else "") + row.title.ifBlank { row.contentKind }, subtitle = listOf(row.captureState, row.processingState, row.syncState, row.backupState).joinToString(" · "), chevron = true) {
                nav.push(LibraryRoutes.inboxItem(row.id))
            }
        }
        QuietButton(tr("Drawer"), { DrawerController.expanded = true })
    }
}

@Composable
fun InboxItemPage(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val item by app.database.inbox().observe(id).collectAsStateWithLifecycle(null)
    val contents by app.database.inbox().contents(id).collectAsStateWithLifecycle(emptyList())
    val actions by app.database.inbox().actions(id).collectAsStateWithLifecycle(emptyList())
    val messages by app.database.inbox().conversation(id).collectAsStateWithLifecycle(emptyList())
    var notes by remember(item?.notes) { mutableStateOf(item?.notes.orEmpty()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var question by rememberSaveable { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf<Int?>(null) }
    val categories by app.database.library().categories().collectAsStateWithLifecycle(emptyList())
    val collections by app.database.library().collections(listOf("COLLECTION", "ALBUM")).collectAsStateWithLifecycle(emptyList())
    PushedPage(item?.title?.ifBlank { tr("Universal inbox") } ?: tr("Universal inbox"), breadcrumb = tr("Inbox")) {
        message?.let { Banner(it) }
        val row = item
        if (row == null) {
            Note("This saved item is not on this phone.")
            return@PushedPage
        }
        if (row.captureState != "COMPLETE") Banner(tr("Incomplete capture") + ": " + (row.captureError ?: "The original was not fully saved."), Tone.Warning)
        Note(row.summary.ifBlank { "No summary yet." })
        SecondaryButton(if (row.favorite) "★ " + tr("Remove from favorites") else "☆ " + tr("Add to favorites"), {
            scope.launch { withContext(Dispatchers.IO) { DrawerInbox.setFavorite(app, id, !row.favorite) } }
        })
        Text(tr("Processing") + ": " + row.processingState, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        Text(tr("Sync") + ": " + row.syncState, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        Text(tr("Backup") + ": " + row.backupState, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        contents.forEach { media ->
            ListRow(media.name, subtitle = "${media.contentKind} · ${media.mime}", chevron = media.contentKind == "VIDEO" || media.contentKind == "AUDIO") {
                if (media.contentKind == "VIDEO" || media.contentKind == "AUDIO") nav.push(LibraryRoutes.player(media.id))
            }
        }
        HfTextField(notes, { notes = it.take(4000) }, "Notes", singleLine = false, minLines = 2)
        SecondaryButton(tr("Saved"), {
            scope.launch {
                withContext(Dispatchers.IO) { app.database.inbox().saveEdits(row.copy(notes = notes, updatedAt = System.currentTimeMillis())) }
                message = "Notes saved."
            }
        })
        val target = contents.firstOrNull()
        if (target != null && categories.isNotEmpty()) {
            Text(tr("Categories"), style = Hf.type.label, color = Hf.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.take(30).forEach { cat -> SecondaryButton(cat.name, {
                    scope.launch {
                        withContext(Dispatchers.IO) { contents.forEach { media -> app.database.library().setCategory(com.hellfire.ass.library.ItemCategory(media.id, cat.id, "USER", updatedAt = System.currentTimeMillis())) } }
                        message = "Assigned to ${cat.name}."
                    }
                }) }
            }
        }
        if (target != null && collections.isNotEmpty()) {
            Text(tr("Collections"), style = Hf.type.label, color = Hf.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                collections.take(30).forEach { col -> SecondaryButton(col.name, {
                    scope.launch {
                        withContext(Dispatchers.IO) { contents.forEach { media -> app.database.library().addToCollection(col.id, media.id, System.currentTimeMillis()) } }
                        message = "Added to ${col.name}."
                    }
                }) }
            }
        }
        if (row.contentKind == "IMAGE") Note("QR codes are not decoded: no on-device barcode decoder is included.")
        Text(tr("Follow-up"), style = Hf.type.label, color = Hf.colors.muted)
        messages.forEach { Text("${it.role}: ${it.text.take(500)}", style = Hf.type.body, color = Hf.colors.onSurface) }
        HfTextField(question, { question = it.take(2000) }, tr("Follow-up"), singleLine = false, minLines = 2, enabled = !asking)
        PrimaryButton(tr("Follow-up"), {
            val asked = question.trim()
            asking = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    DrawerTalk(app.database.inbox()).ask(id, DrawerTalk.session(id), asked, DrawerContent.text(app, id)) { prompt -> DrawerAi.answer(app, prompt) }
                }
                question = ""; asking = false
            }
        }, Modifier.fillMaxWidth(), enabled = !asking && question.isNotBlank())
        if (!DrawerAi.available(app)) Note(DrawerAi.NOT_INSTALLED)
        QuietButton(tr("Clear context"), {
            scope.launch {
                withContext(Dispatchers.IO) { DrawerTalk(app.database.inbox()).clear(DrawerTalk.session(id)) }
                message = "Context cleared. The saved item is still here."
            }
        })
        Text("Actions", style = Hf.type.label, color = Hf.colors.muted)
        actions.forEach { Text("${it.label}: ${it.state}" + (it.result?.let { result -> " · $result" } ?: ""), style = Hf.type.rowSubtitle, color = Hf.colors.muted) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.lifecycle == "TRASHED") SecondaryButton(tr("Restore"), { scope.launch { withContext(Dispatchers.IO) { DrawerInbox.restore(app, id) } } })
            else {
                SecondaryButton(tr("Archive"), { scope.launch { withContext(Dispatchers.IO) { DrawerInbox.archive(app, id) } } })
                SecondaryButton(tr("Move to trash"), { scope.launch { withContext(Dispatchers.IO) { DrawerInbox.trash(app, id) } } })
            }
        }
        if (!confirmDelete) SecondaryButton(tr("Delete permanently"), {
            confirmDelete = true
            scope.launch { kept = withContext(Dispatchers.IO) { contents.size - DrawerInbox.deletableOriginals(app, id).size } }
        })
        else {
            Note("Permanently delete this item, its conversation, action history and places saved from it? This cannot be undone." +
                (kept?.takeIf { it > 0 }?.let { " $it original(s) are also used by another inbox item, a collection or the library and will be kept there." } ?: ""))
            PrimaryButton(tr("Delete permanently"), {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { DrawerInbox.deletePermanently(app, id) } }
                    message = result.exceptionOrNull()?.message ?: "Deleted."
                    if (result.isSuccess) nav.pop()
                }
            })
            QuietButton(tr("Close"), { confirmDelete = false })
        }
        SecondaryButton(tr("Drawer"), { DrawerController.show(id) })
    }
}
