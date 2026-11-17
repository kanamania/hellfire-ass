package com.hellfire.ass

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.media3.exoplayer.ExoPlayer
import com.hellfire.ass.player.*
import com.hellfire.ass.sharing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerShareReviewTest {
    @Test fun `metadata removal preserves faststart video bytes and sample offsets`() {
        val bytes = javaClass.getResourceAsStream("/media/synthetic-faststart.mp4")!!.use { it.readBytes() }
        val result = Mp4Privacy.strip(bytes)
        assertTrue(Mp4Privacy.clean(result.scan))
        assertEquals(bytes.size, result.bytes.size)
        // This is an encoded H264 fixture with moov before mdat, not an invented box-only example.
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val clean = result.bytes.toString(Charsets.ISO_8859_1)
        assertTrue(raw.indexOf("moov") < raw.indexOf("mdat"))
        assertEquals(raw.indexOf("mdat"), clean.indexOf("mdat"))
        val mdat = raw.indexOf("mdat")
        assertArrayEquals(bytes.copyOfRange(mdat, bytes.size), result.bytes.copyOfRange(mdat, bytes.size))
        val stco = raw.indexOf("stco")
        assertTrue(stco > 0)
        assertArrayEquals(bytes.copyOfRange(stco, stco + 16), result.bytes.copyOfRange(stco, stco + 16))
        File("build/test-artifacts/sanitized-faststart.mp4").apply { parentFile!!.mkdirs(); writeBytes(result.bytes) }
    }
    @Test fun `streamed stripping preserves the same offsets as in-memory stripping`() {
        val bytes = javaClass.getResourceAsStream("/media/synthetic-faststart.mp4")!!.use { it.readBytes() }
        val file = File.createTempFile("streamed", ".mp4")
        try {
            file.writeBytes(bytes)
            assertTrue(Mp4Privacy.clean(Mp4Privacy.stripFile(file)))
            assertArrayEquals(Mp4Privacy.strip(bytes).bytes, file.readBytes())
        } finally { file.delete() }
    }
    @Test fun `loop messages are delivered on the application looper`() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            val message = HellFirePlaybackService.abLoopMessage(player, 1000) { }
            assertSame(player.applicationLooper, message.looper)
            message.cancel()
        } finally { player.release() }
    }
    @Test fun `live position advances without a player event`() = runBlocking {
        withTimeout(5000) {
            var position = 0L
            val samples = mutableListOf<Long>()
            val done = CompletableDeferred<Unit>()
            val ticker = launch { PlaybackLogic.tickPositions(1, { true }, { position += 250; position }) {
                samples += it; if (samples.size == 3) done.complete(Unit)
            } }
            done.await(); ticker.cancelAndJoin()
            assertEquals(listOf(250L, 500L, 750L), samples.take(3))
        }
    }
    @Test fun `auto advance saves outgoing position without using the next item position`() {
        val outgoing = PlaybackLogic.outgoing("first", 180_000, "second", true)!!
        assertEquals("first", outgoing.itemId); assertEquals(180_000L, outgoing.positionMs); assertTrue(outgoing.completed)
        assertNull(PlaybackLogic.outgoing("first", 100, "first", false))
        assertEquals(0L, PlaybackLogic.resumeMs(10000, false, 0, 20000))
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `video covers use the full frame dimensions including portrait video`() {
        listOf(160 to 90, 90 to 160).forEach { (w, h) ->
            val bitmap = ContainerExport.coverBitmap(w, h, listOf(RedactRect(0f, 0f, 0.25f, 0.25f)))
            assertEquals(Color.BLACK, bitmap.getPixel(1, 1))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(w / 2, h / 2))
            bitmap.recycle()
        }
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `OCR coordinate space is the baked rotated image used at export`() {
        val raw = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        raw.eraseColor(Color.WHITE)
        val oriented = ImageOrientation.bake(raw, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(20, oriented.width); assertEquals(40, oriented.height)
        val redacted = ImageRedaction.apply(oriented, listOf(RedactRect(0f, 0f, 0.5f, 0.25f)), RedactMode.COVER)
        assertEquals(Color.BLACK, redacted.getPixel(2, 2))
        assertEquals(Color.WHITE, redacted.getPixel(15, 30))
        if (redacted !== oriented) redacted.recycle()
        if (oriented !== raw) oriented.recycle()
        raw.recycle()
    }
    @Test fun `clip metadata cannot take title or description from whole-file analysis`() {
        val facts = ShareFacts("VIDEO", 5000, 32, 24, ClipRange(1000, 2000),
            listOf(AnalysisFact("description", "whole file secret", false, mapOf("title" to listOf("wrong title"), "description" to listOf("whole file secret")))),
            listOf(SegmentFact(1000, 2000, "boat", "blue water")), emptyList(), emptyList(), emptyList(), emptyList(), "wrong summary", 0)
        val draft = ShareTemplates.fromFacts(facts, CaptionStyle.FACTUAL)
        assertEquals("boat", draft.title)
        assertFalse(draft.detailedSummary.contains("secret")); assertFalse(draft.shortSummary.contains("wrong"))
    }
    @Test fun `timestamp edits parse into export values and invalid edits are rejected`() {
        assertEquals(listOf(TimestampNote(12000, 20000, "edited")), ShareTemplates.parseTimestampNotes("0:12–0:20 edited"))
        assertEquals(emptyList<TimestampNote>(), ShareTemplates.parseTimestampNotes(""))
        assertNull(ShareTemplates.parseTimestampNotes("0:99–1:00 invalid"))
        assertNull(ShareTemplates.parseTimestampNotes("1:00–0:30 reversed"))
    }
    @Test fun `stereo envelope covers the second half rather than only the last bucket`() {
        val (acc, _) = WaveformPeaks.forStream(100, 1_000_000, 2, 10)
        val bytes = ByteArray(400)
        for (i in 200 until 400 step 2) { bytes[i] = 0xff.toByte(); bytes[i + 1] = 0x7f }
        acc.feedPcm16le(bytes)
        val peaks = acc.finish()
        assertEquals(0f, peaks[0]); assertTrue(peaks[5] > 0.9f); assertTrue(peaks[8] > 0.9f)
    }
    @Test fun `private session rejects arbitrary apps and permissions are per media kind`() {
        assertFalse(SessionAccess.allowed("ours", "stranger", false, false, false))
        assertTrue(SessionAccess.allowed("ours", "ours", false, false, false))
        assertEquals(listOf(android.Manifest.permission.READ_MEDIA_AUDIO), MediaReadAccess.request("AUDIO", 34).toList())
        assertFalse(MediaReadAccess.requiresMediaPermission("content://com.android.providers.downloads.documents/document/12"))
        assertTrue(MediaReadAccess.granted(mapOf(android.Manifest.permission.READ_MEDIA_VIDEO to false, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to true)))
    }
    @Test fun `pip follows video aspect and closing it pauses background playback`() {
        assertEquals(0.5, PipControls.aspect(0.5f).toDouble(), 0.001)
        assertTrue(PlaybackLogic.pipDismissed(false, false))
        assertFalse(PlaybackLogic.pipDismissed(false, true))
    }
    @Test fun `expired exports are removed without touching a recent recipient copy`() = runBlocking {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.hellfire.ass.data.EventDatabase::class.java).build()
        val app = HellFireApp()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.content.Context::class.java, context))
        org.robolectric.util.ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        val old = File(app.cacheDir, "share/old-review").apply { mkdirs() }
        val recent = File(app.cacheDir, "share/new-review").apply { mkdirs() }
        File(old, "copy.mp4").writeText("synthetic")
        val current = File(recent, "copy.mp4").apply { writeText("recipient is reading") }
        val now = System.currentTimeMillis()
        old.setLastModified(now - 25 * 60 * 60_000L)
        try {
            ShareSession.cleanup(app, now)
            assertFalse(old.exists()); assertEquals("recipient is reading", current.readText())
        } finally { old.deleteRecursively(); recent.deleteRecursively(); db.close() }
    }
    @Test fun `bookmark thumbnails stay outside backups and are deleted with the item`() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        val app = HellFireApp()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.content.Context::class.java, context))
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val ref = FrameScrubber.writeThumb(app, bitmap, "synthetic-item", "bookmark")!!
        val file = FrameScrubber.thumbFile(app, ref)!!
        assertTrue(file.path.startsWith(app.noBackupFilesDir.path))
        FrameScrubber.deleteForItem(app, "synthetic-item")
        assertFalse(file.exists()); bitmap.recycle()
    }

}
