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
        var count = 0; var skipped = false
        app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (++count > 300) { skipped = true; break }
                val id = cursor.getString(0); val mime = cursor.getString(1).orEmpty()
                if (!mime.startsWith("audio/")) continue
                val signature = "${cursor.getLong(2)}:${cursor.getLong(3)}"
                if (seen.optString(id) == signature) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                app.media.import(uri, "CALL_RECORDING")
                seen.put(id, signature)
                config.put("seen", seen); app.secrets.save("media-folder", config)
            }
        } ?: error("Folder unavailable")
        app.database.communications().integration(IntegrationState("CALL_FOLDER", if (skipped) "PARTIAL_FIRST_300" else "CONNECTED", System.currentTimeMillis()))
    }
}
class FolderImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HellFireApp
        return try { FolderImport.scan(app); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { app.database.communications().integration(IntegrationState("CALL_FOLDER", "FAILED", System.currentTimeMillis(), "FOLDER_ACCESS_OR_STORAGE_UNAVAILABLE")); Result.retry() }
    }
}
