package com.hellfire.ass

import android.app.Application
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.drawer.*
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.security.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DrawerBackupReviewTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private lateinit var db: EventDatabase
    private lateinit var target: EventDatabase
    private lateinit var app: HellFireApp
    private lateinit var other: HellFireApp
    private lateinit var manager: androidx.work.impl.WorkManagerImpl
    private val password = "synthetic-review-passphrase"
    @Before fun open() {
        root = File.createTempFile("drawer-backup-review", "dir").apply { delete(); mkdirs() }
        androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setExecutor { }.build())
        manager = androidx.work.WorkManager.getInstance(context) as androidx.work.impl.WorkManagerImpl
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = makeApp(db, "source"); other = makeApp(target, "target")
    }
    @After fun close() {
        manager.cancelAllWork().result.get(); manager.closeDatabase()
        androidx.work.impl.WorkManagerImpl.setDelegate(null)
        ReflectionHelpers.setStaticField(androidx.work.impl.WorkManagerImpl::class.java, "sDefaultInstance", null)
        db.close(); target.close(); root.deleteRecursively()
    }
    private fun id() = UUID.randomUUID().toString()
    private fun makeApp(database: EventDatabase, name: String): HellFireApp {
        val base = File(root, name).apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) {
            override fun getFilesDir() = File(base, "files").apply { mkdirs() }
            override fun getCacheDir() = File(base, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(base, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(base, "databases/$name").apply { parentFile!!.mkdirs() }
        }
        return HellFireApp().also {
            ReflectionHelpers.callInstanceMethod<Unit>(it, "attach", ClassParameter.from(Context::class.java, wrapped))
            ReflectionHelpers.setField(it, "database\$delegate", lazyOf(database))
            ReflectionHelpers.setField(it, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        }
    }
    // Only synthetic host staging uses normal SQLite. Production's default factory remains SQLCipher.
    private fun backup(owner: HellFireApp) = PortableBackup(owner) { file, _ -> Room.databaseBuilder(context, EventDatabase::class.java, file.path).build() }
    private suspend fun original(text: String = "synthetic original", origin: CaptureOrigin = CaptureOrigin.DRAWER) =
        app.media.capture(text.toByteArray(), "fixture.txt", "text/plain", origin, contentKind = ContentKind.TEXT)
    private suspend fun inbox(itemId: String, title: String = "Synthetic inbox"): String {
        val id = id(); db.inbox().saveCapture(InboxItem(id, "TEXT", title, capturedAt = 1, updatedAt = 1), listOf(itemId)); return id
    }
    private fun file(name: String) = File(root, name)

    @Test fun `inbox purge retains an original shared with another capture or imported library item`() = runBlocking {
        val media = original(origin = CaptureOrigin.IMPORT)
        val first = inbox(media.id); val second = inbox(media.id)
        db.inbox().insertMessage(DrawerMessage(id(), DrawerTalk.session(first), first, "USER", "private note", 1))
        DrawerInbox.deletePermanently(app, first)
        assertNotNull(db.library().item(media.id)); assertTrue(app.media.file(media.id).isFile)
        assertNotNull(db.inbox().item(second)); assertNull(db.inbox().item(first))
        assertTrue(db.inbox().context(DrawerTalk.session(first), 20).isEmpty())
        assertFalse(db.library().search("Synthetic", 20).any { it.itemId == first })
        DrawerInbox.deletePermanently(app, second)
        assertNotNull(db.library().item(media.id)); assertTrue(app.media.file(media.id).isFile)
    }
    @Test fun `an owned unreferenced drawer original is removed but library bookmarks protect one`() = runBlocking {
        val owned = original("owned"); val capture = inbox(owned.id)
        DrawerInbox.deletePermanently(app, capture)
        assertNull(db.library().item(owned.id)); assertFalse(app.media.file(owned.id).exists())
        val bookmarked = original("bookmarked"); val keepCapture = inbox(bookmarked.id)
        db.playback().saveBookmark(MediaBookmark(id(), bookmarked.id, 0, title = "owner marker", createdAt = 1, updatedAt = 1))
        DrawerInbox.deletePermanently(app, keepCapture)
        assertNotNull(db.library().item(bookmarked.id)); assertTrue(app.media.file(bookmarked.id).exists())
    }
    @Test fun `followups use actual saved text and per-item sessions even with a legacy global session`() = runBlocking {
        val first = inbox(original("first evidence").id)
        val second = inbox(original("second evidence").id)
        val talk = DrawerTalk(db.inbox())
        talk.ask(first, "global-old-session", "first question", DrawerContent.text(app, first)) { "first reply" }
        var seen = ""
        talk.ask(second, "global-old-session", "second question", DrawerContent.text(app, second)) { seen = it; "second reply" }
        assertTrue(seen.contains("second evidence")); assertFalse(seen.contains("first reply")); assertFalse(seen.contains("first question"))
        talk.clear(DrawerTalk.session(first))
        assertEquals(2, db.inbox().context(DrawerTalk.session(second), 20).size)
    }
    @Test fun `capture saves before analysis and provides original content without touching cloud routing`() = runBlocking {
        ReflectionHelpers.setField(app, "ai\$delegate", lazy<com.hellfire.ass.ai.AiRouter> { error("Cloud router must not be accessed") })
        var prompt = ""
        val result = DrawerPipeline(app, answer = {
            assertEquals(1, db.inbox().recent(10).first().size)
            prompt = it; "Synthetic local answer"
        }).ingest(listOf(DropPart.Text("Important synthetic evidence in the original")))
        assertTrue(prompt.contains("Important synthetic evidence in the original"))
        assertTrue(DrawerContent.text(app, result.inboxId).contains("synthetic evidence"))
        assertEquals(DrawerAi.NOT_INSTALLED, DrawerAi.answer(app, "no installed model"))
    }
    @Test fun `a rejected URI still creates a truthful incomplete capture`() = runBlocking {
        ShadowContentResolver.registerProviderInternal("rejected", object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String = throw SecurityException("read revoked")
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        val result = DrawerPipeline(app).ingest(listOf(DropPart.UriPart(Uri.parse("content://rejected/item"), name = "Rejected fixture", mime = "")))
        val saved = db.inbox().item(result.inboxId)!!
        assertEquals("INCOMPLETE", saved.captureState); assertTrue(saved.notes.contains("content://rejected/item"))
    }
    @Test fun `upload staging does not mark anything backed up until destination confirmation`() = runBlocking {
        val media = original()
        val outcome = backup(app).exportForUpload(file("upload.hfb"), password.toCharArray())
        assertNull(db.sync().lastVerifiedBackup(media.id)); assertTrue(db.sync().manifests(10).first().isEmpty())
        backup(app).commit(outcome, "drive", "drive:synthetic-confirmed")
        assertNotNull(db.sync().lastVerifiedBackup(media.id))
    }
    @Test fun `full restore reencrypts originals and preserves external references without requiring their bytes`() = runBlocking {
        val media = original()
        val externalId = id()
        db.library().insertItem(MediaItem(externalId, null, "external.jpg", "image/jpeg", "IMAGE", null, 50, 1, null,
            storage = "EXTERNAL", contentKind = "IMAGE", externalUri = "content://media/external", origin = "MEDIASTORE"))
        val archive = file("full.hfb"); backup(app).export(archive, password.toCharArray())
        val report = backup(other).restoreFiles(listOf(archive), password.toCharArray())
        assertEquals(1, report.restoredOriginals)
        assertEquals("synthetic original", other.media.openStream(target.media().item(media.id)!!).use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals("UNVERIFIED", target.library().item(externalId)!!.availability)
        assertTrue(app.media.file(media.id).exists())
    }
    @Test fun `base plus incremental restore accepts the UI selected archives in either order`() = runBlocking {
        val first = original("first")
        val base = file("base.hfb"); backup(app).export(base, password.toCharArray())
        val second = original("second")
        val incremental = file("incremental.hfb")
        val out = backup(app).export(incremental, password.toCharArray(), incremental = true)
        assertEquals(1, out.included); assertEquals(1, out.omitted)
        val report = backup(other).restoreFiles(listOf(base, incremental), password.toCharArray())
        assertEquals(2, report.restoredOriginals)
        assertEquals("first", other.media.openStream(target.media().item(first.id)!!).use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals("second", other.media.openStream(target.media().item(second.id)!!).use { it.readBytes().toString(Charsets.UTF_8) })
    }
    @Test fun `one missing original produces a partial archive whose other originals still restore`() = runBlocking {
        val good = original()
        val missing = id()
        db.library().insertItem(MediaItem(missing, null, "missing.txt", "text/plain", "TEXT", "a".repeat(64), 12, 1, null, contentKind = "TEXT"))
        val archive = file("partial.hfb"); val outcome = backup(app).export(archive, password.toCharArray())
        assertEquals(1, outcome.blocked.size); assertEquals("PARTIAL", db.sync().manifests(10).first().first().state)
        assertNull(db.sync().lastVerifiedBackup(missing)); assertNotNull(db.sync().lastVerifiedBackup(good.id))
        val report = backup(other).restoreFiles(listOf(archive), password.toCharArray())
        assertEquals(1, report.restoredOriginals); assertEquals(listOf("missing.txt"), report.missing)
        assertEquals("MISSING", target.library().item(missing)!!.availability)
    }
    @Test fun `metadata-only managed rows are distinguished from a lost original`() = runBlocking {
        val row = MediaItem(id(), null, "remote", "video/mp4", "VIDEO", null, 0, 1, null, contentKind = "VIDEO")
        assertEquals(BackupAction.METADATA_ONLY, BackupPlanner.plan(listOf(row), emptySet(), 10000, false) { false }.items.single().action)
        assertEquals(BackupAction.BLOCKED, BackupPlanner.plan(listOf(row.copy(size = 10, contentHash = "a".repeat(64))), emptySet(), 10000, false) { false }.items.single().action)
    }
    @Test fun `selective restore includes category ancestry and clears references to unselected members`() = runBlocking {
        val keep = original("keep"); val drop = original("leave out")
        val a = id(); val b = id(); val c = id()
        listOf(MediaCategory(a, null, "root", "USER", createdAt = 1, updatedAt = 1), MediaCategory(b, a, "child", "USER", createdAt = 1, updatedAt = 1), MediaCategory(c, b, "leaf", "USER", createdAt = 1, updatedAt = 1)).forEach { db.library().insertCategory(it) }
        db.playback().saveBookmark(MediaBookmark(id(), keep.id, 0, categoryId = c, createdAt = 1, updatedAt = 1))
        val capture = inbox(keep.id); db.inbox().insertMember(InboxMember(capture, drop.id, 1))
        val action = id()
        db.inbox().insertAction(ActionRecord(action, capture, drop.id, "copy", "Copy", "{}", "PROPOSED", createdAt = 1, updatedAt = 1))
        SelectiveRestore.copy(db.openHelper.writableDatabase, target.openHelper.writableDatabase, listOf(keep.id))
        assertNotNull(target.library().category(a)); assertNotNull(target.library().category(b)); assertNotNull(target.library().category(c))
        target.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        target.openHelper.readableDatabase.query("SELECT itemId FROM action_record WHERE id = ?", arrayOf(action)).use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
        assertNull(target.media().item(drop.id))
    }
    @Test fun `selective archive restore is usable with nonempty local data`() = runBlocking {
        val media = original()
        val archive = file("select.hfb"); backup(app).export(archive, password.toCharArray())
        val keep = id(); target.library().insertCategory(MediaCategory(keep, null, "existing", "USER", createdAt = 1, updatedAt = 1))
        val report = backup(other).restoreFiles(listOf(archive), password.toCharArray(), BackupSelection(itemIds = setOf(media.id)))
        assertEquals(1, report.restoredOriginals); assertNotNull(target.library().category(keep)); assertNotNull(target.media().item(media.id))
    }
    @Test fun `a corrupt destination cannot be recorded as a verified backup`() = runBlocking {
        val media = original()
        val destination = file("corrupt.hfb")
        ShadowContentResolver.registerProviderInternal("corrupt-backup", object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (mode == "r") destination.appendBytes(byteArrayOf(1))
                return ParcelFileDescriptor.open(destination, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        assertTrue(runCatching { backup(app).export(Uri.parse("content://corrupt-backup/archive"), password.toCharArray()) }.isFailure)
        assertNull(db.sync().lastVerifiedBackup(media.id)); assertTrue(db.sync().manifests(10).first().isEmpty())
    }
    @Test fun `cache cleanup retains active copies downloads and referenced thumbnails`() = runBlocking {
        val media = original()
        val dir = File(app.cacheDir, "intelligence-thumbs").apply { mkdirs() }
        val referenced = File(dir, "used.jpg").apply { writeText("derived") }
        val orphan = File(dir, "orphan.jpg").apply { writeText("orphan") }
        db.library().saveSegment(MediaSegment(id(), media.id, null, "SCENE", 0, 1, thumbnailPath = "intelligence-thumbs/used.jpg", createdAt = 1))
        val share = File(app.cacheDir, "share/session/copy").apply { parentFile!!.mkdirs(); writeText("active") }
        val download = File(app.filesDir, "models/download.part").apply { parentFile!!.mkdirs(); writeText("downloading") }
        assertTrue(BackupStorage.evictCache(app) > 0)
        assertTrue(referenced.exists()); assertFalse(orphan.exists()); assertTrue(share.exists()); assertTrue(download.exists())
        assertEquals(referenced.length(), BackupStorage.usage(app).thumbnailBytes)
    }
    @Test fun `executing a proposed drawer action updates its row instead of duplicating it`() = runBlocking {
        val result = DrawerPipeline(app).ingest(listOf(DropPart.Text("Call +255700000000 about the fixture")))
        val before = db.inbox().actions(result.inboxId).first()
        val proposed = before.first { it.actionType == "extract_entities" }
        val outcome = DrawerActions(app).execute(result.inboxId, proposed.actionType, true, actionId = proposed.id)
        assertFalse(outcome.failed)
        val after = db.inbox().actions(result.inboxId).first()
        assertEquals(before.size, after.size); assertEquals("EXECUTED", after.first { it.id == proposed.id }.state)
    }
    @Test fun `wrong passphrase leaves the replacement database and originals untouched`() = runBlocking {
        original()
        val archive = file("auth.hfb"); backup(app).export(archive, password.toCharArray())
        assertTrue(runCatching { backup(other).restoreFiles(listOf(archive), "incorrect-review-passphrase".toCharArray()) }.isFailure)
        assertTrue(target.media().all().isEmpty())
        assertTrue(other.media.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `selective restore refuses to replace a different local original with the same id`() = runBlocking {
        val media = original()
        val archive = file("preserve.hfb"); backup(app).export(archive, password.toCharArray())
        val existing = "existing local bytes".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(existing).joinToString("") { "%02x".format(it) }
        target.library().insertItem(media.copy(sourceId = null, contentHash = hash, size = existing.size.toLong()))
        com.hellfire.ass.media.ChunkedMedia.write(java.io.ByteArrayInputStream(existing), other.media.file(media.id), media.id, false, other.cipher)
        assertTrue(runCatching { backup(other).restoreFiles(listOf(archive), password.toCharArray(), BackupSelection(itemIds = setOf(media.id))) }.isFailure)
        assertEquals(hash, target.media().item(media.id)!!.contentHash)
        assertArrayEquals(existing, other.media.openStream(target.media().item(media.id)!!).use { it.readBytes() })
    }
    @Test fun `selective restore repairs a missing matching original and its availability`() = runBlocking {
        val media = original()
        val archive = file("repair.hfb"); backup(app).export(archive, password.toCharArray())
        target.library().insertItem(media.copy(sourceId = null, availability = "MISSING"))
        backup(other).restoreFiles(listOf(archive), password.toCharArray(), BackupSelection(itemIds = setOf(media.id)))
        assertEquals("AVAILABLE", target.media().item(media.id)!!.availability)
        assertTrue(other.media.file(media.id).isFile)
    }

}
