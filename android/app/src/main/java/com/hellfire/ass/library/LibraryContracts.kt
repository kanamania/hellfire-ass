package com.hellfire.ass.library

import java.net.URLDecoder
import java.net.URLEncoder
import com.hellfire.ass.ui.Route
import com.hellfire.ass.ui.Screen

/*
 * Shared contracts for the universal library (schema v18). Room columns store these values as their
 * enum `name` strings, matching the project convention of plain String state columns. Parse with
 * `valueOf` inside runCatching: rows restored from newer backups may carry values this build lacks.
 */

/** What a stored or captured thing is. Detected from MIME first; drawer/text detection may refine TEXT into URL/LOCATION/CODE. */
enum class ContentKind {
    IMAGE, VIDEO, AUDIO, DOCUMENT, TEXT, URL, LOCATION, CONTACT, EVENT, CODE, OTHER;

    companion object {
        private val documents = setOf("application/pdf", "application/msword", "application/rtf", "application/epub+zip",
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.ms-excel", "application/vnd.ms-powerpoint")
        private val code = setOf("application/json", "application/xml", "application/javascript", "application/x-sh",
            "text/x-kotlin", "text/x-java", "text/x-python", "text/javascript", "text/x-c", "text/x-c++", "text/xml", "text/css", "text/html")

        /** Deterministic MIME classification. Unknown or missing types are OTHER, never guessed from the file name. */
        fun fromMime(mime: String?): ContentKind {
            val type = mime.orEmpty().substringBefore(';').trim().lowercase()
            return when {
                type.startsWith("image/") -> IMAGE
                type.startsWith("video/") -> VIDEO
                type.startsWith("audio/") || type == "application/ogg" -> AUDIO
                type == "text/uri-list" -> URL
                type == "text/vcard" || type == "text/x-vcard" -> CONTACT
                type == "text/calendar" -> EVENT
                type in code -> CODE
                type in documents || type.startsWith("application/vnd.openxmlformats-officedocument.") -> DOCUMENT
                type.startsWith("text/") -> TEXT
                else -> OTHER
            }
        }
    }
}

/** MANAGED: original bytes copied into encrypted MediaVault storage. EXTERNAL: indexed MediaStore/SAF URI, no copy, never synced unless promoted. */
enum class StorageMode { MANAGED, EXTERNAL }

/** Whether the original can currently be opened. UNVERIFIED after a restore until a scanner re-checks an external URI. */
enum class Availability { AVAILABLE, MISSING, PERMISSION_REVOKED, CHANGED, UNVERIFIED }

/** How an item entered the library (media_item.origin / inbox_item.origin). */
enum class CaptureOrigin { IMPORT, DRAWER, SHARE, RECORDING, CALL_FOLDER, MEDIASTORE, SAF_FOLDER, SYNC }

/** Spec §17 processing states for processing_job.state; also used for inbox_item.processingState. */
enum class JobState {
    PENDING, PREPARING, PROCESSING, INDEXING, COMPLETED, PAUSED, FAILED, RETRY_PENDING;
    companion object {
        val queued = listOf(PENDING.name, RETRY_PENDING.name)
        val running = listOf(PREPARING.name, PROCESSING.name, INDEXING.name)
    }
}

/** Spec §17 priority order; lower runs first (processing_job.priority). */
object JobPriority {
    const val PLAYBACK = 1
    const val USER_REQUESTED = 2
    const val CAPTURE = 3
    const val BACKGROUND = 4
}

/** processing_job types. The intelligence worker claims only [ANALYSIS] (= ContentEngine.JOB); older builds used other names. */
object JobTypes {
    const val ANALYSIS = "intelligence"
    val LEGACY_ANALYSIS = listOf("ANALYZE", "transcribe")
    fun normalize(type: String) = if (type in LEGACY_ANALYSIS) ANALYSIS else type
}

/** Who produced a category link, tag, bookmark or analysis edit. USER rows always win over AI suggestions. */
enum class Origin { AI, USER }

/** Category/tag link state. EXCLUDED is a persisted user correction: re-indexing must not re-add the link. */
enum class LinkState { ASSIGNED, EXCLUDED }

/** Per-category processing policy (media_category.policy). */
enum class CategoryPolicy { DEFAULT, SKIP_ANALYSIS, SKIP_TRANSCRIPTION, LOCAL_ONLY }

enum class CollectionKind { ALBUM, COLLECTION, PLAYLIST }

enum class SegmentKind { SCENE, CHAPTER, TOPIC, SHOT }

/** Universal inbox lifecycle. DELETED rows are pending purge; sync keeps its own tombstone in sync_record. */
enum class InboxLifecycle { ACTIVE, ARCHIVED, TRASHED, DELETED }

/** Spec §13: an incomplete capture is recorded explicitly instead of being reported as saved. */
enum class CaptureCompleteness { COMPLETE, INCOMPLETE, FAILED }

/** Per-item sync status (inbox_item.syncState, sync_record.state). LOCAL_ONLY means not selected for sync. */
enum class SyncState { LOCAL_ONLY, PENDING, UPLOADING, SYNCED, FAILED, CONFLICT, PAUSED }

/** Per-item backup status (inbox_item.backupState). BACKED_UP only after original + records were written and verified. */
enum class BackupState { NOT_BACKED_UP, PENDING, BACKED_UP, FAILED }

enum class MessageRole { USER, ASSISTANT, SYSTEM }

/** Contextual action lifecycle; external side effects require APPROVED first (AGENTS.md). */
enum class ActionState { PROPOSED, APPROVED, EXECUTED, FAILED, DISMISSED }

/** Spec §19.2 export choices (share_export.profile). */
enum class ShareProfile { MEDIA_ONLY, MEDIA_CAPTION, MEDIA_CATEGORIES, MEDIA_DESCRIPTION, MEDIA_FULL_METADATA, MEDIA_JSON_SIDECAR }

enum class ExportState { PREPARING, READY, FAILED, HANDED_OFF }

/** Sanitization verification result; never VERIFIED unless the output was re-inspected (spec §20.4). */
enum class ExportVerification { NOT_VERIFIED, VERIFIED, FAILED, UNSUPPORTED }

enum class FindingStatus { FOUND_IN_ORIGINAL, REMOVED, STILL_PRESENT, UNSUPPORTED }

/**
 * Route contract for the media-intelligence screens. A [Route] carries one string; these screens encode it as
 * `key=value&key=value` (URL-encoded). Always build routes with these functions and read them with [args];
 * never concatenate arg strings by hand. Keys:
 *  - Library: optional `kind` (ContentKind), `collection` (media_collection.id), `category` (media_category.id)
 *  - MediaDetail / ShareWithAi / Player: `id` = media_item.id; Player adds `start` (ms); ShareWithAi adds optional `start`,`end` (ms)
 *  - InboxItem: `id` = inbox_item.id;  Search: `q` (free text, may be empty)
 *  - UniversalInbox, ProcessingQueue, SyncDashboard, BackupDashboard, StatusDashboard, MediaSettings: no args
 */
object LibraryRoutes {
    fun library(kind: ContentKind? = null, collectionId: String? = null, categoryId: String? = null) =
        route(Screen.Library, "kind" to kind?.name, "collection" to collectionId, "category" to categoryId)
    fun mediaDetail(itemId: String) = route(Screen.MediaDetail, "id" to itemId)
    fun player(itemId: String, startMs: Long = 0) = route(Screen.Player, "id" to itemId, "start" to startMs.takeIf { it > 0 }?.toString())
    fun universalInbox() = Route(Screen.UniversalInbox)
    fun inboxItem(inboxId: String) = route(Screen.InboxItem, "id" to inboxId)
    fun search(query: String = "") = route(Screen.Search, "q" to query.ifEmpty { null })
    fun shareWithAi(itemId: String, startMs: Long? = null, endMs: Long? = null) =
        route(Screen.ShareWithAi, "id" to itemId, "start" to startMs?.toString(), "end" to endMs?.toString())
    fun processingQueue() = Route(Screen.ProcessingQueue)
    fun syncDashboard() = Route(Screen.SyncDashboard)
    fun backupDashboard() = Route(Screen.BackupDashboard)
    fun statusDashboard() = Route(Screen.StatusDashboard)
    fun mediaSettings() = Route(Screen.MediaSettings)

    /** Decoded arguments of a route built above; unknown or malformed pairs are dropped. */
    fun args(arg: String): Map<String, String> = arg.split('&').mapNotNull { pair ->
        val key = pair.substringBefore('=', "")
        if (key.isEmpty()) null else runCatching { decode(key) to decode(pair.substringAfter('=')) }.getOrNull()
    }.toMap()
    fun id(route: Route): String = args(route.arg)["id"].orEmpty()
    fun startMs(route: Route): Long? = args(route.arg)["start"]?.toLongOrNull()?.takeIf { it >= 0 }
    fun endMs(route: Route): Long? = args(route.arg)["end"]?.toLongOrNull()?.takeIf { it >= 0 }

    private fun route(screen: Screen, vararg pairs: Pair<String, String?>) = Route(screen,
        pairs.filter { it.second != null }.joinToString("&") { "${encode(it.first)}=${encode(it.second!!)}" })
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
}
