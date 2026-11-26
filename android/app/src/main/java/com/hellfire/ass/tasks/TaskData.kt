package com.hellfire.ass.tasks

import androidx.room.*
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "assistant_task", indices = [Index("sourceId"), Index("nextAlertAt")],
    foreignKeys = [ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class AssistantTask(@PrimaryKey val id: String, val sourceId: String, val title: String,
    val dueAt: Long, val timezone: String = "Africa/Dar_es_Salaam", val party: String,
    val evidence: String, val state: String = "REVIEW", val createdAt: Long, val updatedAt: Long,
    val nextAlertAt: Long? = null, val lastAlertAt: Long? = null, val alertCount: Int = 0,
    /** When the owner marked it done; null for tasks finished before v10 without an audit record. */
    val completedAt: Long? = null,
    /** Promise direction (v11): I_OWE, OWED_TO_ME or NONE. */
    @ColumnInfo(defaultValue = "NONE") val direction: String = "NONE",
    /** Optional whole-TZS amount for promises and bills (v11). Display only; never counted in the ledger. */
    val amount: Long? = null,
    /** TASK or BILL (v11). */
    @ColumnInfo(defaultValue = "TASK") val kind: String = "TASK",
    /** Bills only: when done, the next month's bill is scheduled (v11). */
    @ColumnInfo(defaultValue = "0") val repeatMonthly: Boolean = false, val personKey: String? = null,
    @ColumnInfo(defaultValue = "NONE") val repeatRule: String = "NONE",
    @ColumnInfo(defaultValue = "PERSISTENT") val alertProfile: String = "PERSISTENT",
    @ColumnInfo(defaultValue = "10") val snoozeMinutes: Int = 10,
    /** v23: "DEBT_UNPAID:<name>" or "NO_REPLY:<name>"; the reminder closes itself once [TaskConditions.met]. */
    val condition: String? = null)

@Entity(tableName = "daily_review")
data class DailyReview(@PrimaryKey val day: String, val state: String = "PENDING", val createdAt: Long, val completedAt: Long? = null)

@Dao
interface TaskDao {
    @Query("UPDATE assistant_task SET state = 'ACTIVE', nextAlertAt = :next, updatedAt = :now, completedAt = NULL, lastAlertAt = NULL, alertCount = 0 WHERE id = :id AND state = 'REJECTED'")
    suspend fun reactivateRepeat(id: String, next: Long?, now: Long)

    @Query("UPDATE assistant_task SET repeatRule = :repeat, alertProfile = :profile, snoozeMinutes = :snooze WHERE id = :id")
    suspend fun reminderOptions(id: String, repeat: String, profile: String, snooze: Int)

    @Query("UPDATE assistant_task SET personKey = :key, updatedAt = :now WHERE id = :id") suspend fun setPerson(id: String, key: String?, now: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(task: AssistantTask): Long
    @Query("SELECT * FROM assistant_task ORDER BY CASE state WHEN 'REVIEW' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, dueAt") fun tasks(): Flow<List<AssistantTask>>
    @Query("SELECT * FROM assistant_task WHERE id = :id") suspend fun task(id: String): AssistantTask?
    @Query("UPDATE assistant_task SET title=:title, dueAt=:due, party=:party, state='ACTIVE', nextAlertAt=:next, updatedAt=:now WHERE id=:id AND state IN ('REVIEW','ACTIVE')")
    suspend fun activate(id: String, title: String, due: Long, party: String, next: Long, now: Long): Int
    @Query("UPDATE assistant_task SET state=:state, nextAlertAt=NULL, updatedAt=:now, completedAt=CASE WHEN :state='DONE' THEN :now ELSE completedAt END WHERE id=:id")
    suspend fun close(id: String, state: String, now: Long)
    @Query("UPDATE assistant_task SET nextAlertAt=:next, updatedAt=:now WHERE id=:id AND state='ACTIVE'") suspend fun snooze(id: String, next: Long, now: Long)
    @Query("SELECT MIN(nextAlertAt) FROM assistant_task WHERE state='ACTIVE'") suspend fun nextAlert(): Long?
    @Query("SELECT * FROM assistant_task WHERE state='ACTIVE' AND nextAlertAt <= :now ORDER BY nextAlertAt") suspend fun due(now: Long): List<AssistantTask>
    @Query("UPDATE assistant_task SET lastAlertAt=:now, alertCount=alertCount+1, nextAlertAt=:next WHERE id=:id AND state='ACTIVE'")
    suspend fun alerted(id: String, now: Long, next: Long)
    @Query("SELECT * FROM assistant_task WHERE state='ACTIVE' AND instr(lower(title), lower(:query)) > 0 ORDER BY dueAt LIMIT 6") suspend fun activeMatching(query: String): List<AssistantTask>
    @Query("SELECT * FROM assistant_task WHERE state='ACTIVE' AND dueAt >= :start AND dueAt < :end ORDER BY dueAt") suspend fun period(start: Long, end: Long): List<AssistantTask>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun review(value: DailyReview)
    @Query("SELECT * FROM daily_review WHERE state='PENDING' ORDER BY day") fun reviews(): Flow<List<DailyReview>>
    @Query("SELECT EXISTS(SELECT 1 FROM daily_review WHERE day=:day)") suspend fun reviewedOrPrompted(day: String): Boolean
    @Query("UPDATE daily_review SET state='DONE', completedAt=:now WHERE day=:day") suspend fun completeReview(day: String, now: Long)
    @Query("UPDATE assistant_task SET state='ACTIVE', nextAlertAt=:next, updatedAt=:now, completedAt=NULL WHERE id=:id AND state='DONE'")
    suspend fun reopen(id: String, next: Long, now: Long): Int
    @Query("UPDATE assistant_task SET direction=:direction, amount=:amount, kind=:kind, repeatMonthly=:repeatMonthly, updatedAt=:now WHERE id=:id")
    suspend fun details(id: String, direction: String, amount: Long?, kind: String, repeatMonthly: Boolean, now: Long): Int
    @Query("SELECT * FROM assistant_task") suspend fun all(): List<AssistantTask>
    @Query("SELECT * FROM daily_review WHERE state='PENDING' ORDER BY day") suspend fun reviewsNow(): List<DailyReview>
    @Query("SELECT * FROM assistant_task WHERE id = :id") fun observe(id: String): Flow<AssistantTask?>
}
