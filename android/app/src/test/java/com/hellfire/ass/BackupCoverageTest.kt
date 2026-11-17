package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.TranscriptSegment
import com.hellfire.ass.security.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupCoverageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var source: EventDatabase
    private lateinit var target: EventDatabase

    @Before fun open() {
        source = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
    }
    @After fun close() { source.close(); target.close() }

    private fun item(id: String, hash: String, size: Long) = MediaItem(id, null, id, "video/mp4", "VIDEO", hash, size, 1, null, storage = "MANAGED", contentKind = "VIDEO")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hex(seed: Int) = "%064x".format(seed)

    @Test fun `incremental plan dedups by hash and reports storage blocks`() {
        val kept = hex(1)
        val fresh = hex(2)
        val shared = hex(3)
        val huge = hex(4)
        val items = listOf(
            item("a", kept, 100),
            item("b", fresh, 50),
            item("c", shared, 40),
            item("d", shared, 40),
            item("e", huge, 10_000),
            item("f", "", 10),
        )
        val plan = BackupPlanner.plan(items, setOf(kept), freeBytes = 220, incremental = true)
        assertEquals("INCREMENTAL", plan.kind)
        assertEquals(BackupAction.OMITTED, plan.items.first { it.id == "a" }.action)
        assertEquals(BackupAction.INCLUDE, plan.items.first { it.id == "b" }.action)
        val sharedActions = plan.items.filter { it.contentHash == shared }.map { it.action }.toSet()
        assertEquals(setOf(BackupAction.INCLUDE, BackupAction.DEDUPE), sharedActions)
        assertEquals(listOf(shared), plan.includedHashes.filter { it == shared })
        assertEquals("destination storage", plan.items.first { it.id == "e" }.reason)
        assertEquals(BackupAction.BLOCKED, plan.items.first { it.id == "f" }.action)
        assertFalse(plan.items.any { it.action == BackupAction.BLOCKED && it.reason == null })
    }

    @Test fun `verification failure does not mark an item backed up`() {
        val hash = hex(9)
        val plan = BackupPlan("FULL", listOf(
            PlannedItem("ok", hash, 10, BackupAction.INCLUDE),
            PlannedItem("same", hash, 10, BackupAction.DEDUPE),
            PlannedItem("old", hex(8), 10, BackupAction.OMITTED),
            PlannedItem("bad", hex(7), 10, BackupAction.INCLUDE),
            PlannedItem("blocked", hex(6), 10, BackupAction.BLOCKED, "destination storage"),
        ))
        val marked = BackupPlanner.backedUpIds(plan, verifiedThisRun = setOf(hash), previouslyVerified = setOf(hex(8)))
        assertEquals(setOf("ok", "same", "old"), marked)
        assertFalse("bad" in marked)
        assertFalse("blocked" in marked)
        assertTrue(BackupPlanner.backedUpIds(plan, emptySet(), emptySet()).isEmpty())
    }

    @Test fun `retention keeps the newest N manifests`() {
        val rows = listOf("old" to 1L, "mid" to 2L, "new" to 3L, "newest" to 4L)
        assertEquals(listOf("mid", "old"), BackupPlanner.retain(rows, 2))
        assertEquals(emptyList<String>(), BackupPlanner.retain(rows, 0))
        assertEquals(emptyList<String>(), BackupPlanner.retain(rows, 4))
    }

    @Test fun `recovery metadata never carries a key`() {
        val recovery = BackupPlanner.recoveryMetadata()
        assertFalse(recovery.getBoolean("keyStored"))
        assertEquals(BackupCrypto.ITERATIONS, recovery.getInt("iterations"))
        assertFalse(recovery.toString().contains("passphrase", ignoreCase = true))
        assertTrue(BackupPlanner.settings.flatMap { it.second }.none { it.contains("passphrase") || it.contains("token") })
    }

    @Test fun `large originals stream in 64 KiB chunks`() {
        val bytes = ByteArray(3 * 1024 * 1024) { (it * 17 % 251).toByte() }
        val out = java.io.ByteArrayOutputStream()
        val (actual, size) = BackupStream.copy(ByteArrayInputStream(bytes), out, bytes.size.toLong())
        assertEquals(BackupStream.BUFFER, 64 * 1024)
        assertEquals(bytes.size.toLong(), size)
        assertEquals(hash(bytes), actual)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test fun `schema 17 rows still restore and schema 8 stays on the old table list`() = runBlocking {
        val cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        target.events().insertCaptured(SourceEvent("s", externalId = "s", packageName = "fixture", occurredAt = 1, receivedAt = 1,
            kind = "POSTED", payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
        val row = """{"id":"m","sourceId":"s","name":"Call","mime":"audio/aac","kind":"CALL_RECORDING","contentHash":"abc","size":10,"importedAt":1,"recordedAt":null,"processedMs":0,"state":"RETAINED","summary":"","summaryState":"NONE","error":null,"durationMs":900}"""
        val archive = java.io.ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("tables/media_item.jsonl"))
            zip.write((row + "\n").toByteArray())
            zip.closeEntry()
        }
        ZipInputStream(java.io.ByteArrayInputStream(archive.toByteArray())).use { zip ->
            zip.nextEntry
            assertEquals(1L, BackupTables.readTable(target.openHelper.writableDatabase, zip, "media_item", cipher))
        }
        val restored = target.media().item("m")!!
        assertEquals("MANAGED", restored.storage)
        assertEquals(900L, restored.durationMs)
        assertEquals(BackupTables.tablesFor(17), BackupTables.tablesFor(16) + listOf("ai_skill", "skill_use", "memory_revision"))
        assertFalse(BackupTables.tablesFor(8).contains("media_category"))
        assertTrue(BackupTables.tablesFor(18).contains("backup_item"))
    }

    @Test fun `selective restore keeps bookmarks categories transcripts and scenes`() = runBlocking {
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        source.events().insertCaptured(SourceEvent("s", externalId = "s", packageName = "fixture", occurredAt = 1, receivedAt = 1,
            kind = "POSTED", payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
        source.events().insertCaptured(SourceEvent("s2", externalId = "s2", packageName = "fixture", occurredAt = 1, receivedAt = 1,
            kind = "POSTED", payloadHash = "h2", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
        source.media().insert(MediaItem(id, "s", "clip", "video/mp4", "VIDEO", hex(1), 20, 1, null, contentKind = "VIDEO"))
        source.media().insert(MediaItem(other, "s2", "other", "audio/aac", "AUDIO", hex(2), 20, 2, null, contentKind = "AUDIO"))
        source.media().segment(TranscriptSegment("tr", id, "s", 0, 1500, "UNKNOWN", "hotel api"))
        source.library().insertCategory(MediaCategory("cat", null, "Travel", Origin.USER.name, createdAt = 1, updatedAt = 1))
        source.library().setCategory(ItemCategory(id, "cat", Origin.USER.name, updatedAt = 1))
        source.library().saveSegment(MediaSegment("seg", id, null, SegmentKind.SCENE.name, 0, 1500, "Lobby", "talking", createdAt = 1))
        source.playback().saveBookmark(MediaBookmark("bm", id, 400, title = "Mark", note = "keep", createdAt = 1, updatedAt = 1))
        source.library().insertCollection(MediaCollection("col", "Trip", CollectionKind.COLLECTION.name, createdAt = 1, updatedAt = 1))
        source.library().addToCollection("col", id, 1)
        source.inbox().saveCapture(InboxItem("in", "VIDEO", title = "Drop", capturedAt = 1, updatedAt = 1), listOf(id))
        val ids = SelectiveRestore.resolve(source.openHelper.writableDatabase, BackupSelection(kinds = setOf("VIDEO")))
        assertEquals(listOf(id), ids)
        SelectiveRestore.copy(source.openHelper.writableDatabase, target.openHelper.writableDatabase, ids)
        target.library().rebuildSearch()
        assertEquals("clip", target.media().item(id)!!.name)
        assertNull(target.media().item(other))
        assertEquals("hotel api", target.media().transcript(id).single().text)
        assertEquals("Lobby", target.library().segments(id).first().single().title)
        assertEquals("Mark", target.playback().bookmarks(id, true).first().single().title)
        assertEquals(id, target.library().members("col").first().single().id)
        assertEquals("Travel", target.library().itemCategories(id).first().single().category.name)
        assertEquals("Drop", target.inbox().item("in")!!.title)
        target.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun `cache eviction never deletes managed originals`() {
        val root = File(context.cacheDir, "evict-${UUID.randomUUID()}").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val media = File(root, "media").apply { mkdirs() }
        File(cache, "thumb.tmp").writeBytes(ByteArray(32))
        val original = File(media, "${UUID.randomUUID()}.hfm").apply { writeBytes(ByteArray(32)) }
        val freed = BackupStorage.evictTree(cache, media)
        assertEquals(32L, freed)
        assertFalse(File(cache, "thumb.tmp").exists())
        assertTrue(original.exists())
        assertEquals(0L, BackupStorage.evictTree(media, media))
        assertTrue(original.exists())
        root.deleteRecursively()
    }

    @Test fun `export verifies then marks backed up and incremental omits that hash`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        val app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        ReflectionHelpers.setField(app, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        app.media.directory.deleteRecursively()
        try {
            val original = ByteArray(64 * 1024 + 100) { (it % 251).toByte() }
            val item = app.media.capture(original, "clip.mp4", "video/mp4", CaptureOrigin.DRAWER)
            val file = File(context.cacheDir, "backup-${UUID.randomUUID()}.hfb")
            val outcome = PortableBackup(app).export(file, "synthetic-passphrase".toCharArray())
            assertTrue(outcome.verified)
            assertEquals(1, outcome.included)
            val record = db.sync().lastVerifiedBackup(item.id)
            assertNotNull(record)
            val again = PortableBackup(app).export(File(context.cacheDir, "skip.hfb"), "synthetic-passphrase".toCharArray(), incremental = true)
            assertEquals(0, again.included)
            assertEquals(1, again.omitted)
            val fresh = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
            val restored = HellFireApp()
            ReflectionHelpers.callInstanceMethod<Unit>(restored, "attach", ClassParameter.from(Context::class.java, context))
            ReflectionHelpers.setField(restored, "database\$delegate", lazyOf(fresh))
            ReflectionHelpers.setField(restored, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
            app.media.directory.deleteRecursively()
            restored.media.directory.deleteRecursively()
            PortableBackup(restored).restore(file, "synthetic-passphrase".toCharArray())
            val back = restored.database.media().item(item.id)!!
            assertEquals(item.contentHash, back.contentHash)
            assertArrayEquals(original, restored.media.openStream(back).use { it.readBytes() })
            file.delete()
            fresh.close()
        } finally { db.close(); app.media.directory.deleteRecursively() }
    }
}
