package com.hellfire.ass.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.intelligence.ContentEngine
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID



internal fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
internal fun JSONArray?.rows(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

internal const val PARKED = "library-sync-parked"

internal fun parkedFile(app: HellFireApp) = File(app.noBackupFilesDir, "library-sync-parked.json")

internal const val UNREADABLE = "unreadable"
internal const val STUCK = "stuck"

/** Remote records this phone gave up on: [UNREADABLE] (other passphrase or tampered) or [STUCK] (related item never arrived). */
internal fun mark(app: HellFireApp, set: String, key: String) {
    val prefs = app.getSharedPreferences(LibrarySync.PREFS, Context.MODE_PRIVATE)
    prefs.edit().putStringSet(set, marked(app, set) + key).apply()
}

internal fun clearMark(app: HellFireApp, set: String, key: String) {
    val prefs = app.getSharedPreferences(LibrarySync.PREFS, Context.MODE_PRIVATE)
    if (key in marked(app, set)) prefs.edit().putStringSet(set, marked(app, set) - key).apply()
}

internal fun marked(app: HellFireApp, set: String): Set<String> =
    app.getSharedPreferences(LibrarySync.PREFS, Context.MODE_PRIVATE).getStringSet(set, emptySet()).orEmpty().toSet()

/**
 * Forgets everything this phone knew about the server copy (new server, server reset, restore onto a new phone):
 * every row is offered again from version 0 and the next pull merges the server's full history without conflicts.
 */
internal suspend fun resetLibrarySyncState(app: HellFireApp) {
    app.database.openHelper.writableDatabase.execSQL("UPDATE sync_record SET state = 'PENDING', nextAttemptAt = 0, attempts = 0, uploadSession = NULL, " +
        "localHash = NULL, version = 0, remoteId = NULL, bytesDone = 0, error = NULL")
    app.secrets.remove(LibrarySync.BASE)
    app.secrets.remove(LibrarySync.CURSOR)
    app.secrets.remove(PARKED)
    parkedFile(app).delete()
    app.getSharedPreferences(LibrarySync.PREFS, Context.MODE_PRIVATE).edit()
        .remove(UNREADABLE).remove(STUCK).remove("scan-tracked").remove("scan-media").remove("scan-inbox").apply()
}

internal fun openRecord(keys: LibrarySync.Keys, row: JSONObject): Pair<String, LibrarySync.Doc>? = runCatching {
    val type = row.getString("entityType"); val remote = row.getString("entityId")
    val envelope = row.getJSONObject("envelope")
    val plain = keys.cipher.decrypt(Base64.getDecoder().decode(envelope.getString("ct")), LibrarySync.aad("record", "$type:$remote")).toString(Charsets.UTF_8)
    if (envelope.optInt("v", 1) == 1) remote to LibrarySync.parse(plain)
    else LibrarySync.parseBody(plain).also { (id, _) -> require(keys.remoteId(type, id) == remote) }
}.getOrNull()

internal fun canOpenRecord(keys: LibrarySync.Keys, row: JSONObject) = openRecord(keys, row) != null

/** Correct a local typo, but never silently start using another key for an existing library. */
suspend fun saveLibraryPassphrase(app: HellFireApp, passphrase: String) = withContext(Dispatchers.IO) {
    require(passphrase.length in 12..1024) { "Use a passphrase of 12 to 1024 characters" }
    LibrarySync.mutex.withLock {
        val previous = app.secrets.read(LibrarySync.PASS)?.optString("passphrase")
        val config = app.secrets.read(WebSync.CONFIG)
        if (config != null) {
            val api = LibraryClient(config.getString("url"), config.getString("token"))
            val state = api.get("/api/library/state")
            val salt = state.optStringOrNull("salt")
            if (salt != null) {
                val keys = LibrarySync.keys(passphrase.toCharArray(), Base64.getDecoder().decode(salt))
                val check = state.optStringOrNull("keyCheck")
                val first = if (check == null) api.get("/api/library/changes?after=0&limit=1").optJSONArray("records")?.optJSONObject(0) else null
                require(if (check != null) LibrarySync.checkKey(keys.cipher, check) else first == null || canOpenRecord(keys, first)) {
                    "This library already uses another passphrase. Enter its existing passphrase; changing encryption keys is not supported."
                }
            }
        } else require(previous.isNullOrEmpty() || previous == passphrase) { "Connect to the server before changing a saved passphrase so its key can be verified." }
        app.secrets.save(LibrarySync.PASS, JSONObject().put("passphrase", passphrase))
    }
}

internal fun conflictChoice(merged: LibrarySync.Doc, choice: LibrarySync.Doc, fields: List<String>): String {
    val selected = merged.fields.toMutableMap()
    for (name in fields) choice.fields[name]?.let { selected[name] = it }
    return JSONObject(LibrarySync.canonical(merged.copy(fields = selected, deleted = if ("*deleted" in fields) choice.deleted else merged.deleted)))
        .put("conflicts", JSONArray(fields)).toString()
}

/** Only conflicting fields change; unrelated merged values and edits made since detection survive. */
suspend fun resolveConflict(app: HellFireApp, conflict: SyncConflict, keepLocal: Boolean) = withContext(Dispatchers.IO) {
    LibrarySync.mutex.withLock {
        app.database.withTransaction {
            val json = JSONObject(if (keepLocal) conflict.localJson else conflict.remoteJson)
            val choice = LibrarySync.parse(json.toString())
            val names = json.optJSONArray("conflicts")
            val now = System.currentTimeMillis()
            val current = readSyncFields(app, conflict.entityType, conflict.entityId)
            val fields = current?.mapValues { LibrarySync.Field(it.value, now) }?.toMutableMap() ?: mutableMapOf()
            if (names == null) fields.putAll(choice.fields) else for (index in 0 until names.length()) {
                val name = names.getString(index)
                choice.fields[name]?.let { fields[name] = it.copy(updatedAt = now) }
            }
            val deletionChanged = names == null || (0 until names.length()).any { names.getString(it) == "*deleted" }
            val deleted = if (deletionChanged) choice.deleted else app.database.sync().record(conflict.entityType, conflict.entityId)?.deleted == true
            val doc = LibrarySync.Doc(fields, deleted, now)
            check(applyDoc(app, conflict.entityType, conflict.entityId, doc)) { "That item can no longer be changed here" }
            app.database.sync().resolve(conflict.id, if (keepLocal) "local" else "remote", now)
            app.database.sync().markChanged(conflict.entityType, conflict.entityId, doc.deleted, now)
        }
    }
    LibrarySync.now(app)
}

/** One row of the outbound queue for the sync dashboard. */
data class SyncQueueRow(val entityType: String, val entityId: String, val label: String, val state: String, val deleted: Boolean,
    val bytesDone: Long, val bytesTotal: Long, val error: String?, val nextAttemptAt: Long)

suspend fun syncQueue(app: HellFireApp, limit: Int = 30): List<SyncQueueRow> = withContext(Dispatchers.IO) {
    val out = mutableListOf<SyncQueueRow>()
    app.database.openHelper.readableDatabase.query("SELECT s.entityType, s.entityId, COALESCE(m.name, i.title, ''), s.state, s.deleted, s.bytesDone, s.bytesTotal, s.error, s.nextAttemptAt " +
        "FROM sync_record s LEFT JOIN media_item m ON s.entityType = 'media_item' AND m.id = s.entityId " +
        "LEFT JOIN inbox_item i ON s.entityType = 'inbox_item' AND i.id = s.entityId WHERE s.state != 'SYNCED' " +
        "ORDER BY CASE s.state WHEN 'UPLOADING' THEN 0 WHEN 'FAILED' THEN 1 ELSE 2 END, s.updatedAt LIMIT ?", arrayOf<Any>(limit)).use { c ->
        while (c.moveToNext()) out += SyncQueueRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) != 0,
            c.getLong(5), c.getLong(6), c.getString(7), c.getLong(8))
    }
    out
}

internal fun deviceId(app: HellFireApp): String {
    val saved = app.secrets.read(LibrarySync.DEVICE)
    if (saved != null) return saved.getString("id")
    val id = UUID.randomUUID().toString()
    app.secrets.save(LibrarySync.DEVICE, JSONObject().put("id", id))
    return id
}

internal fun outgoingDoc(record: SyncRecord, fields: Map<String, String>?, clocks: Map<String, LibrarySync.Clock>): LibrarySync.Doc =
    LibrarySync.doc(LibrarySync.stamp(if (record.deleted) emptyMap() else fields.orEmpty(), clocks, record.updatedAt), record.deleted, record.updatedAt)

/** One decrypted chunk in memory at a time; downloaded originals are never staged as plaintext files. */
internal class SyncChunkInput(private val size: Long, private val load: (Int) -> ByteArray) : InputStream() {
    private var chunk = ByteArray(0)
    private var offset = 0
    private var position = 0L
    private var index = 0
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }
    override fun read(target: ByteArray, off: Int, length: Int): Int {
        require(off >= 0 && length >= 0 && off <= target.size - length)
        if (length == 0) return 0
        if (position == size) return -1
        if (offset == chunk.size) {
            chunk.fill(0)
            chunk = load(index++)
            offset = 0
            require(chunk.size.toLong() == minOf(LibrarySync.CHUNK.toLong(), size - position)) { "Unexpected sync chunk size" }
        }
        val count = minOf(length, chunk.size - offset)
        chunk.copyInto(target, off, offset, offset + count)
        offset += count
        position += count
        return count
    }
    override fun close() { chunk.fill(0); chunk = ByteArray(0) }
}

/** The synced values of one local row, or null when it no longer exists. Per-device state (sync state) is never sent. */
internal suspend fun readSyncFields(app: HellFireApp, type: String, id: String): Map<String, String>? = when (type) {
    "media_item" -> app.database.library().item(id)?.let { item ->
        mapOf("name" to item.name, "mime" to item.mime, "kind" to item.kind, "contentKind" to item.contentKind,
            "size" to item.size.toString(), "contentHash" to item.contentHash.orEmpty(), "summary" to item.summary,
            "favorite" to item.favorite.toString(), "origin" to item.origin, "durationMs" to (item.durationMs ?: 0).toString(),
            "syncEnabled" to item.syncEnabled.toString())
    }
    "inbox_item" -> app.database.inbox().item(id)?.let { item ->
        mapOf("title" to item.title, "summary" to item.summary, "notes" to item.notes, "contentKind" to item.contentKind,
            "origin" to item.origin, "lifecycle" to item.lifecycle, "favorite" to item.favorite.toString(),
            "processingState" to item.processingState, "backupState" to item.backupState,
            "capturedAt" to item.capturedAt.toString(), "originalRef" to item.originalRef.orEmpty())
    }
    "media_bookmark" -> app.database.openHelper.readableDatabase.row("SELECT mediaId, startMs, endMs, title, note, favorite, hidden, origin, categoryId FROM media_bookmark WHERE id = ?", id)
    "media_category" -> app.database.openHelper.readableDatabase.row("SELECT name, parentId, origin, isPrivate, policy FROM media_category WHERE id = ?", id)
    "saved_location" -> app.database.openHelper.readableDatabase.row("SELECT label, latitude, longitude, address, sourceText, note, inboxId, itemId FROM saved_location WHERE id = ?", id)
    "transcript_segment" -> app.database.openHelper.readableDatabase.row("SELECT mediaId, startMs, endMs, speaker, text FROM transcript_segment WHERE id = ?", id)
    "media_analysis" -> app.database.openHelper.readableDatabase.row("SELECT mediaId, analyzer, modelId, modelVersion, pipelineVersion, state, rawJson, editedJson, text FROM media_analysis WHERE id = ?", id)
    "media_segment" -> app.database.openHelper.readableDatabase.row("SELECT mediaId, analysisId, kind, startMs, endMs, title, description, labels FROM media_segment WHERE id = ?", id)
    "media_collection" -> app.database.openHelper.readableDatabase.row("SELECT name, kind, description, coverItemId FROM media_collection WHERE id = ?", id)
    "drawer_message" -> app.database.openHelper.readableDatabase.row("SELECT sessionId, inboxId, role, text, createdAt, inContext FROM drawer_message WHERE id = ?", id)
    "action_record" -> app.database.openHelper.readableDatabase.row("SELECT inboxId, itemId, actionType, label, payload, state FROM action_record WHERE id = ?", id)
    "media_item_category", "media_item_tag", "collection_member", "inbox_member" -> linkFields(app, type, id)
    else -> null
}

private fun linkFields(app: HellFireApp, type: String, id: String): Map<String, String>? {
    val parts = id.split(":", limit = 2)
    if (parts.size != 2) return null
    val columns = when (type) {
        "media_item_category" -> "itemId" to "categoryId"
        "media_item_tag" -> "itemId" to "tag"
        "collection_member" -> "collectionId" to "itemId"
        "inbox_member" -> "inboxId" to "itemId"
        else -> return null
    }
    app.database.openHelper.readableDatabase.query("SELECT * FROM $type WHERE ${columns.first} = ? AND ${columns.second} = ?", parts.toTypedArray()).use { row ->
        if (!row.moveToFirst()) return null
        return (0 until row.columnCount).associate { row.getColumnName(it) to (row.getString(it) ?: "") }
    }
}

private fun SupportSQLiteDatabase.row(query: String, id: String): Map<String, String>? {
    query(query, arrayOf(id)).use { cursor ->
        if (!cursor.moveToFirst()) return null
        return (0 until cursor.columnCount).associate { cursor.getColumnName(it) to (cursor.getString(it) ?: "") }
    }
}

internal suspend fun applyDoc(app: HellFireApp, type: String, id: String, doc: LibrarySync.Doc): Boolean {
    if (doc.deleted) return applyTombstone(app, type, id)
    val f = doc.fields
    val now = System.currentTimeMillis()
    fun flag(name: String) = f[name]?.value in setOf("1", "true")
    return try {
        when (type) {
            "media_item" -> {
                val existing = app.database.library().item(id)
                if (existing == null) {
                    val blob = f["blobId"]?.value
                    if (!blob.isNullOrBlank() && !f["contentHash"]?.value.isNullOrBlank()) return false
                    app.database.library().insertItem(MediaItem(id, null, f["name"]?.value ?: "Synced item", f["mime"]?.value ?: "application/octet-stream",
                        f["kind"]?.value ?: (f["contentKind"]?.value ?: "OTHER"), f["contentHash"]?.value, f["size"]?.value?.toLongOrNull() ?: 0, now,
                        f["recordedAt"]?.value?.toLongOrNull(), summary = f["summary"]?.value ?: "", durationMs = f["durationMs"]?.value?.toLongOrNull(),
                        contentKind = f["contentKind"]?.value ?: "OTHER", origin = CaptureOrigin.SYNC.name, syncEnabled = true, favorite = flag("favorite")))
                } else {
                    app.database.library().updateItem(existing.copy(name = f["name"]?.value ?: existing.name, summary = f["summary"]?.value ?: existing.summary,
                        favorite = f["favorite"]?.value?.toBooleanStrictOrNull() ?: existing.favorite,
                        state = if (existing.state == "SYNC_REMOVED") "RETAINED" else existing.state,
                        syncEnabled = if (existing.state == "SYNC_REMOVED") true else existing.syncEnabled))
                }
                // Website uploads ask for the on-phone analysis the website cannot run.
                if (f["needsAnalysis"]?.value == "1") app.database.jobs().enqueue(ContentEngine.newJob(id, now).copy(priority = JobPriority.CAPTURE))
                true
            }
            "inbox_item" -> {
                val item = InboxItem(id, f["contentKind"]?.value ?: "OTHER", f["title"]?.value ?: "", f["summary"]?.value ?: "", f["notes"]?.value ?: "",
                    f["originalRef"]?.value, f["origin"]?.value ?: "SYNC", lifecycle = f["lifecycle"]?.value ?: "ACTIVE", favorite = flag("favorite"),
                    processingState = f["processingState"]?.value ?: "PENDING", syncState = SyncState.SYNCED.name, backupState = f["backupState"]?.value ?: "NOT_BACKED_UP",
                    capturedAt = f["capturedAt"]?.value?.toLongOrNull() ?: now, updatedAt = now)
                if (app.database.inbox().item(id) == null) app.database.inbox().insert(item) else app.database.inbox().saveEdits(item)
                true
            }
            "media_bookmark" -> {
                val mediaId = f["mediaId"]?.value ?: return false
                if (app.database.library().item(mediaId) == null) return false
                app.database.playback().saveBookmark(MediaBookmark(id, mediaId, f["startMs"]?.value?.toLongOrNull() ?: 0, f["endMs"]?.value?.toLongOrNull(),
                    f["title"]?.value ?: "", f["note"]?.value ?: "", favorite = flag("favorite"), hidden = flag("hidden"),
                    origin = f["origin"]?.value ?: "USER", categoryId = f["categoryId"]?.value?.ifBlank { null }, createdAt = now, updatedAt = now))
                true
            }
            "media_category" -> {
                val category = MediaCategory(id, f["parentId"]?.value?.ifBlank { null }, f["name"]?.value ?: "Category",
                    f["origin"]?.value ?: "USER", flag("isPrivate"), f["policy"]?.value ?: "DEFAULT", createdAt = now, updatedAt = now)
                if (app.database.library().category(id) == null) app.database.library().insertCategory(category) else app.database.library().updateCategory(category)
                true
            }
            "media_analysis" -> {
                val mediaId = f["mediaId"]?.value ?: return false
                if (app.database.library().item(mediaId) == null) return false
                app.database.library().saveAnalysis(MediaAnalysis(id, mediaId, f["analyzer"]?.value ?: "sync", f["modelId"]?.value ?: "",
                    f["modelVersion"]?.value ?: "", f["pipelineVersion"]?.value?.toIntOrNull() ?: 1, f["state"]?.value ?: "COMPLETED",
                    f["rawJson"]?.value ?: "{}", f["editedJson"]?.value, f["text"]?.value ?: "", createdAt = now))
                true
            }
            "media_segment" -> {
                val mediaId = f["mediaId"]?.value ?: return false
                if (app.database.library().item(mediaId) == null) return false
                app.database.library().saveSegment(MediaSegment(id, mediaId, f["analysisId"]?.value?.ifBlank { null }, f["kind"]?.value ?: "SCENE",
                    f["startMs"]?.value?.toLongOrNull() ?: 0, f["endMs"]?.value?.toLongOrNull() ?: 0, f["title"]?.value ?: "", f["description"]?.value ?: "",
                    f["labels"]?.value ?: "[]", createdAt = now))
                true
            }
            "media_collection" -> {
                val collection = MediaCollection(id, f["name"]?.value ?: "Collection", f["kind"]?.value ?: "COLLECTION", f["description"]?.value ?: "",
                    f["coverItemId"]?.value?.ifBlank { null }, now, now)
                if (app.database.library().insertCollection(collection) == -1L) app.database.library().updateCollection(collection)
                true
            }
            "media_item_category" -> {
                val itemId = f["itemId"]?.value ?: return false
                val categoryId = f["categoryId"]?.value ?: return false
                if (app.database.library().item(itemId) == null || app.database.library().category(categoryId) == null) return false
                app.database.library().setCategory(ItemCategory(itemId, categoryId, f["origin"]?.value ?: "USER", f["state"]?.value ?: "ASSIGNED", updatedAt = now))
                true
            }
            "media_item_tag" -> {
                val itemId = f["itemId"]?.value ?: return false
                if (app.database.library().item(itemId) == null) return false
                app.database.library().setTag(ItemTag(itemId, f["tag"]?.value ?: return false, f["origin"]?.value ?: "USER", f["state"]?.value ?: "ASSIGNED", now))
                true
            }
            "collection_member" -> {
                val collectionId = f["collectionId"]?.value ?: return false
                val itemId = f["itemId"]?.value ?: return false
                if (app.database.library().item(itemId) == null) return false
                app.database.library().addToCollection(collectionId, itemId, now)
                true
            }
            "inbox_member" -> {
                val inboxId = f["inboxId"]?.value ?: return false
                val itemId = f["itemId"]?.value ?: return false
                if (app.database.inbox().item(inboxId) == null || app.database.library().item(itemId) == null) return false
                app.database.inbox().insertMember(InboxMember(inboxId, itemId, f["position"]?.value?.toIntOrNull() ?: 0))
                true
            }
            "drawer_message" -> {
                app.database.inbox().insertMessage(DrawerMessage(id, f["sessionId"]?.value ?: id, f["inboxId"]?.value?.ifBlank { null },
                    f["role"]?.value ?: "USER", f["text"]?.value ?: "", f["createdAt"]?.value?.toLongOrNull() ?: now, f["inContext"]?.value !in setOf("false", "0")))
                true
            }
            "action_record" -> {
                app.database.inbox().insertAction(ActionRecord(id, f["inboxId"]?.value?.ifBlank { null }, f["itemId"]?.value?.ifBlank { null },
                    f["actionType"]?.value ?: "NOTE", f["label"]?.value ?: "", f["payload"]?.value ?: "{}", f["state"]?.value ?: "PROPOSED", createdAt = now, updatedAt = now))
                true
            }
            "saved_location" -> {
                app.database.inbox().saveLocation(SavedLocation(id, f["inboxId"]?.value?.ifBlank { null }, f["itemId"]?.value?.ifBlank { null },
                    f["label"]?.value ?: "Place", f["latitude"]?.value?.toDoubleOrNull(), f["longitude"]?.value?.toDoubleOrNull(),
                    f["address"]?.value ?: "", f["sourceText"]?.value ?: "", f["note"]?.value ?: "", now, now))
                true
            }
            "transcript_segment" -> {
                val mediaId = f["mediaId"]?.value ?: return false
                val item = app.database.library().item(mediaId) ?: return false
                app.database.media().segment(TranscriptSegment(id, mediaId, item.sourceId ?: mediaId, f["startMs"]?.value?.toLongOrNull() ?: 0,
                    f["endMs"]?.value?.toLongOrNull() ?: 0, f["speaker"]?.value ?: "UNKNOWN", f["text"]?.value ?: ""))
                true
            }
            else -> true
        }
    } catch (_: Exception) { false }
}

/**
 * Remote removal hides retained originals; it never calls MediaVault.delete or deletes source evidence. The hidden
 * row keeps syncEnabled so the plaintext web mirror (managed rows with syncEnabled = 0) never picks it up.
 */
private suspend fun applyTombstone(app: HellFireApp, type: String, id: String): Boolean {
    val sql = app.database.openHelper.writableDatabase
    return try {
        when (type) {
            "media_item" -> sql.execSQL("UPDATE media_item SET state = 'SYNC_REMOVED' WHERE id = ?", arrayOf(id))
            "inbox_item" -> sql.execSQL("UPDATE inbox_item SET lifecycle = 'TRASHED', trashedAt = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), id))
            "media_item_category", "media_item_tag", "collection_member", "inbox_member" -> {
                val parts = id.split(":", limit = 2)
                if (parts.size != 2) return false
                val columns = when (type) {
                    "media_item_category" -> "itemId" to "categoryId"
                    "media_item_tag" -> "itemId" to "tag"
                    "collection_member" -> "collectionId" to "itemId"
                    else -> "inboxId" to "itemId"
                }
                sql.execSQL("DELETE FROM $type WHERE ${columns.first} = ? AND ${columns.second} = ?", parts.toTypedArray())
            }
            in LibrarySync.TYPES -> sql.execSQL("DELETE FROM $type WHERE id = ?", arrayOf(id))
            else -> return false
        }
        true
    } catch (_: Exception) { false }
}
