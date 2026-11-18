package com.hellfire.ass

import android.graphics.Color
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.hellfire.ass.media.AudioDecoder
import com.hellfire.ass.sharing.ContainerExport
import com.hellfire.ass.sharing.RedactRect
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

class MediaExportIntegrationTest {
    @Test fun actualAndroidTranscodeClipsCoversMutesAndStripsMetadata() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "fixture-${UUID.randomUUID()}.mp4")
        val output = File(context.cacheDir, "fixture-${UUID.randomUUID()}-copy.mp4")
        instrumentation.context.assets.open("synthetic-av.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
        try {
            val result = withTimeout(120000) { ContainerExport.export(context, input, output, false, 0, 1500,
                listOf(300L to 700L), listOf(RedactRect(0f, 0f, 0.5f, 0.5f))) }
            assertTrue(result.message, result.ok)
            assertTrue(result.findings.toString(), result.findings.isEmpty())
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.path)
                assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 1400..1700)
                val frame = retriever.getFrameAtTime(500000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                try {
                    val pixel = frame.getPixel(frame.width / 8, frame.height / 8)
                    assertTrue("Covered pixel: $pixel", Color.red(pixel) < 24 && Color.green(pixel) < 24 && Color.blue(pixel) < 24)
                    val visible = frame.getPixel(frame.width * 7 / 8, frame.height * 7 / 8)
                    assertTrue(Color.red(visible) + Color.green(visible) + Color.blue(visible) > 50)
                } finally { frame.recycle() }
            } finally { retriever.release() }
            val bytes = output.readBytes()
            val source = object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun close() { }
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val count = minOf(size.toLong(), bytes.size-position).toInt()
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt()+count)
                    return count
                }
            }
            val decoded = AudioDecoder.chunk(source, 0, 3).samples
            fun rms(start: Int, end: Int) = sqrt(decoded.copyOfRange(start, end).map { it.toDouble()*it }.average())
            val audible = rms(14400, 17600)
            val muted = rms(6400, 9600)
            assertTrue("Audible RMS: $audible", audible > 0.02)
            assertTrue("Muted/audible RMS: $muted/$audible", muted < audible * 0.1)
        } finally { input.delete(); output.delete() }
    }

    @Test fun cancellationDoesNotLeaveAnIncompleteShareCopy() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "fixture-${UUID.randomUUID()}.mp4")
        val output = File(context.cacheDir, "fixture-${UUID.randomUUID()}-cancelled.mp4")
        instrumentation.context.assets.open("synthetic-av.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
        try {
            val export = launch(Dispatchers.Default) { ContainerExport.export(context, input, output, false, null, null,
                emptyList(), listOf(RedactRect(0f, 0f, 1f, 1f))) }
            delay(100)
            export.cancelAndJoin()
            instrumentation.waitForIdleSync()
            assertFalse("Cancelled export left an output", output.exists())
        } finally { input.delete(); output.delete() }
    }
}
