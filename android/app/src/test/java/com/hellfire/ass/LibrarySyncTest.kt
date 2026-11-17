package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.library.SyncRecord
import com.hellfire.ass.security.BackupCrypto
import com.hellfire.ass.sync.LibrarySync
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibrarySyncTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun `drawer captures are queued and discovered media is not`() {
        assertTrue(LibrarySync.includeMedia(syncEnabled = true, storage = "MANAGED"))
        assertFalse(LibrarySync.includeMedia(syncEnabled = false, storage = "MANAGED"))
        assertFalse(LibrarySync.includeMedia(syncEnabled = true, storage = "EXTERNAL"))
        assertTrue(LibrarySync.includeInbox("DRAWER", "PENDING", "ACTIVE"))
        assertTrue(LibrarySync.includeInbox("SHARE", "PENDING", "ACTIVE"))
        assertFalse(LibrarySync.includeInbox("MEDIASTORE", "PENDING", "ACTIVE"))
        assertFalse(LibrarySync.includeInbox("DRAWER", "LOCAL_ONLY", "ACTIVE"))
        assertFalse(LibrarySync.includeInbox("DRAWER", "PENDING", "DELETED"))
    }

    @Test fun `backoff grows and stays capped`() {
        assertEquals(5L * 60_000, LibrarySync.backoffMs(1))
        assertEquals(10L * 60_000, LibrarySync.backoffMs(2))
        assertEquals(20L * 60_000, LibrarySync.backoffMs(3))
        assertEquals(LibrarySync.backoffMs(7), LibrarySync.backoffMs(20))
    }

    @Test fun `chunk plan resumes missing pieces and counts bytes`() {
        val size = LibrarySync.CHUNK * 2L + 10
        assertEquals(3, LibrarySync.chunkCount(size))
        assertEquals(listOf(1, 2), LibrarySync.missing(setOf(0), 3))
        assertEquals(LibrarySync.CHUNK.toLong(), LibrarySync.bytesDone(setOf(0), size))
        assertEquals(size, LibrarySync.bytesDone(setOf(0, 1, 2), size))
        assertEquals(10L, LibrarySync.bytesDone(setOf(2), size))
    }

    @Test fun `upload resumes from chunks the server already has`() {
        val plain = ByteArray(LibrarySync.CHUNK + 8) { it.toByte() }
        val hash = LibrarySync.sha256(plain)
        val stored = sortedMapOf<Int, ByteArray>()
        val api = object : LibrarySync.BlobApi {
            override fun open(contentHash: String, size: Long, chunkSize: Int, chunkCount: Int) =
                JSONObject().put("blobId", "blob-1").put("contentHash", contentHash).put("received", JSONArray(stored.keys.toList())).put("complete", stored.size == chunkCount)
            override fun putChunk(blobId: String, index: Int, sha256: String, bytes: ByteArray): JSONObject {
                assertEquals(sha256, LibrarySync.sha256(bytes))
                stored[index] = bytes
                return JSONObject().put("sha256", sha256).put("complete", stored.size == 2)
            }
        }
        val cipher = BackupCrypto.derive("correct horse".toCharArray(), ByteArray(16) { 7 })
        stored[0] = cipher.encrypt(plain.copyOfRange(0, LibrarySync.CHUNK), LibrarySync.aad("chunk", "$hash:0"))
        val id = LibrarySync.upload(api, cipher, plain.size.toLong(), hash) { index ->
            val start = index * LibrarySync.CHUNK
            plain.copyOfRange(start, minOf(plain.size, start + LibrarySync.CHUNK))
        }
        assertEquals("blob-1", id)
        assertEquals(setOf(0, 1), stored.keys)
        assertArrayEquals(plain.copyOfRange(LibrarySync.CHUNK, plain.size), cipher.decrypt(stored.getValue(1), LibrarySync.aad("chunk", "$hash:1")))
        // The same content under a different item id reuses decryptable chunks.
        assertEquals(id, LibrarySync.upload(api, cipher, plain.size.toLong(), hash) { error("Complete blob must be reused") })
    }

    @Test fun `later field wins and a tied field is a conflict`() {
        val base = LibrarySync.doc(mapOf("title" to LibrarySync.Field("a", 1), "note" to LibrarySync.Field("n", 1)), updatedAt = 1)
        val local = LibrarySync.doc(mapOf("title" to LibrarySync.Field("phone", 5), "note" to LibrarySync.Field("same", 4)), updatedAt = 5)
        val remote = LibrarySync.doc(mapOf("title" to LibrarySync.Field("web", 3), "note" to LibrarySync.Field("other", 4)), updatedAt = 4)
        val merged = LibrarySync.merge(base, local, remote)
        assertEquals("phone", merged.doc.fields.getValue("title").value)
        assertEquals("same", merged.doc.fields.getValue("note").value)
        assertTrue(merged.conflicts.contains("note"))
        assertTrue(merged.conflicts.contains("title"))
        assertFalse(merged.delete)
    }

    @Test fun `a remote tombstone deletes only when the phone has not edited since`() {
        val base = LibrarySync.doc(mapOf("title" to LibrarySync.Field("a", 1)), updatedAt = 1)
        val local = LibrarySync.doc(mapOf("title" to LibrarySync.Field("a", 1)), updatedAt = 1)
        val remote = LibrarySync.Doc(emptyMap(), deleted = true, updatedAt = 8)
        val merged = LibrarySync.merge(base, local, remote)
        assertTrue(merged.delete)
        assertTrue(merged.conflicts.isEmpty())
        val edited = LibrarySync.doc(mapOf("title" to LibrarySync.Field("kept", 6)), updatedAt = 6)
        val conflict = LibrarySync.merge(base, edited, remote)
        assertTrue(conflict.conflicts.isNotEmpty())
    }

    @Test fun `unchanged fields keep their clock`() {
        val base = mapOf("title" to LibrarySync.Clock(LibrarySync.sha256("same"), 4))
        val stamped = LibrarySync.stamp(mapOf("title" to "same", "note" to "new"), base, 9)
        assertEquals(4, stamped.getValue("title").updatedAt)
        assertEquals(9, stamped.getValue("note").updatedAt)
    }

    @Test fun `passphrase envelope matches the web crypto vector`() {
        val salt = ByteArray(16) { 0x0b }
        val iv = ByteArray(12) { 1 }
        val cipher = BackupCrypto.derive("correct horse".toCharArray(), salt)
        val sealed = LibrarySync.seal(cipher, "hello library".toByteArray(), LibrarySync.aad("record", "media_item:item-1"), iv)
        assertEquals("0101010101010101010101010125292b7958ae59fdb3115a6fabac8d5abbb6beb49657e7b10946f8f799", sealed.joinToString("") { "%02x".format(it) })
        assertEquals("hello library", LibrarySync.open(cipher, sealed, LibrarySync.aad("record", "media_item:item-1")).toString(Charsets.UTF_8))
        assertEquals(16, Base64.getDecoder().decode(LibrarySync.envelope(salt, sealed).getString("salt")).size)
    }

    @Test fun `queue survives process restart and interrupted uploads return to pending`() = kotlinx.coroutines.runBlocking {
        db.sync().markChanged("media_item", "item-1", false, 10)
        db.sync().progress("media_item", "item-1", 100, 40, "blob")
        assertEquals(1, db.sync().recoverInterrupted())
        val row = db.sync().record("media_item", "item-1")!!
        assertEquals("PENDING", row.state)
        assertEquals("blob", row.uploadSession)
        assertEquals(40, row.bytesDone)
        db.sync().failed("media_item", "item-1", "offline", 10 + LibrarySync.backoffMs(1))
        val failed = db.sync().record("media_item", "item-1")!!
        assertEquals("FAILED", failed.state)
        assertEquals(1, failed.attempts)
        assertTrue(db.sync().due(failed.nextAttemptAt, 10).any { it.entityId == "item-1" })
        assertTrue(db.sync().due(failed.nextAttemptAt - 1, 10).none { it.entityId == "item-1" })
        db.sync().markChanged("inbox_item", "gone", true, 12)
        db.sync().synced("inbox_item", "gone", "gone", 2, "hash", 12, 13)
        assertEquals(1, db.sync().purgeSentTombstones())
        db.sync().save(SyncRecord("media_item", "item-1", state = "PENDING", updatedAt = 1))
        db.sync().pauseAll()
        assertEquals("PAUSED", db.sync().record("media_item", "item-1")!!.state)
        db.sync().resumeAll()
        assertEquals("PENDING", db.sync().record("media_item", "item-1")!!.state)
    }

    @Test fun `derived key is a 256-bit AES key`() {
        val cipher = BackupCrypto.derive("correct horse".toCharArray(), ByteArray(16) { 1 })
        val field = cipher.javaClass.getDeclaredField("key")
        field.isAccessible = true
        val key = field.get(cipher) as SecretKeySpec
        assertEquals(32, key.encoded.size)
    }
}
