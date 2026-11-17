package com.hellfire.ass.library

import androidx.room.*
import com.hellfire.ass.media.MediaItem

/*
 * Schema v18 library records. Every content-bearing thing is a media_item row (see MediaItem); the tables here
 * hang analysis, organisation, playback, capture, sharing, sync and backup state off media_item.id or
 * inbox_item.id. IDs are random UUID strings (globally stable across devices). No file bytes live in Room:
 * originals are in MediaVault, derived files (thumbnails, share copies) are app-private relative paths.
 * Data separation (spec §27): original bytes = MediaVault; embedded metadata = media_item.originalMetadata;
 * raw inference = media_analysis.rawJson; user edits = media_analysis.editedJson; categories/tags = link tables;
 * search = library_search/source_search/media_embedding (rebuildable, not backed up); sync = sync_record;
 * backup = backup_manifest/backup_item.
 */

private const val CASCADE = ForeignKey.CASCADE
private const val SET_NULL = ForeignKey.SET_NULL

/** One versioned analysis run. rawJson is the untouched model/parser output; editedJson holds only user-edited fields. */
@Entity(tableName = "media_analysis", indices = [Index(value = ["mediaId", "analyzer", "createdAt"])],
    foreignKeys = [ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = CASCADE)])
data class MediaAnalysis(@PrimaryKey val id: String, val mediaId: String,
    /** Pipeline name, e.g. image_labels, ocr, scenes, transcript_summary, categorize. */
    val analyzer: String, val modelId: String, val modelVersion: String,
    /** Version of the app-side pipeline that produced the run; bump to re-analyse. */
    val pipelineVersion: Int,
    /** COMPLETED, PARTIAL or FAILED. */
    val state: String, val rawJson: String, val editedJson: String? = null,
    /** Plain searchable text derived from the result (indexed into library_search). */
    val text: String = "", val confidence: Double? = null, val createdAt: Long, val editedAt: Long? = null)

/** Timestamped scene/chapter/topic of an audio or video item. labels is a JSON array of detected tags/objects. */
@Entity(tableName = "media_segment", indices = [Index(value = ["mediaId", "startMs"]), Index("analysisId")], foreignKeys = [
    ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = CASCADE),
    ForeignKey(entity = MediaAnalysis::class, parentColumns = ["id"], childColumns = ["analysisId"], onDelete = CASCADE)])
data class MediaSegment(@PrimaryKey val id: String, val mediaId: String, val analysisId: String?, val kind: String,
    val startMs: Long, val endMs: Long, val title: String = "", val description: String = "", val labels: String = "[]",
    /** App-private relative path of a derived thumbnail, or null. */
    val thumbnailPath: String? = null, val confidence: Double? = null, val createdAt: Long)

/** Visual/audio embedding of an item or a time range. Rebuildable; excluded from backups like semantic_vector. */
@Entity(tableName = "media_embedding", indices = [Index(value = ["mediaId", "model"]), Index("model")],
    foreignKeys = [ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = CASCADE)])
data class MediaEmbedding(@PrimaryKey val id: String, val mediaId: String, val model: String, val startMs: Long? = null,
    val endMs: Long? = null, val vector: ByteArray, val createdAt: Long)

/** One node of the unified category tree. parentId/mergedInto are plain ids (no FK) so rows restore in any order. */
@Entity(tableName = "media_category", indices = [Index(value = ["parentId", "name"]), Index("mergedInto")])
data class MediaCategory(@PrimaryKey val id: String, val parentId: String?, val name: String, val origin: String,
    val isPrivate: Boolean = false, val policy: String = "DEFAULT", val mergedInto: String? = null, val createdAt: Long, val updatedAt: Long)

/** Item ↔ category. A USER row (assigned or EXCLUDED) is a correction that AI suggestions never overwrite. */
@Entity(tableName = "media_item_category", primaryKeys = ["itemId", "categoryId"], indices = [Index("categoryId")], foreignKeys = [
    ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = CASCADE),
    ForeignKey(entity = MediaCategory::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = CASCADE)])
data class ItemCategory(val itemId: String, val categoryId: String, val origin: String, val state: String = "ASSIGNED",
    val confidence: Double? = null, val updatedAt: Long)

/** Item ↔ free tag (normalised lower case). Same USER-wins/EXCLUDED rules as categories. */
@Entity(tableName = "media_item_tag", primaryKeys = ["itemId", "tag"], indices = [Index("tag")],
    foreignKeys = [ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = CASCADE)])
data class ItemTag(val itemId: String, val tag: String, val origin: String, val state: String = "ASSIGNED", val updatedAt: Long)

/** Album, collection or playlist (kind = CollectionKind). */
@Entity(tableName = "media_collection")
data class MediaCollection(@PrimaryKey val id: String, val name: String, val kind: String, val description: String = "",
    val coverItemId: String? = null, val createdAt: Long, val updatedAt: Long)

/** Ordered membership; position ascending is play/display order. */
@Entity(tableName = "collection_member", primaryKeys = ["collectionId", "itemId"],
    indices = [Index("itemId"), Index(value = ["collectionId", "position"])], foreignKeys = [
    ForeignKey(entity = MediaCollection::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = CASCADE),
    ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = CASCADE)])
data class CollectionMember(val collectionId: String, val itemId: String, val position: Long, val addedAt: Long)

/** Manual (origin USER) or AI marker (origin AI). Converting an AI marker to manual sets origin USER. endMs makes it a range. */
@Entity(tableName = "media_bookmark", indices = [Index(value = ["mediaId", "startMs"]), Index("categoryId")], foreignKeys = [
    ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = CASCADE),
    ForeignKey(entity = MediaCategory::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = SET_NULL)])
data class MediaBookmark(@PrimaryKey val id: String, val mediaId: String, val startMs: Long, val endMs: Long? = null,
    val title: String = "", val note: String = "", val categoryId: String? = null, val thumbnailPath: String? = null,
    val favorite: Boolean = false, val hidden: Boolean = false, val origin: String = "USER",
    /** The scene an AI marker came from, if any. */
    val segmentId: String? = null, val createdAt: Long, val updatedAt: Long)

/** Resume point and player choices per item. Track ids are Media3 track-group descriptors chosen by the player. */
@Entity(tableName = "playback_state",
    foreignKeys = [ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = CASCADE)])
data class PlaybackState(@PrimaryKey val mediaId: String, val positionMs: Long = 0, val speed: Float = 1f,
    val audioTrack: String? = null, val subtitleTrack: String? = null, val loopStartMs: Long? = null, val loopEndMs: Long? = null,
    val completed: Boolean = false, val lastPlayedAt: Long)

@Entity(tableName = "play_history", indices = [Index("mediaId"), Index("startedAt")],
    foreignKeys = [ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = CASCADE)])
data class PlayHistory(@PrimaryKey val id: String, val mediaId: String, val startedAt: Long, val endedAt: Long? = null,
    val fromMs: Long, val toMs: Long? = null)

/**
 * Durable, resumable background job. One row per (type, itemId): enqueueing again is a no-op. checkpoint is
 * opaque JSON owned by the job type (e.g. {"doneMs":120000}) so completed segments are never re-processed.
 * itemId is null for item-less jobs (e.g. a backup run).
 */
@Entity(tableName = "processing_job", indices = [Index(value = ["type", "itemId"], unique = true),
    Index(value = ["state", "priority", "notBefore"]), Index("itemId"), Index("claimToken")],
    foreignKeys = [ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = CASCADE)])
data class ProcessingJob(@PrimaryKey val id: String, val type: String, val itemId: String?, val state: String = "PENDING",
    val priority: Int = JobPriority.BACKGROUND, val checkpoint: String = "{}", val progress: Double = 0.0, val attempts: Int = 0,
    val error: String? = null, val notBefore: Long = 0, val claimToken: String? = null, val createdAt: Long, val updatedAt: Long)

/**
 * One intentional drawer/share capture (spec §16). The captured content is media_item rows linked through
 * inbox_member (several for a multi-item drop). originalRef is untrusted provenance text (a URL or sending app),
 * never instructions. Processing, sync and backup states are independent (spec §23.3).
 */
@Entity(tableName = "inbox_item", indices = [Index("capturedAt"), Index("lifecycle")])
data class InboxItem(@PrimaryKey val id: String, val contentKind: String, val title: String = "", val summary: String = "",
    val notes: String = "", val originalRef: String? = null, val origin: String = "DRAWER",
    val captureState: String = "COMPLETE", val captureError: String? = null, val lifecycle: String = "ACTIVE",
    val favorite: Boolean = false, val processingState: String = "PENDING", val syncState: String = "PENDING",
    val backupState: String = "NOT_BACKED_UP", val capturedAt: Long, val updatedAt: Long, val trashedAt: Long? = null)

@Entity(tableName = "inbox_member", primaryKeys = ["inboxId", "itemId"], indices = [Index("itemId")], foreignKeys = [
    ForeignKey(entity = InboxItem::class, parentColumns = ["id"], childColumns = ["inboxId"], onDelete = CASCADE),
    ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = CASCADE)])
data class InboxMember(val inboxId: String, val itemId: String, val position: Int)

/** Drawer conversation turn. Clearing context sets inContext = false; it never deletes the saved item. */
@Entity(tableName = "drawer_message", indices = [Index(value = ["sessionId", "createdAt"]), Index("inboxId")],
    foreignKeys = [ForeignKey(entity = InboxItem::class, parentColumns = ["id"], childColumns = ["inboxId"], onDelete = CASCADE)])
data class DrawerMessage(@PrimaryKey val id: String, val sessionId: String, val inboxId: String?, val role: String,
    val text: String, val createdAt: Long, val inContext: Boolean = true)

/** Recommended contextual action and its execution record (state = ActionState). payload is action-specific JSON. */
@Entity(tableName = "action_record", indices = [Index("inboxId"), Index("itemId")], foreignKeys = [
    ForeignKey(entity = InboxItem::class, parentColumns = ["id"], childColumns = ["inboxId"], onDelete = CASCADE),
    ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = CASCADE)])
data class ActionRecord(@PrimaryKey val id: String, val inboxId: String?, val itemId: String?, val actionType: String,
    val label: String, val payload: String = "{}", val state: String = "PROPOSED", val result: String? = null,
    val error: String? = null, val createdAt: Long, val updatedAt: Long)

/** A saved place. Coordinates are null when only an address/text was captured. */
@Entity(tableName = "saved_location", indices = [Index("inboxId"), Index("itemId")], foreignKeys = [
    ForeignKey(entity = InboxItem::class, parentColumns = ["id"], childColumns = ["inboxId"], onDelete = SET_NULL),
    ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = SET_NULL)])
data class SavedLocation(@PrimaryKey val id: String, val inboxId: String?, val itemId: String?, val label: String,
    val latitude: Double? = null, val longitude: Double? = null, val address: String = "",
    /** The captured original text (geo: URI, map link, address). */
    val sourceText: String = "", val note: String = "", val createdAt: Long, val updatedAt: Long)

/** A sanitized share copy (spec §19–22). The original is never modified; outputPath is an app-private cache path. */
@Entity(tableName = "share_export", indices = [Index("itemId")],
    foreignKeys = [ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = CASCADE)])
data class ShareExport(@PrimaryKey val id: String, val itemId: String, val startMs: Long? = null, val endMs: Long? = null,
    val profile: String, val captionStyle: String? = null,
    /** User-approved caption/description text actually exported. */
    val approvedText: String = "", val sidecarJson: String? = null, val outputPath: String? = null, val outputMime: String? = null,
    val outputSize: Long? = null, val outputHash: String? = null, val state: String = "PREPARING",
    val verification: String = "NOT_VERIFIED", val error: String? = null, val createdAt: Long, val updatedAt: Long,
    /** When the Android share sheet was launched; never evidence that anything was published. */
    val handedOffAt: Long? = null)

/** One metadata field inspected during sanitization, e.g. field=GPSLatitude container=EXIF status=REMOVED. */
@Entity(tableName = "privacy_finding", indices = [Index("exportId")],
    foreignKeys = [ForeignKey(entity = ShareExport::class, parentColumns = ["id"], childColumns = ["exportId"], onDelete = CASCADE)])
data class PrivacyFinding(@PrimaryKey val id: String, val exportId: String, val field: String, val container: String,
    val status: String, val detail: String = "")

/**
 * Sync state per portable entity and the durable outbound queue (state PENDING/FAILED = queued). entityType is the
 * table name (media_item, inbox_item, media_bookmark, ...). deleted = tombstone to propagate. bytesDone/uploadSession
 * resume chunked original uploads.
 */
@Entity(tableName = "sync_record", primaryKeys = ["entityType", "entityId"], indices = [Index(value = ["state", "nextAttemptAt"])])
data class SyncRecord(val entityType: String, val entityId: String, val state: String = "PENDING", val deleted: Boolean = false,
    val remoteId: String? = null, val version: Long = 0, val localHash: String? = null, val remoteUpdatedAt: Long? = null,
    val bytesTotal: Long = 0, val bytesDone: Long = 0, val uploadSession: String? = null, val attempts: Int = 0,
    val nextAttemptAt: Long = 0, val error: String? = null, val updatedAt: Long, val syncedAt: Long? = null)

@Entity(tableName = "sync_conflict", indices = [Index(value = ["entityType", "entityId"])])
data class SyncConflict(@PrimaryKey val id: String, val entityType: String, val entityId: String, val localJson: String,
    val remoteJson: String, val detectedAt: Long, val resolvedAt: Long? = null, val resolution: String? = null)

/** One backup run. state RUNNING/COMPLETE/VERIFIED/FAILED; kind FULL/INCREMENTAL (baseId = previous manifest). */
@Entity(tableName = "backup_manifest", indices = [Index("createdAt")])
data class BackupManifest(@PrimaryKey val id: String, val destination: String, val kind: String = "FULL", val baseId: String? = null,
    val schema: Int, val state: String = "RUNNING", val itemCount: Int = 0, val bytes: Long = 0, val archiveHash: String? = null,
    val remoteRef: String? = null, val createdAt: Long, val completedAt: Long? = null, val verifiedAt: Long? = null, val error: String? = null)

/** An original included in a backup. Historical (no FK to media_item). Counts as backed up only with verifiedAt set and a hash matching the item. */
@Entity(tableName = "backup_item", primaryKeys = ["manifestId", "itemId"], indices = [Index("itemId")],
    foreignKeys = [ForeignKey(entity = BackupManifest::class, parentColumns = ["id"], childColumns = ["manifestId"], onDelete = CASCADE)])
data class BackupItemRecord(val manifestId: String, val itemId: String, val contentHash: String, val size: Long,
    val backedUpAt: Long, val verifiedAt: Long? = null)

/** Full-text index over library text (segments, bookmarks, analysis, inbox). Rebuildable via LibraryDao.rebuildSearch; not backed up. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["ref", "itemId", "kind"])
@Entity(tableName = "library_search")
data class LibrarySearch(
    /** Row id in the table named by kind. */
    val ref: String,
    /** media_item.id, or inbox_item.id for kind INBOX. */
    val itemId: String,
    /** SEGMENT, BOOKMARK, ANALYSIS or INBOX. */
    val kind: String, val text: String)
