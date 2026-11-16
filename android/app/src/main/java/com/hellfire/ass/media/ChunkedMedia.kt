package com.hellfire.ass.media

import com.hellfire.ass.security.PayloadCipher
import java.io.*
import java.util.UUID

/** Independently authenticated chunks permit seeking without plaintext temporary files. */
object ChunkedMedia {
    private val magic = "HFAMEDIA2".toByteArray(Charsets.US_ASCII)
    const val CHUNK = 64 * 1024
    const val HEADER = 41L
    data class Written(val id: String, val size: Long, val interrupted: Boolean, val pcm: Boolean = false)
    fun write(input: InputStream, file: File, id: String, pcm: Boolean, cipher: PayloadCipher,
        retainInterrupted: Boolean = false): Written {
        val uuid = UUID.fromString(id)
        var total = 0L
        var interrupted = false
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0); out.write(magic); out.writeLong(uuid.mostSignificantBits); out.writeLong(uuid.leastSignificantBits)
            out.writeInt(if (pcm) 1 else 0); out.writeInt(CHUNK); out.writeLong(-1)
            var index = 0
            val buffer = ByteArray(CHUNK)
            while (true) {
                var used = 0
                var ended = false
                while (used < buffer.size) {
                    val count = try { input.read(buffer, used, buffer.size - used) }
                    catch (error: IOException) { if (!retainInterrupted) throw error; interrupted = true; -1 }
                    if (count < 0) { ended = true; break }
                    if (count == 0) throw IOException("Input did not advance")
                    used += count
                }
                if (used > 0) {
                    require(total + used <= 8L * 1024 * 1024 * 1024) { "Media exceeds the 8 GiB import limit" }
                    val encrypted = cipher.encrypt(buffer.copyOf(used), aad(id, index, used))
                    out.writeInt(used); out.write(encrypted)
                    total += used; index++
                }
                if (ended) break
            }
            buffer.fill(0)
            out.seek(33); out.writeLong(total); out.fd.sync()
        }
        return Written(id, total, interrupted)
    }
    fun aad(id: String, index: Int, size: Int): ByteArray = "hellfire-media-v2:$id:$index:$size".toByteArray(Charsets.UTF_8)

    class Reader(private val file: File, private val cipher: PayloadCipher,
        expectedId: String? = null, expectedSize: Long? = null) : Closeable {
        private val input = RandomAccessFile(file, "r")
        val id: String
        val pcm: Boolean
        val size: Long
        private var cachedIndex = -1
        private var cached = byteArrayOf()
        init {
            try {
            val header = ByteArray(magic.size); input.readFully(header); require(header.contentEquals(magic))
            id = UUID(input.readLong(), input.readLong()).toString()
            pcm = input.readInt() == 1
            require(input.readInt() == CHUNK)
            size = input.readLong()
            require(size in 0..(8L * 1024 * 1024 * 1024)) { "Incomplete encrypted media" }
            require(expectedId == null || id == expectedId)
            require(expectedSize == null || size == expectedSize)
            } catch (error: Exception) { input.close(); throw error }
        }
        @Synchronized
        fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= target.size)
            if (position >= size) return -1
            var copied = 0
            while (copied < length && position + copied < size) {
                val index = ((position + copied) / CHUNK).toInt()
                val inChunk = ((position + copied) % CHUNK).toInt()
                if (index != cachedIndex) {
                    input.seek(HEADER + index.toLong() * (4 + CHUNK + 29))
                    val plainSize = input.readInt()
                    require(plainSize == minOf(CHUNK.toLong(), size - index.toLong() * CHUNK).toInt())
                    val envelope = ByteArray(plainSize + 29); input.readFully(envelope)
                    cached.fill(0)
                    cached = cipher.decrypt(envelope, aad(id, index, plainSize))
                    cachedIndex = index
                }
                val count = minOf(length - copied, cached.size - inChunk)
                cached.copyInto(target, offset + copied, inChunk, inChunk + count)
                copied += count
            }
            return copied
        }
        fun stream(): InputStream = object : InputStream() {
            private var position = 0L
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                return readAt(position, b, off, len).also { if (it > 0) position += it }
            }
        }
        @Synchronized override fun close() { cached.fill(0); input.close() }
    }

    /** Only authenticated, fully written recording chunks are retained after a process interruption. Works for AAC and legacy PCM. */
    fun recoverRecording(file: File, cipher: PayloadCipher): Written? {
        RandomAccessFile(file, "rw").use { input ->
            if (input.length() < HEADER) return null
            val header = ByteArray(magic.size); input.readFully(header)
            if (!header.contentEquals(magic)) return null
            val id = UUID(input.readLong(), input.readLong()).toString()
            val pcm = when (input.readInt()) { 1 -> true; 0 -> false; else -> return null }
            if (input.readInt() != CHUNK) return null
            input.readLong()
            var total = 0L
            var index = 0
            var end = HEADER
            while (input.filePointer < input.length()) {
                val valid = runCatching {
                    val size = input.readInt().also { require(it in 1..CHUNK) }
                    val bytes = ByteArray(size + 29); input.readFully(bytes)
                    cipher.decrypt(bytes, aad(id, index, size)).fill(0)
                    total += size; index++; end = input.filePointer
                    size == CHUNK
                }.getOrDefault(false)
                if (!valid) break
            }
            if (total == 0L) return null
            input.setLength(end); input.seek(33); input.writeLong(total); input.fd.sync()
            return Written(id, total, true, pcm)
        }
    }
}
