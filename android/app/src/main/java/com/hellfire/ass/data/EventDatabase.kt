package com.hellfire.ass.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import com.hellfire.ass.finance.*
import com.hellfire.ass.communications.*
import com.hellfire.ass.tasks.*
import com.hellfire.ass.media.*
import com.hellfire.ass.memory.*
import com.hellfire.ass.security.EncryptedDatabase

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
    val state: String, val label: String?,
)

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: SourceEvent): Long

    @Query("SELECT COUNT(*) FROM source_event")
    fun count(): Flow<Int>

    @Query("SELECT e.id, e.source, e.packageName, e.receivedAt, e.kind, e.contentAvailable, e.contentRedacted, COALESCE(p.state, 'PENDING') AS state, p.label FROM source_event e LEFT JOIN event_processing p ON p.eventId = e.id ORDER BY e.receivedAt DESC LIMIT 20")
    fun recent(): Flow<List<EventSummary>>

    @Query("SELECT EXISTS(SELECT 1 FROM source_event WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT * FROM source_event WHERE receivedAt < :before OR (receivedAt = :before AND id < :lastId) ORDER BY receivedAt DESC, id DESC LIMIT 100")
    suspend fun searchBatch(before: Long, lastId: String): List<SourceEvent>

    @Query("SELECT * FROM source_event WHERE id = :id")
    suspend fun find(id: String): SourceEvent?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: EventProcessing): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAudit(audit: CaptureAudit)

    @Query("UPDATE capture_audit SET lastCapturedAt = :time, captured = captured + 1 WHERE source = :source")
    suspend fun captured(source: String, time: Long)

    @Transaction
    suspend fun insertCaptured(event: SourceEvent): Boolean {
        if (insert(event) == -1L) return false
        insertJob(EventProcessing(event.id, updatedAt = event.receivedAt))
        insertAudit(CaptureAudit(event.source))
        captured(event.source, event.receivedAt)
        return true
    }

    @Query("UPDATE capture_audit SET failures = failures + 1, dropped = dropped + :dropped, lastError = :error WHERE source = :source")
    suspend fun updateFailure(source: String, error: String, dropped: Int)

    @Transaction
    suspend fun failure(source: String, error: String, dropped: Int = 0) {
        insertAudit(CaptureAudit(source))
        updateFailure(source, error, dropped)
    }

    @Query("SELECT * FROM capture_audit ORDER BY source")
    fun audits(): Flow<List<CaptureAudit>>

    @Query("SELECT state, COUNT(*) AS count FROM event_processing GROUP BY state")
    fun queueSummary(): Flow<List<QueueSummary>>

    @Query("SELECT e.* FROM source_event e JOIN event_processing p ON p.eventId = e.id WHERE p.state = 'PENDING' ORDER BY e.receivedAt, e.id LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<SourceEvent>

    @Query("UPDATE event_processing SET state = :state, label = :label, error = :error, attempts = attempts + 1, updatedAt = :time WHERE eventId = :id AND state = 'PENDING'")
    suspend fun finish(id: String, state: String, label: String?, error: String?, time: Long): Int

    @Query("SELECT * FROM event_processing WHERE eventId = :id")
    suspend fun processing(id: String): EventProcessing?

    @Query("UPDATE event_processing SET state = 'PENDING', error = NULL WHERE state = 'FAILED'")
    suspend fun retryFailed()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPolicy(policy: NotificationPolicy)

    @Query("SELECT processEnabled FROM notification_policy WHERE packageName = :packageName")
    suspend fun processEnabled(packageName: String): Boolean?

    @Query("SELECT DISTINCT e.packageName, COALESCE(p.processEnabled, 1) AS processEnabled FROM source_event e LEFT JOIN notification_policy p ON p.packageName = e.packageName WHERE e.source = 'android_notification' ORDER BY e.packageName")
    fun appPolicies(): Flow<List<AppProcessingPolicy>>

    @Query("DELETE FROM source_event")
    suspend fun deleteAll()
}

@Database(entities = [SourceEvent::class, EventProcessing::class, CaptureAudit::class, NotificationPolicy::class, FinanceAccount::class, FinanceCandidate::class, LedgerEntry::class, LedgerEvidence::class, MerchantAlias::class, CommunicationMessage::class, MailCursor::class, OutboundDraft::class, ActionAudit::class, IntegrationState::class, AssistantTask::class, DailyReview::class, MediaItem::class, TranscriptSegment::class, SpeechSpan::class, MemoryItem::class, com.hellfire.ass.memory.SemanticVector::class], version = 9, exportSchema = true)
abstract class EventDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun finances(): FinanceDao
    abstract fun communications(): CommunicationDao
    abstract fun tasks(): TaskDao
    abstract fun media(): MediaDao
    abstract fun memory(): MemoryDao
    abstract fun semantic(): com.hellfire.ass.memory.SemanticDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS event_processing (eventId TEXT NOT NULL, state TEXT NOT NULL, label TEXT, attempts INTEGER NOT NULL, updatedAt INTEGER NOT NULL, error TEXT, PRIMARY KEY(eventId), FOREIGN KEY(eventId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS capture_audit (source TEXT NOT NULL, lastCapturedAt INTEGER, captured INTEGER NOT NULL, failures INTEGER NOT NULL, dropped INTEGER NOT NULL, lastError TEXT, PRIMARY KEY(source))")
                db.execSQL("CREATE TABLE IF NOT EXISTS notification_policy (packageName TEXT NOT NULL, processEnabled INTEGER NOT NULL, PRIMARY KEY(packageName))")
                db.execSQL("INSERT INTO event_processing (eventId, state, attempts, updatedAt) SELECT id, 'PENDING', 0, receivedAt FROM source_event")
                db.execSQL("INSERT INTO capture_audit (source, lastCapturedAt, captured, failures, dropped) SELECT source, MAX(receivedAt), COUNT(*), 0, 0 FROM source_event GROUP BY source")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) { FinanceSchema.migrate(db) }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { CommunicationSchema.migrate(db) }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) { TaskSchema.migrate(db) }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) { MediaSchema.migrate(db) }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) { MemorySchema.migrate(db) }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE outbound_draft ADD COLUMN replyToMessageId TEXT") }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) { com.hellfire.ass.memory.SemanticSchema.migrate(db) }
        }
        fun open(context: Context): EventDatabase = Room.databaseBuilder(
            context.applicationContext, EventDatabase::class.java, "hellfire.db"
        ).openHelperFactory(EncryptedDatabase.factory(context)).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build()
    }
}
