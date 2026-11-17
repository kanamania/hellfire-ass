package com.hellfire.ass.security

import android.content.Context
import androidx.work.*
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object DriveBackupWork {
    const val CONFIG = "drive-backup"
    const val DAILY = "daily-google-drive-backup"
    const val NOW = "google-drive-backup-now"
    val mutex = Mutex()
    /** Newest app-created archives kept on Drive; 0 keeps all. */
    const val DEFAULT_KEEP = 7
    fun setKeep(context: Context, keep: Int) {
        require(keep >= 0)
        val app = context.applicationContext as HellFireApp
        app.secrets.read(CONFIG)?.let { app.secrets.save(CONFIG, it.put("keep", keep)) }
    }
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveBackupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS).setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
    }
    fun now(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DriveBackupWorker>().setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
    }
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DAILY)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
    }
    fun status(context: Context, value: String, successId: String? = null) {
        val edit = context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE).edit().putString("status", value)
        if (successId != null) edit.putLong("last-success", System.currentTimeMillis()).putString("file-id", successId)
        edit.apply()
    }
    /** Last Drive failure shown in the backup and status dashboards; null clears it. */
    fun failure(context: Context, value: String?) {
        val edit = context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE).edit()
        if (value == null) edit.remove("failure") else edit.putString("failure", value).putLong("failure-at", System.currentTimeMillis())
        edit.apply()
    }
    fun lastFailure(context: Context): String? = context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE).getString("failure", null)
    fun readStatus(context: Context): String {
        val prefs = context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE)
        val last = prefs.getLong("last-success", 0)
        return prefs.getString("status", "Not configured").orEmpty() +
            if (last > 0) "\nLast successful upload: ${java.time.Instant.ofEpochMilli(last)}" else "\nNo successful upload recorded on this installation."
    }
}

class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        DriveBackupWork.mutex.withLock {
            val app = applicationContext as HellFireApp
            val file = File(app.noBackupFilesDir, "drive-upload-${UUID.randomUUID()}.hfb")
            var password: CharArray? = null
            try {
                val config = app.secrets.read(DriveBackupWork.CONFIG)
                if (config?.optBoolean("enabled") != true) return@withLock Result.success()
                if (com.hellfire.ass.audio.MicrophoneStatus.busy.value) {
                    DriveBackupWork.status(app, "Waiting for recording to stop")
                    return@withLock Result.retry()
                }
                DriveBackupWork.status(app, "Preparing encrypted backup")
                val token = DriveAuthorization.token(DriveAuthorization.authorize(app, config.getString("account")))
                password = config.getString("passphrase").toCharArray()
                // Nothing is marked backed up until Drive confirmed the archive's size and checksum (commit below).
                val outcome = PortableBackup(app).exportForUpload(file, password)
                currentCoroutineContext().ensureActive()
                // A disconnect or consent change must stop a queued upload.
                if (app.secrets.read(DriveBackupWork.CONFIG)?.optString("generation") != config.getString("generation"))
                    return@withLock Result.success()
                DriveBackupWork.status(app, "Uploading encrypted backup")
                val context = currentCoroutineContext()
                val id = GoogleDriveBackup().upload(file, token) { context.ensureActive() }
                PortableBackup(app).commit(outcome, "drive", "drive:$id")
                if (outcome.blocked.isNotEmpty()) {
                    DriveBackupWork.status(app, "Backup uploaded and confirmed; ${outcome.blocked.size} originals were blocked (${outcome.blocked.first().reason ?: "not included"}) and are not marked backed up", id)
                    DriveBackupWork.failure(app, "${outcome.blocked.size} originals were blocked from the last Drive backup")
                } else {
                    DriveBackupWork.status(app, "Backup uploaded and size/checksum confirmed", id)
                    DriveBackupWork.failure(app, null)
                }
                // Only after a confirmed upload; only archives this app created, never the one just uploaded.
                val keep = config.optInt("keep", DriveBackupWork.DEFAULT_KEEP)
                if (keep > 0) try {
                    val drive = GoogleDriveBackup()
                    drive.list(token).filter { it.id != id }.sortedByDescending { it.createdAt }.drop(keep - 1).forEach { drive.delete(it.id, token) }
                } catch (e: CancellationException) { throw e }
                catch (caught: Exception) {
                    com.hellfire.ass.Diagnostics.warn(caught)
                    DriveBackupWork.status(app, "Backup uploaded and confirmed; removing older backups failed and will be retried after the next upload", id)
                }
                Result.success()
            } catch (e: CancellationException) {
                DriveBackupWork.status(app, "Backup interrupted; waiting for another scheduled attempt")
                DriveBackupWork.failure(app, "Drive backup interrupted")
                throw e
            } catch (_: DriveReconnectRequired) {
                DriveBackupWork.status(app, "Reconnect Google Drive in Backup; authorization is required")
                DriveBackupWork.failure(app, "Google Drive authorization required")
                Result.failure()
            } catch (e: DriveHttpError) {
                DriveBackupWork.failure(app, "Drive upload failed (HTTP ${e.status})")
                DriveBackupWork.status(app, if (e.status == 403) "Drive access or storage quota denied; check Google account and reconnect" else "Drive upload failed (HTTP ${e.status}); no new success recorded")
                if (e.status == 429 || e.status >= 500) Result.retry() else Result.failure()
            } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught)
                DriveBackupWork.failure(app, "Drive backup failed before a confirmed upload")
                DriveBackupWork.status(app, "Backup failed; check connection, storage, Google setup and passphrase. Previous backups retained.")
                Result.retry()
            } finally { password?.fill('\u0000'); file.delete() }
        }
    }
}
