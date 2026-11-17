package com.hellfire.ass.media

import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.library.Availability
import com.hellfire.ass.library.CaptureOrigin
import com.hellfire.ass.library.ContentKind
import com.hellfire.ass.library.StorageMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Managed encrypted storage for originals (spec §13, §16, §27). Contract for every feature:
 *  - [capture] (Uri, bytes or stream) copies the original into `files/media/<id>.hfm` as authenticated AES-GCM
 *    chunks while hashing (SHA-256), deduplicates against existing MANAGED items, then records a media_item row
 *    (storage MANAGED, stable UUID id, contentKind from MIME) plus its provenance source_event. Saving completes
 *    before any analysis; nothing is ever decrypted to disk during capture.
 *  - [promote] turns an EXTERNAL index entry into a MANAGED one in place (same id, so categories/bookmarks stay).
 *  - [openStream] / [dataSource] read the plaintext lazily, 64 KiB at a time with random access — never the whole
 *    file in memory. Use [dataSource] for MediaMetadataRetriever/MediaExtractor/MediaPlayer and a Media3 DataSource
 *    built on [reader] for ExoPlayer.
 *  - [withPlainCopy] is the last resort for APIs that need a real file path: a temporary plaintext copy in
 *    no-backup storage, deleted when the block returns (and swept by [recover] after a crash).
 */
class MediaVault(private val app: HellFireApp) {
    private val lock = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }
    val directory get() = File(app.filesDir, "media").apply { mkdirs() }
    private val plainDirectory get() = File(app.noBackupFilesDir, "plain-tmp").apply { mkdirs() }
    fun file(id: String): File { UUID.fromString(id); return File(directory, "$id.hfm") }
    fun reader(item: MediaItem) = ChunkedMedia.Reader(file(item.id), app.cipher, item.id, item.size)

    suspend fun import(uri: Uri, kind: String): MediaItem = capture(uri, CaptureOrigin.IMPORT, syncEnabled = false, kind = kind)

    /** Copy a content/file URI into managed storage. [kind] is the legacy purpose (defaults to the ContentKind name). */
    suspend fun capture(uri: Uri, origin: CaptureOrigin, syncEnabled: Boolean = defaultSync(origin), kind: String? = null,
        contentKind: ContentKind? = null): MediaItem = withContext(Dispatchers.IO) {
        var name = "Selected media"
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty().take(200).ifBlank { name }
        }
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        val duration = if (mime.startsWith("audio/") || mime.startsWith("video/")) runCatching {
            android.media.MediaMetadataRetriever().run {
                try { setDataSource(app, uri); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } }
                finally { release() }
            }
        }.getOrNull() else null
        val input = app.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("Original unavailable")
        input.use { capture(it, name, mime, origin, syncEnabled, kind, contentKind, uri.toString(), duration) }
    }

    suspend fun capture(bytes: ByteArray, name: String, mime: String, origin: CaptureOrigin, syncEnabled: Boolean = defaultSync(origin),
        kind: String? = null, contentKind: ContentKind? = null): MediaItem =
        capture(bytes.inputStream(), name, mime, origin, syncEnabled, kind, contentKind)

    /** Encrypt, hash and register [input] (not closed here). Returns the existing managed item when the content hash already exists. */
    suspend fun capture(input: InputStream, name: String, mime: String, origin: CaptureOrigin, syncEnabled: Boolean = defaultSync(origin),
        kind: String? = null, contentKind: ContentKind? = null, originRef: String = "", durationMs: Long? = null): MediaItem = withContext(Dispatchers.IO) {
        val content = contentKind ?: ContentKind.fromMime(mime)
        lock.withLock {
            val id = UUID.randomUUID().toString()
            val (hash, size) = encrypt(input, id)
            app.database.media().hash(hash)?.let { existing ->
                File(directory, "$id.importing").delete()
                if (syncEnabled && !existing.syncEnabled) app.database.library().setSyncEnabled(existing.id, true)
                return@withLock app.database.library().item(existing.id)!!
            }
            java.nio.file.Files.move(File(directory, "$id.importing").toPath(), file(id).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            try {
                register(id, name.take(200), mime, kind ?: content.name, hash, size, null, false, originRef, durationMs, content, origin, syncEnabled)
            } catch (error: Exception) { file(id).delete(); throw error }
        }
    }

    /** Copy an EXTERNAL entry's bytes into managed storage under the same id. A managed duplicate (same hash) is returned instead. */
    suspend fun promote(id: String, syncEnabled: Boolean = true): MediaItem = withContext(Dispatchers.IO) {
        val item = app.database.library().item(id) ?: throw IllegalArgumentException("Unknown item")
        if (item.storage == StorageMode.MANAGED.name) {
            if (syncEnabled && !item.syncEnabled) app.database.library().setSyncEnabled(id, true)
            return@withContext app.database.library().item(id)!!
        }
        val uri = Uri.parse(requireNotNull(item.externalUri))
        val input = app.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("Original unavailable")
        input.use { lock.withLock {
            val (hash, size) = encrypt(it, id)
            app.database.media().hash(hash)?.let { existing ->
                File(directory, "$id.importing").delete()
                app.database.library().setHash(id, hash)
                if (syncEnabled && !existing.syncEnabled) app.database.library().setSyncEnabled(existing.id, true)
                return@withLock app.database.library().item(existing.id)!!
            }
            java.nio.file.Files.move(File(directory, "$id.importing").toPath(), file(id).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            try {
                app.database.withTransaction {
                    val sourceId = source(id, item.name, item.mime, item.kind, hash, item.recordedAt, item.externalUri.orEmpty())
                    app.database.library().updateItem(item.copy(sourceId = sourceId, contentHash = hash, size = size, storage = StorageMode.MANAGED.name,
                        availability = Availability.AVAILABLE.name, syncEnabled = syncEnabled))
                }
                app.database.library().item(id)!!
            } catch (error: Exception) { file(id).delete(); throw error }
        } }
    }

    /** Plaintext of an item. MANAGED: decrypted lazily; EXTERNAL: opened through its URI. The caller closes it. */
    fun openStream(item: MediaItem): InputStream = if (item.storage == StorageMode.EXTERNAL.name)
        app.contentResolver.openInputStream(Uri.parse(requireNotNull(item.externalUri))) ?: throw java.io.FileNotFoundException("Original unavailable")
    else reader(item).let { reader ->
        object : java.io.FilterInputStream(reader.stream()) { override fun close() { reader.close() } }
    }

    /** Random-access plaintext of a MANAGED original for platform decoders (MediaMetadataRetriever.setDataSource, MediaExtractor, MediaPlayer). */
    fun dataSource(item: MediaItem): android.media.MediaDataSource {
        require(item.storage == StorageMode.MANAGED.name) { "External items are opened through their URI" }
        return EncryptedMediaSource(reader(item))
    }

    /** UTF-8 text of a small original (notes, links, code), at most [maxBytes]. */
    fun readText(item: MediaItem, maxBytes: Int = 1_000_000): String = openStream(item).use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (out.size() < maxBytes) { val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
        out.toString(Charsets.UTF_8.name())
    }

    /** Run [block] with a temporary plaintext copy (for APIs that require a path); the copy is always deleted afterwards. */
    suspend fun <T> withPlainCopy(item: MediaItem, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        val copy = File(plainDirectory, "${UUID.randomUUID()}.tmp")
        try {
            openStream(item).use { input -> copy.outputStream().use { input.copyTo(it, 65536) } }
            block(copy)
        } finally { copy.delete() }
    }

    private fun defaultSync(origin: CaptureOrigin) = origin == CaptureOrigin.DRAWER || origin == CaptureOrigin.SHARE

    /** Writes `<id>.importing`; returns (sha256 hex, plaintext size). The partial file is removed on failure. */
    private fun encrypt(input: InputStream, id: String): Pair<String, Long> {
        val temporary = File(directory, "$id.importing")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val written = DigestInputStream(input, digest).let { ChunkedMedia.write(it, temporary, id, false, app.cipher) }
            require(written.size > 0) { "Empty original" }
            return digest.digest().joinToString("") { "%02x".format(it) } to written.size
        } catch (error: Exception) { temporary.delete(); throw error }
    }

    suspend fun importStream(input: InputStream, name: String, mime: String, kind: String, origin: String = "", durationMs: Long? = null): MediaItem =
        capture(input, name, mime, CaptureOrigin.IMPORT, syncEnabled = false, kind = kind, originRef = origin, durationMs = durationMs)
    suspend fun registerRecording(id: String, start: Long?, interrupted: Boolean, spans: List<Pair<Long, Long>>, durationMs: Long? = null,
        mime: String = "audio/aac"): MediaItem = lock.withLock {
        val path = file(id)
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        ChunkedMedia.Reader(path, app.cipher, id).use { reader ->
            size = reader.size
            reader.stream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it) }
        app.database.media().hash(digest)?.let { if (it.id != id) path.delete(); return@withLock it }
        val item = register(id, if (interrupted) "Interrupted conversation recording" else "Conversation recording",
            mime, "IN_PERSON_AUDIO", digest, size, start, interrupted, "", durationMs, ContentKind.AUDIO, CaptureOrigin.RECORDING, false)
        spans.forEachIndexed { index, span -> app.database.media().span(SpeechSpan("$id:vad:$index", id, span.first, span.second)) }
        item
    }
    /** File creation time approximates when an interrupted recording started; unknown when the filesystem does not say. */
    private fun recoveredStart(path: java.io.File): Long? = runCatching {
        java.nio.file.Files.readAttributes(path.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis()
    }.getOrNull()?.takeIf { it > 0 }

    private suspend fun register(id: String, name: String, mime: String, kind: String, hash: String,
        size: Long, recordedAt: Long?, interrupted: Boolean, origin: String, durationMs: Long?,
        contentKind: ContentKind, captureOrigin: CaptureOrigin, syncEnabled: Boolean): MediaItem = app.database.withTransaction {
        val now = System.currentTimeMillis()
        val sourceId = source(id, name, mime, kind, hash, recordedAt, origin)
        val item = MediaItem(id, sourceId, name, mime, kind, hash, size, now, recordedAt,
            state = if (interrupted) "INTERRUPTED_RETAINED" else "RETAINED", durationMs = durationMs,
            contentKind = contentKind.name, origin = captureOrigin.name, syncEnabled = syncEnabled)
        app.database.media().insert(item)
        item
    }
    /** Provenance source event for a managed original; returns its id. */
    private suspend fun source(id: String, name: String, mime: String, kind: String, hash: String, recordedAt: Long?, origin: String): String {
        val now = System.currentTimeMillis()
        val source = when (kind) { "CALL_RECORDING" -> "call_recording_import"; "IN_PERSON_AUDIO" -> "in_person_audio"; else -> "media_import" }
        val snapshot = NotificationSnapshot("media:$hash", "owner.media", recordedAt ?: now, now, source = source,
            fields = mapOf("title" to listOf(name)), provenance = mapOf("media_id" to id, "mime" to mime,
                "content_hash" to hash, "origin_uri" to origin, "recorded_time_known" to (recordedAt != null).toString()))
        val safe = NotificationNormalizer.normalize(snapshot, app.packageName)!!
        app.events.capture(snapshot, app.packageName)
        return safe.id
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val item = app.database.media().item(id) ?: return@withLock
            app.database.media().state(id, "DELETING", null)
            androidx.work.WorkManager.getInstance(app).cancelUniqueWork("analyze-media:$id").result.get()
            val path = file(id)
            if (path.exists() && !path.delete()) {
                app.database.media().state(id, "DELETE_FAILED", "FILE_DELETION_FAILED")
                error("Original could not be deleted")
            }
            app.database.media().delete(item.id)
            com.hellfire.ass.performance.ProcessingCheckpoint.clear(app, id)
        }
    }
    suspend fun recover() = withContext(Dispatchers.IO) {
        app.secrets.read("restore-journal")?.let { journal ->
            val ids = journal.getJSONArray("ids")
            for (index in 0 until ids.length()) {
                val id = ids.getString(index)
                if (app.database.media().item(id) == null) {
                    val path = file(id)
                    check(!path.exists() || path.delete()) { "Interrupted restore cleanup failed" }
                }
            }
            app.secrets.remove("restore-journal")
        }
        for (item in app.database.media().deleting()) runCatching { delete(item.id) }
        // Plaintext copies left by a crash inside withPlainCopy; recent ones may still be in use.
        plainDirectory.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 3_600_000 }.forEach { it.delete() }
        // An unregistered file is the live recording while the microphone is busy; never touch it then.
        if (com.hellfire.ass.audio.MicrophoneStatus.busy.value) return@withContext
        for (path in directory.listFiles().orEmpty().filter { it.extension == "hfm" }) {
            val id = path.nameWithoutExtension
            if (runCatching { UUID.fromString(id) }.isFailure || app.database.media().item(id) != null) continue
            runCatching {
                val recovered = ChunkedMedia.recoverRecording(path, app.cipher)
                // A recording with no authenticated audio is an empty orphan; remove it rather than leave ciphertext behind.
                if (recovered == null) path.delete()
                else registerRecording(id, recoveredStart(path), true, emptyList(), mime = if (recovered.pcm) "audio/pcm" else "audio/aac")
            }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }
        }
    }
}
