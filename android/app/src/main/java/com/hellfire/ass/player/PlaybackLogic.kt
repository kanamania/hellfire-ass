package com.hellfire.ass.player

import com.hellfire.ass.library.MediaBookmark
import com.hellfire.ass.library.MediaSegment
import com.hellfire.ass.library.Origin
import com.hellfire.ass.library.SegmentKind

/** A–B loop range in milliseconds. The original file is never copied. */
data class AbRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0) { "A is before the start" }
        require(endMs > startMs) { "B must be after A" }
    }
}

enum class MarkerKind { BOOKMARK, AI, CHAPTER, SCENE }

data class TimelineMarker(
    val id: String, val startMs: Long, val endMs: Long?, val kind: MarkerKind, val title: String,
    val origin: String, val favorite: Boolean, val segmentId: String?, val hidden: Boolean,
)

data class TranscriptLine(val id: String, val startMs: Long, val endMs: Long, val speaker: String, val text: String)

enum class Presentation { CLOSED, MINI, FULL, PIP }

enum class PresentationEvent { OPEN, MINIMIZE, MAXIMIZE, CLOSE, ENTER_PIP, LEAVE_PIP, PIP_REFUSED, USER_LEAVE }

enum class RepeatMode { OFF, ONE, ALL }

/** Position of the item that is being left, saved before the player switches items. */
data class Outgoing(val itemId: String, val positionMs: Long, val completed: Boolean)

object PlaybackLogic {
    const val NEAR_END_MS = 1_500L
    const val TIMELINE_DRAW_LIMIT = 2_000
    const val TICK_MS = 300L
    val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    val SKIP_CHOICES_MS = longArrayOf(5_000, 10_000, 15_000, 30_000, 60_000)
    val SLEEP_CHOICES_MIN = intArrayOf(15, 30, 45, 60)

    /** Explicit route start wins. A completed or near-end item resumes at the beginning. */
    fun resumeMs(savedPositionMs: Long, completed: Boolean, requestedStartMs: Long?, durationMs: Long?): Long {
        if (requestedStartMs != null && requestedStartMs >= 0) return requestedStartMs
        if (completed) return 0
        if (durationMs != null && durationMs > 0 && savedPositionMs >= durationMs - NEAR_END_MS) return 0
        return savedPositionMs.coerceAtLeast(0)
    }

    /** Position at or past B seeks back to A. Null means do not seek. */
    fun abSeek(positionMs: Long, range: AbRange?): Long? {
        if (range == null || positionMs < range.endMs) return null
        return range.startMs
    }

    fun skip(positionMs: Long, deltaMs: Long, durationMs: Long?): Long {
        val next = positionMs + deltaMs
        val capped = if (durationMs != null && durationMs > 0) next.coerceAtMost(durationMs) else next
        return capped.coerceAtLeast(0)
    }

    /** `h:mm:ss`, `m:ss`, a bare number of seconds, or a `ms` suffix. */
    fun parseTimestamp(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.endsWith("ms", ignoreCase = true)) return text.dropLast(2).trim().toLongOrNull()?.takeIf { it >= 0 }
        if (':' in text) {
            val parts = text.split(':')
            if (parts.size !in 2..3) return null
            val nums = parts.map { it.toLongOrNull() ?: return null }
            if (nums.any { it < 0 }) return null
            val hours = if (nums.size == 3) nums[0] else 0
            val minutes = if (nums.size == 3) nums[1] else nums[0]
            val seconds = nums.last()
            if (minutes > 59 || seconds > 59) return null
            return ((hours * 60 + minutes) * 60 + seconds) * 1000
        }
        return text.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
    }

    fun formatTimestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    fun segmentIndexAt(lines: List<TranscriptLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lines[mid]
            when {
                positionMs < line.startMs -> high = mid - 1
                positionMs >= line.endMs -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    fun applyEdits(lines: List<TranscriptLine>, edits: Map<String, String>): List<TranscriptLine> =
        lines.map { line -> edits[line.id]?.let { line.copy(text = it) } ?: line }

    fun filterTranscript(lines: List<TranscriptLine>, query: String): List<TranscriptLine> {
        val needle = query.trim()
        if (needle.isEmpty()) return lines
        return lines.filter { it.text.contains(needle, ignoreCase = true) || it.speaker.contains(needle, ignoreCase = true) }
    }

    fun toManual(bookmark: MediaBookmark, now: Long): MediaBookmark =
        bookmark.copy(origin = Origin.USER.name, hidden = false, updatedAt = now)

    /**
     * Visible timeline. A bookmark that names a segment replaces that segment. A hidden bookmark
     * with a segment id removes the scene marker from the player without deleting the analysis row.
     */
    fun timeline(bookmarks: List<MediaBookmark>, segments: List<MediaSegment>, kinds: Set<MarkerKind>, includeHidden: Boolean = false): List<TimelineMarker> {
        val suppressed = bookmarks.filter { it.hidden && it.segmentId != null }.mapNotNull { it.segmentId }.toSet()
        val covered = bookmarks.mapNotNull { it.segmentId }.toSet()
        val fromBookmarks = bookmarks.filter { includeHidden || !it.hidden }.mapNotNull { bookmark ->
            val kind = if (bookmark.origin == Origin.AI.name) MarkerKind.AI else MarkerKind.BOOKMARK
            if (kind !in kinds) null else TimelineMarker(bookmark.id, bookmark.startMs, bookmark.endMs, kind, bookmark.title, bookmark.origin, bookmark.favorite, bookmark.segmentId, bookmark.hidden)
        }
        val fromSegments = segments.mapNotNull { segment ->
            if (segment.id in covered || segment.id in suppressed) return@mapNotNull null
            val kind = if (segment.kind == SegmentKind.CHAPTER.name) MarkerKind.CHAPTER else MarkerKind.SCENE
            if (kind !in kinds) return@mapNotNull null
            val title = segment.title.ifBlank { segment.description }.ifBlank { segment.kind }
            TimelineMarker(segment.id, segment.startMs, segment.endMs, kind, title, Origin.AI.name, false, segment.id, false)
        }
        return (fromBookmarks + fromSegments).sortedBy { it.startMs }
    }

    fun drawMarkers(markers: List<TimelineMarker>, limit: Int = TIMELINE_DRAW_LIMIT): List<TimelineMarker> {
        if (markers.size <= limit) return markers
        val step = (markers.size + limit - 1) / limit
        return markers.filterIndexed { index, _ -> index % step == 0 }
    }

    fun neighbor(markers: List<TimelineMarker>, positionMs: Long, forward: Boolean): TimelineMarker? {
        val ordered = markers.sortedBy { it.startMs }
        return if (forward) ordered.firstOrNull { it.startMs > positionMs + 250 }
        else ordered.lastOrNull { it.startMs < positionMs - 250 } ?: ordered.firstOrNull { it.startMs <= positionMs }
    }

    fun transition(current: Presentation, event: PresentationEvent, video: Boolean): Presentation = when (event) {
        PresentationEvent.OPEN, PresentationEvent.MAXIMIZE -> Presentation.FULL
        PresentationEvent.MINIMIZE -> if (current == Presentation.CLOSED) Presentation.CLOSED else Presentation.MINI
        PresentationEvent.CLOSE -> Presentation.CLOSED
        PresentationEvent.ENTER_PIP -> if (video && current != Presentation.CLOSED) Presentation.PIP else current
        PresentationEvent.LEAVE_PIP -> if (current == Presentation.PIP) Presentation.FULL else current
        PresentationEvent.PIP_REFUSED -> if (current == Presentation.PIP) Presentation.FULL else current
        PresentationEvent.USER_LEAVE -> if (video && current == Presentation.FULL) Presentation.PIP else current
    }

    fun shouldAutoEnterPip(video: Boolean, playing: Boolean, presentation: Presentation): Boolean =
        video && playing && presentation == Presentation.FULL

    fun nextIndex(index: Int, size: Int, repeat: RepeatMode, shuffle: Boolean, order: List<Int>): Int? {
        if (size <= 0 || index !in 0 until size) return null
        if (repeat == RepeatMode.ONE) return index
        val sequence = if (shuffle && order.size == size && order.toSet().size == size) order else List(size) { it }
        val place = sequence.indexOf(index).takeIf { it >= 0 } ?: return null
        val next = place + 1
        if (next < sequence.size) return sequence[next]
        return if (repeat == RepeatMode.ALL) sequence.first() else null
    }

    fun sleepDeadline(nowMs: Long, minutes: Int): Long = nowMs + minutes * 60_000L

    fun sleepRemainingMs(nowMs: Long, untilMs: Long?): Long? = untilMs?.let { (it - nowMs).coerceAtLeast(0) }

    fun playbackError(codeName: String): String = when {
        codeName.contains("DECODER") || codeName.contains("DECODING") || codeName.contains("PARSING") || codeName.contains("FORMAT") ->
            "This file cannot be played on this device. The codec or container is not supported. The original was not modified."
        codeName.contains("IO") || codeName.contains("FILE") || codeName.contains("TIMEOUT") ->
            "The original could not be read. Nothing was written out as a decrypted file."
        else -> "Playback failed ($codeName). The original was not modified."
    }

    fun trackKey(type: Int, group: Int, track: Int) = "$type:$group:$track"

    fun parseTrackKey(key: String): Triple<Int, Int, Int>? {
        val parts = key.split(':')
        if (parts.size != 3) return null
        val type = parts[0].toIntOrNull() ?: return null
        val group = parts[1].toIntOrNull() ?: return null
        val track = parts[2].toIntOrNull() ?: return null
        if (group < 0 || track < 0) return null
        return Triple(type, group, track)
    }

    fun shouldRestart(currentId: String?, requestedId: String, explicitStart: Long?): Boolean =
        currentId != requestedId || explicitStart != null

    /** Publishes the live position while playing, so the slider, timeline and transcript highlight move. */
    suspend fun tickPositions(intervalMs: Long, playing: () -> Boolean, read: () -> Long, publish: (Long) -> Unit) {
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            if (playing()) publish(read().coerceAtLeast(0))
        }
    }

    /** What to save for the item being left. A repeat of the same item saves nothing. */
    fun outgoing(lastId: String?, lastPositionMs: Long, newId: String?, autoAdvance: Boolean): Outgoing? {
        if (lastId == null || lastId == newId) return null
        return Outgoing(lastId, lastPositionMs.coerceAtLeast(0), autoAdvance)
    }

    fun savedLoop(startMs: Long?, endMs: Long?): AbRange? =
        if (startMs != null && endMs != null && startMs >= 0 && endMs > startMs) AbRange(startMs, endMs) else null

    /** List position of the active transcript line. [before] is the number of list items above the transcript lines. */
    fun transcriptListIndex(before: Int, shown: List<TranscriptLine>, activeId: String?): Int? {
        if (activeId == null) return null
        val index = shown.indexOfFirst { it.id == activeId }
        return if (index < 0) null else before + index
    }

    /** Leaving PiP while the activity is no longer started means the user closed the PiP window. */
    fun pipDismissed(inPip: Boolean, activityStarted: Boolean): Boolean = !inPip && !activityStarted

    /** Marker that contains the position, for sharing the current scene or chapter. */
    fun markerAt(markers: List<TimelineMarker>, positionMs: Long): TimelineMarker? =
        markers.filter { it.endMs != null && positionMs >= it.startMs && positionMs < it.endMs }.maxByOrNull { it.startMs }
}
