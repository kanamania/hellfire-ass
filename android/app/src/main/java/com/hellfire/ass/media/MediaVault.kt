package com.hellfire.ass.media

import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

class MediaVault(private val app: HellFireApp) {
    private val lock = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }
    val directory get() = File(app.filesDir, "media").apply { mkdirs() }
    fun file(id: String): File { UUID.fromString(id); return File(directory, "$id.hfm") }
    fun reader(item: MediaItem) = ChunkedMedia.Reader(file(item.id), app.cipher, item.id, item.size)

    suspend fun import(uri: Uri, kind: String): MediaItem = withContext(Dispatchers.IO) {
        var name = "Selected media"
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty().take(200)
        }
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        app.contentResolver.openInputStream(uri)!!.use { importStream(it, name, mime, kind, uri.toString()) }
    }
    suspend fun importStream(input: InputStream, name: String, mime: String, kind: String, origin: String = ""): MediaItem = lock.withLock {
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, "$id.importing")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val written = DigestInputStream(input, digest).use { ChunkedMedia.write(it, temporary, id, false, app.cipher) }
            require(written.size > 0)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            app.database.media().hash(hash)?.let { temporary.delete(); return@withLock it }
            java.nio.file.Files.move(temporary.toPath(), file(id).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            register(id, name, mime, kind, hash, written.size, null, false, origin)
        } catch (error: Exception) { temporary.delete(); throw error }
    }
    suspend fun registerRecording(id: String, start: Long?, interrupted: Boolean, spans: List<Pair<Long, Long>>): MediaItem = lock.withLock {
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
            "audio/pcm", "IN_PERSON_AUDIO", digest, size, start, interrupted, "")
        spans.forEachIndexed { index, span -> app.database.media().span(SpeechSpan("$id:vad:$index", id, span.first, span.second)) }
        item
    }
    private suspend fun register(id: String, name: String, mime: String, kind: String, hash: String,
        size: Long, recordedAt: Long?, interrupted: Boolean, origin: String): MediaItem = app.database.withTransaction {
        val now = System.currentTimeMillis()
        val source = when (kind) { "CALL_RECORDING" -> "call_recording_import"; "IN_PERSON_AUDIO" -> "in_person_audio"; else -> "media_import" }
        val snapshot = NotificationSnapshot("media:$hash", "owner.media", recordedAt ?: now, now, source = source,
            fields = mapOf("title" to listOf(name)), provenance = mapOf("media_id" to id, "mime" to mime,
                "content_hash" to hash, "origin_uri" to origin, "recorded_time_known" to (recordedAt != null).toString()))
        val safe = NotificationNormalizer.normalize(snapshot, app.packageName)!!
        app.events.capture(snapshot, app.packageName)
        val item = MediaItem(id, safe.id, name, mime, kind, hash, size, now, recordedAt,
            state = if (interrupted) "INTERRUPTED_RETAINED" else "RETAINED")
        app.database.media().insert(item)
        item
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
        for (path in directory.listFiles().orEmpty().filter { it.extension == "hfm" }) {
            val id = path.nameWithoutExtension
            if (runCatching { UUID.fromString(id) }.isFailure || app.database.media().item(id) != null) continue
            runCatching {
                ChunkedMedia.recoverRecording(path, app.cipher)?.let { registerRecording(id, null, true, emptyList()) }
            }
        }
    }
}
