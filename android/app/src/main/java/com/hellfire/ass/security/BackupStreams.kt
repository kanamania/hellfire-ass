package com.hellfire.ass.security

import com.hellfire.ass.media.*
import kotlinx.coroutines.*
import org.json.*
import java.io.*
import java.security.*
import java.util.*
import java.util.zip.*


internal fun readSmall(input: InputStream): String {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 8 * 1024 * 1024); output.write(buffer, 0, count) }
    return output.toString(Charsets.UTF_8.name())
}

internal suspend fun copyBounded(input: InputStream, output: OutputStream, maximum: Long) {
    val buffer = ByteArray(65536); var count = 0L
    try { while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
        require(n > 0); count += n; require(count <= maximum); output.write(buffer, 0, n) }
    } finally { buffer.fill(0) }
}

internal class NonClosingInput(input: InputStream) : FilterInputStream(input) { override fun close() {} }

internal class Discard : OutputStream() {
    override fun write(b: Int) {}
    override fun write(bytes: ByteArray, off: Int, len: Int) {}
}

internal fun discard(input: InputStream) {
    val buffer = ByteArray(65536)
    while (input.read(buffer) >= 0) {}
}

/** Sequential reader for the ChunkedMedia layout, for archives that are only available as a stream. */
internal class SequentialChunks(private val input: DataInputStream, private val cipher: PayloadCipher) : InputStream() {
    private val id: String
    private val size: Long
    private var index = 0
    private var done = 0L
    private var chunk = ByteArray(0)
    private var position = 0
    init {
        val magic = ByteArray(9); input.readFully(magic)
        require(magic.contentEquals("HFAMEDIA2".toByteArray(Charsets.US_ASCII))) { "Unsupported backup format" }
        id = UUID(input.readLong(), input.readLong()).toString()
        input.readInt()
        require(input.readInt() == ChunkedMedia.CHUNK)
        size = input.readLong()
        require(size in 0..BackupReadiness.MAX_ARCHIVE) { "Incomplete backup" }
    }
    override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position == chunk.size) {
            if (done >= size) return -1
            val plain = input.readInt()
            require(plain == minOf(ChunkedMedia.CHUNK.toLong(), size - done).toInt()) { "Corrupt backup chunk" }
            val envelope = ByteArray(plain + 29); input.readFully(envelope)
            chunk.fill(0)
            chunk = cipher.decrypt(envelope, ChunkedMedia.aad(id, index, plain))
            index++; done += plain; position = 0
        }
        val count = minOf(len, chunk.size - position)
        chunk.copyInto(b, off, position, position + count)
        position += count
        return count
    }
    override fun close() { chunk.fill(0); input.close() }
}

internal fun hashCopy(file: File, output: OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            digest.update(buffer, 0, n); output.write(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
