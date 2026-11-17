package com.hellfire.ass.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.communications.IntegrationState
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object FolderImport {
    const val MAX_IMPORTS_PER_SCAN = 25
    suspend fun select(app: HellFireApp, uri: Uri) = withContext(Dispatchers.IO) {
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.secrets.save("media-folder", JSONObject().put("uri", uri.toString()).put("seen", JSONObject()))
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("call-folder-import", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<FolderImportWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()).result.get()
        scan(app)
    }
    suspend fun disable(app: HellFireApp) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(app).cancelUniqueWork("call-folder-import").result.get()
        app.secrets.read("media-folder")?.optString("uri")?.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        app.secrets.remove("media-folder")
        app.database.communications().integration(IntegrationState("CALL_FOLDER", "DISABLED", System.currentTimeMillis()))
    }
    suspend fun scan(app: HellFireApp) = withContext(Dispatchers.IO) {
        val config = app.secrets.read("media-folder") ?: return@withContext
        val tree = Uri.parse(config.getString("uri"))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val seen = config.optJSONObject("seen") ?: JSONObject()
        data class Entry(val id: String, val signature: String, val modified: Long)
        val entries = mutableListOf<Entry>()
        app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            // Read every entry: providers return them in their own order, so a fixed prefix can miss new recordings.
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (!cursor.getString(1).orEmpty().startsWith("audio/")) continue
                val id = cursor.getString(0)
                val signature = "${cursor.getLong(2)}:${cursor.getLong(3)}"
                if (seen.optString(id) != signature) entries += Entry(id, signature, cursor.getLong(3))
            }
        } ?: error("Folder unavailable")
        // Newest first, a bounded number per run; the rest follow on the next scan.
        val batch = entries.sortedByDescending { it.modified }.take(MAX_IMPORTS_PER_SCAN)
        for (entry in batch) {
            currentCoroutineContext().ensureActive()
            app.media.capture(DocumentsContract.buildDocumentUriUsingTree(tree, entry.id), com.hellfire.ass.library.CaptureOrigin.CALL_FOLDER, syncEnabled = false, kind = "CALL_RECORDING")
            seen.put(entry.id, entry.signature)
            config.put("seen", seen); app.secrets.save("media-folder", config)
        }
        val skipped = entries.size > batch.size
        app.database.communications().integration(IntegrationState("CALL_FOLDER", if (skipped) "IMPORTING_BACKLOG" else "CONNECTED", System.currentTimeMillis()))
    }
}
class FolderImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HellFireApp
        return try { FolderImport.scan(app); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); app.database.communications().integration(IntegrationState("CALL_FOLDER", "FAILED", System.currentTimeMillis(), "FOLDER_ACCESS_OR_STORAGE_UNAVAILABLE")); Result.retry() }
    }
}
