package com.hellfire.ass.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.Availability
import com.hellfire.ass.library.StorageMode
import com.hellfire.ass.media.EncryptedMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Media3 source for a managed original. Reads 64 KiB authenticated chunks through [EncryptedMediaSource].
 * PCM originals get the same virtual WAV header. The plaintext file is never written.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VaultDataSource(private val app: HellFireApp) : BaseDataSource(false) {
    private var source: EncryptedMediaSource? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val id = dataSpec.uri.lastPathSegment ?: throw FileNotFoundException("Missing library id")
        val item = runBlocking(Dispatchers.IO) { app.database.library().item(id) }
            ?: throw FileNotFoundException("Library item is missing")
        if (item.storage != StorageMode.MANAGED.name) throw FileNotFoundException("Not a managed original")
        if (item.availability == Availability.MISSING.name || item.availability == Availability.PERMISSION_REVOKED.name) {
            throw FileNotFoundException("Original is ${item.availability}")
        }
        if (!app.media.file(item.id).isFile) throw FileNotFoundException("Encrypted original is missing")
        val opened = EncryptedMediaSource(app.media.reader(item))
        source = opened
        uri = dataSpec.uri
        val size = opened.size
        if (dataSpec.position < 0 || dataSpec.position > size) {
            opened.close()
            source = null
            throw IOException("Read position is outside the original")
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position else dataSpec.length.coerceAtMost(size - position)
        transferStarted(dataSpec)
        started = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val opened = source ?: throw IOException("Original is not open")
        val count = minOf(length.toLong(), bytesRemaining).toInt()
        val read = opened.readAt(position, buffer, offset, count)
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        source?.let { runCatching { it.close() } }
        source = null
        if (started) {
            started = false
            transferEnded()
        }
        bytesRemaining = 0
        position = 0
    }

    class Factory(private val app: HellFireApp) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(app)
    }

    companion object {
        fun uri(itemId: String): Uri = Uri.parse("hellfire-vault:///$itemId")
    }
}
