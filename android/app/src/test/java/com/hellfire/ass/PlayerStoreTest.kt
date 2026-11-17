@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.library.*
import com.hellfire.ass.media.ChunkedMedia
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.TranscriptSegment
import com.hellfire.ass.player.LibrarySearchWithin
import com.hellfire.ass.player.PlaybackLogic
import com.hellfire.ass.player.PlayerStore
import com.hellfire.ass.player.VaultDataSource
import com.hellfire.ass.security.PayloadCipher
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
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    private lateinit var app: HellFireApp

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        ReflectionHelpers.setField(app, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        app.media.directory.deleteRecursively()
    }

    @After fun close() { db.close(); app.media.directory.deleteRecursively() }

    private suspend fun item(id: String = "m"): MediaItem {
        db.events().insertCaptured(SourceEvent(id, externalId = id, packageName = "p", occurredAt = 1, receivedAt = 1,
            kind = "POSTED", payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
        return MediaItem(id, id, "clip", "video/mp4", "VIDEO", "hash-$id", 10, 1, null, contentKind = ContentKind.VIDEO.name).also { db.media().insert(it) }
    }

    @Test fun `bookmarks persist convert to manual and playback position resumes`() = runBlocking {
        item()
        val created = MediaBookmark("b", "m", 1_500, 4_000, title = "API", note = "explained", origin = Origin.AI.name, createdAt = 1, updatedAt = 1)
        PlayerStore.saveBookmark(app, created)
        assertEquals("API", db.playback().bookmark("b")!!.title)
        PlayerStore.convertToManual(app, "b", 5)
        assertEquals(Origin.USER.name, db.playback().bookmark("b")!!.origin)
        PlayerStore.setHidden(app, "b", true, 6)
        assertTrue(db.playback().bookmarks("m", false).first().isEmpty())
        assertEquals(1, db.playback().bookmarks("m", true).first().size)
        PlayerStore.deleteBookmark(app, "b", 7)
        assertNull(db.playback().bookmark("b"))
        assertEquals("PENDING", db.sync().record("media_bookmark", "b")!!.state)
        db.playback().savePlayback(PlaybackState("m", positionMs = 18_000, speed = 1.25f, lastPlayedAt = 9))
        val saved = db.playback().playback("m")!!
        assertEquals(18_000L, PlaybackLogic.resumeMs(saved.positionMs, saved.completed, null, 90_000))
        assertEquals(1.25f, saved.speed)
        db.playback().insertHistory(com.hellfire.ass.library.PlayHistory("h", "m", 9, fromMs = 18_000))
        db.playback().endHistory("h", 12, 22_000)
        assertEquals(22_000L, db.playback().history(5).first().single().toMs)
    }

    @Test fun `searchWithin reads indexes and does not queue analysis`() = runBlocking {
        item()
        db.media().segment(TranscriptSegment("t", "m", "m", 2_000, 3_000, text = "payment deadline"))
        db.events().insertSearch(com.hellfire.ass.data.SourceSearch("m", "payment deadline discussed"))
        val hits = LibrarySearchWithin(db).searchWithin("m", "Jump to where the payment deadline was discussed")
        assertEquals(listOf(2_000L), hits.map { it.startMs })
        assertTrue(LibrarySearchWithin(db).searchWithin("m", "boat").isEmpty())
        assertTrue(db.jobs().queue(10).first().isEmpty())
    }

    @Test fun `vault playback reads a window without writing a plaintext file`() = runBlocking {
        val original = ByteArray(ChunkedMedia.CHUNK + 80) { (it % 251).toByte() }
        val stored = app.media.capture(original, "clip.mp4", "video/mp4", CaptureOrigin.IMPORT)
        val before = File(app.noBackupFilesDir, "plain-tmp").listFiles()?.size ?: 0
        val source = VaultDataSource(app)
        val opened = source.open(DataSpec(VaultDataSource.uri(stored.id), ChunkedMedia.CHUNK - 20L, 40))
        assertEquals(40L, opened)
        val window = ByteArray(40)
        assertEquals(40, source.read(window, 0, 40))
        assertArrayEquals(original.copyOfRange(ChunkedMedia.CHUNK - 20, ChunkedMedia.CHUNK + 20), window)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(4), 0, 4))
        source.close()
        assertEquals(before, File(app.noBackupFilesDir, "plain-tmp").listFiles()?.size ?: 0)
        assertTrue(app.media.directory.listFiles()!!.none { it.extension != "hfm" })
    }

    @Test fun `transcript correction is stored as a user edit`() = runBlocking {
        item()
        val segment = TranscriptSegment("t", "m", "m", 0, 100, text = "worng")
        db.media().segment(segment)
        PlayerStore.saveTranscriptEdit(app, "m", segment, "wrong", 4)
        val lines = PlayerStore.lines(listOf(segment), PlayerStore.transcriptEdits(app, "m"))
        assertEquals("wrong", lines.single().text)
        assertEquals("worng", db.media().transcript("m").single().text)
        val saved = db.library().latestAnalysis("m", "transcript_user_edit")!!
        assertEquals("user", saved.modelId)
        assertTrue(saved.editedJson!!.contains("wrong"))
    }
}
