package com.hellfire.ass

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class LibrarySyncReviewTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var app: HellFireApp
    private lateinit var db: EventDatabase
    private lateinit var root: File
    private lateinit var manager: androidx.work.impl.WorkManagerImpl
    private val keys = LibrarySync.Keys(PayloadCipher(SecretKeySpec(ByteArray(32) { 5 }, "AES")), ByteArray(32) { 9 })
    private lateinit var api: Server
    private val salt = ByteArray(16) { 11 }
    private fun id() = UUID.randomUUID().toString()
    @Before fun open() {
        root = File.createTempFile("sync-review", "dir").apply { delete(); mkdirs() }
        androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setExecutor { }.build())
        manager = androidx.work.WorkManager.getInstance(context) as androidx.work.impl.WorkManagerImpl
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        val wrapped = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        app = HellFireApp().also {
            ReflectionHelpers.callInstanceMethod<Unit>(it, "attach", ClassParameter.from(Context::class.java, wrapped))
            ReflectionHelpers.setField(it, "database\$delegate", lazyOf(db))
            ReflectionHelpers.setField(it, "cipher\$delegate", lazyOf(keys.cipher))
        }
        app.getSharedPreferences(LibrarySync.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        api = Server()
    }
    @After fun close() {
        manager.cancelAllWork().result.get(); manager.closeDatabase()
        androidx.work.impl.WorkManagerImpl.setDelegate(null)
        ReflectionHelpers.setStaticField(androidx.work.impl.WorkManagerImpl::class.java, "sDefaultInstance", null)
        db.close(); root.deleteRecursively()
    }
    private suspend fun run() = LibrarySyncEngine(app, api, derive = { pass, _ -> pass.fill('\u0000'); keys }).run("synthetic password".toCharArray())
    private suspend fun media(mediaId: String = id(), summary: String = "") = MediaItem(mediaId, null, "Synthetic.txt", "text/plain", "TEXT", null, 0, 1, null,
        contentKind = "TEXT", origin = "DRAWER", syncEnabled = true, summary = summary).also { db.library().insertItem(it) }
    private fun doc(vararg fields: Pair<String, String>, at: Long = 10) = LibrarySync.doc(fields.toMap().mapValues { LibrarySync.Field(it.value, at) }, updatedAt = at)
    private fun remote(type: String, id: String, document: LibrarySync.Doc, legacy: Boolean = false, version: Long = 1): JSONObject {
        val remoteId = if (legacy) id else keys.remoteId(type, id)
        val plain = if (legacy) LibrarySync.canonical(document) else LibrarySync.body(id, document)
        return JSONObject().put("entityType", type).put("entityId", remoteId).put("deleted", document.deleted).put("updatedAt", document.updatedAt)
            .put("version", version).put("deviceId", "browser").put("envelope", LibrarySync.envelope(salt, keys.cipher.encrypt(plain.toByteArray(), LibrarySync.aad("record", "$type:$remoteId"))).put("v", if (legacy) 1 else 2))
    }
    private inner class Server : LibraryApi {
        var epoch = "epoch-one"
        var check: String? = LibrarySync.keyCheck(keys.cipher)
        var initialized = 0
        var seq = 0L
        val rows = linkedMapOf<String, JSONObject>()
        val sent = mutableListOf<JSONObject>()
        val chunks = mutableMapOf<String, ByteArray>()
        fun add(row: JSONObject) { row.put("changeSeq", ++seq); rows["${row.getString("entityType")}/${row.getString("entityId")}"] = row }
        override fun get(path: String): JSONObject = when {
            path.endsWith("/state") -> JSONObject().put("epoch", epoch).put("salt", Base64.getEncoder().encodeToString(salt)).put("keyCheck", check ?: JSONObject.NULL)
            path.contains("/changes?") -> {
                val after = path.substringAfter("after=").substringBefore('&').toLong()
                val limit = path.substringAfter("limit=").toInt()
                JSONObject().put("records", JSONArray(rows.values.filter { it.getLong("changeSeq") > after }.sortedBy { it.getLong("changeSeq") }.take(limit)))
            }
            path.contains("/chunks/") -> chunks[path]?.let { JSONObject().put("sha256", LibrarySync.sha256(it)).put("bytes", Base64.getEncoder().encodeToString(it)) } ?: error("Missing chunk")
            else -> error("Unexpected get $path")
        }
        override fun post(path: String, body: JSONObject): JSONObject = when {
            path.endsWith("key-check") -> { initialized++; if (check == null) check = body.getString("keyCheck"); JSONObject().put("keyCheck", check) }
            path.endsWith("records") -> {
                val accepted = JSONArray(); val conflicts = JSONArray()
                val records = body.getJSONArray("records")
                for (i in 0 until records.length()) {
                    val row = records.getJSONObject(i); sent += row
                    val key = "${row.getString("entityType")}/${row.getString("entityId")}"; val previousKey = "${row.getString("entityType")}/${row.optString("previousId")}" 
                    val old = rows[key] ?: rows[previousKey]
                    if ((old?.getLong("version") ?: 0) != row.getLong("baseVersion")) { if (old != null) conflicts.put(old); continue }
                    rows.remove(previousKey)
                    val stored = JSONObject(row.toString()).put("version", row.getLong("baseVersion") + 1)
                    add(stored)
                    accepted.put(JSONObject().put("entityType", row.getString("entityType")).put("entityId", row.getString("entityId")).put("version", stored.getLong("version")))
                }
                JSONObject().put("accepted", accepted).put("conflicts", conflicts).put("rejected", JSONArray())
            }
            else -> error("Unexpected post $path")
        }
    }

    @Test fun `discovery drains beyond forty items and hides Unicode tag ids`() = runBlocking {
        repeat(65) { media("item-%03d".format(it)) }
        db.library().setTag(ItemTag("item-064", "travel 旅行", "USER", updatedAt = 1))
        run()
        assertEquals(66, api.sent.size)
        assertTrue(api.sent.all { it.getString("entityId").matches(Regex("[0-9a-f]{64}")) })
        val tag = api.sent.single { it.getString("entityType") == "media_item_tag" }
        val plain = keys.cipher.decrypt(Base64.getDecoder().decode(tag.getJSONObject("envelope").getString("ct")), LibrarySync.aad("record", "media_item_tag:${tag.getString("entityId")}"))
        assertTrue(plain.toString(Charsets.UTF_8).contains("travel 旅行"))
        val before = api.sent.size; run(); assertEquals(before, api.sent.size)
    }
    @Test fun `restore receives children before parents across feed pages`() = runBlocking {
        val mediaId = id()
        repeat(105) { index -> api.add(remote("media_bookmark", "bookmark-$index", doc("mediaId" to mediaId, "title" to "Marker $index", "favorite" to "1"))) }
        api.add(remote("media_item", mediaId, doc("name" to "Restored", "size" to "0")))
        run()
        assertEquals(105, db.playback().bookmarks(mediaId, true).first().size)
        assertTrue(db.playback().bookmark("bookmark-0")!!.favorite)
        assertEquals(106L, app.secrets.read(LibrarySync.CURSOR)!!.getLong("cursor"))
        assertEquals(0, app.secrets.read("library-sync-parked")!!.length())
        assertTrue(db.sync().openConflicts().first().isEmpty())
    }
    @Test fun `unreadable row does not prevent later valid records or cursor advancement`() = runBlocking {
        api.add(remote("media_category", "broken", doc("name" to "Bad")).apply { getJSONObject("envelope").put("ct", "AAAA") })
        api.add(remote("media_category", "good", doc("name" to "Good")))
        assertTrue(run().contains("could not be decrypted"))
        assertNotNull(db.library().category("good")); assertEquals(2L, app.secrets.read(LibrarySync.CURSOR)!!.getLong("cursor"))
    }
    @Test fun `missing parents survive a new engine run and apply when parent arrives`() = runBlocking {
        val mediaId = id()
        api.add(remote("media_bookmark", "waiting", doc("mediaId" to mediaId, "title" to "Pending")))
        run(); assertNull(db.playback().bookmark("waiting")); assertEquals(1, app.secrets.read("library-sync-parked")!!.length())
        api.add(remote("media_item", mediaId, doc("name" to "Parent", "size" to "0")))
        run(); assertNotNull(db.playback().bookmark("waiting")); assertEquals(0, app.secrets.read("library-sync-parked")!!.length())
    }
    @Test fun `oversized metadata fails individually and later records still sync`() = runBlocking {
        val item = media()
        db.library().saveAnalysis(MediaAnalysis("large", item.id, "synthetic", "none", "0", 1, "COMPLETED", "x".repeat(240_000), createdAt = 1))
        run()
        assertEquals("FAILED", db.sync().record("media_analysis", "large")!!.state)
        assertEquals("SYNCED", db.sync().record("media_item", item.id)!!.state)
        assertTrue(db.sync().record("media_analysis", "large")!!.error!!.contains("Too large"))
    }
    @Test fun `wrong passphrase sends no records or replacement key check`() = runBlocking {
        media()
        val wrong = LibrarySync.Keys(PayloadCipher(SecretKeySpec(ByteArray(32) { 99 }, "AES")), ByteArray(32))
        val result = LibrarySyncEngine(app, api, derive = { _, _ -> wrong }).run("wrong password".toCharArray())
        assertTrue(result.contains("Nothing was sent")); assertTrue(api.sent.isEmpty()); assertEquals(0, api.initialized)
    }
    @Test fun `legacy library checks passphrase before initializing key check and upgrades ids in place`() = runBlocking {
        api.check = null
        api.add(remote("media_category", "legacy-category", doc("name" to "Legacy"), legacy = true))
        val wrong = LibrarySync.Keys(PayloadCipher(SecretKeySpec(ByteArray(32) { 99 }, "AES")), ByteArray(32))
        LibrarySyncEngine(app, api, derive = { _, _ -> wrong }).run("wrong password".toCharArray())
        assertEquals(0, api.initialized)
        run()
        assertEquals("Legacy", db.library().category("legacy-category")!!.name)
        assertEquals("legacy-category", api.sent.single().getString("previousId"))
        assertEquals(1, api.rows.size); assertEquals(2, api.rows.values.single().getJSONObject("envelope").getInt("v"))
    }
    @Test fun `new epoch resets old versions before reoffering local data`() = runBlocking {
        val item = media(); run()
        assertEquals(1L, db.sync().record("media_item", item.id)!!.version)
        api.rows.clear(); api.sent.clear(); api.seq = 0; api.epoch = "new-server"
        run(); assertEquals(0L, api.sent.single().getLong("baseVersion"))
        assertEquals("SYNCED", db.sync().record("media_item", item.id)!!.state)
    }
    @Test fun `nonconflicting phone and browser edits merge and are pushed together`() = runBlocking {
        val item = media(summary = "base"); run()
        val baseline = api.rows.values.single()
        val prior = LibrarySync.parseBody(keys.cipher.decrypt(Base64.getDecoder().decode(baseline.getJSONObject("envelope").getString("ct")), LibrarySync.aad("record", "media_item:${baseline.getString("entityId")}")).toString(Charsets.UTF_8)).second
        db.library().updateItem(item.copy(summary = "phone summary")); db.sync().markChanged("media_item", item.id, false, 30)
        val remote = prior.copy(fields = prior.fields + ("name" to LibrarySync.Field("browser name", 40)), updatedAt = 40)
        api.add(remote("media_item", item.id, remote, version = 2)); api.sent.clear()
        run()
        assertEquals("browser name", db.library().item(item.id)!!.name); assertEquals("phone summary", db.library().item(item.id)!!.summary)
        assertEquals(1, api.sent.size); assertEquals(2L, api.sent.single().getLong("baseVersion")); assertEquals("SYNCED", db.sync().record("media_item", item.id)!!.state)
        assertTrue(db.sync().openConflicts().first().isEmpty())
    }
    @Test fun `conflict choices recover either edit and preserve changes made after detection`() = runBlocking {
        val item = media(summary = "base"); run()
        db.library().updateItem(item.copy(summary = "phone edit")); db.sync().markChanged("media_item", item.id, false, 20)
        api.add(remote("media_item", item.id, doc("name" to "Remote name", "summary" to "web edit", at = 30), version = 2))
        run()
        val conflict = db.sync().openConflicts().first().single()
        assertEquals("web edit", db.library().item(item.id)!!.summary)
        val current = db.library().item(item.id)!!; db.library().updateItem(current.copy(name = "newer unrelated name"))
        resolveConflict(app, conflict, true)
        assertEquals("phone edit", db.library().item(item.id)!!.summary); assertEquals("newer unrelated name", db.library().item(item.id)!!.name)
        resolveConflict(app, conflict, false)
        assertEquals("web edit", db.library().item(item.id)!!.summary); assertEquals("newer unrelated name", db.library().item(item.id)!!.name)
        assertEquals("PENDING", db.sync().record("media_item", item.id)!!.state)
    }
    @Test fun `pause persists across local edits and new engine runs`() = runBlocking {
        val item = media(); LibrarySync.setPaused(app, true)
        db.sync().markChanged("media_item", item.id, false, 10)
        assertEquals("Paused", run()); assertTrue(api.sent.isEmpty()); assertTrue(LibrarySync.isPaused(app))
        LibrarySync.setPaused(app, false); run(); assertEquals("SYNCED", db.sync().record("media_item", item.id)!!.state)
    }
    @Test fun `missing original is downloaded into existing row without losing dependent bookmarks`() = runBlocking {
        val mediaId = id(); media(mediaId)
        db.playback().saveBookmark(MediaBookmark("keep", mediaId, 0, title = "Kept marker", createdAt = 1, updatedAt = 1))
        val bytes = "synthetic remote original".toByteArray(); val hash = LibrarySync.sha256(bytes); val blob = id()
        api.chunks["/api/library/blobs/$blob/chunks/0"] = keys.cipher.encrypt(bytes, LibrarySync.aad("chunk", "$hash:0"))
        api.add(remote("media_item", mediaId, doc("name" to "Repaired", "blobId" to blob, "contentHash" to hash, "size" to bytes.size.toString(), "mime" to "text/plain")))
        run()
        val repaired = db.library().item(mediaId)!!
        assertArrayEquals(bytes, app.media.openStream(repaired).use { it.readBytes() }); assertNotNull(repaired.sourceId)
        assertNotNull(db.playback().bookmark("keep"))
    }
    @Test fun `updated analysis preserves linked scenes and Sqlite boolean fields`() = runBlocking {
        val item = media()
        val analysis = MediaAnalysis("analysis", item.id, "synthetic", "none", "0", 1, "COMPLETED", "{}", createdAt = 1)
        db.library().saveAnalysis(analysis); db.library().saveSegment(MediaSegment("scene", item.id, analysis.id, "SCENE", 0, 100, createdAt = 1))
        db.library().saveAnalysis(analysis.copy(text = "Updated"))
        assertNotNull(db.library().segment("scene"))
        assertTrue(applyDoc(app, "media_category", "private", doc("name" to "Private", "isPrivate" to "1")))
        assertTrue(db.library().category("private")!!.isPrivate)
    }
    @Test fun `removed captures stay excluded after sent tombstones are purged`() = runBlocking {
        val item = media(); val inboxId = id()
        db.inbox().insert(InboxItem(inboxId, "TEXT", "Removed inbox", capturedAt = 1, updatedAt = 1))
        run()
        val removedAt = System.currentTimeMillis() + 1000
        api.add(remote("media_item", item.id, LibrarySync.Doc(deleted = true, updatedAt = removedAt), version = 2))
        api.add(remote("inbox_item", inboxId, LibrarySync.Doc(deleted = true, updatedAt = removedAt), version = 2))
        run(); api.sent.clear(); run()
        assertTrue(api.sent.isEmpty()); assertEquals("SYNC_REMOVED", db.library().item(item.id)!!.state)
        assertEquals("TRASHED", db.inbox().item(inboxId)!!.lifecycle)
        // Also protect legacy retained rows with syncEnabled=0 from the ordinary plaintext mirror.
        db.library().updateItem(db.library().item(item.id)!!.copy(syncEnabled = false))
        val posted = mutableListOf<JSONObject>()
        WebSync.run(db, keys.cipher, null, WebSync.Call { _, path, body ->
            when {
                path.endsWith("/state") -> JSONObject().put("dataset", "synthetic-mirror")
                path.endsWith("/batch") -> { posted += body!!; JSONObject().put("upserted", 0).put("deleted", 0) }
                else -> JSONObject().put("dataset", "synthetic-mirror")
            }
        }, {})
        assertFalse(posted.any { it.optString("kind") == "media" && it.getJSONArray("upserts").length() > 0 })
    }
    @Test fun `equal field values with different clocks are not conflicts without a common base`() {
        val local = doc("name" to "same", at = 20); val remote = doc("name" to "same", at = 30)
        assertTrue(LibrarySync.merge(LibrarySync.Doc(), local, remote).conflicts.isEmpty())
        assertEquals(42L, LibrarySync.parse(LibrarySync.canonical(LibrarySync.Doc(deleted = true, updatedAt = 42))).updatedAt)
    }
    @Test fun `opaque id vector matches browser and caller password is cleared`() {
        val password = "correct horse".toCharArray(); val actual = LibrarySync.keys(password, salt)
        val expected = "444261990a1a61413c367dcbc5ead251b95b25c9aca1378cf3074b9f8cfa696f"
        assertEquals(expected, actual.remoteId("media_item_tag", "item-1:travel 旅行"))
        assertTrue(password.all { it == '\u0000' })
        assertTrue(LibrarySync.checkKey(actual.cipher, LibrarySync.keyCheck(actual.cipher)))
    }
}
