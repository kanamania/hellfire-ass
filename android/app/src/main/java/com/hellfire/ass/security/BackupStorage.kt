package com.hellfire.ass.security

import android.content.Context
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.sync.withLock
import java.io.File

data class StorageUsage(
    val managedBytes: Long,
    val managedCount: Int,
    val externalCount: Int,
    val thumbnailBytes: Long,
    val cacheBytes: Long,
    val modelBytes: Long,
    val databaseBytes: Long,
    val freeBytes: Long,
    val backedUp: Int,
    val notBackedUp: Int,
)

/** Usage and cache eviction. Originals under files/media are never deleted here. */
object BackupStorage {
    fun usage(app: HellFireApp): StorageUsage {
        val db = app.database.openHelper.readableDatabase
        val managed = count(db, "SELECT COUNT(*), COALESCE(SUM(size), 0) FROM media_item WHERE storage = 'MANAGED' AND size > 0 AND contentHash IS NOT NULL AND state != 'DELETING'")
        val external = count(db, "SELECT COUNT(*), 0 FROM media_item WHERE storage = 'EXTERNAL' AND state != 'DELETING'")
        val backed = count(db, "SELECT COUNT(*), 0 FROM media_item m WHERE m.storage = 'MANAGED' AND m.size > 0 AND m.contentHash IS NOT NULL AND EXISTS (SELECT 1 FROM backup_item b WHERE b.itemId = m.id AND b.contentHash = m.contentHash AND b.verifiedAt IS NOT NULL)")
        val media = File(app.filesDir, "media")
        val models = File(app.filesDir, "models")
        return StorageUsage(
            managedBytes = managed.second,
            managedCount = managed.first,
            externalCount = external.first,
            thumbnailBytes = bytesOf(File(app.cacheDir, "intelligence-thumbs")) + bytesOf(File(app.noBackupFilesDir, "player-thumbs")),
            cacheBytes = bytesOf(app.cacheDir) + bytesOf(File(app.noBackupFilesDir, "plain-tmp")),
            modelBytes = bytesOf(models),
            databaseBytes = app.getDatabasePath("hellfire.db").length(),
            freeBytes = app.filesDir.usableSpace,
            backedUp = backed.first,
            notBackedUp = (managed.first - backed.first).coerceAtLeast(0),
        )
    }

    /** Scene and bookmark thumbnail folder names (stored under cache/no-backup respectively). */
    val THUMBNAIL_DIRS = listOf("intelligence-thumbs", "player-thumbs")
    /** cacheDir children that are in use by other features: share outputs awaiting hand-off. */
    val KEEP_IN_CACHE = setOf("share", "intelligence-thumbs", "player-thumbs")

    /** Removes orphan derived thumbnails; active outputs, model caches and downloads are retained. */
    suspend fun evictCache(app: HellFireApp): Long {
        val refs = app.database.library().segmentThumbs().toSet()
        var freed = 0L
        File(app.cacheDir, "intelligence-thumbs").listFiles().orEmpty().filter { "intelligence-thumbs/${it.name}" !in refs }.forEach { file ->
            val size = file.length(); if (file.delete()) freed += size
        }
        val players = File(app.noBackupFilesDir, "player-thumbs")
        val before = bytesOf(players)
        com.hellfire.ass.player.FrameScrubber.purgeOrphans(app)
        return freed + (before - bytesOf(players)).coerceAtLeast(0)
    }

    fun evictTree(root: File, protected: File): Long {
        if (!root.exists()) return 0
        val protectedPath = protected.canonicalFile
        if (root.canonicalFile == protectedPath || root.canonicalFile.startsWithChild(protectedPath)) return 0
        var freed = 0L
        root.walkBottomUp().forEach { file ->
            if (file == root) return@forEach
            val canonical = file.canonicalFile
            if (canonical == protectedPath || canonical.startsWithChild(protectedPath) || protectedPath.startsWithChild(canonical)) return@forEach
            if (file.isFile) freed += file.length()
            file.delete()
        }
        return freed
    }

    private fun File.startsWithChild(parent: File) = canonicalPath.startsWith(parent.canonicalPath + File.separator)

    private fun bytesOf(dir: File): Long = if (!dir.isDirectory) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Pair<Int, Long> =
        db.query(sql).use { if (!it.moveToFirst()) 0 to 0L else it.getInt(0) to it.getLong(1) }

    fun modelFiles(context: Context): List<Pair<String, String>> = listOf(
        "Language" to File(context.filesDir, "models/assistant.litertlm"),
        "Speech" to File(context.filesDir, "models/speech.ggml"),
        "Embedding" to File(context.filesDir, "models/text_embedder.tflite"),
        "Speaker" to File(context.filesDir, "models/speaker.onnx"),
    ).map { (name, file) -> name to if (file.isFile && file.length() > 0) "file present" else "not installed" }
}
