package com.hellfire.ass.security

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkManager
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ai.HeavyModelGate
import com.hellfire.ass.library.JobState
import com.hellfire.ass.media.MediaWork
import androidx.room.withTransaction
import java.util.UUID

data class StatusSnapshot(
    val libraryItems: Int,
    val drawerCaptures: Int,
    val waitingAnalysis: Int,
    val activeJobs: Int,
    val failedJobs: Int,
    val indexedItems: Int,
    val syncQueued: Int,
    val uploaded: Int,
    val pendingUploads: Int,
    val pendingBytes: Long,
    val backupState: String,
    val notBackedUp: Int,
    val storage: StorageUsage,
    val models: List<Pair<String, String>>,
    val inference: String,
    val thermal: String,
    val errors: List<String>,
    val analysisPaused: Boolean,
    val syncPaused: Boolean = false,
    val driveFailure: String? = null,
    val unindexed: Int = 0,
)

object StatusReport {
    fun load(app: HellFireApp): StatusSnapshot {
        val db = app.database.openHelper.readableDatabase
        fun n(sql: String) = db.query(sql).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        fun s(sql: String) = db.query(sql).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else "" }
        val running = JobState.running.joinToString(",") { "'$it'" }
        val queued = JobState.queued.joinToString(",") { "'$it'" }
        val storage = BackupStorage.usage(app)
        val last = s("SELECT state || ' · ' || datetime(createdAt / 1000, 'unixepoch') FROM backup_manifest ORDER BY createdAt DESC LIMIT 1")
        val errors = mutableListOf<String>()
        val driveFailure = DriveBackupWork.lastFailure(app)
        driveFailure?.let { errors += "Google Drive backup: $it" }
        db.query("SELECT error FROM processing_job WHERE error IS NOT NULL AND error != '' ORDER BY updatedAt DESC LIMIT 5").use { addErrors(it, errors) }
        db.query("SELECT error FROM sync_record WHERE error IS NOT NULL AND error != '' LIMIT 5").use { addErrors(it, errors) }
        db.query("SELECT error FROM backup_manifest WHERE error IS NOT NULL AND error != '' ORDER BY createdAt DESC LIMIT 5").use { addErrors(it, errors) }
        db.query("SELECT captureError FROM inbox_item WHERE captureError IS NOT NULL AND captureError != '' ORDER BY updatedAt DESC LIMIT 5").use { addErrors(it, errors) }
        return StatusSnapshot(
            libraryItems = n("SELECT COUNT(*) FROM media_item WHERE state != 'DELETING'"),
            drawerCaptures = n("SELECT COUNT(*) FROM inbox_item WHERE lifecycle != 'DELETED'"),
            // One count per item: an item waiting in both the legacy state and the job queue is counted once.
            waitingAnalysis = n("SELECT COUNT(*) FROM media_item m WHERE m.state != 'DELETING' AND (m.state IN ('QUEUED','PROCESSING') OR EXISTS (SELECT 1 FROM processing_job j WHERE j.itemId = m.id AND j.state IN ($queued)))"),
            activeJobs = n("SELECT COUNT(*) FROM processing_job WHERE state IN ($running)"),
            failedJobs = n("SELECT COUNT(*) FROM processing_job WHERE state = 'FAILED'"),
            // Library items only; drawer inbox rows use inbox ids in the same index.
            indexedItems = n("SELECT COUNT(DISTINCT s.itemId) FROM library_search s JOIN media_item m ON m.id = s.itemId WHERE s.kind != 'INBOX'"),
            syncQueued = n("SELECT COUNT(*) FROM sync_record WHERE state IN ('PENDING','FAILED','UPLOADING')"),
            uploaded = n("SELECT COUNT(*) FROM sync_record WHERE state = 'SYNCED' AND deleted = 0"),
            pendingUploads = n("SELECT COUNT(*) FROM sync_record WHERE state IN ('PENDING','FAILED')"),
            pendingBytes = db.query("SELECT COALESCE(SUM(bytesTotal - bytesDone), 0) FROM sync_record WHERE state IN ('PENDING','UPLOADING','FAILED')").use { if (it.moveToFirst()) it.getLong(0) else 0L },
            backupState = last.ifBlank { "No backup recorded" },
            notBackedUp = storage.notBackedUp,
            storage = storage,
            models = BackupStorage.modelFiles(app),
            inference = if (HeavyModelGate.mutex.isLocked) "model in use" else "idle",
            thermal = thermal(app),
            errors = errors,
            analysisPaused = app.getSharedPreferences("media", 0).getBoolean("analysis_paused", false),
            syncPaused = com.hellfire.ass.sync.LibrarySync.isPaused(app),
            driveFailure = driveFailure,
            unindexed = unindexed(app).size,
        )
    }

    suspend fun pauseAnalysis(app: HellFireApp) {
        app.getSharedPreferences("media", 0).edit().putBoolean("analysis_paused", true).apply()
        app.database.jobs().pause(null, System.currentTimeMillis())
        WorkManager.getInstance(app).cancelAllWorkByTag(MediaWork.ANALYSIS).result.get()
        WorkManager.getInstance(app).cancelUniqueWork(com.hellfire.ass.intelligence.IntelligenceWork.NAME).result.get()
    }

    suspend fun resumeAnalysis(app: HellFireApp) {
        app.getSharedPreferences("media", 0).edit().putBoolean("analysis_paused", false).apply()
        app.database.jobs().resume(null, System.currentTimeMillis())
        MediaWork.recover(app)
        com.hellfire.ass.intelligence.IntelligenceWork.schedule(app)
    }

    suspend fun retryFailures(app: HellFireApp) {
        app.database.jobs().retryFailed(null, System.currentTimeMillis())
        MediaWork.pump(app)
        com.hellfire.ass.intelligence.IntelligenceWork.schedule(app)
    }

    /** Items with segments, bookmarks or analysis text that have no search entry (for example after an interrupted rebuild). */
    fun unindexed(app: HellFireApp): List<String> {
        val ids = mutableListOf<String>()
        app.database.openHelper.readableDatabase.query("""
            SELECT m.id FROM media_item m WHERE m.state != 'DELETING' AND NOT EXISTS (SELECT 1 FROM library_search s WHERE s.itemId = m.id) AND (
              EXISTS (SELECT 1 FROM media_segment g WHERE g.mediaId = m.id) OR EXISTS (SELECT 1 FROM media_bookmark b WHERE b.mediaId = m.id)
              OR EXISTS (SELECT 1 FROM media_analysis a WHERE a.mediaId = m.id AND a.text != '')) LIMIT 500
        """.trimIndent()).use { while (it.moveToNext()) ids += it.getString(0) }
        return ids
    }

    suspend fun reindex(app: HellFireApp, ids: List<String>) {
        if (ids.isEmpty()) {
            app.database.library().rebuildSearch()
            return
        }
        val db = app.database.openHelper.writableDatabase
        app.database.withTransaction { for (id in ids) {
            UUID.fromString(id)
            db.execSQL("DELETE FROM library_search WHERE itemId = ?", arrayOf(id))
            db.execSQL("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'SEGMENT', title || ' ' || description || ' ' || labels FROM media_segment WHERE mediaId = ?", arrayOf(id))
            db.execSQL("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'BOOKMARK', title || ' ' || note FROM media_bookmark WHERE mediaId = ?", arrayOf(id))
            db.execSQL("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'ANALYSIS', text FROM media_analysis WHERE mediaId = ? AND text != ''", arrayOf(id))
        } }
    }

    /** Uses library sync's persistent pause flag (checked by the worker; cancels queued work). */
    fun pauseSync(app: HellFireApp) { com.hellfire.ass.sync.LibrarySync.setPaused(app, true) }

    suspend fun resumeSync(app: HellFireApp) {
        // Rows paused by an older version of this button.
        app.database.sync().resumeAll()
        com.hellfire.ass.sync.LibrarySync.setPaused(app, false)
    }

    fun requestBackup(context: Context): String {
        val app = context.applicationContext as HellFireApp
        val enabled = app.secrets.read(DriveBackupWork.CONFIG)?.optBoolean("enabled") == true
        return if (!enabled) "Daily Drive backup is not configured. Export an encrypted archive from the backup dashboard."
        else { DriveBackupWork.now(context); "Backup requested. It runs when the network is unmetered and the battery is not low." }
    }

    fun thermal(context: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "not available"
        val manager = context.getSystemService(PowerManager::class.java) ?: return "not available"
        return when (manager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private fun addErrors(cursor: android.database.Cursor, into: MutableList<String>) {
        while (cursor.moveToNext() && into.size < 8) {
            val text = cursor.getString(0).orEmpty().lineSequence().firstOrNull().orEmpty().take(120)
            if (text.isNotBlank()) into += text
        }
    }
}
