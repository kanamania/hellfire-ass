package com.hellfire.ass.media

import androidx.room.*
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.flow.Flow

/**
 * A library entry (schema v18): every stored or indexed piece of content.
 * - storage MANAGED: original bytes encrypted in MediaVault (`file(id)`), contentHash and sourceId set, deduplicated by hash.
 * - storage EXTERNAL: an indexed MediaStore/SAF item at [externalUri]; no copy, no source event, contentHash filled lazily,
 *   never synced or backed up as bytes unless promoted with MediaVault.promote (same id).
 * [kind] keeps the legacy import purpose (CALL_RECORDING, IN_PERSON_AUDIO, VOICE_NOTE, ...); [contentKind] is the
 * library ContentKind. State/analysis columns predate v18 and still drive MediaWork.
 */
@Entity(tableName = "media_item", indices = [Index("contentHash"), Index("sourceId"), Index(value = ["externalUri"], unique = true),
    Index(value = ["contentKind", "importedAt"]), Index("importedAt")],
    foreignKeys = [ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class MediaItem(@PrimaryKey val id: String, val sourceId: String?, val name: String, val mime: String,
    val kind: String, val contentHash: String?, val size: Long, val importedAt: Long, val recordedAt: Long?,
    val processedMs: Long = 0, val state: String = "RETAINED", val summary: String = "", val summaryState: String = "NONE", val error: String? = null,
    /** Recording length saved at import (v10); null when unknown. */
    val durationMs: Long? = null,
    /** StorageMode name. */
    @ColumnInfo(defaultValue = "MANAGED") val storage: String = "MANAGED",
    /** ContentKind name. */
    @ColumnInfo(defaultValue = "OTHER") val contentKind: String = "OTHER",
    val externalUri: String? = null,
    /** Availability name. */
    @ColumnInfo(defaultValue = "AVAILABLE") val availability: String = "AVAILABLE",
    /** CaptureOrigin name. */
    @ColumnInfo(defaultValue = "IMPORT") val origin: String = "IMPORT",
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    /** Source file's last-modified time, for change detection of external entries. */
    val modifiedAt: Long? = null, val width: Int? = null, val height: Int? = null,
    /** Original embedded metadata (EXIF/container tags) as JSON; private, never exported in shares. */
    val originalMetadata: String? = null,
    /** Selected for managed sync of the full original (drawer captures default true; existing imports false). */
    @ColumnInfo(defaultValue = "0") val syncEnabled: Boolean = false)

@Entity(tableName = "transcript_segment", indices = [Index("mediaId"), Index("sourceId")], foreignKeys = [
    ForeignKey(entity = MediaItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
])
data class TranscriptSegment(@PrimaryKey val id: String, val mediaId: String, val sourceId: String,
    val startMs: Long, val endMs: Long, val speaker: String = "UNKNOWN", val text: String)

@Entity(tableName = "speech_span", indices = [Index("mediaId")], foreignKeys = [ForeignKey(
    entity = MediaItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE)])
data class SpeechSpan(@PrimaryKey val id: String, val mediaId: String, val startMs: Long, val endMs: Long)

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: MediaItem): Long
    /** Managed originals only (encrypted in MediaVault); external index entries are listed through LibraryDao. */
    @Query("SELECT * FROM media_item WHERE storage = 'MANAGED' ORDER BY importedAt DESC") suspend fun all(): List<MediaItem>
    @Query("SELECT * FROM media_item WHERE storage = 'MANAGED' ORDER BY importedAt DESC") fun items(): Flow<List<MediaItem>>
    @Query("SELECT COUNT(*) FROM media_item WHERE storage = 'MANAGED'") fun count(): Flow<Int>
    @Query("SELECT * FROM media_item WHERE id=:id") suspend fun item(id: String): MediaItem?
    /** The managed original with this SHA-256, used for capture deduplication. */
    @Query("SELECT * FROM media_item WHERE contentHash=:hash AND storage = 'MANAGED' LIMIT 1") suspend fun hash(hash: String): MediaItem?
    @Query("SELECT * FROM media_item WHERE id=:id") fun observe(id: String): Flow<MediaItem?>
    @Query("UPDATE media_item SET state=:state, error=:error WHERE id=:id AND state != 'SYNC_REMOVED'") suspend fun state(id: String, state: String, error: String?)
    @Query("UPDATE media_item SET processedMs=:time WHERE id=:id") suspend fun progress(id: String, time: Long)
    @Query("UPDATE media_item SET summary=:text, summaryState=:state WHERE id=:id") suspend fun summary(id: String, text: String, state: String)
    @Query("UPDATE media_item SET name=:name, recordedAt=:time WHERE id=:id") suspend fun rename(id: String, name: String, time: Long?)
    @Query("DELETE FROM media_item WHERE id=:id") suspend fun delete(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun segment(value: TranscriptSegment)
    @Query("SELECT * FROM transcript_segment WHERE mediaId=:id ORDER BY startMs") fun segments(id: String): Flow<List<TranscriptSegment>>
    @Query("SELECT * FROM transcript_segment WHERE mediaId=:id ORDER BY startMs") suspend fun transcript(id: String): List<TranscriptSegment>
    @Query("UPDATE transcript_segment SET speaker=:speaker WHERE id=:id") suspend fun speaker(id: String, speaker: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun span(value: SpeechSpan)
    @Query("SELECT * FROM speech_span WHERE mediaId=:id ORDER BY startMs") suspend fun spans(id: String): List<SpeechSpan>
    @Query("SELECT * FROM media_item WHERE state = 'DELETING'") suspend fun deleting(): List<MediaItem>
    @Query("SELECT * FROM media_item WHERE state IN ('QUEUED','PROCESSING')") suspend fun unfinished(): List<MediaItem>
    @Query("SELECT id FROM media_item WHERE state IN ('QUEUED','PROCESSING') AND id NOT IN (:activeIds) ORDER BY importedAt, id LIMIT :limit") suspend fun unfinishedIds(activeIds: List<String>, limit: Int): List<String>
}
