package com.hellfire.ass.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "source_event", indices = [Index(value = ["source", "externalId"], unique = true)])
data class SourceEvent(
    @PrimaryKey val id: String,
    val source: String = "android_notification",
    val externalId: String,
    val packageName: String,
    val occurredAt: Long,
    val receivedAt: Long,
    val kind: String,
    val payloadHash: String,
    val encryptedPayload: ByteArray,
    val contentAvailable: Boolean,
    val contentRedacted: Boolean,
    val securitySensitive: Boolean,
    val ingestVersion: Int = 1,
    val processingStatus: String = "captured",
)

data class EventSummary(
    val id: String, val source: String, val packageName: String, val receivedAt: Long,
    val kind: String, val contentAvailable: Boolean, val contentRedacted: Boolean,
)

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: SourceEvent): Long

    @Query("SELECT COUNT(*) FROM source_event")
    fun count(): Flow<Int>

    @Query("SELECT id, source, packageName, receivedAt, kind, contentAvailable, contentRedacted FROM source_event ORDER BY receivedAt DESC LIMIT 10")
    fun recent(): Flow<List<EventSummary>>

    @Query("SELECT * FROM source_event WHERE id = :id")
    suspend fun find(id: String): SourceEvent?

    @Query("DELETE FROM source_event")
    suspend fun deleteAll()
}

@Database(entities = [SourceEvent::class], version = 1, exportSchema = true)
abstract class EventDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    companion object {
        fun open(context: Context): EventDatabase = Room.databaseBuilder(
            context.applicationContext, EventDatabase::class.java, "hellfire.db"
        ).build()
    }
}
