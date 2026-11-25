package com.hellfire.ass.calendar

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "calendar_link", foreignKeys = [ForeignKey(entity = AssistantTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.RESTRICT)])
data class CalendarLink(@PrimaryKey val taskId: String, val calendarId: Long, val eventId: Long,
    val marker: String, val fingerprint: String, val state: String, val updatedAt: Long)

@Entity(tableName = "calendar_change", indices = [Index("taskId"), Index("sourceId")], foreignKeys = [
    ForeignKey(entity = AssistantTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class CalendarChange(@PrimaryKey val id: String, val taskId: String, val sourceId: String, val taskVersion: Long,
    val operation: String, val calendarId: Long, val calendarLabel: String, val eventId: Long?, val marker: String,
    val expectedFingerprint: String?, val title: String, val start: Long, val end: Long, val timezone: String,
    val createdAt: Long, val state: String = "PROPOSED", val approvalId: String? = null, val approvedAt: Long? = null,
    val resultEventId: Long? = null, val error: String? = null)

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_link WHERE taskId=:task") suspend fun link(task: String): CalendarLink?
    @Query("SELECT * FROM calendar_link WHERE taskId=:task") fun observeLink(task: String): Flow<CalendarLink?>
    @Upsert suspend fun link(row: CalendarLink)
    @Insert suspend fun insert(row: CalendarChange)
    @Query("SELECT * FROM calendar_change WHERE id=:id") suspend fun change(id: String): CalendarChange?
    @Query("SELECT * FROM calendar_change WHERE taskId=:task AND state IN ('APPLYING','UNKNOWN') LIMIT 1") suspend fun unresolved(task: String): CalendarChange?
    @Query("UPDATE calendar_change SET state='APPROVED', approvalId=:token, approvedAt=:now WHERE id=:id AND state='PROPOSED' AND createdAt>=:since")
    suspend fun approve(id: String, token: String, now: Long, since: Long): Int
    @Query("UPDATE calendar_change SET state='APPLYING' WHERE id=:id AND state='APPROVED' AND approvalId=:token AND approvedAt>=:since")
    suspend fun claim(id: String, token: String, since: Long): Int
    @Query("UPDATE calendar_change SET state=:state, resultEventId=:event, error=:error WHERE id=:id") suspend fun finish(id: String, state: String, event: Long?, error: String?)
    @Query("UPDATE calendar_change SET state='PROPOSED', approvalId=NULL, approvedAt=NULL WHERE state='APPROVED'") suspend fun revokeApprovals()
    @Query("UPDATE calendar_change SET state='UNKNOWN', error='INTERRUPTED_CHECK_PROVIDER' WHERE state='APPLYING'") suspend fun interrupt()
}

object CalendarSchema {
    val tables = listOf("calendar_link", "calendar_change")
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS calendar_link (taskId TEXT NOT NULL, calendarId INTEGER NOT NULL, eventId INTEGER NOT NULL, marker TEXT NOT NULL, fingerprint TEXT NOT NULL, state TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(taskId), FOREIGN KEY(taskId) REFERENCES assistant_task(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS calendar_change (id TEXT NOT NULL, taskId TEXT NOT NULL, sourceId TEXT NOT NULL, taskVersion INTEGER NOT NULL, operation TEXT NOT NULL, calendarId INTEGER NOT NULL, calendarLabel TEXT NOT NULL, eventId INTEGER, marker TEXT NOT NULL, expectedFingerprint TEXT, title TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, timezone TEXT NOT NULL, createdAt INTEGER NOT NULL, state TEXT NOT NULL, approvalId TEXT, approvedAt INTEGER, resultEventId INTEGER, error TEXT, PRIMARY KEY(id), FOREIGN KEY(taskId) REFERENCES assistant_task(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_change_taskId ON calendar_change(taskId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_change_sourceId ON calendar_change(sourceId)")
    }
}
