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
    @Test fun speechIntervalsAreBoundedAndDoNotAssignIdentity() {
        val activity = SpeechActivity()
        val loud = ByteArray(32000) { if (it % 2 == 0) 0 else 50 }
        repeat(5) { activity.accept(loud, 0, loud.size) }
        repeat(4) { activity.accept(ByteArray(32000), 0, 32000) }
        assertEquals(listOf(0L to 5000L), activity.finish())
    }
    @Test fun recordingExclusionsRespectOwnerSuppliedPeopleAndPlaces() {
        assertFalse(RecordingPolicy.allowed("Asha Hassan, owner", "Home", "asha", "hospital"))
        assertFalse(RecordingPolicy.allowed("owner", "Hospital reception", "Asha", "hospital"))
        assertTrue(RecordingPolicy.allowed("owner and Ali", "Home", "Asha", "hospital"))
        assertThrows(IllegalArgumentException::class.java) { RecordingPolicy.exclusions("x".repeat(101)) }
    }

}
