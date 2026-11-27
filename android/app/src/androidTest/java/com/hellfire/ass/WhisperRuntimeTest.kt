package com.hellfire.ass

import android.media.MediaDataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.hellfire.ass.media.AudioDecoder
import com.hellfire.ass.media.WhisperNative
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class WhisperRuntimeTest {
    @Test fun realQuantizedModelTranscribesPublicEnglishSample() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val model = listOf(app.getExternalFilesDir(null), app.filesDir).map { File(it, "models/ggml-tiny.en-q5_1.bin") }.firstOrNull { it.isFile } ?: File(app.filesDir, "models/ggml-tiny.en-q5_1.bin")
        assumeTrue("Install the pinned public test model with tools/test_emulator.sh", model.isFile)
        val hash = model.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals("c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b", hash)
        val bytes = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
        var closed = false
        val source = object : MediaDataSource() {
            override fun getSize() = bytes.size.toLong()
            override fun close() { closed = true }
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= bytes.size) return -1
                val count = minOf(size.toLong(), bytes.size - position).toInt()
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }
        }
        val chunk = AudioDecoder.chunk(source, 0, 30)
        assertTrue(closed)
        assertTrue(chunk.end)
        assertTrue("Actual decoded sample count: ${chunk.samples.size}", chunk.samples.size in 170000..180000)
        val handle = WhisperNative.open(model.path)
        try {
            val output = JSONObject(WhisperNative.transcribe(handle, chunk.samples, "en").toString(Charsets.UTF_8))
            val segments = output.getJSONArray("segments")
            val text = (0 until segments.length()).joinToString(" ") { segments.getJSONObject(it).getString("text") }.lowercase()
            assertTrue(text, text.contains("ask not what your country can do for you"))
            assertTrue(text, text.contains("ask what you can do for your country"))
            for (i in 0 until segments.length()) {
                val segment = segments.getJSONObject(i)
                assertTrue(segment.getLong("start_ms") >= 0)
                assertTrue(segment.getLong("end_ms") >= segment.getLong("start_ms"))
                assertTrue(segment.getLong("end_ms") <= 12000)
            }
        } finally { WhisperNative.release(handle); chunk.samples.fill(0f) }
    }
}
