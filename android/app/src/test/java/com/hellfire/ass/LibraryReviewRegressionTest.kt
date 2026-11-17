package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.intelligence.*
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryReviewRegressionTest {
    private lateinit var db: EventDatabase
    private lateinit var app: HellFireApp
    @Before fun open() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
    }
    @After fun close() { db.close() }
    private fun item(id: String, taken: Long = 10) = MediaItem(id, null, "$id.jpg", "image/jpeg", "IMAGE", "same", 12, 1000, taken,
        storage = "EXTERNAL", contentKind = "IMAGE", externalUri = "content://media/$id", origin = "MEDIASTORE")

    @Test fun `discovery queues items beyond first forty and normalizes legacy jobs`() = runBlocking {
        repeat(85) { i ->
            db.library().insertItem(item("m$i"))
            if (i < 40) db.jobs().enqueue(ContentEngine.newJob("m$i", 1))
        }
        IntelligenceWork.enqueueMissing(app, 10)
        assertEquals(35, db.library().unqueued(ContentEngine.JOB, Long.MAX_VALUE, "", 100).size)
        db.jobs().insert(ProcessingJob("legacy", "ANALYZE", "m84", createdAt = 1, updatedAt = 1))
        db.jobs().normalizeLegacy()
        assertEquals("intelligence", db.jobs().find("intelligence", "m84")!!.type)
        assertEquals("intelligence", db.jobs().enqueue(ProcessingJob("new", "transcribe", "m83", createdAt = 1, updatedAt = 1)).type)
    }
    @Test fun `repeated crashes stop retrying and pause survives step release`() = runBlocking {
        val jobs = db.jobs()
        jobs.enqueue(ProcessingJob("poison", "intelligence", null, createdAt = 1, updatedAt = 1))
        repeat(3) { i ->
            assertNotNull(jobs.claim("t$i", 2L + i * 2, "intelligence"))
            jobs.recoverType("intelligence", 3L + i * 2, 3, 3L + i * 2)
        }
        assertEquals("FAILED", jobs.job("poison")!!.state)
        jobs.retryFailed("poison", 20)
        jobs.claim("p", 20, "intelligence")
        jobs.pause("poison", 21)
        jobs.checkpoint("poison", "p", "PROCESSING", 0.2, "{}", 22)
        jobs.release("poison", "p", 23)
        assertEquals("PAUSED", jobs.job("poison")!!.state)
        jobs.fail("poison", "stopped", 40, 3, 24)
        assertEquals("PAUSED", jobs.job("poison")!!.state)
    }
    @Test fun `reprocessing a completed job clears the old checkpoint`() = runBlocking {
        db.library().insertItem(item("restart"))
        db.jobs().enqueue(ProcessingJob("j", "intelligence", "restart", createdAt = 1, updatedAt = 1))
        db.jobs().claim("t", 1)
        db.jobs().checkpoint("j", "t", "PROCESSING", 0.5, AnalysisCheckpoint(6000).encode(), 2)
        db.jobs().complete("j", "t", 3)
        db.jobs().enqueue(ProcessingJob("again", "intelligence", "restart", createdAt = 4, updatedAt = 4))
        assertEquals(0L, AnalysisCheckpoint.decode(db.jobs().job("j")!!.checkpoint).doneMs)
    }
    @Test fun `filter-only search uses date taken and refinements intersect categories with text`() = runBlocking {
        db.library().insertItem(item("boat", 50)); db.library().insertItem(item("road", 50)); db.library().insertItem(item("old", 5))
        db.library().insertCategory(MediaCategory("travel", null, "Travel", "USER", createdAt = 1, updatedAt = 1))
        listOf("boat", "road").forEach { db.library().setCategory(ItemCategory(it, "travel", "USER", updatedAt = 1)) }
        db.library().insertSearch(LibrarySearch("b", "boat", "ANALYSIS", "boats at sea"))
        db.library().insertSearch(LibrarySearch("r", "road", "ANALYSIS", "cars on road"))
        val search = IntelligenceSearch(app)
        assertEquals(setOf("boat", "road"), search.search(SearchConstraints(kinds = setOf(ContentKind.IMAGE), fromMs = 40, toMs = 60)).map { it.itemId }.toSet())
        assertEquals(listOf("boat"), search.search(SearchConstraints(categories = setOf("travel"), text = "boats")).map { it.itemId })
    }
    @Test fun `retiring a missing duplicate preserves both survivors and user metadata`() = runBlocking {
        val dao = db.library()
        dao.insertItem(item("gone").copy(availability = "MISSING", favorite = true))
        dao.insertItem(item("live")); dao.insertItem(item("other"))
        dao.setTag(ItemTag("gone", "kept", "USER", updatedAt = 1))
        db.playback().savePlayback(PlaybackState("gone", 1200, lastPlayedAt = 1))
        db.playback().saveBookmark(MediaBookmark("bookmark", "gone", 500, title = "keep", createdAt = 1, updatedAt = 1))
        val moves = LibraryDiscovery.hashMoves(listOf(dao.item("gone")!!, dao.item("live")!!, dao.item("other")!!))
        assertEquals(1, moves.size)
        val move = moves.single()
        dao.retireInto(move.dropId, move.keepId, 2)
        assertNotNull(dao.item("live")); assertNotNull(dao.item("other")); assertNull(dao.item("gone"))
        assertTrue(dao.item(move.keepId)!!.favorite)
        assertEquals("kept", dao.tagsNow(move.keepId).single().tag)
        assertEquals(1200L, db.playback().playback(move.keepId)!!.positionMs)
        assertEquals(1, db.playback().bookmarks(move.keepId, true).first().size)
    }
    @Test fun `category merge preserves user exclusions over ai assignments`() = runBlocking {
        val dao = db.library(); dao.insertItem(item("m"))
        listOf("from", "into").forEach { dao.insertCategory(MediaCategory(it, null, it, "USER", createdAt = 1, updatedAt = 1)) }
        dao.setCategory(ItemCategory("m", "from", "USER", "EXCLUDED", updatedAt = 1))
        dao.setCategory(ItemCategory("m", "into", "AI", "ASSIGNED", updatedAt = 1))
        dao.mergeCategory("from", "into", 2)
        val link = dao.itemCategoriesNow("m").single()
        assertEquals("USER", link.linkOrigin); assertEquals("EXCLUDED", link.linkState)
    }
    @Test fun `description survives later analysis and search rebuild`() = runBlocking {
        val dao = db.library(); dao.insertItem(item("m"))
        LibraryOrganize.saveDescription(dao, "m", "My Zanzibar holiday", 1)
        dao.saveAnalysis(MediaAnalysis("new", "m", "image_labels", "mlkit", "1", 1, "COMPLETED", "{}", text = "boats", createdAt = 2))
        dao.rebuildSearch()
        assertEquals("My Zanzibar holiday", LibraryOrganize.description(dao, "m"))
        assertTrue(dao.search("Zanzibar", 10).any { it.itemId == "m" })
        assertTrue(dao.search("boats", 10).any { it.itemId == "m" })
    }
    @Test fun `incomplete folder listing never marks unseen files missing`() {
        val walk = LibraryDiscovery.walkTree("root", 50) { id -> if (id == "root") listOf(DocRow("child", "folder", LibraryDiscovery.FOLDER_MIME, 0, 0)) else null }
        assertFalse(walk.complete)
        assertEquals(ScanVisibility.PARTIAL, LibraryDiscovery.treeVisibility(true, walk))
    }
    @Test fun `adult root is private and neutral labels are never adult`() = runBlocking {
        db.library().insertCategory(MediaCategory("adult", null, "Adult content", "USER", isPrivate = false, createdAt = 1, updatedAt = 1))
        db.library().insertCategory(MediaCategory("child", "adult", "uncertain", "AI", isPrivate = false, createdAt = 1, updatedAt = 1))
        val root = LibraryOrganize.adultRoot(db.library(), 2)
        assertTrue(db.library().category("child")!!.isPrivate)
        assertTrue(root.isPrivate); assertEquals("LOCAL_ONLY", root.policy)
        assertFalse(ContentEngine.isAdultLabel("neutral")); assertFalse(ContentEngine.isAdultLabel("drawings"))
        assertTrue(ContentEngine.isAdultLabel("porn"))
    }
    @Test fun `deep sampling differs before discovering a boundary and scene ids are stable`() {
        assertTrue(Sampling.schedule(20000, SampleMode.DEEP).size > Sampling.schedule(20000, SampleMode.BALANCED).size)
        assertEquals(ContentEngine.segmentId("m", 1000), ContentEngine.segmentId("m", 1000))
        val cp = AnalysisCheckpoint(6000, histogram = listOf(1, 2, 3), luma = 0.4)
        assertEquals(cp, AnalysisCheckpoint.decode(cp.encode()))
    }
    @Test fun `capture and modified sorts differ from scan order and hidden copies stay out`() = runBlocking {
        val dao = db.library()
        dao.insertItem(item("old", 10).copy(importedAt = 200, modifiedAt = 300))
        dao.insertItem(item("new", 100).copy(importedAt = 100, modifiedAt = 20))
        assertEquals("new", dao.queryItems(LibraryQuery.sql(LibraryFilter(sort = LibrarySort.TAKEN))).first().id)
        assertEquals("old", dao.queryItems(LibraryQuery.sql(LibraryFilter(sort = LibrarySort.MODIFIED))).first().id)
        LibraryOrganize.hide(dao, "old", 500)
        assertEquals(listOf("new"), dao.queryItems(LibraryQuery.sql(LibraryFilter())).map { it.id })
        assertEquals(listOf("old"), dao.queryItems(LibraryQuery.sql(LibraryFilter(tag = "hidden"))).map { it.id })
    }
    @Test fun `collection move renumbers gapped positions`() = runBlocking {
        val dao = db.library()
        dao.insertCollection(MediaCollection("c", "test", "PLAYLIST", createdAt = 1, updatedAt = 1))
        listOf("a", "b", "c").forEachIndexed { index, id -> dao.insertItem(item(id)); dao.insertMember(CollectionMember("c", id, index * 10L, 1)) }
        LibraryOrganize.moveUp(dao, "c", "c")
        assertEquals(listOf("a", "c", "b"), dao.membersNow("c").map { it.id })
        LibraryOrganize.moveUp(dao, "c", "b")
        assertEquals(listOf("a", "b", "c"), dao.membersNow("c").map { it.id })
    }
    @Test fun `full media permissions do not keep asking for selected access`() {
        val state = LibraryAccessState(true, true, true, true, true, false, true)
        assertTrue(LibraryAccess.missingFor(34, state).isEmpty())
    }

}
