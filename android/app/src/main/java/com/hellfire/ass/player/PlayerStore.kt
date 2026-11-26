package com.hellfire.ass.player

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.CollectionKind
import com.hellfire.ass.library.MediaBookmark
import com.hellfire.ass.library.MediaSegment
import com.hellfire.ass.library.Origin
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.TranscriptSegment
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID
import com.hellfire.ass.library.MediaAnalysis

object PlayerStore {
    private const val EDIT_ANALYZER = "transcript_user_edit"

    suspend fun queue(app: HellFireApp, item: MediaItem): List<MediaItem> {
        val playlists = app.database.library().collections(listOf(CollectionKind.PLAYLIST.name)).first()
        for (playlist in playlists) {
            val members = app.database.library().members(playlist.id).first()
            if (members.any { it.id == item.id }) return members
        }
        return listOf(item)
    }

    suspend fun saveBookmark(app: HellFireApp, bookmark: MediaBookmark) {
        app.database.playback().saveBookmark(bookmark)
        if (bookmark.origin == Origin.USER.name) app.database.sync().markChanged("media_bookmark", bookmark.id, false, bookmark.updatedAt)
    }

    suspend fun deleteBookmark(app: HellFireApp, id: String, now: Long) {
        val thumb = app.database.playback().bookmark(id)?.thumbnailPath
        app.database.playback().deleteBookmark(id)
        app.database.sync().markChanged("media_bookmark", id, true, now)
        FrameScrubber.deleteThumb(app, thumb)
    }

    suspend fun convertToManual(app: HellFireApp, id: String, now: Long) {
        app.database.playback().keepAsManual(id, now)
        app.database.sync().markChanged("media_bookmark", id, false, now)
    }

    suspend fun setHidden(app: HellFireApp, id: String, hidden: Boolean, now: Long) {
        app.database.playback().setHidden(id, hidden, now)
        app.database.sync().markChanged("media_bookmark", id, false, now)
    }

    /** Player-level removal of a scene marker. The analysis row stays so search still works. */
    suspend fun dismissSegment(app: HellFireApp, itemId: String, segment: MediaSegment, now: Long) {
        val existing = app.database.playback().bookmarks(itemId, true).first().firstOrNull { it.segmentId == segment.id }
        if (existing != null) {
            setHidden(app, existing.id, true, now)
            return
        }
        saveBookmark(app, MediaBookmark(
            UUID.randomUUID().toString(), itemId, segment.startMs, segment.endMs,
            title = segment.title.ifBlank { segment.description }, origin = Origin.AI.name, hidden = true,
            segmentId = segment.id, createdAt = now, updatedAt = now,
        ))
    }

    suspend fun transcriptEdits(app: HellFireApp, itemId: String): Map<String, String> {
        val row = app.database.library().latestAnalysis(itemId, EDIT_ANALYZER) ?: return emptyMap()
        return parseEdits(row.editedJson)
    }

    suspend fun saveTranscriptEdit(app: HellFireApp, itemId: String, segment: TranscriptSegment, text: String, now: Long) {
        val library = app.database.library()
        val existing = library.latestAnalysis(itemId, EDIT_ANALYZER)
        val edits = parseEdits(existing?.editedJson).toMutableMap()
        edits[segment.id] = text
        val json = JSONObject().apply { edits.forEach { (key, value) -> put(key, value) } }.toString()
        if (existing == null) {
            library.saveAnalysis(MediaAnalysis(
                UUID.randomUUID().toString(), itemId, EDIT_ANALYZER, "user", "1", 1, "COMPLETED", "{}",
                editedJson = json, createdAt = now, editedAt = now,
            ))
        } else library.editAnalysis(existing.id, json, now)
    }

    fun parseEdits(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun lines(segments: List<TranscriptSegment>, edits: Map<String, String>): List<TranscriptLine> =
        PlaybackLogic.applyEdits(segments.map { TranscriptLine(it.id, it.startMs, it.endMs, it.speaker, it.text) }, edits)
}
