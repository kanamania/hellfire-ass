package com.hellfire.ass.ui.library

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.hellfire.ass.library.*
import com.hellfire.ass.media.FolderImport
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val thumbs = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount.coerceAtLeast(1)
}

@Composable
fun LibraryPage(route: Route) {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val args = remember(route.arg) { LibraryRoutes.args(route.arg) }
    var mode by rememberSaveable { mutableStateOf("browse") }
    var kind by rememberSaveable { mutableStateOf(args["kind"].orEmpty()) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.NEWEST.name) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var rows by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<String, ItemStatus>>(emptyMap()) }
    var access by remember { mutableStateOf(LibraryAccess.read(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var offset by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var nameFilter by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf(FolderImport.libraryTrees(app)) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val categoryId = args["category"]?.ifBlank { null }
    val collectionId = args["collection"]?.ifBlank { null }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = LibraryAccess.read(context)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { FolderImport.addLibraryTree(app, uri) } }
            note = if (saved.isSuccess) "Folder added. Files stay where they are and are not synced." else "Could not keep permission for that folder."
            if (saved.isSuccess) { folders = FolderImport.libraryTrees(app); scan(app) { note = it } }
        }
    }
    LaunchedEffect(Unit) {
        when {
            nav.consume("collections") -> mode = "collections"
            nav.consume("categories") -> mode = "categories"
            nav.consume("duplicates") -> mode = "duplicates"
        }
        withContext(Dispatchers.IO) { runCatching { LibraryOrganize.seedDefaults(app.database.library(), System.currentTimeMillis()) } }
    }
    fun reload() { reloadToken++ }
    LaunchedEffect(kind, sort, favoritesOnly, categoryId, collectionId, offset, mode, nameFilter, tagFilter, reloadToken) {
        if (mode == "browse") {
            val page = withContext(Dispatchers.IO) {
                app.database.library().queryItems(LibraryQuery.sql(LibraryFilter(
                    kinds = if (kind.isBlank()) listOf(ContentKind.IMAGE.name, ContentKind.VIDEO.name, ContentKind.AUDIO.name) else listOf(kind),
                    favoritesOnly = favoritesOnly, categoryId = categoryId, collectionId = collectionId, nameContains = nameFilter, tag = tagFilter.ifBlank { null },
                    sort = runCatching { LibrarySort.valueOf(sort) }.getOrDefault(LibrarySort.NEWEST),
                    limit = 60, offset = offset,
                )))
            }
            rows = if (offset == 0) page else (rows.take(offset) + page).distinctBy { it.id }
            statuses = withContext(Dispatchers.IO) { runCatching { LibraryStatus.load(app, rows) }.getOrDefault(emptyMap()) }
        }
    }
    val title = when {
        kind == ContentKind.IMAGE.name -> tr("Pictures")
        kind == ContentKind.VIDEO.name -> tr("Videos")
        kind == ContentKind.AUDIO.name -> tr("Audio")
        mode == "collections" -> tr("Collections")
        mode == "categories" -> tr("Categories")
        mode == "duplicates" -> tr("Duplicates")
        else -> tr("Library")
    }
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PushedTopBar(title, { nav.pop() }, breadcrumb = "Library")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HfChip(tr("Search"), { nav.push(LibraryRoutes.search()) })
            HfChip("Processing queue", { nav.push(LibraryRoutes.processingQueue()) })
            HfChip("Media settings", { nav.push(LibraryRoutes.mediaSettings()) })
            HfChip(tr("All"), { kind = ""; offset = 0; mode = "browse" })
            HfChip(tr("Pictures"), { kind = ContentKind.IMAGE.name; offset = 0; mode = "browse" })
            HfChip(tr("Videos"), { kind = ContentKind.VIDEO.name; offset = 0; mode = "browse" })
            HfChip(tr("Audio"), { kind = ContentKind.AUDIO.name; offset = 0; mode = "browse" })
            HfChip(tr("Collections"), { mode = "collections" })
            HfChip(tr("Categories"), { mode = "categories" })
            HfChip(tr("Duplicates"), { mode = "duplicates" })
        }
        if (mode == "browse") {
            HfTextField(nameFilter, { nameFilter = it; offset = 0 }, "File name")
            HfTextField(tagFilter, { tagFilter = it; offset = 0 }, "Tag")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HfChip("Date taken", { sort = LibrarySort.TAKEN.name; offset = 0 })
                HfChip("Date modified", { sort = LibrarySort.MODIFIED.name; offset = 0 })
                HfChip(tr("Newest"), { sort = LibrarySort.NEWEST.name; offset = 0 })
                HfChip(tr("Name"), { sort = LibrarySort.NAME.name; offset = 0 })
                HfChip(tr("Size"), { sort = LibrarySort.SIZE.name; offset = 0 })
                HfChip(tr("Recently played"), { sort = LibrarySort.RECENTLY_PLAYED.name; offset = 0 })
                HfChip(tr("Favorites"), { favoritesOnly = !favoritesOnly; offset = 0 })
                HfChip(if (grid) tr("List") else tr("Grid"), { grid = !grid })
                HfChip(tr("Select"), { selecting = !selecting; if (!selecting) selected = emptySet() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Scan"), { scope.launch { busy = true; scan(app) { note = it }; busy = false } }, enabled = !busy)
                SecondaryButton(tr("Add folder"), { folder.launch(null) })
                if (LibraryAccess.missing(context).isNotEmpty()) SecondaryButton(tr("Media access needed"), { permission.launch(LibraryAccess.missing(context)) })
            }
            folders.forEach { tree ->
                ListRow(tree.substringAfterLast('/'), subtitle = "Selected folder", trailing = {
                    SecondaryButton("Remove folder", { scope.launch {
                        withContext(Dispatchers.IO) { LibraryIndex.removeTree(app, tree) }
                        folders = FolderImport.libraryTrees(app); reload()
                    } })
                })
            }
            if (!access.images && !access.videos && !access.audio) Note("Media permission is not granted. Device pictures, videos and audio are not listed. Managed copies already in the app still appear.")
            else if (access.partialVisual) Note("Partial media access. Only items you selected are visible. Unlisted items were not deleted or marked missing.")
            note?.let { Note(it) }
            if (selecting && selected.isNotEmpty()) BulkBar(selected.size, { action = it }, { selected = emptySet(); selecting = false })
            action?.let { BulkAction(it, draft, { draft = it }, selected, { action = null; draft = ""; reload() }) { note = it } }
            if (rows.isEmpty()) Note("No indexed media for this filter.")
            else if (grid) LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, { it.id }) { item -> MediaCell(item, statuses[item.id], grid = true, selected = item.id in selected, selecting = selecting, onClick = {
                    if (selecting) selected = toggle(selected, item.id) else nav.push(LibraryRoutes.mediaDetail(item.id))
                }) }
            } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, { it.id }) { item -> MediaCell(item, statuses[item.id], grid = false, selected = item.id in selected, selecting = selecting, onClick = {
                    if (selecting) selected = toggle(selected, item.id) else nav.push(LibraryRoutes.mediaDetail(item.id))
                }) }
            }
            if (rows.size >= 60 && rows.size % 60 == 0) SecondaryButton(tr("Load more"), { offset += 60 })
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    "categories" -> CategoryPane { note = it }
                    "collections" -> CollectionPane { note = it }
                    else -> DuplicatePane { note = it }
                }
            }
        }
    }
}

private fun toggle(selected: Set<String>, id: String) = if (id in selected) selected - id else selected + id

private suspend fun scan(app: com.hellfire.ass.HellFireApp, report: (String) -> Unit) {
    val result = runCatching { withContext(Dispatchers.IO) { LibraryIndex.scan(app) } }
    LibraryScanWork.runNow(app)
    report(result.fold(
        { "Scan finished. Added ${it.inserted}, changed ${it.changed}, missing ${it.missing}, revoked ${it.revoked}, moved ${it.moved}, hashed ${it.hashed}." +
            (if (it.partial) " Some folders or albums were only partly visible." else "") +
            (it.error?.let { error -> " $error" } ?: "") },
        { "Scan failed. Indexed rows were kept." },
    ))
}

@Composable
private fun BulkBar(count: Int, onAction: (String) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Note("$count selected. Managed sync copies originals into encrypted storage. It does not upload by itself.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HfChip(tr("Mark favorite"), { onAction("favorite") })
            HfChip(tr("Tags"), { onAction("tag") })
            HfChip(tr("Assign"), { onAction("category") })
            HfChip("Exclude category", { onAction("exclude") })
            HfChip("Unassign category", { onAction("unassign") })
            HfChip(tr("Add to collection"), { onAction("collection") })
            HfChip(tr("Managed sync"), { onAction("sync") })
            HfChip(tr("Queue processing"), { onAction("queue") })
            HfChip(tr("Clear"), onClear)
        }
    }
}

@Composable
private fun BulkAction(action: String, draft: String, onDraft: (String) -> Unit, selected: Set<String>, onDone: () -> Unit, note: (String) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(action) {
        choices = withContext(Dispatchers.IO) {
            when (action) {
                "category", "exclude", "unassign" -> app.database.library().categoriesNow().map { it.id to it.name }
                "collection" -> app.database.library().collectionsNow().map { it.id to "${it.kind}: ${it.name}" }
                else -> emptyList()
            }
        }
    }
    SectionCard(tr(when (action) {
        "tag" -> "Tags"
        "category" -> "Assign"
        "exclude" -> "Exclude category"
        "unassign" -> "Unassign category"
        "collection" -> "Add to collection"
        "sync" -> "Managed sync"
        "queue" -> "Queue processing"
        else -> "Favorites"
    })) {
        if (action == "tag") HfTextField(draft, onDraft, "Tags")
        if (action == "sync") Note("This copies each selected original into encrypted managed storage and marks it for sync. External indexing stays in place until the copy succeeds.")
        choices.forEach { (id, label) -> ListRow(label, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    selected.forEach { itemId ->
                        if (action == "exclude") LibraryOrganize.assign(app.database.library(), itemId, id, LinkState.EXCLUDED, System.currentTimeMillis())
                        if (action == "unassign") app.database.library().removeCategoryLink(itemId, id)
                        if (action == "category") LibraryOrganize.assign(app.database.library(), itemId, id, LinkState.ASSIGNED, System.currentTimeMillis())
                        if (action == "collection") app.database.library().addToCollection(id, itemId, System.currentTimeMillis())
                    }
                }
                note("Updated ${selected.size} items.")
                onDone()
            }
        }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("Done"), {
                scope.launch {
                    val message = withContext(Dispatchers.IO) { runBulk(app, action, selected, draft) }
                    note(message)
                    onDone()
                }
            })
            SecondaryButton(tr("Cancel"), onDone)
        }
    }
}

private suspend fun runBulk(app: com.hellfire.ass.HellFireApp, action: String, selected: Set<String>, draft: String): String {
    val now = System.currentTimeMillis()
    var ok = 0
    var failed = 0
    selected.forEach { id ->
        val result = runCatching {
            when (action) {
                "favorite" -> app.database.library().setFavorite(id, true)
                "tag" -> LibraryOrganize.setTag(app.database.library(), id, draft, LinkState.ASSIGNED, now) ?: error("blank")
                "sync" -> app.media.promote(id, true)
                "queue" -> app.database.jobs().enqueue(ProcessingJob(UUID.randomUUID().toString(), com.hellfire.ass.intelligence.ContentEngine.JOB, id, priority = JobPriority.USER_REQUESTED, createdAt = now, updatedAt = now))
            }
        }
        if (result.isSuccess) ok++ else failed++
    }
    return when (action) {
        "sync" -> "Copied $ok into managed storage and marked them for sync. $failed could not be copied. Upload is not confirmed."
        "queue" -> { com.hellfire.ass.intelligence.IntelligenceWork.schedule(app); "Queued $ok for analysis. $failed were not queued. Nothing was sent." }
        else -> "Updated $ok. $failed failed."
    }
}

@Composable
private fun MediaCell(item: MediaItem, status: ItemStatus?, grid: Boolean, selected: Boolean, selecting: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val app = LocalEnv.current.app
    var bitmap by remember(item.id, item.modifiedAt) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, item.modifiedAt) {
        val key = item.id + ":" + item.modifiedAt
        thumbs.get(key)?.let { bitmap = it; return@LaunchedEffect }
        val loaded = withContext(Dispatchers.IO) { runCatching { LibraryMetadata.thumbnail(context, app, item) }.getOrNull() }
        if (loaded != null) { thumbs.put(key, loaded); bitmap = loaded }
    }
    val marks = listOfNotNull(
        if (item.favorite) "Favorite" else null,
        item.availability.takeIf { it != Availability.AVAILABLE.name },
        if (item.storage == StorageMode.EXTERNAL.name) tr("Indexed only") else "Managed",
        status?.sync, status?.backup, status?.processing,
    )
    if (grid) Column(Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) Hf.colors.primaryContainer else Hf.colors.surface).clickable(onClick = onClick).padding(4.dp)) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), item.name, Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)).background(Hf.colors.fill), contentAlignment = Alignment.Center) { Text(item.contentKind.take(1), color = Hf.colors.muted) }
        Text(item.name, style = Hf.type.caption, color = Hf.colors.onSurface, maxLines = 1)
        if (selecting) Text(if (selected) "Selected" else "Tap to select", style = Hf.type.caption, color = Hf.colors.muted)
    } else ListRow(item.name, subtitle = marks.joinToString(" · "), tileText = item.contentKind.take(1), onClick = onClick)
}

@Composable
private fun CategoryPane(note: (String) -> Unit) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<MediaCategory>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<String?>(null) }
    var mergeFrom by remember { mutableStateOf<String?>(null) }
    fun load() { scope.launch { categories = withContext(Dispatchers.IO) { app.database.library().categoriesNow() } } }
    LaunchedEffect(Unit) { load() }
    Note("User assignments and exclusions are kept when indexing runs again. AI suggestions cannot replace them.")
    HfTextField(name, { name = it }, "New category")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(tr("New category"), {
            scope.launch {
                val created = withContext(Dispatchers.IO) { LibraryOrganize.createCategory(app.database.library(), parent, name, false, System.currentTimeMillis()) }
                note(if (created == null) "Category name was empty or already used." else "Category saved.")
                name = ""; load()
            }
        })
        SecondaryButton(tr("Private"), {
            scope.launch {
                val created = withContext(Dispatchers.IO) { LibraryOrganize.createCategory(app.database.library(), parent, name, true, System.currentTimeMillis()) }
                note(if (created == null) "Category name was empty or already used." else "Private category saved.")
                name = ""; load()
            }
        })
    }
    categories.filter { it.parentId == null }.forEach { root ->
        CategoryNode(root, categories, name, { parent = it }, { mergeFrom = it }, mergeFrom, { id ->
            nav.push(LibraryRoutes.library(categoryId = id))
        }, { load(); note(it) })
    }
}

@Composable
private fun CategoryNode(category: MediaCategory, all: List<MediaCategory>, draft: String, onParent: (String) -> Unit, onMergeFrom: (String) -> Unit, mergeFrom: String?, open: (String) -> Unit, done: (String) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    ListCard {
        ListRow(category.name, subtitle = listOfNotNull(if (category.isPrivate) "Private" else null, category.policy, category.origin).joinToString(" · "), onClick = { open(category.id) })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HfChip(tr("Assign"), { onParent(category.id) })
            HfChip(tr("Rename"), {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { LibraryOrganize.rename(app.database.library(), category.id, draft, System.currentTimeMillis()) }
                    done(if (ok) "Renamed." else "Could not rename. The name may already exist.")
                }
            })
            HfChip(tr("Private"), {
                scope.launch {
                    withContext(Dispatchers.IO) { LibraryOrganize.setPrivate(app.database.library(), category.id, !category.isPrivate, System.currentTimeMillis()) }
                    done(if (category.isPrivate) "Category is no longer private." else "Category marked private.")
                }
            })
            HfChip(tr("Merge into"), { onMergeFrom(category.id) })
            if (mergeFrom != null && mergeFrom != category.id) HfChip(tr("Assign"), {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { LibraryOrganize.merge(app.database.library(), mergeFrom, category.id, System.currentTimeMillis()) }
                    done(if (ok) "Merged. Existing user links were kept." else "Could not merge those categories.")
                }
            })
        }
    }
    all.filter { it.parentId == category.id }.forEach { child ->
        Box(Modifier.padding(start = 16.dp)) { CategoryNode(child, all, draft, onParent, onMergeFrom, mergeFrom, open, done) }
    }
}

@Composable
private fun CollectionPane(note: (String) -> Unit) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<MediaCollection>>(emptyList()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    fun load() { scope.launch { rows = withContext(Dispatchers.IO) { app.database.library().collectionsNow() } } }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(openId) { members = openId?.let { withContext(Dispatchers.IO) { app.database.library().membersNow(it) } } ?: emptyList() }
    HfTextField(name, { name = it }, "Collections")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CollectionKind.ALBUM to "Albums", CollectionKind.COLLECTION to "Collections", CollectionKind.PLAYLIST to "Playlists").forEach { (kind, label) ->
            SecondaryButton(tr(label), {
                scope.launch {
                    val created = withContext(Dispatchers.IO) { LibraryOrganize.createCollection(app.database.library(), name, kind, System.currentTimeMillis()) }
                    note(if (created == null) "Name was empty." else "Saved.")
                    name = ""; load()
                }
            })
        }
    }
    rows.forEach { row ->
        ListCard {
            ListRow(row.name, subtitle = row.kind, chevron = true, onClick = { nav.push(LibraryRoutes.library(collectionId = row.id)) })
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("List"), { openId = row.id })
                SecondaryButton(tr("Clear"), {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.database.library().deleteCollection(row.id) }
                        note("Collection removed. Media files were not deleted.")
                        load()
                    }
                })
            }
            if (openId == row.id) members.forEachIndexed { index, item ->
                ListRow(item.name, subtitle = "Position ${index + 1}", trailing = {
                    Row {
                        Text("↑", Modifier.clickable {
                            if (index == 0) return@clickable
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    LibraryOrganize.moveUp(app.database.library(), row.id, item.id)
                                }
                                members = withContext(Dispatchers.IO) { app.database.library().membersNow(row.id) }
                            }
                        }.padding(8.dp), color = Hf.colors.primary)
                        Text("✕", Modifier.clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) { app.database.library().removeMember(row.id, item.id) }
                                members = withContext(Dispatchers.IO) { app.database.library().membersNow(row.id) }
                            }
                        }.padding(8.dp), color = Hf.colors.danger)
                    }
                })
            }
        }
    }
}

@Composable
private fun DuplicatePane(note: (String) -> Unit) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var exact by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var similar by remember { mutableStateOf<List<SimilarPair>>(emptyList()) }
    var compared by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { exact = withContext(Dispatchers.IO) { app.database.library().duplicates(200) } }
    SecondaryButton(tr("Compare pictures"), {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val images = app.database.library().queryItems(LibraryQuery.sql(LibraryFilter(kinds = listOf(ContentKind.IMAGE.name), limit = 80)))
                var read = 0
                images.forEach { item ->
                    if (LibraryIndex.dHashOf(item) != null) return@forEach
                    val hash = runCatching { LibraryMetadata.dHash(app, item) }.getOrNull() ?: return@forEach
                    LibraryIndex.storeDHash(app.database.library(), item, hash)
                    read++
                }
                val hashed = images.mapNotNull { item -> app.database.library().item(item.id)?.let { fresh -> LibraryIndex.dHashOf(fresh)?.let { fresh.id to it } } }
                read to LibraryDiscovery.similar(hashed)
            }
            compared = result.first
            similar = result.second
            note("Compared pictures. ${result.first} new hashes stored. ${result.second.size} similar pairs at distance ${LibraryDiscovery.SIMILAR_MAX_DISTANCE} or less. Hashes are not identities.")
        }
    })
    if (exact.isEmpty()) Note("No exact hash duplicates in the indexed set.")
    exact.groupBy { it.contentHash }.forEach { (hash, group) ->
        if (group.size < 2) return@forEach
        ListCard(title = "Exact ${hash.orEmpty().take(8)}") {
            group.forEach { item -> ListRow(item.name, subtitle = "${item.storage} · ${item.availability}", chevron = true, onClick = { nav.push(LibraryRoutes.mediaDetail(item.id)) }) }
        }
    }
    if (similar.isEmpty() && compared == 0) Note("Similar pictures are not guessed. Compare reads pictures you can open and stores only a perceptual hash.")
    similar.forEach { pair ->
        SectionCard("Similar pictures · distance ${pair.distance}") {
            Row {
                listOf(pair.leftId, pair.rightId).forEach { id ->
                    Column(Modifier.weight(1f)) {
                        var item by remember(id) { mutableStateOf<MediaItem?>(null) }
                        LaunchedEffect(id) { item = withContext(Dispatchers.IO) { app.database.library().item(id) } }
                        item?.let { MediaCell(it, null, grid = true, selected = false, selecting = false, onClick = { nav.push(LibraryRoutes.mediaDetail(id)) }) }
                        SecondaryButton("Hide this copy", { scope.launch {
                            LibraryOrganize.hide(app.database.library(), id, System.currentTimeMillis())
                            similar = similar.filter { it.leftId != id && it.rightId != id }
                            note("Hidden from the library. Original retained; filter by tag hidden to show it.")
                        } })
                    }
                }
            }
            SecondaryButton("Keep both", { similar = similar.filter { it != pair } })
        }
    }
}
