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
                PortableBackup(app).export(file, password)
                currentCoroutineContext().ensureActive()
                // A disconnect or consent change must stop a queued upload.
                if (app.secrets.read(DriveBackupWork.CONFIG)?.optString("generation") != config.getString("generation"))
                    return@withLock Result.success()
                DriveBackupWork.status(app, "Uploading encrypted backup")
                val context = currentCoroutineContext()
                val id = GoogleDriveBackup().upload(file, token) { context.ensureActive() }
                DriveBackupWork.status(app, "Backup uploaded and size/checksum confirmed", id)
                Result.success()
            } catch (e: CancellationException) {
                DriveBackupWork.status(app, "Backup interrupted; waiting for another scheduled attempt")
                throw e
            } catch (_: DriveReconnectRequired) {
                DriveBackupWork.status(app, "Reconnect Google Drive in Backup; authorization is required")
                Result.failure()
            } catch (e: DriveHttpError) {
                DriveBackupWork.status(app, if (e.status == 403) "Drive access or storage quota denied; check Google account and reconnect" else "Drive upload failed (HTTP ${e.status}); no new success recorded")
                if (e.status == 429 || e.status >= 500) Result.retry() else Result.failure()
            } catch (_: Exception) {
                DriveBackupWork.status(app, "Backup failed; check connection, storage, Google setup and passphrase. Previous backups retained.")
                Result.retry()
            } finally { password?.fill('\u0000'); file.delete() }
        }
    }
}
