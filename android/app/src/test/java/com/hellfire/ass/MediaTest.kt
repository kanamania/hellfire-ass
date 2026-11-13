package com.hellfire.ass

import com.hellfire.ass.media.*
import com.hellfire.ass.security.PayloadCipher
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID
import javax.crypto.KeyGenerator

class MediaTest {
    private fun cipher() = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    @Test fun encryptedMediaSeeksAcrossAuthenticatedChunks() {
        val key = cipher(); val id = UUID.randomUUID().toString(); val file = File.createTempFile("media-test", ".enc")
        val bytes = ByteArray(ChunkedMedia.CHUNK + 123) { (it % 251).toByte() }
        try {
            val saved = ChunkedMedia.write(bytes.inputStream(), file, id, true, key)
            assertEquals(bytes.size.toLong(), saved.size)
            assertFalse(file.readBytes().take(100).toByteArray().contentEquals(bytes.take(100).toByteArray()))
            ChunkedMedia.Reader(file, key, id, saved.size).use { reader ->
                val target = ByteArray(60)
                assertEquals(60, reader.readAt(ChunkedMedia.CHUNK - 20L, target, 0, target.size))
                assertArrayEquals(bytes.copyOfRange(ChunkedMedia.CHUNK - 20, ChunkedMedia.CHUNK + 40), target)
                assertEquals(-1, reader.readAt(saved.size, target, 0, 1))
            }
        } finally { file.delete() }
    }
    @Test fun corruptionAndWrongIdentityFailAuthentication() {
        val key = cipher(); val id = UUID.randomUUID().toString(); val file = File.createTempFile("media-test", ".enc")
        try {
            ChunkedMedia.write(byteArrayOf(1, 2, 3).inputStream(), file, id, false, key)
            assertThrows(IllegalArgumentException::class.java) { ChunkedMedia.Reader(file, key, UUID.randomUUID().toString()) }
            RandomAccessFile(file, "rw").use { it.seek(it.length() - 1); val last = it.read(); it.seek(it.length() - 1); it.write(last xor 1) }
            assertThrows(Exception::class.java) { ChunkedMedia.Reader(file, key, id).use { it.readAt(0, ByteArray(3), 0, 3) } }
        } finally { file.delete() }
    }
    @Test fun interruptedRecordingRetainsOnlyAuthenticatedPrefix() {
        val key = cipher(); val id = UUID.randomUUID().toString(); val file = File.createTempFile("media-test", ".enc")
        try {
            val source = object : InputStream() {
                var calls = 0
                override fun read() = error("unused")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (calls++ > 0) throw IOException("interruption")
                    b.fill(42, off, off + 200); return 200
                }
            }
            val result = ChunkedMedia.write(source, file, id, true, key, true)
            assertTrue(result.interrupted); assertEquals(200L, result.size)
            RandomAccessFile(file, "rw").use { it.seek(33); it.writeLong(-1); it.seek(it.length()); it.writeInt(100); it.write(byteArrayOf(1, 2)) }
            assertEquals(200L, ChunkedMedia.recoverRecording(file, key)!!.size)
            ChunkedMedia.Reader(file, key, id, 200).use { assertEquals(200, it.stream().readBytes().size) }
        } finally { file.delete() }
    }
    /** Synthetic 16 kHz PCM: optional speech-like 200 Hz tone with 4 Hz syllable modulation over seeded noise. */
    private fun pcm(seconds: Double, speech: Double = 0.0, noise: Double = 0.0, seed: Long = 7): ByteArray {
        val random = java.util.Random(seed)
        val n = (seconds * 16000).toInt()
        return ByteArray(n * 2).also { out -> for (i in 0 until n) {
            val t = i / 16000.0
            val voice = speech * (0.55 + 0.45 * kotlin.math.sin(2 * Math.PI * 4 * t)) * kotlin.math.sin(2 * Math.PI * 200 * t)
            val v = ((voice + noise * random.nextGaussian()).coerceIn(-1.0, 0.999) * 32768).toInt()
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
        } }
    }
    private fun spans(vararg parts: ByteArray, chunk: Int = 4096) = SpeechActivity().apply {
        val all = parts.fold(ByteArray(0)) { a, b -> a + b }
        var at = 0; while (at < all.size) { val size = minOf(chunk, all.size - at); accept(all, at, size); at += size }
    }.finish()
    @Test fun speechIntervalsAreBoundedAndDoNotAssignIdentity() {
        val result = spans(pcm(5.0, speech = 0.3), pcm(4.0))
        assertEquals(1, result.size)
        assertEquals(0L, result[0].first)                 // 30 s pre-roll clamps to the recording start
        assertTrue(result[0].second in 4900L..5000L)
    }
    @Test fun steadyBackgroundNoiseIsNotSpeech() {
        // 0.03 RMS noise exceeded the old fixed 0.02 threshold for the whole 19 s.
        val result = spans(pcm(10.0, noise = 0.03, seed = 1), pcm(3.0, speech = 0.4, noise = 0.03, seed = 2), pcm(6.0, noise = 0.03, seed = 3))
        assertEquals(1, result.size)
        assertTrue("ended at ${result[0].second}", result[0].second in 12800L..13000L)
        assertTrue(spans(pcm(12.0, noise = 0.03)).isEmpty())
    }
    @Test fun clicksAndOddReadBoundariesAreHandled() {
        val click = ByteArray(640).also { for (i in 0 until 320) { it[2 * i] = 0; it[2 * i + 1] = 0x40 } }  // one 20 ms frame
        assertTrue(spans(pcm(2.0, noise = 0.001), click, pcm(3.0, noise = 0.001)).isEmpty())
        assertEquals(spans(pcm(5.0, speech = 0.3), pcm(4.0)), spans(pcm(5.0, speech = 0.3), pcm(4.0), chunk = 777))
    }
    @Test fun adtsHeadersDescribeAacLcFrames() {
        val header = Adts.header(100, 16000, 1)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60, 0x40, 0x0D, 0x7F, 0xFC.toByte()), header)
        val length = ((header[3].toInt() and 3) shl 11) or ((header[4].toInt() and 255) shl 3) or ((header[5].toInt() and 255) shr 5)
        assertEquals(107, length)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte()), Adts.header(10, 44100, 2).copyOf(4))
        assertThrows(IllegalArgumentException::class.java) { Adts.header(10, 17000, 1) }
        assertThrows(IllegalArgumentException::class.java) { Adts.header(8200, 16000, 1) }
    }
    @Test fun recordingExclusionsRespectOwnerSuppliedPeopleAndPlaces() {
        assertFalse(RecordingPolicy.allowed("Asha Hassan, owner", "Home", "asha", "hospital"))
        assertFalse(RecordingPolicy.allowed("owner", "Hospital reception", "Asha", "hospital"))
        assertTrue(RecordingPolicy.allowed("owner and Ali", "Home", "Asha", "hospital"))
        assertThrows(IllegalArgumentException::class.java) { RecordingPolicy.exclusions("x".repeat(101)) }
    }

}
