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

/**
 * Bounded in-memory frames for the seek bar. Extraction uses a streaming data source, not a plaintext file.
 * Bookmark thumbnails are small unencrypted JPEG frames in no-backup storage, one folder per item, removed
 * with the bookmark or the item.
 */
object FrameScrubber {
    private const val THUMBS = "player-thumbs"
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

    fun writeThumb(app: HellFireApp, bitmap: Bitmap, itemId: String, bookmarkId: String): String? = runCatching {
        require(safe(itemId) && safe(bookmarkId))
        val relative = "$THUMBS/$itemId/$bookmarkId.jpg"
        val file = File(app.noBackupFilesDir, relative)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        relative
    }.getOrNull()

    fun thumbFile(app: HellFireApp, relative: String?): File? {
        if (relative.isNullOrBlank() || relative.contains("..")) return null
        val file = File(app.noBackupFilesDir, relative)
        return file.takeIf { it.isFile && it.canonicalPath.startsWith(base(app).canonicalPath + File.separator) }
    }

    fun deleteThumb(app: HellFireApp, relative: String?) {
        thumbFile(app, relative)?.delete()
    }

    fun deleteForItem(app: HellFireApp, itemId: String) {
        if (!safe(itemId)) return
        File(base(app), itemId).deleteRecursively()
    }

    /** Removes thumbnails written by earlier builds into backed-up app files. */
    fun deleteLegacy(app: HellFireApp) {
        File(app.filesDir, THUMBS).takeIf { it.exists() }?.deleteRecursively()
    }

    /** Drops thumbnail folders of items that were deleted by a path that does not call [deleteForItem]. */
    suspend fun purgeOrphans(app: HellFireApp) = withContext(Dispatchers.IO) {
        deleteLegacy(app)
        base(app).listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            if (app.database.library().item(dir.name) == null) dir.deleteRecursively()
        }
    }

    private fun base(app: HellFireApp) = File(app.noBackupFilesDir, THUMBS)

    private fun safe(id: String) = id.isNotBlank() && id.none { it == '/' || it == '\\' } && id != "." && id != ".."

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
