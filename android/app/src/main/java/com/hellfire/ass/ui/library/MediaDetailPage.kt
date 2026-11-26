package com.hellfire.ass.ui.library

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

@Composable
fun MediaDetailPage(route: Route) {
    val id = LibraryRoutes.id(route)
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<MediaItem?>(null) }
    var status by remember { mutableStateOf<ItemStatus?>(null) }
    var analysis by remember { mutableStateOf<MediaAnalysis?>(null) }
    var categories by remember { mutableStateOf<List<CategoryLink>>(emptyList()) }
    var tags by remember { mutableStateOf<List<ItemTag>>(emptyList()) }
    var allCategories by remember { mutableStateOf<List<MediaCategory>>(emptyList()) }
    var description by remember { mutableStateOf("") }
    var tagDraft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf(false) }
    var siblings by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var slideshow by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    fun load() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val row = app.database.library().item(id) ?: return@withContext null
                val latest = app.database.library().latestAny(id)
                Loaded(row, LibraryStatus.load(app, listOf(row))[id], latest, app.database.library().itemCategoriesNow(id),
                    app.database.library().tagsNow(id), app.database.library().categoriesNow())
            } ?: return@launch
            item = loaded.item
            status = loaded.status
            analysis = loaded.analysis
            categories = loaded.categories
            tags = loaded.tags
            allCategories = loaded.all
            description = withContext(Dispatchers.IO) { LibraryOrganize.description(app.database.library(), id) }.orEmpty()
        }
    }
    LaunchedEffect(id) { load() }
    val current = item
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PushedTopBar(current?.name ?: "Media", { nav.pop() }, breadcrumb = stringResource(R.string.ui_library))
            if (current == null) {
                Note("This item is not in the library.")
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.contentKind == ContentKind.IMAGE.name) SecondaryButton(stringResource(R.string.ui_pictures), { viewing = true })
                SecondaryButton(stringResource(R.string.ui_play), { nav.push(LibraryRoutes.player(current.id)) })
                SecondaryButton(stringResource(R.string.ui_share_with_ai), { nav.push(LibraryRoutes.shareWithAi(current.id)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(current.availability)
                StatusPill(if (current.storage == StorageMode.EXTERNAL.name) stringResource(R.string.ui_indexed_only) else "Managed")
                StatusPill(status?.sync ?: stringResource(R.string.ui_not_selected_for_sync))
                StatusPill(status?.backup ?: stringResource(R.string.ui_not_backed_up))
                StatusPill(status?.processing ?: stringResource(R.string.ui_not_queued))
            }
            note?.let { Note(it) }
            SectionCard(stringResource(R.string.ui_original_metadata)) {
                MetadataBlock(current)
                if (!LibraryAccess.read(env.app).location) Note("Location permission is not granted, so GPS metadata is not read.")
                SecondaryButton(stringResource(R.string.ui_scan), {
                    scope.launch {
                        val json = withContext(Dispatchers.IO) {
                            val fresh = app.database.library().item(id) ?: return@withContext null
                            val read = LibraryMetadata.read(app, fresh, LibraryAccess.read(app).location)
                            app.database.library().setMetadata(id, read.toString(), fresh.width, fresh.height)
                            read
                        }
                        note = if (json == null) "Item is gone." else "Embedded metadata was read from the original. It was not exported."
                        load()
                    }
                })
            }
            SectionCard(stringResource(R.string.ui_ai_metadata)) {
                val ai = analysis?.takeIf { it.analyzer != "user_description" && it.modelId != "user" && it.rawJson != "{}" }
                if (ai == null) Note(stringResource(R.string.ui_not_analysed_yet))
                else {
                    Note("${ai.analyzer} · ${ai.modelId} ${ai.modelVersion} · ${ai.state}")
                    if (ai.confidence != null) Note("Confidence ${ai.confidence}")
                    if (ai.text.isNotBlank()) Note(ai.text)
                    Note(ai.rawJson.take(1500))
                }
            }
            SectionCard(stringResource(R.string.ui_your_description)) {
                HfTextField(description, { description = it }, stringResource(R.string.ui_your_description), singleLine = false, minLines = 3)
                SecondaryButton(stringResource(R.string.ui_done), {
                    scope.launch {
                        withContext(Dispatchers.IO) { LibraryOrganize.saveDescription(app.database.library(), id, description, System.currentTimeMillis()) }
                        note = "Saved as your edit. The raw analysis was not overwritten."
                        load()
                    }
                })
            }
            SectionCard(stringResource(R.string.ui_categories)) {
                if (categories.isEmpty()) Note("No categories assigned.")
                categories.forEach { link ->
                    ListRow(link.category.name, subtitle = "${link.linkOrigin} · ${link.linkState}" + if (link.category.isPrivate) " · Private" else "")
                }
                allCategories.filter { it.mergedInto == null }.forEach { category ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category.name, style = Hf.type.body, color = Hf.colors.onSurface, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.ui_assign), Modifier.padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Hf.colors.fill).padding(8.dp), color = Hf.colors.primary)
                        Text(stringResource(R.string.ui_exclude), Modifier.padding(8.dp), color = Hf.colors.danger)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.ui_assign), {
                            scope.launch {
                                withContext(Dispatchers.IO) { LibraryOrganize.assign(app.database.library(), id, category.id, LinkState.ASSIGNED, System.currentTimeMillis()) }
                                load()
                            }
                        })
                        SecondaryButton(stringResource(R.string.ui_exclude), {
                            scope.launch {
                                withContext(Dispatchers.IO) { LibraryOrganize.assign(app.database.library(), id, category.id, LinkState.EXCLUDED, System.currentTimeMillis()) }
                                note = "Excluded. Re-indexing will not put this category back."
                                load()
                            }
                        })
                    }
                }
            }
            SectionCard(stringResource(R.string.ui_tags)) {
                tags.forEach { tag -> ListRow(tag.tag, subtitle = "${tag.origin} · ${tag.state}") }
                HfTextField(tagDraft, { tagDraft = it }, stringResource(R.string.ui_tags))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.ui_assign), {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { LibraryOrganize.setTag(app.database.library(), id, tagDraft, LinkState.ASSIGNED, System.currentTimeMillis()) }
                            note = if (saved == null) "Tag was empty." else "Tag saved."
                            tagDraft = ""; load()
                        }
                    })
                    SecondaryButton(stringResource(R.string.ui_remove_tag), {
                        scope.launch {
                            withContext(Dispatchers.IO) { LibraryOrganize.setTag(app.database.library(), id, tagDraft, LinkState.EXCLUDED, System.currentTimeMillis()) }
                            note = "Tag excluded. A later suggestion will not restore it."
                            tagDraft = ""; load()
                        }
                    })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(if (current.favorite) stringResource(R.string.ui_favorites) else stringResource(R.string.ui_mark_favorite), {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.database.library().setFavorite(id, !current.favorite) }
                        load()
                    }
                })
                SecondaryButton(stringResource(R.string.ui_managed_sync), {
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { app.media.promote(id, true) } }
                        note = result.fold({ "Copied into managed storage and marked for sync. Upload is not confirmed." }, { "Could not copy the original. It was not marked synced." })
                        load()
                    }
                })
                SecondaryButton(stringResource(R.string.ui_reprocess), {
                    scope.launch {
                        val job = withContext(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            app.database.jobs().enqueue(ProcessingJob(UUID.randomUUID().toString(), com.hellfire.ass.intelligence.ContentEngine.JOB, id, priority = JobPriority.USER_REQUESTED, createdAt = now, updatedAt = now))
                        }
                        com.hellfire.ass.intelligence.IntelligenceWork.schedule(app)
                        note = "Analysis job ${job.state}. This does not mean a model ran."
                        load()
                    }
                })
            }
            SceneThumbnails(id)
            if (current.storage == StorageMode.EXTERNAL.name && !current.syncEnabled) Note("Indexed only. The original was not copied and is not selected for sync.")
        }
        if (viewing && current?.contentKind == ContentKind.IMAGE.name) {
            BackHandler { viewing = false; slideshow = false }
            ImageViewer(current, siblings, slideshow, zoomed, { slideshow = it }, { zoomed = it }, { viewing = false; slideshow = false; zoomed = false }) {
                scope.launch {
                    siblings = withContext(Dispatchers.IO) {
                        app.database.library().queryItems(LibraryQuery.sql(LibraryFilter(kinds = listOf(ContentKind.IMAGE.name), limit = 120)))
                    }
                }
            }
        }
    }
}

private data class Loaded(val item: MediaItem, val status: ItemStatus?, val analysis: MediaAnalysis?, val categories: List<CategoryLink>, val tags: List<ItemTag>, val all: List<MediaCategory>)

@Composable
private fun MetadataBlock(item: MediaItem) {
    val json = item.originalMetadata?.let { runCatching { JSONObject(it) }.getOrNull() }
    if (json == null) {
        Note("Embedded metadata has not been read yet.")
        return
    }
    val rows = mutableListOf("Name" to item.name, "Kind" to item.contentKind, "Storage" to item.storage, "Size" to item.size.toString())
    item.width?.let { rows += "Width" to it.toString() }
    item.height?.let { rows += "Height" to it.toString() }
    item.durationMs?.let { rows += "Duration" to it.toString() }
    json.optJSONObject("exif")?.let { exif -> exif.keys().forEach { key -> rows += key to exif.optString(key) } }
    json.optJSONObject("container")?.let { container -> container.keys().forEach { key -> rows += "container $key" to container.optString(key) } }
    json.optString("exifError").takeIf { it.isNotBlank() }?.let { rows += "Image metadata" to it }
    json.optString("containerError").takeIf { it.isNotBlank() }?.let { rows += "Container metadata" to it }
    KvList(rows)
}

@Composable
private fun ImageViewer(current: MediaItem, siblings: List<MediaItem>, slideshow: Boolean, zoomed: Boolean, onSlideshow: (Boolean) -> Unit, onZoomed: (Boolean) -> Unit, onClose: () -> Unit, onNeedSiblings: () -> Unit) {
    LaunchedEffect(Unit) { if (siblings.isEmpty()) onNeedSiblings() }
    val pages = if (siblings.isEmpty()) listOf(current) else siblings
    val start = pages.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val pager = rememberPagerState(start) { pages.size }
    LaunchedEffect(slideshow, pager.currentPage, pages.size) {
        if (!slideshow || pages.size < 2) return@LaunchedEffect
        delay(4000)
        val next = (pager.currentPage + 1) % pages.size
        pager.animateScrollToPage(next)
    }
    Column(Modifier.fillMaxSize().background(Hf.colors.surfaceDark).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.ui_clear), onClose)
            SecondaryButton(stringResource(R.string.ui_slideshow), { onSlideshow(!slideshow) })
            Text(if (slideshow) "Slideshow on" else "Slideshow off", color = Hf.colors.onSurface, modifier = Modifier.align(Alignment.CenterVertically))
        }
        HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = !zoomed) { page ->
            Zoomable(pages[page], onZoomed)
        }
        Text("${pager.currentPage + 1} / ${pages.size}", color = Hf.colors.muted)
    }
}

@Composable
private fun Zoomable(item: MediaItem, onZoomed: (Boolean) -> Unit) {
    val app = LocalEnv.current.app
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var panX by remember(item.id) { mutableFloatStateOf(0f) }
    var panY by remember(item.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(item.id) {
        val decoded = withContext(Dispatchers.IO) { runCatching { LibraryMetadata.decode(app, item, 1600) }.getOrNull() }
        bitmap = decoded
        failed = decoded == null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) Text(if (failed) "This picture could not be decoded on this device." else "Reading picture…", color = Hf.colors.onSurface)
        else Image(image.asImageBitmap(), item.name, Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = panX; translationY = panY
        }.pointerInput(item.id) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                if (scale > 1f) { panX += pan.x; panY += pan.y } else { panX = 0f; panY = 0f }
                onZoomed(scale > 1f)
            }
        }, contentScale = ContentScale.Fit)
    }
}

@Composable
internal fun SceneThumbnail(path: String?) {
    val app = LocalEnv.current.app
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            path?.takeIf { it.startsWith("intelligence-thumbs/") && !it.contains("..") }?.let {
                android.graphics.BitmapFactory.decodeFile(java.io.File(app.cacheDir, it).path)
            }
        }
    }
    bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), "Sampled video scene", Modifier.height(90.dp)) }
}

@Composable
private fun SceneThumbnails(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var scenes by remember(id) { mutableStateOf<List<com.hellfire.ass.library.MediaSegment>>(emptyList()) }
    LaunchedEffect(id) { scenes = withContext(Dispatchers.IO) { app.database.library().segmentsNow(id).filter { it.kind == "SCENE" } } }
    scenes.take(60).forEach { scene ->
        SceneThumbnail(scene.thumbnailPath)
        SecondaryButton("${scene.startMs / 1000}s: ${scene.title}", { nav.push(LibraryRoutes.player(id, scene.startMs)) })
    }
}
