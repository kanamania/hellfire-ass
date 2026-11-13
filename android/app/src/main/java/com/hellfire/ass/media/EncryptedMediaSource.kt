package com.hellfire.ass.media

import android.media.MediaDataSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A virtual WAV header lets Android decode/play original raw PCM without creating a WAV file. */
class EncryptedMediaSource(private val reader: ChunkedMedia.Reader) : MediaDataSource() {
    private val header = if (reader.pcm) ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        require(reader.size <= Int.MAX_VALUE - 44)
        put("RIFF".toByteArray()); putInt(reader.size.toInt() + 36); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(reader.size.toInt())
    }.array() else byteArrayOf()
    override fun getSize(): Long = reader.size + header.size
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= getSize()) return -1
        var copied = 0
        if (position < header.size) {
            copied = minOf(size, header.size - position.toInt())
            header.copyInto(buffer, offset, position.toInt(), position.toInt() + copied)
        }
        if (copied < size) {
            val count = reader.readAt(position + copied - header.size, buffer, offset + copied, size - copied)
            if (count > 0) copied += count
        }
        return copied
    }
    override fun close() = reader.close()
}
