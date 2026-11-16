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

/** Full-text index of capturable, non-sensitive source text (v13). Rebuildable; excluded from backups. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["eventId"])
@Entity(tableName = "source_search")
data class SourceSearch(val eventId: String, val text: String)

data class SearchHit(val eventId: String, val text: String)

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

    @Insert suspend fun insertSearch(row: SourceSearch)

    @Query("DELETE FROM source_search") suspend fun clearSearch()

    /** Newest matching sources first; [query] must come from SearchText.query. */
    @Query("SELECT s.eventId AS eventId, MIN(s.text) AS text FROM source_search s JOIN source_event e ON e.id = s.eventId WHERE s.text MATCH :query GROUP BY s.eventId ORDER BY MAX(e.receivedAt) DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<SearchHit>

    @Query("SELECT * FROM source_event WHERE receivedAt < :before AND (receivedAt > :afterAt OR (receivedAt = :afterAt AND id > :afterId)) ORDER BY receivedAt, id LIMIT :limit")
    suspend fun olderThan(before: Long, afterAt: Long, afterId: String, limit: Int): List<SourceEvent>

    @Transaction
    suspend fun insertCaptured(event: SourceEvent, searchText: String? = null): Boolean {
        if (insert(event) == -1L) return false
        if (searchText != null) insertSearch(SourceSearch(event.id, searchText))
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

    @Query("SELECT * FROM capture_audit") suspend fun auditsNow(): List<CaptureAudit>

    /** Readable events of one source in a period, newest first. */
    @Query("SELECT * FROM source_event WHERE source = :source AND occurredAt >= :start AND occurredAt < :end AND contentAvailable = 1 AND securitySensitive = 0 ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun fromSource(source: String, start: Long, end: Long, limit: Int): List<SourceEvent>

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

    @Query("UPDATE event_processing SET state = 'PENDING', error = NULL WHERE eventId = :id")
    suspend fun retry(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPolicy(policy: NotificationPolicy)

    @Query("SELECT processEnabled FROM notification_policy WHERE packageName = :packageName")
    suspend fun processEnabled(packageName: String): Boolean?

    @Query("SELECT DISTINCT e.packageName, COALESCE(p.processEnabled, 1) AS processEnabled FROM source_event e LEFT JOIN notification_policy p ON p.packageName = e.packageName WHERE e.source = 'android_notification' ORDER BY e.packageName")
    fun appPolicies(): Flow<List<AppProcessingPolicy>>

    @Query("DELETE FROM source_event")
    suspend fun deleteSources()

    @Transaction
    suspend fun deleteAll() { deleteSources(); clearSearch() }

    @Query("SELECT e.*, p.label AS label, COALESCE(p.state, 'PENDING') AS state FROM source_event e LEFT JOIN event_processing p ON p.eventId = e.id WHERE e.source IN (:sources) ORDER BY e.receivedAt DESC LIMIT :limit")
    fun recentFrom(sources: List<String>, limit: Int): Flow<List<LabelledEvent>>

    @Query("SELECT e.*, p.label AS label, COALESCE(p.state, 'PENDING') AS state FROM source_event e LEFT JOIN event_processing p ON p.eventId = e.id WHERE e.receivedAt >= :start AND e.receivedAt < :end")
    suspend fun labelledPeriod(start: Long, end: Long): List<LabelledEvent>
}

data class LabelledEvent(@Embedded val event: SourceEvent, val label: String?, val state: String)

@Database(entities = [SourceEvent::class, EventProcessing::class, CaptureAudit::class, NotificationPolicy::class, FinanceAccount::class, FinanceCandidate::class, LedgerEntry::class, LedgerEvidence::class, MerchantAlias::class, CommunicationMessage::class, MailCursor::class, OutboundDraft::class, ActionAudit::class, IntegrationState::class, AssistantTask::class, DailyReview::class, MediaItem::class, TranscriptSegment::class, SpeechSpan::class, MemoryItem::class, com.hellfire.ass.memory.SemanticVector::class, AiUsage::class, CategoryBudget::class, ProviderBalance::class, SourceSearch::class,
    com.hellfire.ass.templates.ActionTemplate::class, com.hellfire.ass.insights.DebtEntry::class, com.hellfire.ass.insights.PersonAlias::class], version = 14, exportSchema = true)
abstract class EventDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun finances(): FinanceDao
    abstract fun communications(): CommunicationDao
    abstract fun tasks(): TaskDao
    abstract fun media(): MediaDao
    abstract fun memory(): MemoryDao
    abstract fun semantic(): com.hellfire.ass.memory.SemanticDao
    abstract fun aiUsage(): AiUsageDao
    abstract fun widgetData(): WidgetDataDao
    abstract fun templates(): com.hellfire.ass.templates.TemplateDao
    abstract fun debts(): com.hellfire.ass.insights.DebtDao
    abstract fun people(): com.hellfire.ass.insights.PeopleDao
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
        /** v10 (UI redesign §6): task completion time, media length, AI usage log. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistant_task ADD COLUMN completedAt INTEGER")
                // Completion times already recorded in the owner action audit are real evidence; other done tasks stay unknown.
                db.execSQL("UPDATE assistant_task SET completedAt = (SELECT MAX(a.occurredAt) FROM action_audit a WHERE a.draftId = assistant_task.id AND a.action = 'OWNER_TASK_DONE') WHERE state = 'DONE'")
                db.execSQL("ALTER TABLE media_item ADD COLUMN durationMs INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_usage (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, requestId TEXT NOT NULL, at INTEGER NOT NULL, task TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, result TEXT NOT NULL, inputTokens INTEGER, outputTokens INTEGER, microUsd INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_usage_at ON ai_usage(at)")
            }
        }
        /** v11 (widgets §8): promise direction and amount, bills, category budgets, last SMS balance per provider. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistant_task ADD COLUMN direction TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE assistant_task ADD COLUMN amount INTEGER")
                db.execSQL("ALTER TABLE assistant_task ADD COLUMN kind TEXT NOT NULL DEFAULT 'TASK'")
                db.execSQL("ALTER TABLE assistant_task ADD COLUMN repeatMonthly INTEGER NOT NULL DEFAULT 0")
                // Commitments extracted from messages already say who does it; owner-typed tasks stay NONE.
                db.execSQL("UPDATE assistant_task SET direction = CASE party WHEN 'OWNER' THEN 'I_OWE' WHEN 'OTHER' THEN 'OWED_TO_ME' ELSE 'NONE' END WHERE sourceId IN (SELECT id FROM source_event WHERE source NOT IN ('owner_entry', 'owner_memory'))")
                db.execSQL("CREATE TABLE IF NOT EXISTS category_budget (category TEXT NOT NULL, month TEXT NOT NULL, amount INTEGER NOT NULL, PRIMARY KEY(category, month))")
                db.execSQL("CREATE TABLE IF NOT EXISTS provider_balance (provider TEXT NOT NULL, amount INTEGER NOT NULL, observedAt INTEGER NOT NULL, sourceId TEXT NOT NULL, PRIMARY KEY(provider))")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE finance_candidate ADD COLUMN accountId TEXT")
                db.execSQL("ALTER TABLE finance_candidate ADD COLUMN category TEXT")
            }
        }
        /** v13: cross-source payment duplicates, full-text source search, per-account integration states. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE finance_candidate ADD COLUMN duplicateOf TEXT")
                db.execSQL(SOURCE_SEARCH_SQL)
                // Single-account states are replaced by per-account keys (EMAIL_IMAP:<id>, ...); the next sync writes them.
                db.execSQL("DELETE FROM integration_state WHERE adapter IN ('EMAIL_IMAP', 'WHATSAPP_GATEWAY', 'IMAP_IDLE')")
            }
        }
        /** v14: action templates, the owner's debt notes, and names for phone numbers and addresses. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS action_template (id TEXT NOT NULL, name TEXT NOT NULL, items TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastUsedAt INTEGER, uses INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS debt_entry (id TEXT NOT NULL, person TEXT NOT NULL, amount INTEGER NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL, occurredAt INTEGER NOT NULL, dueAt INTEGER, ledgerId TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debt_entry_person ON debt_entry(person)")
                db.execSQL("CREATE TABLE IF NOT EXISTS person_alias (handle TEXT NOT NULL, name TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(handle))")
            }
        }
        const val SOURCE_SEARCH_SQL = "CREATE VIRTUAL TABLE IF NOT EXISTS `source_search` USING FTS4(`eventId` TEXT NOT NULL, `text` TEXT NOT NULL, tokenize=unicode61, notindexed=`eventId`)"
        val ALL_MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
        fun open(context: Context): EventDatabase = Room.databaseBuilder(
            context.applicationContext, EventDatabase::class.java, "hellfire.db"
        ).openHelperFactory(EncryptedDatabase.factory(context)).addMigrations(*ALL_MIGRATIONS).build()
    }
}
