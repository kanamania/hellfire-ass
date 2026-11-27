package com.hellfire.ass.data

import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.tasks.TaskTime
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Audit HF-063: storage does not grow forever, and the owner can delete one record. Kept: anything a payment, task,
 * message, memory, recording or skill was made from, and saved, bookmarked or followed news.
 */
object Retention {
    const val REMOVAL_DAYS = 30L
    const val NEWS_DAYS = 180L
    private const val DAY = 86_400_000L

    /** Once a day: drop old notification-removal records and old unsaved news. Returns rows removed. */
    suspend fun daily(app: HellFireApp, now: Long = System.currentTimeMillis()): Int {
        val prefs = app.getSharedPreferences("retention", 0)
        val today = Instant.ofEpochMilli(now).atZone(TaskTime.zone).toLocalDate().toString()
        if (prefs.getString("day", null) == today) return 0
        val removed = prune(app.database, now)
        prefs.edit().putString("day", today).apply()
        return removed
    }

    suspend fun prune(db: EventDatabase, now: Long): Int {
        var removed = 0
        while (true) {
            val ids = db.events().staleRemovals(now - REMOVAL_DAYS * DAY)
            if (ids.isEmpty()) break
            db.withTransaction { db.events().unsearch(ids); db.events().deleteIds(ids) }
            removed += ids.size
            if (ids.size < 500) break
        }
        val news = db.news()
        while (true) {
            val ids = news.staleEvents(now - NEWS_DAYS * DAY)
            if (ids.isEmpty()) break
            db.withTransaction {
                news.deleteArticlesOf(ids); news.deleteVersionsOf(ids); news.deleteStatesOf(ids); news.deleteFeedbackOf(ids); news.deleteEvents(ids)
            }
            removed += ids.size
            if (ids.size < 500) break
        }
        news.pruneBriefings(now - NEWS_DAYS * DAY)
        news.pruneProgress()
        return removed
    }

    /**
     * Deletes one captured record at the owner's request. If a payment, task or other record was made from it, the
     * text is blanked instead (the row stays for that link). Returns true when the row itself was deleted.
     */
    suspend fun deleteRecord(app: HellFireApp, id: String): Boolean = app.database.withTransaction {
        val events = app.database.events()
        val whole = events.unreferenced(id) == 1
        if (whole) { events.unsearch(listOf(id)); events.deleteIds(listOf(id)) }
        else {
            val blank = JSONObject().put("fields", JSONObject()).put("provenance", JSONObject()).put("content_status", "DELETED_BY_OWNER")
            events.blank(listOf(id), app.cipher.encrypt(blank.toString().toByteArray(Charsets.UTF_8)))
            events.unsearch(listOf(id))
            app.database.semantic().forget(listOf(id))
        }
        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_RECORD_DELETE", if (whole) "DELETED" else "CONTENT_CLEARED", System.currentTimeMillis()))
        whole
    }
}
