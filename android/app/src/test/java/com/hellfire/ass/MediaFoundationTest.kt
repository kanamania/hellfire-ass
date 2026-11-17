package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SearchText
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.SpeechSpan
import com.hellfire.ass.media.TranscriptSegment
import com.hellfire.ass.security.BackupTables
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.ui.Screen
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

/** Schema v18 library foundation: migration, DAO contracts, backup registration and route arguments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaFoundationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build() }
    @After fun close() { db.close() }

    private suspend fun source(id: String) = db.events().insertCaptured(SourceEvent(id, externalId = id, packageName = "p", occurredAt = 1, receivedAt = 1,
        kind = "POSTED", payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
    private fun external(id: String, uri: String, hash: String? = null, kind: ContentKind = ContentKind.IMAGE, at: Long = 1) =
        MediaItem(id, null, "$id.jpg", "image/jpeg", kind.name, hash, 10, at, null, storage = StorageMode.EXTERNAL.name,
            contentKind = kind.name, externalUri = uri, origin = CaptureOrigin.MEDIASTORE.name)
    private suspend fun managed(id: String, hash: String, mime: String = "video/mp4", at: Long = 1): MediaItem {
        source("s-$id")
        return MediaItem(id, "s-$id", id, mime, "VIDEO", hash, 10, at, null, contentKind = ContentKind.fromMime(mime).name).also { db.media().insert(it) }
    }

    @Test fun `schema 17 media rows and dependents survive the media_item rebuild with backfilled kinds`() = runBlocking {
        val name = "synthetic-library-17.db"
        MigrationFixture.create(context, name, 17) { old ->
            old.execSQL("INSERT INTO source_event (id, source, externalId, packageName, occurredAt, receivedAt, kind, payloadHash, encryptedPayload, contentAvailable, contentRedacted, securitySensitive, ingestVersion, processingStatus) VALUES ('s', 'media_import', 's', 'p', 1, 1, 'POSTED', 'h', X'01', 1, 0, 0, 2, 'captured')")
            old.execSQL("INSERT INTO media_item (id, sourceId, name, mime, kind, contentHash, size, importedAt, processedMs, state, summary, summaryState, durationMs) VALUES ('a', 's', 'Call', 'audio/aac', 'CALL_RECORDING', 'h1', 10, 1, 5, 'INDEXED', 'sum', 'NONE', 900)")
            old.execSQL("INSERT INTO media_item (id, sourceId, name, mime, kind, contentHash, size, importedAt, processedMs, state, summary, summaryState) VALUES ('i', 's', 'Scan', 'image/png', 'IMAGE', 'h2', 10, 2, 0, 'RETAINED', '', 'NONE')")
            old.execSQL("INSERT INTO media_item (id, sourceId, name, mime, kind, contentHash, size, importedAt, processedMs, state, summary, summaryState) VALUES ('d', 's', 'Doc', 'application/pdf', 'DOCUMENT', 'h3', 10, 3, 0, 'RETAINED', '', 'NONE')")
            old.execSQL("INSERT INTO transcript_segment (id, mediaId, sourceId, startMs, endMs, speaker, text) VALUES ('t', 'a', 's', 0, 10, 'UNKNOWN', 'hello')")
            old.execSQL("INSERT INTO speech_span (id, mediaId, startMs, endMs) VALUES ('v', 'a', 0, 10)")
        }
        val migrated = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals(18, migrated.openHelper.writableDatabase.version)
            val audio = migrated.media().item("a")!!
            assertEquals(listOf("AUDIO", "MANAGED", "IMPORT", "AVAILABLE"), listOf(audio.contentKind, audio.storage, audio.origin, audio.availability))
            assertEquals(900L, audio.durationMs); assertEquals("h1", audio.contentHash); assertFalse(audio.syncEnabled); assertFalse(audio.favorite)
            assertEquals("IMAGE", migrated.media().item("i")!!.contentKind)
            assertEquals("DOCUMENT", migrated.media().item("d")!!.contentKind)
            assertEquals(1, migrated.media().transcript("a").size)
            assertEquals(1, migrated.media().spans("a").size)
            // Nullable source/hash and non-unique hashes now admit external index entries, including duplicate files.
            migrated.library().insertItem(external("e1", "content://media/1", "h2"))
            migrated.library().insertItem(external("e2", "content://media/2", "h2"))
            assertEquals(3, migrated.library().byHash("h2").size)
            assertEquals("i", migrated.media().hash("h2")!!.id)
            assertEquals(3, migrated.media().all().size)
            migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun `external uris are unique and library pages filter by kind storage and favorites`() = runBlocking {
        managed("m", "hm", at = 5)
        db.library().insertItem(external("e1", "content://media/1", at = 4))
        db.library().insertItem(external("e2", "content://media/2", kind = ContentKind.AUDIO, at = 3))
        assertEquals(-1L, db.library().insertItem(external("e3", "content://media/1")))
        val all = ContentKind.entries.map { it.name }; val both = StorageMode.entries.map { it.name }
        assertEquals(listOf("m", "e1", "e2"), db.library().page(all, both, false, Long.MAX_VALUE, "", 10).map { it.id })
        assertEquals(listOf("e1"), db.library().page(listOf("IMAGE"), listOf("EXTERNAL"), false, Long.MAX_VALUE, "", 10).map { it.id })
        val first = db.library().page(all, both, false, Long.MAX_VALUE, "", 1).single()
        assertEquals(listOf("e1"), db.library().page(all, both, false, first.importedAt, first.id, 1).map { it.id })
        db.library().setFavorite("e2", true)
        assertEquals(listOf("e2"), db.library().page(all, both, true, Long.MAX_VALUE, "", 10).map { it.id })
        assertEquals(listOf("m"), db.media().all().map { it.id })
        assertEquals(0, db.library().setSyncEnabled("e1", true))
        assertEquals(listOf("e1"), db.library().unhashedExternal(10).filter { it.contentKind == "IMAGE" }.map { it.id })
    }

    @Test fun `user category corrections and exclusions survive AI re-indexing and merges keep links`() = runBlocking {
        managed("m", "h")
        val travel = MediaCategory("travel", null, "Travel", Origin.USER.name, createdAt = 1, updatedAt = 1)
        val trips = MediaCategory("trips", null, "Trips", Origin.AI.name, createdAt = 1, updatedAt = 1)
        val beach = MediaCategory("beach", "trips", "Beach", Origin.AI.name, createdAt = 1, updatedAt = 1)
        listOf(travel, trips, beach).forEach { db.library().insertCategory(it) }
        assertEquals("beach", db.library().findCategory("trips", "BEACH")!!.id)
        assertNull(db.library().findCategory(null, "Beach"))
        db.library().setCategory(ItemCategory("m", "travel", Origin.USER.name, LinkState.EXCLUDED.name, updatedAt = 2))
        db.library().clearAiCategories("m")
        db.library().suggestCategory(ItemCategory("m", "travel", Origin.AI.name, confidence = 0.9, updatedAt = 3))
        db.library().suggestCategory(ItemCategory("m", "trips", Origin.AI.name, confidence = 0.7, updatedAt = 3))
        val links = db.library().itemCategories("m").first().associateBy { it.category.id }
        assertEquals(LinkState.EXCLUDED.name, links["travel"]!!.linkState)
        assertEquals(Origin.USER.name, links["travel"]!!.linkOrigin)
        assertEquals(listOf<String>(), db.library().inCategory("travel", 10, 0).map { it.id })
        db.library().mergeCategory("trips", "travel", 4)
        assertEquals("travel", db.library().category("beach")!!.parentId)
        assertEquals("travel", db.library().category("trips")!!.mergedInto)
        assertEquals(LinkState.EXCLUDED.name, db.library().itemCategories("m").first().single().linkState)
        assertEquals(listOf("beach", "travel"), db.library().categories().first().map { it.id })
    }

    @Test fun `jobs dedupe claim by priority checkpoint retry and recover`() = runBlocking {
        managed("m", "h")
        val jobs = db.jobs()
        jobs.enqueue(ProcessingJob("j1", "SCENES", "m", priority = JobPriority.BACKGROUND, createdAt = 1, updatedAt = 1))
        assertEquals("j1", jobs.enqueue(ProcessingJob("dup", "SCENES", "m", createdAt = 2, updatedAt = 2)).id)
        jobs.enqueue(ProcessingJob("j2", "TRANSCRIBE", "m", priority = JobPriority.USER_REQUESTED, createdAt = 3, updatedAt = 3))
        jobs.enqueue(ProcessingJob("later", "OCR", "m", priority = JobPriority.PLAYBACK, notBefore = 1_000, createdAt = 4, updatedAt = 4))
        val first = jobs.claim("token-1", 10)!!
        assertEquals("j2", first.id); assertEquals(JobState.PREPARING.name, first.state)
        assertEquals(0, jobs.checkpoint("j2", "wrong-token", JobState.PROCESSING.name, 0.5, """{"doneMs":1}""", 11))
        assertEquals(1, jobs.checkpoint("j2", "token-1", JobState.PROCESSING.name, 0.5, """{"doneMs":60000}""", 11))
        assertEquals(1, jobs.recoverInterrupted())
        val resumed = jobs.claim("token-2", 12)!!
        assertEquals("j2", resumed.id); assertEquals("""{"doneMs":60000}""", resumed.checkpoint)
        jobs.fail("j2", "MODEL_MISSING", retryAt = 100, maxAttempts = 2, now = 13)
        assertEquals(JobState.RETRY_PENDING.name, jobs.job("j2")!!.state)
        assertEquals("j1", jobs.claim("token-3", 14)!!.id)
        assertEquals(1, jobs.complete("j1", "token-3", 15))
        assertEquals("j2", jobs.claim("token-4", 100)!!.id)
        jobs.fail("j2", "MODEL_MISSING", retryAt = 200, maxAttempts = 2, now = 101)
        assertEquals(JobState.FAILED.name, jobs.job("j2")!!.state)
        assertEquals("later", jobs.claim("token-5", 1_000)!!.id)
        assertNull(jobs.claim("token-6", 2_000))
        assertEquals(1, jobs.retryFailed(null, 3_000))
        assertEquals(1, jobs.pause(null, 3_001)); assertNull(jobs.claim("token-7", 3_002))
        assertEquals(1, jobs.resume(null, 3_003)); assertEquals("j2", jobs.claim("token-8", 3_004)!!.id)
        jobs.enqueue(ProcessingJob("again", "SCENES", "m", priority = JobPriority.USER_REQUESTED, createdAt = 5, updatedAt = 3_005))
        assertEquals(JobState.PENDING.name to JobPriority.USER_REQUESTED, jobs.job("j1")!!.let { it.state to it.priority })
    }

    @Test fun `bookmarks scenes analysis and transcripts are timestamp searchable and rebuildable`() = runBlocking {
        managed("v", "h")
        source("t-src")
        db.media().segment(TranscriptSegment("t1", "v", "t-src", 90_000, 95_000, text = "budget review"))
        db.events().insertSearch(com.hellfire.ass.data.SourceSearch("t-src", "the speaker discusses the budget"))
        val analysis = MediaAnalysis("an", "v", "scenes", "synthetic-model", "1", 1, "COMPLETED", "{}", text = "harbour at dusk", createdAt = 1)
        db.library().saveAnalysis(analysis)
        db.library().saveSegment(MediaSegment("g1", "v", "an", SegmentKind.SCENE.name, 10_000, 20_000, description = "a boat leaves the harbour", labels = """["boat"]""", createdAt = 1))
        db.playback().saveBookmark(MediaBookmark("b1", "v", 30_000, 40_000, title = "API explained", createdAt = 1, updatedAt = 1))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { db.playback().saveBookmark(MediaBookmark("bad", "v", 5, 4, createdAt = 1, updatedAt = 1)) } }
        fun q(text: String) = SearchText.query(text)!!
        assertEquals(listOf("g1"), db.library().timestampSearch(q("boat"), "v", 10).map { it.ref })
        assertEquals(listOf(90_000L), db.library().timestampSearch(q("budget"), null, 10).map { it.startMs })
        assertEquals(setOf("BOOKMARK"), db.library().search(q("api"), 10).map { it.kind }.toSet())
        assertTrue(db.library().timestampSearch(q("boat"), "other", 10).isEmpty())
        db.playback().saveBookmark(MediaBookmark("b1", "v", 30_000, title = "Pricing section", createdAt = 1, updatedAt = 2))
        assertTrue(db.library().search(q("api"), 10).isEmpty())
        db.library().clearSearch()
        db.library().rebuildSearch()
        assertEquals(setOf("SEGMENT", "ANALYSIS"), db.library().search(q("harbour"), 10).map { it.kind }.toSet())
        assertEquals(listOf("b1"), db.library().search(q("pricing"), 10).map { it.ref })
        db.library().insertCategory(MediaCategory("c", null, "Work", Origin.USER.name, createdAt = 1, updatedAt = 1))
        db.playback().saveBookmark(MediaBookmark("b2", "v", 1, categoryId = "c", origin = Origin.AI.name, createdAt = 1, updatedAt = 1))
        db.playback().keepAsManual("b2", 5)
        db.playback().clearAiBookmarks("v")
        assertEquals(listOf("b2", "b1"), db.playback().bookmarks("v", false).first().map { it.id })
        db.media().delete("v")
        assertTrue(db.playback().bookmarks("v", true).first().isEmpty())
        assertTrue(db.library().segments("v").first().isEmpty())
    }

    @Test fun `inbox captures keep independent states, conversation context and ordered collections`() = runBlocking {
        managed("p", "h1", "image/jpeg"); managed("q", "h2", "text/plain")
        val inbox = db.inbox()
        inbox.saveCapture(InboxItem("x", ContentKind.IMAGE.name, title = "Beach receipt", capturedAt = 1, updatedAt = 1), listOf("q", "p"))
        assertEquals(listOf("q", "p"), inbox.contentsNow("x").map { it.id })
        inbox.setSyncState("x", SyncState.SYNCED.name, 2)
        inbox.item("x")!!.let { assertEquals(listOf("PENDING", "SYNCED", "NOT_BACKED_UP"), listOf(it.processingState, it.syncState, it.backupState)) }
        assertEquals(listOf("x"), db.library().search(SearchText.query("receipt")!!, 5).map { it.itemId })
        inbox.insertMessage(DrawerMessage("m1", "session", "x", MessageRole.USER.name, "Explain this", 1))
        inbox.clearContext("session")
        assertTrue(inbox.context("session", 10).isEmpty())
        assertEquals(1, inbox.conversation("x").first().size)
        inbox.setLifecycle("x", InboxLifecycle.TRASHED.name, 3)
        assertEquals(listOf("x"), inbox.trashedBefore(4).map { it.id })
        assertNotNull(db.media().item("p"))
        val library = db.library()
        library.insertCollection(MediaCollection("album", "Trip", CollectionKind.PLAYLIST.name, createdAt = 1, updatedAt = 1))
        library.addToCollection("album", "p", 1); library.addToCollection("album", "q", 2); library.addToCollection("album", "p", 3)
        assertEquals(listOf("p", "q"), library.members("album").first().map { it.id })
        library.moveMember("album", "q", -1)
        assertEquals(listOf("q", "p"), library.members("album").first().map { it.id })
    }

    @Test fun `sync queue keeps tombstones and resumable progress and backup status needs a verified hash`() = runBlocking {
        managed("m", "h")
        val sync = db.sync()
        sync.markChanged("media_item", "m", false, 1)
        sync.progress("media_item", "m", 100, 40, "upload-1")
        assertEquals(1, sync.recoverInterrupted())
        assertEquals(listOf(40L to "upload-1"), sync.due(2, 10).map { it.bytesDone to it.uploadSession })
        sync.failed("media_item", "m", "HTTP_503", retryAt = 50)
        assertTrue(sync.due(10, 10).isEmpty()); assertEquals(1, sync.due(50, 10).size)
        sync.synced("media_item", "m", "r1", 3, "lh", 60, 61)
        sync.markChanged("media_item", "m", true, 70)
        sync.record("media_item", "m")!!.let { assertTrue(it.deleted); assertEquals("r1", it.remoteId); assertEquals(SyncState.PENDING.name, it.state) }
        assertEquals(listOf("m"), sync.notBackedUp(10).map { it.id })
        sync.saveManifest(BackupManifest("b", "LOCAL_FILE", schema = BackupTables.SCHEMA, createdAt = 1))
        sync.saveBackupItems(listOf(BackupItemRecord("b", "m", "h", 10, 2)))
        assertEquals(listOf("m"), sync.notBackedUp(10).map { it.id })
        sync.verifyBackupItems("b", 3)
        assertTrue(sync.notBackedUp(10).isEmpty()); assertEquals(3L, sync.lastVerifiedBackup("m"))
    }

    @Test fun `schema 18 backups register every portable library table and schema 17 media rows restore with defaults`() = runBlocking {
        assertEquals(18, BackupTables.SCHEMA)
        val portable = setOf("media_category", "media_collection", "media_analysis", "media_segment", "media_item_category", "media_item_tag",
            "collection_member", "media_bookmark", "playback_state", "play_history", "processing_job", "inbox_item", "inbox_member", "drawer_message",
            "action_record", "saved_location", "share_export", "privacy_finding", "sync_record", "sync_conflict", "backup_manifest", "backup_item")
        assertEquals(portable, (BackupTables.tables - BackupTables.tablesFor(17).toSet()).toSet())
        assertFalse(BackupTables.tables.contains("library_search")); assertFalse(BackupTables.tables.contains("media_embedding"))
        assertEquals(BackupTables.tables, BackupTables.tablesFor(BackupTables.SCHEMA))
        assertTrue(BackupTables.tablesFor(17).containsAll(BackupTables.tablesFor(16)))
        source("s")
        val row = """{"id":"a","sourceId":"s","name":"Call","mime":"video/mp4","kind":"VIDEO","contentHash":"h","size":10,"importedAt":1,"recordedAt":null,"processedMs":0,"state":"RETAINED","summary":"","summaryState":"NONE","error":null,"durationMs":null}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("t")); it.write((row + "\n").toByteArray()); it.closeEntry() }
        val zip = ZipInputStream(ByteArrayInputStream(out.toByteArray())).also { it.nextEntry }
        BackupTables.readTable(db.openHelper.writableDatabase, zip, "media_item", PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        LibrarySchema.backfill(db.openHelper.writableDatabase)
        db.media().item("a")!!.let { assertEquals(listOf("MANAGED", "VIDEO", "AVAILABLE"), listOf(it.storage, it.contentKind, it.availability)); assertFalse(it.syncEnabled) }
        // Restored approvals and in-flight work never resume as if confirmed.
        db.inbox().insert(InboxItem("x", "TEXT", capturedAt = 1, updatedAt = 1))
        db.inbox().insertAction(ActionRecord("act", "x", null, "NAVIGATE", "Directions", state = ActionState.APPROVED.name, createdAt = 1, updatedAt = 1))
        db.jobs().insert(ProcessingJob("j", "SCENES", "a", state = JobState.PROCESSING.name, claimToken = "t", createdAt = 1, updatedAt = 1))
        db.library().insertItem(external("e", "content://media/9"))
        BackupTables.revokeRestoredApprovals(db.openHelper.writableDatabase)
        assertEquals(ActionState.PROPOSED.name, db.inbox().actions("x").first().single().state)
        assertEquals(JobState.PENDING.name, db.jobs().job("j")!!.state)
        assertEquals(Availability.UNVERIFIED.name, db.library().item("e")!!.availability)
    }

    @Test fun `route arguments round trip through the library route contract`() {
        val player = LibraryRoutes.player("item-1", 90_500)
        assertEquals(Screen.Player, player.screen)
        assertEquals("item-1", LibraryRoutes.id(player)); assertEquals(90_500L, LibraryRoutes.startMs(player))
        assertNull(LibraryRoutes.startMs(LibraryRoutes.player("item-1")))
        val share = LibraryRoutes.shareWithAi("x", 1_000, 2_000)
        assertEquals(listOf(1_000L, 2_000L), listOf(LibraryRoutes.startMs(share), LibraryRoutes.endMs(share)))
        val search = LibraryRoutes.search("hotel API & budget = 100%")
        assertEquals("hotel API & budget = 100%", LibraryRoutes.args(search.arg)["q"])
        assertEquals(mapOf("kind" to "VIDEO", "collection" to "c/1"), LibraryRoutes.args(LibraryRoutes.library(ContentKind.VIDEO, "c/1").arg))
        assertEquals("", LibraryRoutes.library().arg)
        assertEquals(emptyMap<String, String>(), LibraryRoutes.args("garbage&%zz=1"))
    }

    @Test fun `content kinds come from MIME only`() {
        assertEquals(ContentKind.IMAGE, ContentKind.fromMime("image/heic"))
        assertEquals(ContentKind.VIDEO, ContentKind.fromMime("video/x-matroska"))
        assertEquals(ContentKind.AUDIO, ContentKind.fromMime("audio/amr"))
        assertEquals(ContentKind.DOCUMENT, ContentKind.fromMime("application/pdf"))
        assertEquals(ContentKind.DOCUMENT, ContentKind.fromMime("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(ContentKind.URL, ContentKind.fromMime("text/uri-list"))
        assertEquals(ContentKind.CODE, ContentKind.fromMime("application/json; charset=utf-8"))
        assertEquals(ContentKind.TEXT, ContentKind.fromMime("text/plain"))
        assertEquals(ContentKind.CONTACT, ContentKind.fromMime("text/x-vcard"))
        assertEquals(ContentKind.OTHER, ContentKind.fromMime(null))
        assertEquals(ContentKind.OTHER, ContentKind.fromMime("application/octet-stream"))
    }
}
