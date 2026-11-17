package com.hellfire.ass.intelligence

import android.media.MediaDataSource
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

class FdMediaSource(private val descriptor: ParcelFileDescriptor) : MediaDataSource() {
    private val channel = java.io.FileInputStream(descriptor.fileDescriptor).channel
    override fun getSize(): Long = channel.size()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val read = channel.read(ByteBuffer.wrap(buffer, offset, size), position)
        return if (read < 0) -1 else read
    }
    override fun close() {
        runCatching { channel.close() }
        runCatching { descriptor.close() }
    }
}
