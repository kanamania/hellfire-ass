package com.hellfire.ass.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.*
import com.hellfire.ass.media.ChunkedMedia
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.TranscriptSegment
import com.hellfire.ass.security.PayloadCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class LibraryClient(private val url: String, private val token: String) : LibrarySync.BlobApi {
    fun get(path: String) = send("GET", path, null)
    fun post(path: String, body: JSONObject) = send("POST", path, body)
    override fun open(contentHash: String, size: Long, chunkSize: Int, chunkCount: Int) =
        post("/api/library/blobs", JSONObject().put("contentHash", contentHash).put("size", size).put("chunkSize", chunkSize).put("chunkCount", chunkCount))
    override fun putChunk(blobId: String, index: Int, sha256: String, bytes: ByteArray) =
        post("/api/library/blobs/$blobId/chunks/$index", JSONObject().put("sha256", sha256).put("bytes", Base64.getEncoder().encodeToString(bytes)))
    private fun send(method: String, path: String, body: JSONObject?): JSONObject {
        val connection = URI(url.trim().trimEnd('/') + path).toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= 2 * 1024 * 1024) { "Reply too large" }
                    output.write(buffer, 0, read)
                }
            }
            if (code == 413) throw LibrarySync.Quota()
            if (code !in 200..299) throw WebSync.Rejected(code)
            val text = output.toString("UTF-8")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
}

class LibrarySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        LibrarySync.mutex.withLock {
            val app = applicationContext as HellFireApp
            if (LibrarySync.paused(app)) return@withLock Result.success()
            val config = app.secrets.read(WebSync.CONFIG) ?: return@withLock Result.success()
            val pass = app.secrets.read(LibrarySync.PASS)?.optString("passphrase").orEmpty()
            if (pass.length !in 12..1024) {
                LibrarySync.status(app, "Add a library passphrase to encrypt sync")
                return@withLock Result.success()
            }
            val chars = pass.toCharArray()
            try {
                val client = LibraryClient(config.getString("url"), config.getString("token"))
                LibrarySync.status(app, "Syncing library")
                val state = client.get("/api/library/state")
                val epoch = state.optString("epoch")
                val cursorFile = app.secrets.read(LibrarySync.CURSOR) ?: JSONObject()
                if (epoch.isNotEmpty() && cursorFile.optString("epoch") != epoch) {
                    app.database.openHelper.writableDatabase.execSQL("UPDATE sync_record SET state = 'PENDING', nextAttemptAt = 0, uploadSession = NULL, localHash = NULL")
                    cursorFile.put("cursor", 0)
                }
                val saltText = state.optString("salt").ifEmpty {
                    val fresh = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    client.post("/api/library/salt", JSONObject().put("salt", Base64.getEncoder().encodeToString(fresh))).getString("salt")
                }
                val salt = Base64.getDecoder().decode(saltText)
                val cipher = LibrarySync.cipher(chars.copyOf(), salt)
                app.database.sync().recoverInterrupted()
                discover(app)
                push(app, client, cipher, salt)
                val cursor = pull(app, client, cipher, salt, cursorFile.optLong("cursor"))
                cursorFile.put("epoch", epoch).put("cursor", cursor)
                app.secrets.save(LibrarySync.CURSOR, cursorFile)
                LibrarySync.status(app, "Library synced")
                Result.success()
            } catch (_: LibrarySync.Quota) {
                LibrarySync.status(app, "Server storage quota is full")
                Result.success()
            } catch (e: WebSync.Rejected) {
                LibrarySync.status(app, if (e.status == 401) "The server refused the sync token" else "Library sync will retry (HTTP ${e.status})")
                if (e.status == 401) Result.failure() else Result.retry()
            } catch (_: Exception) {
                LibrarySync.status(app, "Library sync will retry")
                Result.retry()
            } finally { chars.fill('\u0000') }
        }
    }
}

private val pushOrder = listOf("media_category", "media_collection", "media_item", "inbox_item", "media_analysis", "media_segment",
    "media_bookmark", "media_item_category", "media_item_tag", "collection_member", "inbox_member", "drawer_message",
    "saved_location", "action_record", "transcript_segment")

private suspend fun discover(app: HellFireApp) {
    val db = app.database
    val sql = db.openHelper.readableDatabase
    val now = System.currentTimeMillis()
    val base = app.secrets.read(LibrarySync.BASE) ?: JSONObject()
    val tracked = mutableListOf<Pair<String, String>>()
    sql.query("SELECT entityType, entityId FROM sync_record WHERE deleted = 0").use { rows ->
        while (rows.moveToNext()) tracked += rows.getString(0) to rows.getString(1)
    }
    for ((type, id) in tracked) if (readFields(app, type, id) == null) db.sync().markChanged(type, id, true, now)
    val media = ids(sql, "SELECT id FROM media_item WHERE syncEnabled = 1 AND storage = 'MANAGED' AND state NOT IN ('DELETING', 'SYNC_REMOVED') LIMIT 40")
    for (id in media) queue(app, base, "media_item", id, now)
    if (media.isEmpty()) return
    val marks = media.joinToString(",") { "?" }
    child(app, base, sql, now, "media_analysis", "SELECT id FROM media_analysis WHERE mediaId IN ($marks)", media)
    child(app, base, sql, now, "media_segment", "SELECT id FROM media_segment WHERE mediaId IN ($marks)", media)
    child(app, base, sql, now, "media_bookmark", "SELECT id FROM media_bookmark WHERE mediaId IN ($marks)", media)
    child(app, base, sql, now, "transcript_segment", "SELECT id FROM transcript_segment WHERE mediaId IN ($marks)", media)
    child(app, base, sql, now, "media_item_tag", "SELECT itemId || ':' || tag FROM media_item_tag WHERE itemId IN ($marks)", media)
    child(app, base, sql, now, "media_item_category", "SELECT itemId || ':' || categoryId FROM media_item_category WHERE itemId IN ($marks)", media)
    val categories = ids(sql, "SELECT DISTINCT categoryId FROM media_item_category WHERE itemId IN ($marks)", media.toTypedArray())
    categories.forEach { queue(app, base, "media_category", it, now) }
    val collections = ids(sql, "SELECT DISTINCT collectionId FROM collection_member WHERE itemId IN ($marks)", media.toTypedArray())
    collections.forEach { queue(app, base, "media_collection", it, now) }
    child(app, base, sql, now, "collection_member", "SELECT collectionId || ':' || itemId FROM collection_member WHERE itemId IN ($marks)", media)
    val inbox = ids(sql, "SELECT id FROM inbox_item WHERE origin IN ('DRAWER', 'SHARE', 'SYNC') AND syncState != 'LOCAL_ONLY' AND lifecycle != 'DELETED' LIMIT 40")
    inbox.forEach { queue(app, base, "inbox_item", it, now) }
    if (inbox.isNotEmpty()) {
        val inMarks = inbox.joinToString(",") { "?" }
        child(app, base, sql, now, "inbox_member", "SELECT inboxId || ':' || itemId FROM inbox_member WHERE inboxId IN ($inMarks)", inbox)
        child(app, base, sql, now, "drawer_message", "SELECT id FROM drawer_message WHERE inboxId IN ($inMarks)", inbox)
        child(app, base, sql, now, "saved_location", "SELECT id FROM saved_location WHERE inboxId IN ($inMarks)", inbox)
        child(app, base, sql, now, "action_record", "SELECT id FROM action_record WHERE inboxId IN ($inMarks)", inbox)
    }
}

private suspend fun child(app: HellFireApp, base: JSONObject, sql: SupportSQLiteDatabase, now: Long, type: String, query: String, args: List<String>) {
    ids(sql, query, args.toTypedArray()).forEach { queue(app, base, type, it, now) }
}

private suspend fun queue(app: HellFireApp, base: JSONObject, type: String, id: String, now: Long) {
    if (type !in LibrarySync.TYPES) return
    val fields = readFields(app, type, id) ?: return
    val hash = LibrarySync.sha256(LibrarySync.canonical(LibrarySync.doc(LibrarySync.stamp(fields, clocks(base, type, id), now))))
    val existing = app.database.sync().record(type, id)
    if (existing?.deleted == true || existing?.localHash == hash) return
    app.database.sync().markChanged(type, id, false, now)
}

private fun ids(sql: SupportSQLiteDatabase, query: String, args: Array<String> = emptyArray()): List<String> {
    val out = mutableListOf<String>()
    sql.query(query, args).use { cursor -> while (cursor.moveToNext()) out += cursor.getString(0) }
    return out
}

private suspend fun push(app: HellFireApp, client: LibraryClient, cipher: PayloadCipher, salt: ByteArray) {
    val now = System.currentTimeMillis()
    val due = app.database.sync().due(now, 20).sortedBy { pushOrder.indexOf(it.entityType).let { index -> if (index < 0) 99 else index } }
    val device = deviceId(app)
    val base = app.secrets.read(LibrarySync.BASE) ?: JSONObject()
    for (record in due) {
        val fields = readFields(app, record.entityType, record.entityId)
        if (fields == null && !record.deleted) {
            app.database.sync().markChanged(record.entityType, record.entityId, true, now)
            continue
        }
        val clocks = clocks(base, record.entityType, record.entityId)
        val stamped = outgoingDoc(record, fields, clocks).fields
        var blobId = stamped["blobId"]?.value
        val item = if (record.entityType == "media_item") app.database.library().item(record.entityId) else null
        if (!record.deleted && item != null && LibrarySync.includeMedia(item.syncEnabled, item.storage) && item.size > 0 && !item.contentHash.isNullOrBlank()) {
            val hash = item.contentHash
            app.database.sync().progress(record.entityType, record.entityId, item.size, record.bytesDone, record.uploadSession)
            ChunkedMedia.Reader(app.media.file(item.id), app.cipher, item.id, item.size).use { reader ->
                blobId = LibrarySync.upload(client, cipher, item.size, hash) { index ->
                    val start = index.toLong() * LibrarySync.CHUNK
                    val length = minOf(LibrarySync.CHUNK.toLong(), item.size - start).toInt()
                    val bytes = ByteArray(length)
                    var filled = 0
                    while (filled < length) {
                        val n = reader.readAt(start + filled, bytes, filled, length - filled)
                        if (n < 0) break
                        filled += n
                    }
                    require(filled == length)
                    bytes
                }
            }
            app.database.sync().progress(record.entityType, record.entityId, item.size, item.size, blobId)
        }
        val docFields = stamped.toMutableMap()
        if (!blobId.isNullOrBlank()) docFields["blobId"] = LibrarySync.Field(blobId, stamped["blobId"]?.updatedAt ?: record.updatedAt)
        val doc = LibrarySync.doc(docFields, record.deleted, record.updatedAt)
        val body = LibrarySync.canonical(doc)
        val sealed = cipher.encrypt(body.toByteArray(Charsets.UTF_8), LibrarySync.aad("record", "${record.entityType}:${record.entityId}"))
        val reply = client.post("/api/library/records", JSONObject().put("records", JSONArray().put(JSONObject()
            .put("entityType", record.entityType).put("entityId", record.entityId).put("baseVersion", record.version)
            .put("deleted", record.deleted).put("updatedAt", doc.updatedAt).put("deviceId", device)
            .put("envelope", LibrarySync.envelope(salt, sealed)))))
        val accepted = reply.optJSONArray("accepted")
        if (accepted != null && accepted.length() == 1) {
            val version = accepted.getJSONObject(0).getLong("version")
            app.database.sync().synced(record.entityType, record.entityId, record.entityId, version, LibrarySync.sha256(body), doc.updatedAt, now)
            saveClocks(base, record.entityType, record.entityId, doc)
            if (record.entityType == "inbox_item") app.database.inbox().setSyncState(record.entityId, SyncState.SYNCED.name, now)
        } else {
            val conflicts = reply.optJSONArray("conflicts")
            if (conflicts != null && conflicts.length() > 0) noteConflict(app, cipher, record, doc, conflicts.getJSONObject(0), now)
            else app.database.sync().failed(record.entityType, record.entityId, "rejected", now + LibrarySync.backoffMs(record.attempts + 1))
        }
    }
    app.secrets.save(LibrarySync.BASE, base)
}

private suspend fun pull(app: HellFireApp, client: LibraryClient, cipher: PayloadCipher, salt: ByteArray, after: Long): Long {
    val device = deviceId(app)
    var cursor = after
    val page = client.get("/api/library/changes?after=$cursor&limit=50")
    val records = page.optJSONArray("records") ?: return cursor
    val base = app.secrets.read(LibrarySync.BASE) ?: JSONObject()
    for (i in 0 until records.length()) {
        val row = records.getJSONObject(i)
        if (row.optString("deviceId") == device) { cursor = row.getLong("changeSeq"); continue }
        val type = row.getString("entityType")
        val id = row.getString("entityId")
        if (type !in LibrarySync.TYPES) continue
        val envelope = row.getJSONObject("envelope")
        val sealed = Base64.getDecoder().decode(envelope.getString("ct"))
        val remote = LibrarySync.parse(cipher.decrypt(sealed, LibrarySync.aad("record", "$type:$id")).toString(Charsets.UTF_8))
            .copy(deleted = row.optBoolean("deleted"), updatedAt = row.getLong("updatedAt"))
        if (type == "media_item" && !remote.deleted) {
            val blob = remote.fields["blobId"]?.value
            val hash = remote.fields["contentHash"]?.value
            val size = remote.fields["size"]?.value?.toLongOrNull() ?: 0
            val existing = app.database.library().item(id)
            if (!blob.isNullOrBlank() && !hash.isNullOrBlank() && size > 0 && existing?.storage != "MANAGED") {
                if (!download(app, client, cipher, id, blob, hash, size, remote.fields["name"]?.value ?: "Synced item", remote.fields["mime"]?.value ?: "application/octet-stream")) return cursor
            }
        }
        val localFields = readFields(app, type, id)
        val prior = baseDoc(base, type, id)
        val tracked = app.database.sync().record(type, id)
        val local = if (localFields == null) LibrarySync.Doc(deleted = tracked?.deleted == true, updatedAt = tracked?.updatedAt ?: 0)
            else LibrarySync.doc(LibrarySync.stamp(localFields, clocks(base, type, id), prior.updatedAt), tracked?.deleted == true, tracked?.updatedAt ?: prior.updatedAt)
        val merged = LibrarySync.merge(prior, local, remote)
        if (merged.conflicts.isNotEmpty()) {
            app.database.sync().saveConflict(SyncConflict(UUID.randomUUID().toString(), type, id, LibrarySync.canonical(local), LibrarySync.canonical(remote), System.currentTimeMillis()))
        }
        if (!applyDoc(app, type, id, merged.doc)) return cursor
        val now = System.currentTimeMillis()
        val status = app.database.sync().record(type, id)
        app.database.sync().save((status ?: SyncRecord(type, id, updatedAt = now)).copy(deleted = merged.doc.deleted))
        app.database.sync().synced(type, id, id, row.getLong("version"), LibrarySync.sha256(LibrarySync.canonical(merged.doc)), remote.updatedAt, now)
        saveClocks(base, type, id, merged.doc)
        if (type == "media_item" && merged.doc.fields["needsAnalysis"]?.value == "1") {
            app.database.jobs().enqueue(ProcessingJob(UUID.randomUUID().toString(), "ANALYZE", id, priority = JobPriority.CAPTURE, createdAt = now, updatedAt = now))
        }
        cursor = row.getLong("changeSeq")
    }
    app.secrets.save(LibrarySync.BASE, base)
    return cursor
}

private suspend fun noteConflict(app: HellFireApp, cipher: PayloadCipher, record: com.hellfire.ass.library.SyncRecord, local: LibrarySync.Doc, remoteRow: JSONObject, now: Long) {
    val envelope = remoteRow.getJSONObject("envelope")
    val sealed = Base64.getDecoder().decode(envelope.getString("ct"))
    val remote = LibrarySync.parse(cipher.decrypt(sealed, LibrarySync.aad("record", "${record.entityType}:${record.entityId}")).toString(Charsets.UTF_8))
    app.database.sync().saveConflict(SyncConflict(UUID.randomUUID().toString(), record.entityType, record.entityId, LibrarySync.canonical(local), LibrarySync.canonical(remote), now))
    val existing = app.database.sync().record(record.entityType, record.entityId) ?: return
    app.database.sync().save(existing.copy(version = remoteRow.getLong("version"), state = "PENDING", localHash = null, error = "conflict", updatedAt = now))
}

private fun deviceId(app: HellFireApp): String {
    val saved = app.secrets.read(LibrarySync.DEVICE)
    if (saved != null) return saved.getString("id")
    val id = UUID.randomUUID().toString()
    app.secrets.save(LibrarySync.DEVICE, JSONObject().put("id", id))
    return id
}

private fun clocks(base: JSONObject, type: String, id: String): Map<String, LibrarySync.Clock> =
    LibrarySync.clocks(baseDoc(base, type, id).fields)

private fun baseDoc(base: JSONObject, type: String, id: String): LibrarySync.Doc {
    val text = base.optJSONObject("$type/$id")?.optString("doc").orEmpty()
    return if (text.isEmpty()) LibrarySync.Doc() else runCatching { LibrarySync.parse(text) }.getOrDefault(LibrarySync.Doc())
}

private fun saveClocks(base: JSONObject, type: String, id: String, doc: LibrarySync.Doc) {
    base.put("$type/$id", JSONObject().put("doc", LibrarySync.canonical(doc)))
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

private suspend fun download(app: HellFireApp, client: LibraryClient, cipher: PayloadCipher, id: String, blobId: String, hash: String, size: Long, name: String, mime: String): Boolean {
    val context = currentCoroutineContext()
    return try {
        LibrarySync.chunkCount(size)
        SyncChunkInput(size) { index ->
            context.ensureActive()
            val chunk = client.get("/api/library/blobs/$blobId/chunks/$index")
            val bytes = Base64.getDecoder().decode(chunk.getString("bytes"))
            require(LibrarySync.sha256(bytes) == chunk.getString("sha256"))
            cipher.decrypt(bytes, LibrarySync.aad("chunk", "$hash:$index"))
        }.use { app.media.adoptSynced(id, it, name, mime, hash) }
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

private suspend fun readFields(app: HellFireApp, type: String, id: String): Map<String, String>? = when (type) {
    "media_item" -> app.database.library().item(id)?.let { item ->
        mapOf("name" to item.name, "mime" to item.mime, "kind" to item.kind, "contentKind" to item.contentKind,
            "size" to item.size.toString(), "contentHash" to item.contentHash.orEmpty(), "summary" to item.summary,
            "favorite" to item.favorite.toString(), "origin" to item.origin, "durationMs" to (item.durationMs ?: 0).toString(),
            "syncEnabled" to item.syncEnabled.toString())
    }
    "inbox_item" -> app.database.inbox().item(id)?.let { item ->
        mapOf("title" to item.title, "summary" to item.summary, "notes" to item.notes, "contentKind" to item.contentKind,
            "origin" to item.origin, "lifecycle" to item.lifecycle, "favorite" to item.favorite.toString(),
            "processingState" to item.processingState, "syncState" to item.syncState, "backupState" to item.backupState,
            "capturedAt" to item.capturedAt.toString(), "originalRef" to item.originalRef.orEmpty())
    }
    "media_bookmark" -> app.database.openHelper.readableDatabase.row("SELECT mediaId, startMs, endMs, title, note, favorite, hidden, origin FROM media_bookmark WHERE id = ?", id)
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
                        contentKind = f["contentKind"]?.value ?: "OTHER", origin = CaptureOrigin.SYNC.name, syncEnabled = true, favorite = f["favorite"]?.value == "true"))
                } else {
                    app.database.library().updateItem(existing.copy(name = f["name"]?.value ?: existing.name, summary = f["summary"]?.value ?: existing.summary,
                        favorite = f["favorite"]?.value?.toBooleanStrictOrNull() ?: existing.favorite,
                        state = if (existing.state == "SYNC_REMOVED") "RETAINED" else existing.state,
                        syncEnabled = if (existing.state == "SYNC_REMOVED") true else existing.syncEnabled))
                }
                if (f["needsAnalysis"]?.value == "1") app.database.jobs().enqueue(ProcessingJob(UUID.randomUUID().toString(), "ANALYZE", id, priority = JobPriority.CAPTURE, createdAt = now, updatedAt = now))
                true
            }
            "inbox_item" -> {
                val item = InboxItem(id, f["contentKind"]?.value ?: "OTHER", f["title"]?.value ?: "", f["summary"]?.value ?: "", f["notes"]?.value ?: "",
                    f["originalRef"]?.value, f["origin"]?.value ?: "SYNC", lifecycle = f["lifecycle"]?.value ?: "ACTIVE", favorite = f["favorite"]?.value == "true",
                    processingState = f["processingState"]?.value ?: "PENDING", syncState = SyncState.SYNCED.name, backupState = f["backupState"]?.value ?: "NOT_BACKED_UP",
                    capturedAt = f["capturedAt"]?.value?.toLongOrNull() ?: now, updatedAt = now)
                if (app.database.inbox().item(id) == null) app.database.inbox().insert(item) else app.database.inbox().saveEdits(item)
                true
            }
            "media_bookmark" -> {
                val mediaId = f["mediaId"]?.value ?: return false
                if (app.database.library().item(mediaId) == null) return false
                app.database.playback().saveBookmark(MediaBookmark(id, mediaId, f["startMs"]?.value?.toLongOrNull() ?: 0, f["endMs"]?.value?.toLongOrNull(),
                    f["title"]?.value ?: "", f["note"]?.value ?: "", favorite = f["favorite"]?.value == "true", hidden = f["hidden"]?.value == "true",
                    origin = f["origin"]?.value ?: "USER", createdAt = now, updatedAt = now))
                true
            }
            "media_category" -> {
                val category = MediaCategory(id, f["parentId"]?.value?.ifBlank { null }, f["name"]?.value ?: "Category",
                    f["origin"]?.value ?: "USER", f["isPrivate"]?.value == "true", f["policy"]?.value ?: "DEFAULT", createdAt = now, updatedAt = now)
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
                    f["role"]?.value ?: "USER", f["text"]?.value ?: "", f["createdAt"]?.value?.toLongOrNull() ?: now, f["inContext"]?.value != "false"))
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

/** Remote removal hides retained originals; it never calls MediaVault.delete or deletes source evidence. */
private suspend fun applyTombstone(app: HellFireApp, type: String, id: String): Boolean {
    val sql = app.database.openHelper.writableDatabase
    return try {
        when (type) {
            "media_item" -> sql.execSQL("UPDATE media_item SET state = 'SYNC_REMOVED', syncEnabled = 0 WHERE id = ?", arrayOf(id))
            "inbox_item" -> sql.execSQL("UPDATE inbox_item SET lifecycle = 'TRASHED', trashedAt = ? WHERE id = ?", arrayOf(System.currentTimeMillis(), id))
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
