package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.intelligence.HybridRanker
import com.hellfire.ass.intelligence.ImageTensorShape
import com.hellfire.ass.library.MediaEmbedding
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.sharing.ImagePrivacyScan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PredeviceRegressionTest {
    @Test fun `image tensor layouts preserve independent height and width`() {
        assertEquals(ImageTensorShape(11, 7, true), ImageTensorShape.from(longArrayOf(1, 3, 7, 11)))
        assertEquals(ImageTensorShape(11, 7, false), ImageTensorShape.from(longArrayOf(1, 7, 11, 3)))
        assertEquals(ImageTensorShape(224, 224, false), ImageTensorShape.from(longArrayOf(-1, -1, -1, 3)))
    }

    @Test fun `untrusted image dimensions and unsupported channels fail before allocation`() {
        for (shape in listOf(longArrayOf(1, 4, 224, 224), longArrayOf(2, 3, 224, 224), longArrayOf(1, 3, Long.MAX_VALUE, 224), longArrayOf(1, 3, 2048, 2048))) {
            assertTrue(runCatching { ImageTensorShape.from(shape) }.isFailure)
        }
    }

    @Test fun `namespaced media ids retain playback timestamps`() {
        val hit = HybridRanker.rank(listOf(Triple("external:folder:clip:1200", "SEMANTIC", 0.9))).single()
        assertEquals("external:folder:clip", hit.itemId)
        assertEquals(1200L, hit.startMs)
    }

    @Test fun `repeated embeddings cannot manufacture independent evidence`() {
        val once = HybridRanker.rank(listOf(Triple("a:-1", "SEMANTIC", 0.9))).single()
        val repeated = HybridRanker.rank(List(50) { Triple("a:-1", "SEMANTIC", 0.9) }).single()
        assertEquals(once.score, repeated.score, 0.0)
        assertTrue(HybridRanker.rank(listOf(Triple("bad:-1", "SEMANTIC", Double.NaN))).isEmpty())
    }

    @Test fun `embedding cursor does not skip remaining segments of one video`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        try {
            db.media().insert(MediaItem("video", null, "Synthetic", "video/mp4", "VIDEO", null, 1, 1, null, storage = "EXTERNAL", contentKind = "VIDEO"))
            repeat(230) { i -> db.library().insertEmbedding(MediaEmbedding("v%03d".format(i), "video", "synthetic-model", i.toLong(), i + 1L, byteArrayOf(1), 1)) }
            val first = db.library().embeddings("synthetic-model", "", 200)
            val second = db.library().embeddings("synthetic-model", first.last().mediaId, 200, first.last().id)
            assertEquals(200, first.size)
            assertEquals(30, second.size)
            assertEquals(230, (first + second).map { it.id }.distinct().size)
        } finally { db.close() }
    }

    @Test fun `webp metadata after a pixel chunk is discovered with RIFF byte order`() {
        fun chunk(kind: String, data: ByteArray) = ByteArrayOutputStream().apply {
            write(kind.toByteArray()); write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()); write(data)
            if (data.size % 2 == 1) write(0)
        }.toByteArray()
        val body = "WEBP".toByteArray() + chunk("VP8 ", byteArrayOf(1, 2, 3, 4)) + chunk("EXIF", "synthetic".toByteArray()) + chunk("XMP ", "fixture".toByteArray())
        val bytes = "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body
        assertEquals(listOf("EXIF", "XMP "), ImagePrivacyScan.scan(bytes).map { it.field })
    }
}
