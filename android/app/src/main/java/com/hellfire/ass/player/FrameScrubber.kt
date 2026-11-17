package com.hellfire.ass.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.StorageMode
import com.hellfire.ass.media.EncryptedMediaSource
import com.hellfire.ass.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Bounded in-memory frames for the seek bar. Extraction uses a streaming data source, not a plaintext file. */
object FrameScrubber {
    private val cache = BoundedCache<String, Bitmap>(8)
    private val gate = Mutex()

    fun bucket(positionMs: Long) = positionMs.coerceAtLeast(0) / 5_000 * 5_000

    suspend fun frame(app: HellFireApp, item: MediaItem, positionMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${item.id}:${bucket(positionMs)}"
        cache.get(key)?.let { return@withContext it }
        gate.withLock {
            cache.get(key)?.let { return@withLock it }
            val bitmap = extract(app, item, bucket(positionMs)) ?: return@withLock null
            cache.put(key, bitmap)
            bitmap
        }
    }

    fun writeThumb(app: HellFireApp, bitmap: Bitmap, bookmarkId: String): String? = runCatching {
        val dir = File(app.filesDir, "player-thumbs").apply { mkdirs() }
        val relative = "player-thumbs/$bookmarkId.jpg"
        File(dir, "$bookmarkId.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        relative
    }.getOrNull()

    fun thumbFile(app: HellFireApp, relative: String?): File? {
        if (relative.isNullOrBlank() || relative.contains("..")) return null
        val file = File(app.filesDir, relative)
        return file.takeIf { it.isFile && it.canonicalPath.startsWith(File(app.filesDir, "player-thumbs").canonicalPath) }
    }

    private fun extract(app: HellFireApp, item: MediaItem, positionMs: Long): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        var source: EncryptedMediaSource? = null
        try {
            if (item.storage == StorageMode.MANAGED.name) {
                source = EncryptedMediaSource(app.media.reader(item))
                retriever.setDataSource(source)
            } else {
                val uri = item.externalUri ?: return null
                retriever.setDataSource(app, Uri.parse(uri))
            }
            retriever.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            runCatching { retriever.release() }
            runCatching { source?.close() }
        }
    }.getOrNull()
}
