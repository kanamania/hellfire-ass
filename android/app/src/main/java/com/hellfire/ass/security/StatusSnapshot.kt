package com.hellfire.ass.security

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkManager
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ai.HeavyModelGate
import com.hellfire.ass.library.JobState
import com.hellfire.ass.media.MediaWork
import com.hellfire.ass.sync.WebSync
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
        db.query("SELECT error FROM processing_job WHERE error IS NOT NULL AND error != '' ORDER BY updatedAt DESC LIMIT 5").use { addErrors(it, errors) }
        db.query("SELECT error FROM sync_record WHERE error IS NOT NULL AND error != '' LIMIT 5").use { addErrors(it, errors) }
        db.query("SELECT error FROM backup_manifest WHERE error IS NOT NULL AND error != '' ORDER BY createdAt DESC LIMIT 5").use { addErrors(it, errors) }
        db.query("SELECT captureError FROM inbox_item WHERE captureError IS NOT NULL AND captureError != '' ORDER BY updatedAt DESC LIMIT 5").use { addErrors(it, errors) }
        return StatusSnapshot(
            libraryItems = n("SELECT COUNT(*) FROM media_item WHERE state != 'DELETING'"),
            drawerCaptures = n("SELECT COUNT(*) FROM inbox_item WHERE lifecycle != 'DELETED'"),
            waitingAnalysis = n("SELECT COUNT(*) FROM media_item WHERE state IN ('QUEUED','PROCESSING')") + n("SELECT COUNT(*) FROM processing_job WHERE state IN ($queued)"),
            activeJobs = n("SELECT COUNT(*) FROM processing_job WHERE state IN ($running)"),
            failedJobs = n("SELECT COUNT(*) FROM processing_job WHERE state = 'FAILED'"),
            indexedItems = n("SELECT COUNT(DISTINCT itemId) FROM library_search"),
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
        )
    }

    suspend fun pauseAnalysis(app: HellFireApp) {
        app.getSharedPreferences("media", 0).edit().putBoolean("analysis_paused", true).apply()
        app.database.jobs().pause(null, System.currentTimeMillis())
        WorkManager.getInstance(app).cancelAllWorkByTag(MediaWork.ANALYSIS).result.get()
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

    suspend fun reindex(app: HellFireApp, ids: List<String>) {
        if (ids.isEmpty()) {
            app.database.library().rebuildSearch()
            return
        }
        val db = app.database.openHelper.writableDatabase
        for (id in ids) {
            UUID.fromString(id)
            db.execSQL("DELETE FROM library_search WHERE itemId = ?", arrayOf(id))
            db.execSQL("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'SEGMENT', title || ' ' || description || ' ' || labels FROM media_segment WHERE mediaId = ?", arrayOf(id))
            db.execSQL("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'BOOKMARK', title || ' ' || note FROM media_bookmark WHERE mediaId = ?", arrayOf(id))
            db.execSQL("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'ANALYSIS', text FROM media_analysis WHERE mediaId = ? AND text != ''", arrayOf(id))
        }
    }

    suspend fun pauseSync(app: HellFireApp) { app.database.sync().pauseAll() }

    suspend fun resumeSync(app: HellFireApp) {
        app.database.sync().resumeAll()
        WebSync.now(app)
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
