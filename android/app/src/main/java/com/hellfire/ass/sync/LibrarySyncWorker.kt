package com.hellfire.ass.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.intelligence.ContentEngine
import com.hellfire.ass.library.*
import com.hellfire.ass.media.ChunkedMedia
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.TranscriptSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** The encrypted library routes of web/api. */
interface LibraryApi : LibrarySync.BlobApi {
    fun get(path: String): JSONObject
    fun post(path: String, body: JSONObject): JSONObject
    override fun open(contentHash: String, size: Long, chunkSize: Int, chunkCount: Int) =
        post("/api/library/blobs", JSONObject().put("contentHash", contentHash).put("size", size).put("chunkSize", chunkSize).put("chunkCount", chunkCount))
    override fun putChunk(blobId: String, index: Int, sha256: String, bytes: ByteArray) =
        post("/api/library/blobs/$blobId/chunks/$index", JSONObject().put("sha256", sha256).put("bytes", Base64.getEncoder().encodeToString(bytes)))
}

class LibraryClient(private val url: String, private val token: String) : LibraryApi {
    override fun get(path: String) = send("GET", path, null)
    override fun post(path: String, body: JSONObject) = send("POST", path, body)

    /** A rate-limited (429) call waits and tries again a few times before the run gives up. */
    private fun send(method: String, path: String, body: JSONObject?): JSONObject {
        var attempt = 0
        while (true) {
            try { return once(method, path, body) } catch (e: WebSync.Rejected) {
                if (e.status != 429 || attempt >= 3) throw e
                Thread.sleep(2_000L shl attempt++)
            }
        }
    }

    private fun once(method: String, path: String, body: JSONObject?): JSONObject {
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
                    require(output.size() + read <= 4 * 1024 * 1024) { "Reply too large" }
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
            if (LibrarySync.isPaused(app)) return@withLock Result.success()
            val config = app.secrets.read(WebSync.CONFIG) ?: return@withLock Result.success()
            val pass = app.secrets.read(LibrarySync.PASS)?.optString("passphrase").orEmpty()
            if (pass.length !in 12..1024) {
                LibrarySync.status(app, "Add a library passphrase to encrypt sync")
                return@withLock Result.success()
            }
            val chars = pass.toCharArray()
            try {
                LibrarySync.status(app, "Syncing library")
                LibrarySync.status(app, LibrarySyncEngine(app, LibraryClient(config.getString("url"), config.getString("token"))).run(chars))
                Result.success()
            } catch (cancelled: CancellationException) {
                LibrarySync.status(app, if (LibrarySync.isPaused(app)) "Paused" else "Library sync stopped; it continues next time")
                throw cancelled
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

/** Remote tombstones hide these rows instead of deleting them, so they must not be revived by a local scan. */
private val hiddenByTombstone = setOf("media_item", "inbox_item")

/**
 * One sync pass: verify the passphrase against the server key check, find local changes, pull (and merge) remote
 * changes, then push. Work is bounded by [budgetMs]; whatever is left continues on the next run.
 */
internal class LibrarySyncEngine(
    private val app: HellFireApp,
    private val api: LibraryApi,
    private val derive: (CharArray, ByteArray) -> LibrarySync.Keys = LibrarySync::keys,
    private val clock: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = 8 * 60_000L,
) {
    enum class Outcome { APPLIED, SKIPPED, PARKED, UNREADABLE }
    private class Out(val record: SyncRecord, val remoteId: String, val fields: Map<String, String>?, val doc: LibrarySync.Doc, val json: JSONObject, val size: Int)

    private val db get() = app.database
    private lateinit var keys: LibrarySync.Keys
    private lateinit var salt: ByteArray
    private lateinit var device: String
    private var base = JSONObject()
    private var parked = JSONObject()
    private var deadline = 0L
    private var pushed = 0
    private var pulled = 0
    private var failed = 0

    suspend fun run(passphrase: CharArray): String {
        deadline = clock() + budgetMs
        val state = api.get("/api/library/state")
        val epoch = state.optString("epoch")
        var cursorFile = app.secrets.read(LibrarySync.CURSOR) ?: JSONObject()
        if (epoch.isNotEmpty() && cursorFile.optString("epoch") != epoch) {
            resetLibrarySyncState(app)
            cursorFile = JSONObject()
        }
        base = app.secrets.read(LibrarySync.BASE) ?: JSONObject()
        parked = runCatching { JSONObject(parkedFile(app).readText()) }.getOrDefault(JSONObject())
        val saltText = state.optStringOrNull("salt") ?: api.post("/api/library/salt",
            JSONObject().put("salt", Base64.getEncoder().encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }))).getString("salt")
        salt = Base64.getDecoder().decode(saltText)
        keys = derive(passphrase.copyOf(), salt)
        val check = state.optStringOrNull("keyCheck") ?: api.post("/api/library/key-check", JSONObject().put("keyCheck", LibrarySync.keyCheck(keys.cipher))).getString("keyCheck")
        if (!LibrarySync.checkKey(keys.cipher, check))
            return "The passphrase on this phone does not match the server library. Nothing was sent. Enter the passphrase used before, or replace the server library."
        device = deviceId(app)
        db.sync().recoverInterrupted()
        db.sync().resumeAll()
        discover()
        val cursor = pull(cursorFile.optLong("cursor")) { cursorFile.put("epoch", epoch).put("cursor", it); app.secrets.save(LibrarySync.CURSOR, cursorFile) }
        cursorFile.put("epoch", epoch).put("cursor", cursor)
        app.secrets.save(LibrarySync.CURSOR, cursorFile)
        retryParked()
        push()
        db.sync().purgeSentTombstones()
        saveState()
        val unreadable = marked(app, UNREADABLE).size
        val stuck = marked(app, STUCK).size
        return buildList {
            add("Library synced")
            if (pulled > 0) add("$pulled received")
            if (pushed > 0) add("$pushed sent")
            if (failed > 0) add("$failed failed")
            if (parked.length() > 0) add("${parked.length()} waiting for related items")
            if (unreadable > 0) add("$unreadable could not be decrypted with this passphrase")
            if (stuck > 0) add("$stuck could not be applied because a related item never arrived")
            if (clock() >= deadline) add("more remains for the next run")
        }.joinToString(" · ")
    }

    private fun saveState() {
        app.secrets.save(LibrarySync.BASE, base)
        parkedFile(app).writeText(parked.toString())
    }

    // ---- local change discovery ----

    /** Pages through every in-scope row from a persisted cursor, so no item is ever starved behind earlier ones. */
    private suspend fun discover() {
        val stop = minOf(deadline, clock() + 60_000)
        val now = clock()
        scan("tracked", stop, 200, "SELECT entityType || char(31) || entityId FROM sync_record WHERE deleted = 0 AND entityType || char(31) || entityId > ? " +
            "ORDER BY entityType || char(31) || entityId LIMIT 200") { keys ->
            for (key in keys) {
                val (type, id) = key.split('\u001f', limit = 2)
                if (readFields(type, id) == null) db.sync().markChanged(type, id, true, now)
            }
        }
        scan("media", stop, 25, "SELECT id FROM media_item WHERE syncEnabled = 1 AND storage = 'MANAGED' AND state NOT IN ('DELETING', 'SYNC_REMOVED') " +
            "AND id > ? ORDER BY id LIMIT 25") { media -> queueMedia(media, now) }
        scan("inbox", stop, 25, "SELECT id FROM inbox_item WHERE origin IN ('DRAWER', 'SHARE', 'SYNC') AND syncState != 'LOCAL_ONLY' AND lifecycle != 'DELETED' " +
            "AND id > ? ORDER BY id LIMIT 25") { inbox -> queueInbox(inbox, now) }
    }

    private suspend fun scan(name: String, stop: Long, page: Int, sql: String, each: suspend (List<String>) -> Unit) {
        val prefs = app.getSharedPreferences(LibrarySync.PREFS, Context.MODE_PRIVATE)
        var after = prefs.getString("scan-$name", "").orEmpty()
        while (clock() < stop) {
            currentCoroutineContext().ensureActive()
            val ids = ids(sql, arrayOf(after))
            if (ids.isNotEmpty()) each(ids)
            after = if (ids.size < page) "" else ids.last()
            prefs.edit().putString("scan-$name", after).apply()
            if (after.isEmpty()) return
        }
    }

    private suspend fun queueMedia(media: List<String>, now: Long) {
        media.forEach { queue("media_item", it, now) }
        val marks = media.joinToString(",") { "?" }
        val args = media.toTypedArray()
        children(now, "media_analysis", "SELECT id FROM media_analysis WHERE mediaId IN ($marks)", args)
        children(now, "media_segment", "SELECT id FROM media_segment WHERE mediaId IN ($marks)", args)
        children(now, "media_bookmark", "SELECT id FROM media_bookmark WHERE mediaId IN ($marks)", args)
        children(now, "transcript_segment", "SELECT id FROM transcript_segment WHERE mediaId IN ($marks)", args)
        children(now, "media_item_tag", "SELECT itemId || ':' || tag FROM media_item_tag WHERE itemId IN ($marks)", args)
        children(now, "media_category", "SELECT DISTINCT categoryId FROM media_item_category WHERE itemId IN ($marks)", args)
        children(now, "media_item_category", "SELECT itemId || ':' || categoryId FROM media_item_category WHERE itemId IN ($marks)", args)
        children(now, "media_collection", "SELECT DISTINCT collectionId FROM collection_member WHERE itemId IN ($marks)", args)
        children(now, "collection_member", "SELECT collectionId || ':' || itemId FROM collection_member WHERE itemId IN ($marks)", args)
    }

    private suspend fun queueInbox(inbox: List<String>, now: Long) {
        inbox.forEach { queue("inbox_item", it, now) }
        val marks = inbox.joinToString(",") { "?" }
        val args = inbox.toTypedArray()
        children(now, "inbox_member", "SELECT inboxId || ':' || itemId FROM inbox_member WHERE inboxId IN ($marks)", args)
        children(now, "drawer_message", "SELECT id FROM drawer_message WHERE inboxId IN ($marks)", args)
        children(now, "saved_location", "SELECT id FROM saved_location WHERE inboxId IN ($marks)", args)
        children(now, "action_record", "SELECT id FROM action_record WHERE inboxId IN ($marks)", args)
    }

    private suspend fun children(now: Long, type: String, sql: String, args: Array<String>) = ids(sql, args).forEach { queue(type, it, now) }

    /** Queues a row that is new or changed since it last synced. Rows already queued keep their retry backoff. */
    private suspend fun queue(type: String, id: String, now: Long) {
        val fields = readFields(type, id) ?: return
        val existing = db.sync().record(type, id)
        when {
            existing == null -> db.sync().markChanged(type, id, false, now)
            existing.deleted -> if (type !in hiddenByTombstone) db.sync().markChanged(type, id, false, now)
            existing.state == SyncState.SYNCED.name && existing.localHash != LibrarySync.fieldsHash(fields) -> db.sync().markChanged(type, id, false, now)
        }
    }

    private fun ids(query: String, args: Array<String> = emptyArray()): List<String> {
        val out = mutableListOf<String>()
        db.openHelper.readableDatabase.query(query, args).use { cursor -> while (cursor.moveToNext()) out += cursor.getString(0) }
        return out
    }

    // ---- pull ----

    private suspend fun pull(start: Long, save: (Long) -> Unit): Long {
        var cursor = start
        while (clock() < deadline) {
            val records = api.get("/api/library/changes?after=$cursor&limit=100").optJSONArray("records") ?: break
            for (i in 0 until records.length()) {
                currentCoroutineContext().ensureActive()
                val row = records.getJSONObject(i)
                if (row.optString("deviceId") != device) incoming(row)
                cursor = row.getLong("changeSeq")
            }
            saveState()
            save(cursor)
            if (records.length() < 100) break
        }
        return cursor
    }

    /** Rows whose parent has not arrived yet are retried here, after the rest of the feed, until nothing more applies. */
    private suspend fun retryParked() {
        val counted = mutableSetOf<String>()
        repeat(5) {
            val rows = parked.keys().asSequence().toList().map { parked.getJSONObject(it).getJSONObject("row") }.sortedBy { it.optLong("changeSeq") }
            if (rows.isEmpty()) return
            var progress = false
            for (row in rows) {
                val key = parkKey(row)
                val outcome = incoming(row, count = key !in counted)
                counted += key
                if (outcome != Outcome.PARKED) progress = true
            }
            if (!progress) return
        }
    }

    /** Decrypts, merges and applies one remote record. Used for the change feed and for push conflicts. */
    internal suspend fun incoming(row: JSONObject, count: Boolean = true): Outcome {
        val type = row.getString("entityType")
        val remoteId = row.getString("entityId")
        if (type !in LibrarySync.TYPES) return Outcome.SKIPPED
        val opened = runCatching {
            val sealed = Base64.getDecoder().decode(row.getJSONObject("envelope").getString("ct"))
            LibrarySync.parseBody(keys.cipher.decrypt(sealed, LibrarySync.aad("record", "$type:$remoteId")).toString(Charsets.UTF_8))
        }.getOrNull()?.takeIf { (id, _) -> keys.remoteId(type, id) == remoteId }
        if (opened == null) {
            mark(app, UNREADABLE, "$type/$remoteId")
            unpark(row)
            return Outcome.UNREADABLE
        }
        val (id, sealedDoc) = opened
        val remote = sealedDoc.copy(deleted = row.optBoolean("deleted"), updatedAt = row.getLong("updatedAt"))
        val version = row.getLong("version")
        val tracked = db.sync().record(type, id)
        if (tracked != null && tracked.version >= version && tracked.remoteId == remoteId) { unpark(row); return Outcome.SKIPPED }
        if (type == "media_item" && !remote.deleted && !download(id, remote)) return park(row, count)
        val prior = baseDoc(type, id)
        val localFields = readFields(type, id)
        val localAt = tracked?.updatedAt ?: 0
        val local = if (localFields == null) LibrarySync.Doc(deleted = tracked?.deleted == true, updatedAt = localAt)
            else LibrarySync.doc(LibrarySync.stamp(localFields, LibrarySync.clocks(prior.fields), localAt), tracked?.deleted == true, localAt)
        val merged = LibrarySync.merge(prior, local, remote)
        if (!applyDoc(app, type, id, merged.doc)) return park(row, count)
        val now = clock()
        if (merged.conflicts.isNotEmpty())
            db.sync().saveConflict(SyncConflict(UUID.randomUUID().toString(), type, id, LibrarySync.canonical(local), LibrarySync.canonical(remote), now))
        setBase(type, id, remote)
        val record = tracked ?: SyncRecord(type, id, updatedAt = now)
        if (LibrarySync.sameValues(merged.doc, remote)) {
            db.sync().save(record.copy(deleted = merged.doc.deleted))
            db.sync().synced(type, id, remoteId, version, readFields(type, id)?.let(LibrarySync::fieldsHash), remote.updatedAt, now)
        } else {
            // This phone still holds changes the server lacks: send the merged result on top of the remote version.
            db.sync().save(record.copy(state = SyncState.PENDING.name, deleted = merged.doc.deleted, remoteId = remoteId, version = version,
                localHash = null, attempts = 0, nextAttemptAt = 0, error = null))
        }
        pulled++
        unpark(row)
        return Outcome.APPLIED
    }

    private fun parkKey(row: JSONObject) = "${row.getString("entityType")}/${row.getString("entityId")}"

    private fun park(row: JSONObject, count: Boolean): Outcome {
        val key = parkKey(row)
        val previous = parked.optJSONObject(key)
        if (previous != null && previous.getJSONObject("row").getLong("version") > row.getLong("version")) return Outcome.PARKED
        val attempts = (previous?.optInt("attempts") ?: 0) + if (count) 1 else 0
        if (attempts > 20) {
            // A parent that never arrives (or an original that never downloads) stops being retried every run.
            parked.remove(key)
            mark(app, STUCK, key)
            return Outcome.UNREADABLE
        }
        parked.put(key, JSONObject().put("row", row).put("attempts", attempts))
        return Outcome.PARKED
    }

    private fun unpark(row: JSONObject) {
        val key = parkKey(row)
        val entry = parked.optJSONObject(key) ?: return
        if (entry.getJSONObject("row").getLong("version") <= row.getLong("version")) parked.remove(key)
    }

    // ---- push ----

    private suspend fun push() {
        while (clock() < deadline) {
            val due = db.sync().due(clock(), 25).sortedBy { pushOrder.indexOf(it.entityType).let { index -> if (index < 0) 99 else index } }
            if (due.isEmpty()) return
            val batch = mutableListOf<Out>()
            for (record in due) {
                currentCoroutineContext().ensureActive()
                prepare(record)?.let { out ->
                    if (batch.isNotEmpty() && batch.sumOf { it.size } + out.size > 2_500_000) { send(batch); batch.clear() }
                    batch += out
                }
            }
            if (batch.isNotEmpty()) send(batch)
            saveState()
        }
    }

    private suspend fun failRecord(record: SyncRecord, error: String) {
        failed++
        db.sync().failed(record.entityType, record.entityId, error, clock() + LibrarySync.backoffMs(record.attempts + 1))
    }

    private suspend fun prepare(record: SyncRecord): Out? {
        val type = record.entityType
        val id = record.entityId
        val fields = if (record.deleted) null else readFields(type, id)
        if (!record.deleted && fields == null) {
            db.sync().markChanged(type, id, true, clock())
            return null
        }
        val remoteId = keys.remoteId(type, id)
        val prior = baseDoc(type, id)
        val stamped = outgoingDoc(record, fields, LibrarySync.clocks(prior.fields)).fields.toMutableMap()
        var blobId: String? = null
        val item = if (type == "media_item" && !record.deleted) db.library().item(id) else null
        val hash = item?.contentHash
        if (item != null && LibrarySync.includeMedia(item.syncEnabled, item.storage) && item.size > 0 && !hash.isNullOrBlank()) {
            blobId = prior.fields["blobId"]?.value?.takeIf { prior.fields["contentHash"]?.value == hash }
            if (blobId == null) {
                if (!app.media.file(id).exists()) { failRecord(record, "The original is missing on this phone"); return null }
                blobId = try {
                    ChunkedMedia.Reader(app.media.file(id), app.cipher, id, item.size).use { reader ->
                        LibrarySync.upload(api, keys.cipher, item.size, hash, keys.blobKey(hash),
                            progress = { blob, done -> db.sync().progress(type, id, item.size, done, blob); currentCoroutineContext().ensureActive() }) { index ->
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
                } catch (e: WebSync.Rejected) {
                    if (e.status in 400..499 && e.status != 401 && e.status != 429) { failRecord(record, "The server refused the original (HTTP ${e.status})"); return null }
                    throw e
                }
            }
            stamped["blobId"] = LibrarySync.Field(blobId, prior.fields["blobId"]?.takeIf { it.value == blobId }?.updatedAt ?: record.updatedAt)
        }
        val doc = LibrarySync.doc(stamped, record.deleted, record.updatedAt)
        val sealed = keys.cipher.encrypt(LibrarySync.body(id, doc).toByteArray(Charsets.UTF_8), LibrarySync.aad("record", "$type:$remoteId"))
        val envelope = LibrarySync.envelope(salt, sealed)
        val size = envelope.toString().length
        if (size > LibrarySync.MAX_ENVELOPE) { failRecord(record, "Too large to sync (${size / 1024} KiB encrypted; the limit is ${LibrarySync.MAX_ENVELOPE / 1024} KiB)"); return null }
        val json = JSONObject().put("entityType", type).put("entityId", remoteId).put("baseVersion", record.version)
            .put("deleted", record.deleted).put("updatedAt", doc.updatedAt).put("deviceId", device).put("envelope", envelope)
        if (blobId != null) json.put("blobId", blobId)
        return Out(record, remoteId, fields, doc, json, size)
    }

    private suspend fun send(batch: List<Out>) {
        val reply = api.post("/api/library/records", JSONObject().put("records", JSONArray(batch.map { it.json })))
        val accepted = reply.optJSONArray("accepted").rows().associateBy { "${it.getString("entityType")}/${it.getString("entityId")}" }
        val conflicts = reply.optJSONArray("conflicts").rows().associateBy { "${it.getString("entityType")}/${it.getString("entityId")}" }
        val rejected = reply.optJSONArray("rejected").rows().associateBy { "${it.optString("entityType")}/${it.optString("entityId")}" }
        val now = clock()
        for (out in batch) {
            val record = out.record
            val key = "${record.entityType}/${out.remoteId}"
            val ok = accepted[key]
            val conflict = conflicts[key]
            val refusal = rejected[key]
            when {
                ok != null -> {
                    pushed++
                    val version = ok.getLong("version")
                    val current = db.sync().record(record.entityType, record.entityId)
                    if (current != null && current.updatedAt != record.updatedAt) {
                        // Edited again while this copy was in flight: keep it queued on top of the new version.
                        db.sync().save(current.copy(version = version, remoteId = out.remoteId))
                    } else {
                        db.sync().synced(record.entityType, record.entityId, out.remoteId, version, out.fields?.let(LibrarySync::fieldsHash), out.doc.updatedAt, now)
                        if (record.entityType == "inbox_item" && !record.deleted) db.inbox().setSyncState(record.entityId, SyncState.SYNCED.name, now)
                    }
                    setBase(record.entityType, record.entityId, out.doc)
                }
                conflict != null -> {
                    // Someone else wrote a newer version: merge it now; anything still local-only is re-queued on top of it.
                    if (incoming(conflict) != Outcome.APPLIED) failRecord(record, "Waiting for a newer server version to merge")
                }
                refusal?.optString("error") == "missing base record" ->
                    db.sync().save(record.copy(state = SyncState.PENDING.name, version = 0, remoteId = null))
                else -> failRecord(record, "The server refused this record: ${refusal?.optString("error")?.ifBlank { null } ?: "no reason given"}")
            }
        }
    }

    // ---- originals ----

    private suspend fun download(id: String, remote: LibrarySync.Doc): Boolean {
        val blob = remote.fields["blobId"]?.value
        val hash = remote.fields["contentHash"]?.value
        val size = remote.fields["size"]?.value?.toLongOrNull() ?: 0
        if (blob.isNullOrBlank() || hash.isNullOrBlank() || size <= 0) return true
        if (db.library().item(id)?.storage == StorageMode.MANAGED.name) return true
        val context = currentCoroutineContext()
        return try {
            LibrarySync.chunkCount(size)
            SyncChunkInput(size) { index ->
                context.ensureActive()
                val chunk = api.get("/api/library/blobs/$blob/chunks/$index")
                val bytes = Base64.getDecoder().decode(chunk.getString("bytes"))
                require(LibrarySync.sha256(bytes) == chunk.getString("sha256"))
                keys.cipher.decrypt(bytes, LibrarySync.aad("chunk", "$hash:$index"))
            }.use { app.media.adoptSynced(id, it, remote.fields["name"]?.value ?: "Synced item", remote.fields["mime"]?.value ?: "application/octet-stream", hash) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (network: IOException) { throw network }
        catch (e: WebSync.Rejected) { if (e.status == 429 || e.status >= 500) throw e else false }
        catch (_: Exception) { false }
    }

    // ---- per-record base documents (last version both sides agreed on) ----

    private fun baseDoc(type: String, id: String): LibrarySync.Doc {
        val text = base.optJSONObject("$type/$id")?.optString("doc").orEmpty()
        return if (text.isEmpty()) LibrarySync.Doc() else runCatching { LibrarySync.parse(text) }.getOrDefault(LibrarySync.Doc())
    }

    private fun setBase(type: String, id: String, doc: LibrarySync.Doc) {
        base.put("$type/$id", JSONObject().put("doc", LibrarySync.canonical(doc)))
    }

    private suspend fun readFields(type: String, id: String) = readSyncFields(app, type, id)
}

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
private fun JSONArray?.rows(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun parkedFile(app: HellFireApp) = File(app.noBackupFilesDir, "library-sync-parked.json")

internal const val UNREADABLE = "unreadable"
internal const val STUCK = "stuck"

/** Remote records this phone gave up on: [UNREADABLE] (other passphrase or tampered) or [STUCK] (related item never arrived). */
private fun mark(app: HellFireApp, set: String, key: String) {
    val prefs = app.getSharedPreferences(LibrarySync.PREFS, Context.MODE_PRIVATE)
    prefs.edit().putStringSet(set, marked(app, set) + key).apply()
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
    parkedFile(app).delete()
    app.getSharedPreferences(LibrarySync.PREFS, Context.MODE_PRIVATE).edit()
        .remove(UNREADABLE).remove(STUCK).remove("scan-tracked").remove("scan-media").remove("scan-inbox").apply()
}

/**
 * Deletes the server's encrypted library (records, originals, key check, salt) so this phone can upload everything
 * again under its current passphrase. Website uploads this phone has not pulled yet are lost.
 */
suspend fun replaceServerLibrary(app: HellFireApp) = withContext(Dispatchers.IO) {
    LibrarySync.mutex.withLock {
        val config = app.secrets.read(WebSync.CONFIG) ?: error("Web access is off")
        LibraryClient(config.getString("url"), config.getString("token")).post("/api/library/reset", JSONObject())
        resetLibrarySyncState(app)
    }
    LibrarySync.now(app)
}

/** Applies the chosen side of a conflict on this phone and queues it, so the server and other devices get the same value. */
suspend fun resolveConflict(app: HellFireApp, conflict: SyncConflict, keepLocal: Boolean) {
    val doc = LibrarySync.parse(if (keepLocal) conflict.localJson else conflict.remoteJson)
    val now = System.currentTimeMillis()
    check(applyDoc(app, conflict.entityType, conflict.entityId, doc)) { "That item can no longer be changed here" }
    app.database.sync().resolve(conflict.id, if (keepLocal) "local" else "remote", now)
    app.database.sync().markChanged(conflict.entityType, conflict.entityId, doc.deleted, now)
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

private fun deviceId(app: HellFireApp): String {
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
                // Website uploads ask for the on-phone analysis the website cannot run.
                if (f["needsAnalysis"]?.value == "1") app.database.jobs().enqueue(ContentEngine.newJob(id, now).copy(priority = JobPriority.CAPTURE))
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

/**
 * Remote removal hides retained originals; it never calls MediaVault.delete or deletes source evidence. The hidden
 * row keeps syncEnabled so the plaintext web mirror (managed rows with syncEnabled = 0) never picks it up.
 */
private suspend fun applyTombstone(app: HellFireApp, type: String, id: String): Boolean {
    val sql = app.database.openHelper.writableDatabase
    return try {
        when (type) {
            "media_item" -> sql.execSQL("UPDATE media_item SET state = 'SYNC_REMOVED' WHERE id = ?", arrayOf(id))
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
