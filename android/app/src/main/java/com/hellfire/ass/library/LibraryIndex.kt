package com.hellfire.ass.library

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.media.FolderImport
import com.hellfire.ass.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class ScanReport(val inserted: Int, val changed: Int, val missing: Int, val revoked: Int, val moved: Int, val hashed: Int, val partial: Boolean, val error: String?)

object LibraryIndex {
    const val HASH_BATCH = 30
    private const val SAF_LIST_CAP = 5000

    suspend fun scan(app: HellFireApp): ScanReport = withContext(Dispatchers.IO) {
        val access = LibraryAccess.read(app)
        val dao = app.database.library()
        var inserted = 0
        var changed = 0
        var missing = 0
        var revoked = 0
        var moved = 0
        var partial = access.partialVisual
        var error: String? = null
        val indexed = dao.indexedExternal()
        fun of(origin: String) = indexed.filter { it.origin == origin }.map { indexedOf(it) }
        val imagePlan = planStore(app, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", ContentKind.IMAGE, access.images,
            LibraryAccess.visibility(access.images, access.imagesFull), of(CaptureOrigin.MEDIASTORE.name).filter { it.kindKey == "image" })
        val videoPlan = planStore(app, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", ContentKind.VIDEO, access.videos,
            LibraryAccess.visibility(access.videos, access.videosFull), of(CaptureOrigin.MEDIASTORE.name).filter { it.kindKey == "video" })
        val audioPlan = planStore(app, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", ContentKind.AUDIO, access.audio,
            if (access.audio) ScanVisibility.FULL else ScanVisibility.DENIED, of(CaptureOrigin.MEDIASTORE.name).filter { it.kindKey == "audio" })
        listOf(imagePlan, videoPlan, audioPlan).forEach { plan ->
            partial = partial || plan.partial
            error = error ?: plan.error
            val applied = apply(dao, plan.decisions, CaptureOrigin.MEDIASTORE, System.currentTimeMillis())
            inserted += applied.inserted; changed += applied.changed; missing += applied.missing; revoked += applied.revoked; moved += applied.moved
        }
        FolderImport.libraryTrees(app).forEach { raw ->
            val tree = Uri.parse(raw)
            val held = app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault(raw)
            val known = indexed.filter { it.origin == CaptureOrigin.SAF_FOLDER.name && belongs(it, treeId) }.map { indexedOf(it) }
            val walk = if (held) walkTree(app, tree, treeId) else null
            val visibility = LibraryDiscovery.treeVisibility(held, walk)
            if (visibility == ScanVisibility.PARTIAL) partial = true
            error = error ?: walk?.error ?: if (!held) "Folder permission is not held" else null
            val entries = walk?.files.orEmpty().mapNotNull { row ->
                val kind = ContentKind.fromMime(row.mime)
                if (kind != ContentKind.IMAGE && kind != ContentKind.VIDEO && kind != ContentKind.AUDIO) return@mapNotNull null
                ScannedMedia(DocumentsContract.buildDocumentUriUsingTree(tree, row.docId).toString(), "saf:$treeId/${row.docId}", row.name, row.mime, row.size, row.modifiedAt, 0)
            }
            val applied = apply(dao, LibraryDiscovery.diff(entries, known.map { it.media }, visibility), CaptureOrigin.SAF_FOLDER, System.currentTimeMillis())
            inserted += applied.inserted; changed += applied.changed; missing += applied.missing; revoked += applied.revoked; moved += applied.moved
        }
        val hashed = hashPending(app, HASH_BATCH)
        moved += reconcile(dao, System.currentTimeMillis())
        ScanReport(inserted, changed, missing, revoked, moved, hashed, partial, error)
    }

    suspend fun apply(dao: LibraryDao, decisions: List<IndexDecision>, origin: CaptureOrigin, now: Long): ApplyCounts {
        var inserted = 0
        var changed = 0
        var missing = 0
        var revoked = 0
        var moved = 0
        decisions.forEach { decision ->
            when (decision.change) {
                IndexChange.INSERT -> {
                    val scan = decision.scanned ?: return@forEach
                    val kind = ContentKind.fromMime(scan.mime)
                    val row = MediaItem(UUID.randomUUID().toString(), null, scan.name.take(200).ifBlank { "Media" }, scan.mime.ifBlank { "application/octet-stream" },
                        kind.name, scan.contentHash, scan.size, now, scan.takenAt, durationMs = scan.durationMs, storage = StorageMode.EXTERNAL.name,
                        contentKind = kind.name, externalUri = scan.uri, availability = Availability.AVAILABLE.name, origin = origin.name,
                        modifiedAt = scan.modifiedAt, width = scan.width, height = scan.height, originalMetadata = meta(null, scan), syncEnabled = false)
                    if (dao.insertItem(row) != -1L) inserted++
                }
                IndexChange.CHANGED, IndexChange.MOVED -> {
                    val id = decision.id ?: return@forEach
                    val scan = decision.scanned ?: return@forEach
                    val existing = dao.item(id) ?: return@forEach
                    if (existing.storage != StorageMode.EXTERNAL.name) return@forEach
                    dao.updateItem(existing.copy(
                        name = scan.name.take(200).ifBlank { existing.name }, mime = scan.mime.ifBlank { existing.mime },
                        contentKind = ContentKind.fromMime(scan.mime).name, kind = ContentKind.fromMime(scan.mime).name,
                        size = scan.size, modifiedAt = scan.modifiedAt, width = scan.width ?: existing.width, height = scan.height ?: existing.height,
                        durationMs = scan.durationMs ?: existing.durationMs, recordedAt = scan.takenAt ?: existing.recordedAt,
                        externalUri = scan.uri, availability = Availability.AVAILABLE.name,
                        contentHash = if (decision.change == IndexChange.CHANGED) scan.contentHash else (scan.contentHash ?: existing.contentHash),
                        originalMetadata = meta(existing.originalMetadata, scan),
                    ))
                    if (decision.change == IndexChange.MOVED) moved++ else changed++
                }
                IndexChange.MISSING -> {
                    decision.id?.let { dao.setAvailability(it, Availability.MISSING.name); missing++ }
                }
                IndexChange.REVOKED -> {
                    decision.id?.let { dao.setAvailability(it, Availability.PERMISSION_REVOKED.name); revoked++ }
                }
                IndexChange.UNCHANGED -> Unit
            }
        }
        return ApplyCounts(inserted, changed, missing, revoked, moved)
    }

    /**
     * Hash up to [limit] unhashed external entries. An entry that cannot be opened is skipped, not marked missing: under partial
     * or revoked access an unreadable file may still exist (only a full scan decides availability). A cursor rotates through
     * the entries so unreadable ones never block the rest.
     */
    suspend fun hashPending(app: HellFireApp, limit: Int): Int {
        val dao = app.database.library()
        val prefs = app.getSharedPreferences("library", 0)
        var after = prefs.getString("hash-cursor", "").orEmpty()
        var hashed = 0
        var tried = 0
        var wrapped = after.isEmpty()
        while (hashed < limit && tried < limit * 4) {
            val page = dao.unhashedExternalAfter(after, limit)
            if (page.isEmpty()) {
                if (wrapped) break
                wrapped = true; after = ""; continue
            }
            for (item in page) {
                tried++
                after = item.id
                val hash = runCatching { app.media.openStream(item).use { LibraryDiscovery.sha256(it) } }.getOrNull()
                if (hash != null) { dao.setHash(item.id, hash); hashed++ }
                if (hashed >= limit || tried >= limit * 4) break
            }
        }
        prefs.edit().putString("hash-cursor", after).apply()
        return hashed
    }

    /** Retire missing external entries into live entries with the same content hash (see [LibraryDao.retireInto]). */
    suspend fun reconcile(dao: LibraryDao, now: Long): Int {
        val moves = LibraryDiscovery.hashMoves(dao.indexedExternal())
        moves.forEach { move -> dao.retireInto(move.dropId, move.keepId, now) }
        return moves.size
    }

    /** Stop indexing a picked folder: release its permission and mark its entries as no longer permitted (user data kept). */
    suspend fun removeTree(app: HellFireApp, treeUri: String): Int = withContext(Dispatchers.IO) {
        FolderImport.removeLibraryTree(app, treeUri)
        app.database.library().revokeUnder(treePrefix(treeUri))
    }

    /** Document URIs built from a tree URI start with "<tree uri>/document/". */
    fun treePrefix(treeUri: String) = treeUri.trimEnd('/') + "/document/"

    suspend fun storeDHash(dao: LibraryDao, item: MediaItem, hash: Long) {
        val json = item.originalMetadata?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        json.put("dHash", hash.toULong().toString(16))
        dao.setMetadata(item.id, json.toString(), item.width, item.height)
    }

    fun dHashOf(item: MediaItem): Long? = item.originalMetadata?.let { raw ->
        runCatching { JSONObject(raw).optString("dHash").ifBlank { null }?.toULong(16)?.toLong() }.getOrNull()
    }

    private data class StorePlan(val decisions: List<IndexDecision>, val partial: Boolean, val error: String?)
    data class ApplyCounts(val inserted: Int, val changed: Int, val missing: Int, val revoked: Int, val moved: Int)
    private data class IndexedView(val media: IndexedMedia, val kindKey: String)

    private fun planStore(app: HellFireApp, collection: Uri, kindKey: String, kind: ContentKind, granted: Boolean, visibility: ScanVisibility, indexed: List<IndexedView>): StorePlan {
        if (!granted) return StorePlan(LibraryDiscovery.diff(emptyList(), indexed.map { it.media }, ScanVisibility.DENIED), false, null)
        val scanned = runCatching { queryStore(app, collection, kindKey, kind) }.getOrElse { return StorePlan(emptyList(), false, "MediaStore could not be read") }
        return StorePlan(LibraryDiscovery.diff(scanned, indexed.map { it.media }, visibility), visibility == ScanVisibility.PARTIAL, null)
    }

    private fun queryStore(app: HellFireApp, collection: Uri, kindKey: String, kind: ContentKind): List<ScannedMedia> {
        val projection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN)
        if (kind != ContentKind.AUDIO) projection += listOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
        if (kind != ContentKind.IMAGE) projection += MediaStore.MediaColumns.DURATION
        if (Build.VERSION.SDK_INT >= 30) projection += MediaStore.MediaColumns.GENERATION_MODIFIED
        val out = mutableListOf<ScannedMedia>()
        app.contentResolver.query(collection, projection.toTypedArray(), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val generationCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED) else -1
            val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                out += ScannedMedia(uri, "ms:$kindKey:$id", cursor.getString(nameCol).orEmpty(), cursor.getString(mimeCol) ?: kindMime(kind),
                    if (cursor.isNull(sizeCol)) 0 else cursor.getLong(sizeCol),
                    (if (cursor.isNull(modifiedCol)) 0 else cursor.getLong(modifiedCol)) * 1000,
                    if (generationCol < 0 || cursor.isNull(generationCol)) 0 else cursor.getLong(generationCol),
                    width = if (widthCol < 0 || cursor.isNull(widthCol)) null else cursor.getInt(widthCol),
                    height = if (heightCol < 0 || cursor.isNull(heightCol)) null else cursor.getInt(heightCol),
                    durationMs = if (durationCol < 0 || cursor.isNull(durationCol)) null else cursor.getLong(durationCol),
                    takenAt = if (takenCol < 0 || cursor.isNull(takenCol)) null else cursor.getLong(takenCol).takeIf { it > 0 })
            }
        }
        return out
    }

    private fun walkTree(app: HellFireApp, tree: Uri, treeId: String): TreeWalk = LibraryDiscovery.walkTree(treeId, SAF_LIST_CAP) { parent ->
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        runCatching {
            app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
                ?.use { cursor ->
                    val rows = mutableListOf<DocRow>()
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0) ?: continue
                        rows += DocRow(docId, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getLong(3), cursor.getLong(4))
                    }
                    rows
                }
        }.getOrNull()
    }

    private fun indexedOf(item: MediaItem): IndexedView {
        val key = metaString(item.originalMetadata, "stableKey")
        val kindKey = key?.substringAfter("ms:")?.substringBefore(":") ?: when (item.contentKind) {
            ContentKind.VIDEO.name -> "video"
            ContentKind.AUDIO.name -> "audio"
            else -> "image"
        }
        return IndexedView(IndexedMedia(item.id, item.externalUri.orEmpty(), key, item.modifiedAt, metaLong(item.originalMetadata, "generation"),
            item.contentHash, item.size, item.availability, item.storage), kindKey)
    }

    private fun belongs(item: MediaItem, treeId: String): Boolean {
        val key = metaString(item.originalMetadata, "stableKey").orEmpty()
        return key.startsWith("saf:$treeId/") || item.externalUri.orEmpty().contains(treeId)
    }

    private fun meta(previous: String?, scan: ScannedMedia): String {
        val json = previous?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        json.put("stableKey", scan.stableKey)
        json.put("generation", scan.generation)
        return json.toString()
    }

    private fun metaString(json: String?, key: String): String? = json?.let { runCatching { JSONObject(it).optString(key).ifBlank { null } }.getOrNull() }
    private fun metaLong(json: String?, key: String): Long? = json?.let {
        runCatching { val obj = JSONObject(it); if (!obj.has(key) || obj.isNull(key)) null else obj.getLong(key) }.getOrNull()
    }
    private fun kindMime(kind: ContentKind) = when (kind) {
        ContentKind.IMAGE -> "image/*"
        ContentKind.VIDEO -> "video/*"
        ContentKind.AUDIO -> "audio/*"
        else -> "application/octet-stream"
    }
}
