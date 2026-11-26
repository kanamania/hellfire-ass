package com.hellfire.ass

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.library.*
import com.hellfire.ass.media.ChunkedMedia
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.security.PayloadCipher
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
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.KeyGenerator

/** MediaVault managed-storage contract: encrypted, hashed, deduplicated capture; streaming reads; promotion of external entries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaVaultContractTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    private lateinit var app: HellFireApp
    private val vault get() = app.media

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        ReflectionHelpers.setField(app, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        app.media.directory.deleteRecursively()
    }
    @After fun close() { db.close(); app.media.directory.deleteRecursively() }

    private fun bytes(size: Int) = ByteArray(size) { (it * 31 % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `capture encrypts hashes registers and deduplicates without plaintext on disk`() = runBlocking {
        val original = bytes(ChunkedMedia.CHUNK * 2 + 77)
        val item = vault.capture(original, "clip.mp4", "video/mp4", CaptureOrigin.DRAWER)
        assertEquals(StorageMode.MANAGED.name, item.storage)
        assertEquals(ContentKind.VIDEO.name, item.contentKind)
        assertEquals(sha(original), item.contentHash)
        assertEquals(original.size.toLong(), item.size)
        assertTrue(item.syncEnabled)
        assertNotNull(db.events().find(item.sourceId!!))
        val stored = vault.file(item.id).readBytes()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains(String(original.copyOfRange(0, 64), Charsets.ISO_8859_1)))
        assertTrue(vault.directory.listFiles()!!.none { it.extension == "importing" })

        val again = vault.capture(original.inputStream(), "copy.mp4", "video/mp4", CaptureOrigin.IMPORT, syncEnabled = false)
        assertEquals(item.id, again.id)
        assertEquals(1, vault.directory.listFiles()!!.count { it.extension == "hfm" })
        val imported = vault.capture(bytes(10), "note.txt", "text/plain", CaptureOrigin.IMPORT)
        assertFalse(imported.syncEnabled)
        assertEquals(ContentKind.TEXT.name, imported.contentKind)
        // Re-capturing the same bytes from the drawer/share path opts the existing original into sync.
        vault.capture(bytes(10), "n", "text/plain", CaptureOrigin.SHARE).let { assertEquals(imported.id, it.id); assertTrue(it.syncEnabled) }
    }

    @Test fun `streams data source text and plain copies return the original bytes`() = runBlocking {
        val original = bytes(ChunkedMedia.CHUNK + 5000)
        val item = vault.capture(original, "voice.m4a", "audio/mp4", CaptureOrigin.RECORDING, kind = "VOICE_NOTE")
        assertEquals("VOICE_NOTE", item.kind); assertEquals(ContentKind.AUDIO.name, item.contentKind)
        assertArrayEquals(original, vault.openStream(item).use(InputStream::readBytes))
        vault.dataSource(item).use { source ->
            val window = ByteArray(100)
            assertEquals(100, source.readAt(ChunkedMedia.CHUNK - 50L, window, 0, 100))
            assertArrayEquals(original.copyOfRange(ChunkedMedia.CHUNK - 50, ChunkedMedia.CHUNK + 50), window)
        }
        var path: File? = null
        val size = vault.withPlainCopy(item) { file -> path = file; assertArrayEquals(original, file.readBytes()); file.length() }
        assertEquals(original.size.toLong(), size)
        assertFalse(path!!.exists())
        assertThrows(IllegalStateException::class.java) { runBlocking { vault.withPlainCopy(item) { path = it; error("decoder failed") } } }
        assertFalse(path!!.exists())
        val text = vault.capture("Meet at Kariakoo 15:00".toByteArray(), "note", "text/plain", CaptureOrigin.DRAWER)
        assertEquals("Meet at Kariakoo 15:00", vault.readText(text))
        assertEquals("Meet", vault.readText(text, maxBytes = 4))
    }

    @Test fun `empty or failing input leaves no record or file`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { vault.capture(ByteArray(0), "empty", "image/png", CaptureOrigin.DRAWER) } }
        val failing = object : InputStream() {
            var sent = false
            override fun read() = error("unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (sent) throw java.io.IOException("revoked"); sent = true; b.fill(1, off, off + 10); return 10 }
        }
        assertThrows(java.io.IOException::class.java) { runBlocking { vault.capture(failing, "x", "image/png", CaptureOrigin.DRAWER) } }
        assertTrue(vault.directory.listFiles().orEmpty().isEmpty())
        assertTrue(db.media().all().isEmpty())
    }

    @Test fun `promoting an external entry keeps its id and links and syncs the full original`() = runBlocking {
        val original = bytes(4096)
        val file = File(context.cacheDir, "external-photo.jpg").apply { writeBytes(original) }
        try {
            val entry = MediaItem("1b4e28ba-2fa1-11d2-883f-0016d3cca427", null, "photo.jpg", "image/jpeg", "IMAGE", null, original.size.toLong(), 1, null,
                storage = StorageMode.EXTERNAL.name, contentKind = ContentKind.IMAGE.name, externalUri = Uri.fromFile(file).toString(), origin = CaptureOrigin.MEDIASTORE.name)
            db.library().insertItem(entry)
            assertArrayEquals(original, vault.openStream(entry).use(InputStream::readBytes))
            assertThrows(IllegalArgumentException::class.java) { vault.dataSource(entry) }
            db.library().setFavorite(entry.id, true)
            val promoted = vault.promote(entry.id)
            assertEquals(entry.id, promoted.id)
            assertEquals(StorageMode.MANAGED.name, promoted.storage)
            assertEquals(sha(original), promoted.contentHash)
            assertTrue(promoted.syncEnabled); assertTrue(promoted.favorite); assertNotNull(promoted.sourceId)
            assertArrayEquals(original, vault.openStream(promoted).use(InputStream::readBytes))
            // A second external copy of the same bytes resolves to the managed original instead of storing it twice.
            val twin = entry.copy(id = "2b4e28ba-2fa1-11d2-883f-0016d3cca427", externalUri = Uri.fromFile(file).toString() + "?copy", favorite = false)
            db.library().insertItem(twin.copy(externalUri = Uri.fromFile(File(context.cacheDir, "twin.jpg").apply { writeBytes(original) }).toString()))
            assertEquals(entry.id, vault.promote(twin.id).id)
            assertEquals(sha(original), db.library().item(twin.id)!!.contentHash)
            assertEquals(StorageMode.EXTERNAL.name, db.library().item(twin.id)!!.storage)
            assertEquals(1, vault.directory.listFiles()!!.count { it.extension == "hfm" })
        } finally { file.delete(); File(context.cacheDir, "twin.jpg").delete() }
    }

    @Test fun `deleting an original purges its transcript from Ask search`() = runBlocking {
        val item = vault.capture(bytes(4096), "meeting.m4a", "audio/mp4", CaptureOrigin.DRAWER)
        com.hellfire.ass.media.MediaWork.text(app, item, 0, 30_000, "We agreed the zebrafinch invoice is due Friday", "speech")
        val hit = db.events().search("zebrafinch", 10).single()
        db.semantic().put(listOf(com.hellfire.ass.memory.SemanticVector(hit.eventId, "m", byteArrayOf(1), 1)))
        vault.purgeDerivedText(item.id)
        assertTrue(db.events().search("zebrafinch", 10).isEmpty())
        val row = db.events().find(hit.eventId)!!
        assertFalse(row.contentAvailable)
        assertFalse(String(app.cipher.decrypt(row.encryptedPayload), Charsets.UTF_8).contains("zebrafinch"))
        assertEquals(0, db.semantic().indexedCount("m"))
    }
}
