package com.hellfire.ass.media

import androidx.room.*
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media_item", indices = [Index(value = ["contentHash"], unique = true), Index("sourceId")],
    foreignKeys = [ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class MediaItem(@PrimaryKey val id: String, val sourceId: String, val name: String, val mime: String,
    val kind: String, val contentHash: String, val size: Long, val importedAt: Long, val recordedAt: Long?,
    val processedMs: Long = 0, val state: String = "RETAINED", val summary: String = "", val summaryState: String = "NONE", val error: String? = null,
    /** Recording length saved at import (v10); null when unknown. */
    val durationMs: Long? = null)

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
    @Query("SELECT * FROM media_item ORDER BY importedAt DESC") suspend fun all(): List<MediaItem>
    @Query("SELECT * FROM media_item ORDER BY importedAt DESC") fun items(): Flow<List<MediaItem>>
    @Query("SELECT COUNT(*) FROM media_item") fun count(): Flow<Int>
    @Query("SELECT * FROM media_item WHERE id=:id") suspend fun item(id: String): MediaItem?
    @Query("SELECT * FROM media_item WHERE contentHash=:hash") suspend fun hash(hash: String): MediaItem?
    @Query("SELECT * FROM media_item WHERE id=:id") fun observe(id: String): Flow<MediaItem?>
    @Query("UPDATE media_item SET state=:state, error=:error WHERE id=:id") suspend fun state(id: String, state: String, error: String?)
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
}
