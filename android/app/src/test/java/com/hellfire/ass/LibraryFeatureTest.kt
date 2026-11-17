package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
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
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryFeatureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build() }
    @After fun close() { db.close() }

    private fun scan(uri: String, key: String, name: String = nameOf(uri), size: Long = 10, modified: Long = 1_000, generation: Long = 1, hash: String? = null) =
        ScannedMedia(uri, key, name, "image/jpeg", size, modified, generation, hash)
    private fun nameOf(uri: String) = uri.substringAfterLast('/')
    private fun indexed(id: String, uri: String, key: String? = "ms:image:$id", modified: Long? = 1_000, generation: Long? = 1, hash: String? = "h$id", size: Long = 10, availability: String = Availability.AVAILABLE.name, storage: String = StorageMode.EXTERNAL.name) =
        IndexedMedia(id, uri, key, modified, generation, hash, size, availability, storage)
    private fun external(id: String, uri: String, hash: String? = null, at: Long = 1, availability: String = Availability.AVAILABLE.name, favorite: Boolean = false, size: Long = 10, name: String = "$id.jpg", kind: ContentKind = ContentKind.IMAGE) =
        MediaItem(id, null, name, "image/jpeg", kind.name, hash, size, at, null, storage = StorageMode.EXTERNAL.name, contentKind = kind.name,
            externalUri = uri, availability = availability, origin = CaptureOrigin.MEDIASTORE.name, favorite = favorite, syncEnabled = false)

    @Test fun `discovery keeps stable ids and does not treat partial absence as deletion`() {
        val existing = listOf(indexed("a", "content://media/1", "ms:image:1"), indexed("b", "content://media/2", "ms:image:2", hash = "hb"))
        val full = LibraryDiscovery.diff(listOf(scan("content://media/1", "ms:image:1"), scan("content://media/9", "ms:image:9")), existing, ScanVisibility.FULL)
        assertEquals(IndexChange.UNCHANGED, full.first { it.id == "a" }.change)
        assertEquals(IndexChange.MISSING, full.first { it.id == "b" }.change)
        assertEquals(IndexChange.INSERT, full.first { it.scanned?.stableKey == "ms:image:9" }.change)
        val partial = LibraryDiscovery.diff(listOf(scan("content://media/1", "ms:image:1")), existing, ScanVisibility.PARTIAL)
        assertTrue(partial.none { it.change == IndexChange.MISSING })
        assertEquals(IndexChange.REVOKED, LibraryDiscovery.diff(emptyList(), existing, ScanVisibility.DENIED).first().change)
    }

    @Test fun `changed files keep their id and a matching hash is a move`() {
        val changed = LibraryDiscovery.diff(listOf(scan("content://media/1", "ms:image:1", modified = 2_000, generation = 2)), listOf(indexed("a", "content://media/1", "ms:image:1")), ScanVisibility.FULL)
        assertEquals(IndexChange.CHANGED, changed.single().change)
        assertEquals("a", changed.single().id)
        val moved = LibraryDiscovery.diff(
            listOf(scan("content://media/9", "ms:image:9", hash = "same")),
            listOf(indexed("a", "content://media/1", "ms:image:1", hash = "same", availability = Availability.MISSING.name)),
            ScanVisibility.FULL,
        )
        assertEquals(IndexChange.MOVED, moved.single().change)
        assertEquals("a", moved.single().id)
        assertEquals("content://media/9", moved.single().scanned!!.uri)
        val managed = indexed("m", "content://media/1", storage = StorageMode.MANAGED.name)
        assertEquals(IndexChange.UNCHANGED, LibraryDiscovery.diff(listOf(scan("content://media/1", "ms:image:1", modified = 9)), listOf(managed), ScanVisibility.FULL).single().change)
    }

    @Test fun `streaming hash and perceptual hash are deterministic`() {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        assertEquals(LibraryDiscovery.sha256(ByteArrayInputStream(bytes)), LibraryDiscovery.sha256(ByteArrayInputStream(bytes)))
        assertNotEquals(LibraryDiscovery.sha256(ByteArrayInputStream(bytes)), LibraryDiscovery.sha256(ByteArrayInputStream(bytes + 1)))
        val dark = IntArray(9 * 8) { 0xff000000.toInt() }
        val edge = dark.copyOf().also { it[1] = 0xffffffff.toInt() }
        assertEquals(0, LibraryDiscovery.hamming(LibraryDiscovery.dHash(dark, 9, 8), LibraryDiscovery.dHash(dark, 9, 8)))
        assertTrue(LibraryDiscovery.hamming(LibraryDiscovery.dHash(dark, 9, 8), LibraryDiscovery.dHash(edge, 9, 8)) in 1..LibraryDiscovery.SIMILAR_MAX_DISTANCE)
        val wide = IntArray(18 * 16) { if (it % 18 < 9) 0xff000000.toInt() else 0xffffffff.toInt() }
        val pairs = LibraryDiscovery.similar(listOf("a" to LibraryDiscovery.dHash(dark, 9, 8), "b" to LibraryDiscovery.dHash(edge, 9, 8), "c" to LibraryDiscovery.dHash(wide, 18, 16)))
        assertTrue(pairs.any { it.leftId == "a" && it.rightId == "b" })
        assertTrue(pairs.none { it.distance > LibraryDiscovery.SIMILAR_MAX_DISTANCE })
    }

    @Test fun `hash move keeps the live row and retires only the missing row`() {
        val missing = external("old", "content://old", "same", at = 1, availability = Availability.MISSING.name)
        val added = external("new", "content://new", "same", at = 5)
        val other = external("dup", "content://dup", "other", at = 2)
        val moves = LibraryDiscovery.hashMoves(listOf(missing, added, other, added.copy(id = "managed", storage = StorageMode.MANAGED.name)))
        assertEquals(1, moves.size)
        assertEquals("new", moves.single().keepId)
        assertEquals("old", moves.single().dropId)
        assertEquals("content://new", moves.single().uri)
    }

    @Test fun `user category corrections survive suggestions merge and reindexing`() = runBlocking {
        val dao = db.library()
        LibraryOrganize.seedDefaults(dao, 1)
        assertEquals(LibraryOrganize.DEFAULTS.size, dao.categories().first().size)
        LibraryOrganize.seedDefaults(dao, 2)
        assertEquals(LibraryOrganize.DEFAULTS.size, dao.categories().first().size)
        assertTrue(dao.findCategory(null, "Adult content")!!.isPrivate)
        val item = external("pic", "content://pic", "h")
        dao.insertItem(item)
        val photos = dao.findCategory(null, "Personal photos")!!
        val travel = dao.findCategory(null, "Travel")!!
        val food = LibraryOrganize.createCategory(dao, travel.id, "Beach", false, 3)!!
        LibraryOrganize.assign(dao, item.id, photos.id, LinkState.ASSIGNED, 4)
        LibraryOrganize.assign(dao, item.id, travel.id, LinkState.EXCLUDED, 5)
        assertFalse(LibraryOrganize.suggest(dao, item.id, photos.id, 0.9, 6))
        assertFalse(LibraryOrganize.suggest(dao, item.id, travel.id, 0.9, 7))
        LibraryOrganize.applySuggestions(dao, item.id, listOf(photos.id, travel.id, food.id), 8)
        val links = dao.itemCategories(item.id).first().associateBy { it.category.id }
        assertEquals(Origin.USER.name, links[photos.id]!!.linkOrigin)
        assertEquals(LinkState.ASSIGNED.name, links[photos.id]!!.linkState)
        assertEquals(LinkState.EXCLUDED.name, links[travel.id]!!.linkState)
        assertEquals(Origin.AI.name, links[food.id]!!.linkOrigin)
        val other = LibraryOrganize.createCategory(dao, null, "Trips", false, 9)!!
        assertTrue(LibraryOrganize.merge(dao, travel.id, other.id, 10))
        assertEquals(other.id, dao.category(travel.id)!!.mergedInto)
        assertEquals(other.id, dao.category(food.id)!!.parentId)
        assertTrue(dao.itemCategories(item.id).first().any { it.category.id == other.id && it.linkState == LinkState.EXCLUDED.name })
        assertFalse(LibraryOrganize.merge(dao, other.id, food.id, 11))
        val applied = LibraryIndex.apply(dao, listOf(IndexDecision(IndexChange.CHANGED, item.id, scan(item.externalUri!!, "ms:image:pic", modified = 9, generation = 3))), CaptureOrigin.MEDIASTORE, 12)
        assertEquals(1, applied.changed)
        assertFalse(dao.item(item.id)!!.syncEnabled)
        assertEquals(StorageMode.EXTERNAL.name, dao.item(item.id)!!.storage)
        assertEquals(LinkState.EXCLUDED.name, dao.itemCategories(item.id).first().first { it.category.id == other.id }.linkState)
    }

    @Test fun `sort and filter queries use kind favorite category tag and collection`() = runBlocking {
        val dao = db.library()
        dao.insertItem(external("a", "content://a", at = 30, size = 5, name = "zebra.jpg"))
        dao.insertItem(external("b", "content://b", at = 20, size = 50, name = "alpha.jpg", favorite = true, kind = ContentKind.VIDEO).copy(mime = "video/mp4"))
        dao.insertItem(external("c", "content://c", at = 10, size = 20, name = "middle.mp3", kind = ContentKind.AUDIO).copy(mime = "audio/mpeg"))
        val trips = LibraryOrganize.createCategory(dao, null, "Travel", false, 1)!!
        LibraryOrganize.assign(dao, "a", trips.id, LinkState.ASSIGNED, 2)
        LibraryOrganize.setTag(dao, "c", "Voice Note", LinkState.ASSIGNED, 3)
        val album = LibraryOrganize.createCollection(dao, "Holiday", CollectionKind.ALBUM, 4)!!
        dao.addToCollection(album.id, "b", 5)
        db.playback().savePlayback(PlaybackState("c", lastPlayedAt = 99))
        val newest = dao.queryItems(LibraryQuery.sql(LibraryFilter(sort = LibrarySort.NEWEST, limit = 10)))
        assertEquals(listOf("a", "b", "c"), newest.map { it.id })
        val byName = dao.queryItems(LibraryQuery.sql(LibraryFilter(sort = LibrarySort.NAME, limit = 10)))
        assertEquals(listOf("alpha.jpg", "middle.mp3", "zebra.jpg"), byName.map { it.name })
        val bySize = dao.queryItems(LibraryQuery.sql(LibraryFilter(sort = LibrarySort.SIZE, limit = 10)))
        assertEquals(listOf("b", "c", "a"), bySize.map { it.id })
        val pictures = dao.queryItems(LibraryQuery.sql(LibraryFilter(kinds = listOf(ContentKind.IMAGE.name))))
        assertEquals(listOf("a"), pictures.map { it.id })
        val favorites = dao.queryItems(LibraryQuery.sql(LibraryFilter(favoritesOnly = true)))
        assertEquals(listOf("b"), favorites.map { it.id })
        val inTrips = dao.queryItems(LibraryQuery.sql(LibraryFilter(categoryId = trips.id)))
        assertEquals(listOf("a"), inTrips.map { it.id })
        val tagged = dao.queryItems(LibraryQuery.sql(LibraryFilter(tag = "voice note")))
        assertEquals(listOf("c"), tagged.map { it.id })
        val collected = dao.queryItems(LibraryQuery.sql(LibraryFilter(collectionId = album.id)))
        assertEquals(listOf("b"), collected.map { it.id })
        val played = dao.queryItems(LibraryQuery.sql(LibraryFilter(sort = LibrarySort.RECENTLY_PLAYED)))
        assertEquals("c", played.first().id)
        val named = dao.queryItems(LibraryQuery.sql(LibraryFilter(nameContains = "mid")))
        assertEquals(listOf("c"), named.map { it.id })
    }

    @Test fun `indexing inserts external rows without copying or enabling sync`() = runBlocking {
        val dao = db.library()
        val inserted = LibraryIndex.apply(dao, listOf(IndexDecision(IndexChange.INSERT, null, scan("content://media/7", "ms:image:7"))), CaptureOrigin.MEDIASTORE, 20)
        assertEquals(1, inserted.inserted)
        val row = dao.indexedExternal().single()
        assertEquals(StorageMode.EXTERNAL.name, row.storage)
        assertFalse(row.syncEnabled)
        assertNull(row.sourceId)
        assertEquals(Availability.AVAILABLE.name, row.availability)
        assertEquals("ms:image:7", org.json.JSONObject(row.originalMetadata).getString("stableKey"))
        val again = LibraryIndex.apply(dao, listOf(IndexDecision(IndexChange.INSERT, null, scan("content://media/7", "ms:image:7"))), CaptureOrigin.MEDIASTORE, 21)
        assertEquals(0, again.inserted)
        assertEquals(1, dao.indexedExternal().size)
    }

    @Test fun `exif reader omits gps unless location permission is granted`() {
        val file = java.io.File(context.cacheDir, "library-exif.jpg")
        android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, file.outputStream())
        val writer = androidx.exifinterface.media.ExifInterface(file.absolutePath)
        writer.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, "TestMake")
        writer.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE, "1/1,0/1,0/1")
        writer.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        writer.saveAttributes()
        val hidden = file.inputStream().use { LibraryMetadata.exifJson(it, false) }
        val shown = file.inputStream().use { LibraryMetadata.exifJson(it, true) }
        assertEquals("TestMake", hidden.getString(androidx.exifinterface.media.ExifInterface.TAG_MAKE))
        assertFalse(hidden.has(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE))
        assertEquals("not read; permission not granted", hidden.getString("location"))
        assertTrue(shown.has(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE))
        file.delete()
    }
}
