package com.hellfire.ass.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.*
import com.hellfire.ass.media.ChunkedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Base64
import java.util.UUID




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
        if (LibrarySync.isPaused(app)) return "Paused"
        deadline = clock() + budgetMs
        val state = api.get("/api/library/state")
        val epoch = state.optString("epoch")
        var cursorFile = app.secrets.read(LibrarySync.CURSOR) ?: JSONObject()
        if (epoch.isNotEmpty() && cursorFile.optString("epoch") != epoch) {
            resetLibrarySyncState(app)
            cursorFile = JSONObject()
        }
        base = app.secrets.read(LibrarySync.BASE) ?: JSONObject()
        parked = app.secrets.read(PARKED) ?: runCatching { JSONObject(parkedFile(app).readText()) }.getOrDefault(JSONObject())
        val saltText = state.optStringOrNull("salt") ?: api.post("/api/library/salt",
            JSONObject().put("salt", Base64.getEncoder().encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }))).getString("salt")
        salt = Base64.getDecoder().decode(saltText)
        keys = derive(passphrase.copyOf(), salt)
        var check = state.optStringOrNull("keyCheck")
        if (check == null) {
            // Existing v1 libraries have no check. Prove the key before installing one.
            val first = api.get("/api/library/changes?after=0&limit=1").optJSONArray("records")?.optJSONObject(0)
            if (first != null && !canOpenRecord(keys, first)) return "The passphrase does not match the existing server library. Nothing was sent."
            check = api.post("/api/library/key-check", JSONObject().put("keyCheck", LibrarySync.keyCheck(keys.cipher))).getString("keyCheck")
        }
        if (!LibrarySync.checkKey(keys.cipher, check))
            return "The passphrase on this phone does not match the server library. Nothing was sent. Enter the passphrase used before."
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
        app.secrets.save(PARKED, parked)
        parkedFile(app).delete()
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
        scan("inbox", stop, 25, "SELECT id FROM inbox_item WHERE origin IN ('DRAWER', 'SHARE', 'SYNC') AND syncState != 'LOCAL_ONLY' AND lifecycle NOT IN ('DELETED', 'TRASHED') " +
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
        val categories = ids("SELECT DISTINCT categoryId FROM media_item_category WHERE itemId IN ($marks) UNION SELECT categoryId FROM media_bookmark WHERE mediaId IN ($marks) AND categoryId IS NOT NULL", args + args)
        val seen = mutableSetOf<String>()
        for (category in categories) {
            var next: String? = category
            while (next != null && seen.add(next)) {
                queue("media_category", next, now)
                next = db.library().category(next)?.parentId
            }
        }
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
            existing.state == SyncState.SYNCED.name && (existing.localHash != LibrarySync.fieldsHash(fields) || existing.remoteId != keys.remoteId(type, id)) -> db.sync().markChanged(type, id, false, now)
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
                if (clock() >= deadline) return
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
        val envelope = row.getJSONObject("envelope")
        val legacy = envelope.optInt("v", 1) == 1
        val opened = openRecord(keys, row)
        if (opened == null) {
            mark(app, UNREADABLE, "$type/$remoteId")
            unpark(row)
            return Outcome.UNREADABLE
        }
        val (id, sealedDoc) = opened
        val remote = if (legacy) sealedDoc.copy(deleted = row.optBoolean("deleted"), updatedAt = row.getLong("updatedAt")) else sealedDoc
        if (!legacy && (remote.deleted != row.optBoolean("deleted") || remote.updatedAt != row.getLong("updatedAt"))) {
            mark(app, UNREADABLE, "$type/$remoteId")
            unpark(row)
            return Outcome.UNREADABLE
        }
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
            db.sync().saveConflict(SyncConflict(UUID.randomUUID().toString(), type, id, conflictChoice(merged.doc, local, merged.conflicts), conflictChoice(merged.doc, remote, merged.conflicts), now))
        setBase(type, id, remote)
        val record = tracked ?: SyncRecord(type, id, updatedAt = now)
        if (!legacy && LibrarySync.sameValues(merged.doc, remote)) {
            db.sync().save(record.copy(deleted = merged.doc.deleted))
            db.sync().synced(type, id, remoteId, version, readFields(type, id)?.let(LibrarySync::fieldsHash), remote.updatedAt, now)
        } else {
            // This phone still holds changes the server lacks: send the merged result on top of the remote version.
            db.sync().save(record.copy(state = SyncState.PENDING.name, deleted = merged.doc.deleted, remoteId = remoteId, version = version,
                localHash = null, attempts = 0, nextAttemptAt = 0, error = null))
        }
        clearMark(app, UNREADABLE, "$type/$remoteId")
        clearMark(app, STUCK, "$type/$remoteId")
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
        if (attempts > 20) mark(app, STUCK, key)
        // Keep the sealed row: a late parent must still be able to unblock it on a future run.
        parked.put(key, JSONObject().put("row", row).put("attempts", attempts.coerceAtMost(21)))
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
            blobId = prior.fields["blobId"]?.value?.takeIf { prior.fields["contentHash"]?.value == hash && record.remoteId == remoteId }
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
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (network: IOException) { throw network }
                catch (_: Exception) { failRecord(record, "The local original could not be read for sync"); return null }
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
        record.remoteId?.takeIf { it != remoteId }?.let { json.put("previousId", it) }
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
            val conflict = conflicts[key] ?: record.remoteId?.let { conflicts["${record.entityType}/$it"] }
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
        db.library().item(id)?.let { existing ->
            if (existing.storage == StorageMode.MANAGED.name && app.media.file(id).isFile && existing.contentHash == hash && existing.size == size) return true
        }
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
