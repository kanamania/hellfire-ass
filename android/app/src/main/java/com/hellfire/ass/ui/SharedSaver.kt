package com.hellfire.ass.ui

import com.hellfire.ass.R

import android.net.Uri
import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.*
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.memory.AiSkills
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** One explicitly approved share, with durable per-file checkpoints. MediaVault also deduplicates by content hash. */
object SharedSaver {
    private val lock = Mutex()
    data class Result(val sourceId: String, val taskId: String?, val savedFiles: Int)
    fun key(requestId: String, action: String, text: String, subject: String, files: List<Uri>) = AiSkills.key(listOf(requestId, action, text, subject, files.joinToString()).joinToString("\u0001"))
    suspend fun save(app: HellFireApp, requestId: String, action: String, text: String, subject: String, files: List<Uri>): Result = lock.withLock {
        require(files.size <= 20 && files.all { it.scheme == "content" })
        require(text.length <= 60000 && subject.length <= 200 && (text.isNotBlank() || files.isNotEmpty()))
        require(action in setOf("Save note", "Create reminder", "Review expense"))
        val key = key(requestId, action, text, subject, files)
        val all = app.secrets.read("share-progress") ?: JSONObject()
        val progress = all.optJSONObject(key) ?: JSONObject().put("at", System.currentTimeMillis()).also { all.put(key, it); app.secrets.save("share-progress", all) }
        val now = progress.getLong("at")
        val (sourceId, taskId) = saveText(app.database, app.events, app.packageName, key, now, action, text, subject)
        val done = progress.optJSONObject("files") ?: JSONObject().also { progress.put("files", it) }
        for (uri in files) {
            if (done.has(uri.toString())) continue
            val mime = app.contentResolver.getType(uri).orEmpty()
            val kind = when { mime.startsWith("image/") -> "IMAGE"; mime.startsWith("audio/") -> "VOICE_NOTE"; mime.startsWith("video/") -> "VIDEO"; mime == "application/pdf" -> "DOCUMENT"; else -> throw IllegalArgumentException("Unsupported file. Share an image, PDF, audio or video.") }
            val item = app.media.import(uri, kind)
            com.hellfire.ass.media.MediaWork.queue(app, item.id)
            done.put(uri.toString(), item.id); app.secrets.save("share-progress", all)
        }
        progress.put("complete", true); app.secrets.save("share-progress", all)
        Result(sourceId, taskId, done.length())
    }
    suspend fun saveText(db: com.hellfire.ass.data.EventDatabase, events: com.hellfire.ass.data.EventRepository, packageName: String,
        key: String, now: Long, action: String, text: String, subject: String): Pair<String, String?> {
        require(action in setOf("Save note", "Create reminder", "Review expense") && text.length <= 60000 && subject.length <= 200)
        val raw = NotificationSnapshot("share:$key", "owner.share", now, now, source = "owner_share", fields = mapOf("text" to listOf(text), "title" to listOf(subject)))
        val safe = requireNotNull(NotificationNormalizer.normalize(raw, packageName))
        if (safe.securitySensitive) throw com.hellfire.ass.OwnerError(R.string.ui_remove_authentication_content_before)
        val sanitized = safe.snapshot.fields["text"].orEmpty().joinToString("\n")
        val recordId = UUID.nameUUIDFromBytes(key.toByteArray()).toString()
        db.withTransaction {
            if (!db.events().exists(safe.id)) {
                events.capture(raw, packageName)
                if (sanitized.isNotBlank()) when (action) {
                    "Save note" -> db.memory().insert(MemoryItem(recordId, safe.id, safe.snapshot.fields["title"]?.firstOrNull()?.ifBlank { "Shared note" } ?: "Shared note", sanitized.take(4000), "OWNER_NOTE", 100, now, now))
                    "Review expense" -> com.hellfire.ass.finance.FinanceRepository(db).collect(safe.id, now, "owner.share", sanitized)
                    "Create reminder" -> db.tasks().insert(AssistantTask(recordId, safe.id, sanitized.take(300), now + 3_600_000, party = "OWNER", evidence = sanitized.take(1000), createdAt = now, updatedAt = now))
                }
            }
        }
        return safe.id to recordId.takeIf { action == "Create reminder" && sanitized.isNotBlank() }
    }

}
