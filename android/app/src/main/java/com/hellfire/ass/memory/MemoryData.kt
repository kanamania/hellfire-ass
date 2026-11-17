package com.hellfire.ass.memory

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memory_item", indices = [Index("sourceId")], foreignKeys = [ForeignKey(
    entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class MemoryItem(@PrimaryKey val id: String, val sourceId: String, val subject: String, val text: String,
    val kind: String, val confidence: Int, val createdAt: Long, val confirmedAt: Long?, val state: String = "ACTIVE")

@Entity(tableName = "memory_revision", indices = [Index("memoryId")], foreignKeys = [ForeignKey(entity = MemoryItem::class, parentColumns = ["id"], childColumns = ["memoryId"], onDelete = ForeignKey.CASCADE)])
data class MemoryRevision(@PrimaryKey val id: String, val memoryId: String, val text: String, val changedAt: Long)

@Dao
interface MemoryDao {
    @Insert suspend fun insert(value: MemoryItem)
    @Insert suspend fun revision(value: MemoryRevision)
    @Query("SELECT * FROM memory_revision WHERE memoryId=:id ORDER BY changedAt DESC") fun revisions(id: String): Flow<List<MemoryRevision>>
    @Query("SELECT * FROM memory_item WHERE state='ACTIVE' AND (:query='' OR instr(lower(subject),lower(:query))>0 OR instr(lower(text),lower(:query))>0) AND (:kind='' OR kind=:kind) ORDER BY createdAt DESC, id LIMIT :limit")
    fun browse(query: String, kind: String, limit: Int): Flow<List<MemoryItem>>
    @Transaction suspend fun revise(id: String, subject: String, text: String, now: Long) {
        val old = requireNotNull(item(id))
        revision(MemoryRevision(java.util.UUID.randomUUID().toString(), id, old.text, now))
        correct(id, subject, text, now)
    }
    @Query("SELECT * FROM memory_item WHERE state='ACTIVE' ORDER BY createdAt DESC") fun items(): Flow<List<MemoryItem>>
    @Query("SELECT * FROM memory_item WHERE state='ACTIVE' AND (instr(lower(subject),lower(:query))>0 OR instr(lower(text),lower(:query))>0) ORDER BY createdAt DESC LIMIT 50") suspend fun search(query: String): List<MemoryItem>
    @Query("SELECT * FROM memory_item WHERE id=:id") suspend fun item(id: String): MemoryItem?
    @Query("UPDATE memory_item SET subject=:subject, text=:text, kind='OWNER_CORRECTION', confidence=100, confirmedAt=:now WHERE id=:id AND state='ACTIVE'")
    suspend fun correct(id: String, subject: String, text: String, now: Long)
    @Query("DELETE FROM memory_item WHERE id=:id") suspend fun forget(id: String)
}
object MemorySchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_item (id TEXT NOT NULL, sourceId TEXT NOT NULL, subject TEXT NOT NULL, text TEXT NOT NULL, kind TEXT NOT NULL, confidence INTEGER NOT NULL, createdAt INTEGER NOT NULL, confirmedAt INTEGER, state TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_item_sourceId ON memory_item(sourceId)")
    }
}
