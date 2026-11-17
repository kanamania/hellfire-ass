package com.hellfire.ass.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One published version of the model file: repository commit, file SHA-256 and size. */
data class ModelRelease(val commit: String, val sha256: String, val size: Long)

/**
 * Gemma 4 E2B for LiteRT-LM, published by Google in the litert-community Hugging Face repository
 * (Apache-2.0, not gated, no account needed). The pinned release is installed on first launch over
 * Wi-Fi; a daily check reports newer files, which download only when the owner taps Update.
 */
object GemmaModel {
    const val NAME = "Gemma 4 E2B"
    const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    const val FILE = "gemma-4-E2B-it.litertlm"
    const val MAX_BYTES = 12L * 1024 * 1024 * 1024
    /** Release checked when this app version was built (file uploaded 2026-05-04, listed at commit 2d74ac4 on 2026-11-11). */
    val PINNED = ModelRelease("2d74ac41a33d7da82f39f7d5720a04c68514a103",
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 2_588_147_712)
    const val METADATA_URL = "https://huggingface.co/api/models/$REPO?blobs=true"
    fun downloadUrl(release: ModelRelease) = "https://huggingface.co/$REPO/resolve/${release.commit}/$FILE"

    /** The model file's current release from the repository metadata, or null if the file is missing or malformed. */
    fun parse(metadata: JSONObject): ModelRelease? {
        val commit = metadata.optString("sha").takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return null
        val siblings = metadata.optJSONArray("siblings") ?: return null
        val file = (0 until siblings.length()).map { siblings.getJSONObject(it) }.firstOrNull { it.optString("rfilename") == FILE } ?: return null
        val sha = file.optJSONObject("lfs")?.optString("sha256")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        val size = file.optLong("size", -1).takeIf { it in 1..MAX_BYTES } ?: return null
        return ModelRelease(commit, sha, size)
    }
}

/** HTTPS transfer of the model file. Resumes partial downloads; the result is accepted only with the expected size and SHA-256. */
class ModelDownloader(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    class HttpError(val status: Int) : IOException("Model server returned HTTP $status")

    fun latest(): ModelRelease {
        val connection = open(URL(GemmaModel.METADATA_URL), 0)
        try {
            if (connection.responseCode != 200) throw HttpError(connection.responseCode)
            val text = connection.inputStream.use { readSmall(it) }
            return GemmaModel.parse(JSONObject(text)) ?: throw IOException("Model file is not listed in the repository")
        } finally { connection.disconnect() }
    }

    fun download(release: ModelRelease, part: File, checkActive: () -> Unit = {}, progress: (Long) -> Unit = {}, downloadUrl: String = GemmaModel.downloadUrl(release)) {
        require(release.size in 1..GemmaModel.MAX_BYTES)
        if (part.length() > release.size) part.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var have = part.length()
        // Re-hash what an earlier attempt already saved so the final check covers the whole file.
        if (have > 0) part.inputStream().use { input -> copy(input, null, digest, checkActive) }
        if (have < release.size) {
            val connection = open(URL(downloadUrl), have)
            try {
                val append = when (connection.responseCode) {
                    206 -> {
                        val start = Regex("bytes (\\d+)-").find(connection.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLong()
                        if (start != have) throw IOException("Server resumed at the wrong position")
                        true
                    }
                    200 -> false
                    else -> throw HttpError(connection.responseCode)
                }
                if (!append) { digest.reset(); have = 0 }
                FileOutputStream(part, append).use { output ->
                    connection.inputStream.use { input -> copy(input, output, digest, checkActive) { have += it; require(have <= release.size); progress(have) } }
                    output.fd.sync()
                }
            } finally { connection.disconnect() }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (part.length() != release.size || hash != release.sha256) {
            part.delete()
            throw IOException("Downloaded model failed its size or checksum check")
        }
    }

    /** Follows the repository's redirect to its file host itself, so every hop stays on HTTPS and keeps the Range header. */
    private fun open(start: URL, from: Long): HttpURLConnection {
        var url = start
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null) { "Model downloads use HTTPS only" }
            val connection = connect(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000; connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "HellFireAss-model-updater")
            if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
            val code = connection.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                connection.disconnect()
                url = URL(url, location)
            } else return connection
        }
        throw IOException("Too many redirects")
    }

    private fun copy(input: InputStream, output: java.io.OutputStream?, digest: MessageDigest, checkActive: () -> Unit, counted: (Int) -> Unit = {}) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            checkActive()
            val n = input.read(buffer); if (n < 0) break
            digest.update(buffer, 0, n); output?.write(buffer, 0, n); counted(n)
        }
    }

    private fun readSmall(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 2 * 1024 * 1024); out.write(buffer, 0, n) }
        return out.toString(Charsets.UTF_8.name())
    }
}

/** Saved install/update state. "managed" means the installed file is the downloaded Gemma release. */
object ModelUpdates {
    private const val PREFS = "model-updates"
    const val DOWNLOAD = "gemma-download"
    const val CHECK = "gemma-update-check"
    private const val CHANNEL = "model-download"

    data class State(val source: String, val installed: ModelRelease?, val available: ModelRelease?, val declined: Boolean,
        val status: String, val downloaded: Long, val total: Long)

    fun state(context: Context): State {
        val p = context.getSharedPreferences(PREFS, 0)
        fun release(prefix: String) = p.getString("${prefix}_sha", null)?.let { ModelRelease(p.getString("${prefix}_commit", "")!!, it, p.getLong("${prefix}_size", 0)) }
        return State(p.getString("source", "")!!, release("installed"), release("available"), p.getBoolean("declined", false),
            p.getString("status", "")!!, p.getLong("downloaded", 0), p.getLong("total", 0))
    }

    /** A newer published file than the installed managed one; null when up to date or the model is the owner's own file. */
    fun pendingUpdate(state: State): ModelRelease? =
        state.available?.takeIf { state.source == "managed" && it.sha256 != state.installed?.sha256 }

    private fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        context.getSharedPreferences(PREFS, 0).edit().apply(block).apply()
    }
    private fun android.content.SharedPreferences.Editor.putRelease(prefix: String, release: ModelRelease?) {
        if (release == null) { remove("${prefix}_sha"); remove("${prefix}_commit"); remove("${prefix}_size") }
        else { putString("${prefix}_sha", release.sha256); putString("${prefix}_commit", release.commit); putLong("${prefix}_size", release.size) }
    }
    fun status(context: Context, text: String, downloaded: Long? = null, total: Long? = null) = edit(context) {
        putString("status", text); downloaded?.let { putLong("downloaded", it) }; total?.let { putLong("total", it) }
    }
    fun markInstalled(context: Context, release: ModelRelease) = edit(context) {
        putString("source", "managed"); putRelease("installed", release); putRelease("available", null); putBoolean("declined", false)
        putString("status", "${GemmaModel.NAME} installed and loaded successfully"); putLong("downloaded", 0); putLong("total", 0)
    }
    fun markImported(context: Context) = edit(context) { putString("source", "imported"); putRelease("installed", null) }
    fun markRemoved(context: Context) = edit(context) { putString("source", ""); putRelease("installed", null); putBoolean("declined", true); putString("status", "") }
    fun markAvailable(context: Context, release: ModelRelease) = edit(context) { putRelease("available", release); putLong("checked_at", System.currentTimeMillis()) }

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build()

    /** First launch installs the pinned release unless the owner removed the model or uses their own file. */
    fun onStartup(app: HellFireApp) {
        val state = state(app)
        if (app.localModel.configured && state.source.isEmpty()) markImported(app)
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(CHECK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ModelCheckWorker>(24, TimeUnit.HOURS).setConstraints(constraints()).build())
        if (!app.localModel.configured && !state.declined) download(app, GemmaModel.PINNED, ExistingWorkPolicy.KEEP)
    }

    fun download(context: Context, release: ModelRelease, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        edit(context) { putBoolean("declined", false) }
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, policy, OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf("commit" to release.commit, "sha" to release.sha256, "size" to release.size))
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }

    fun suppressAutomaticDownload(context: Context) = edit(context) { putBoolean("declined", true) }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD)
        partFile(context).delete()
        status(context, "Download cancelled", 0, 0)
    }

    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("$CHECK-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ModelCheckWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun partFile(context: Context) = File(context.filesDir, "models/gemma.part")

    fun notification(context: Context, text: String, done: Long, total: Long): android.app.Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "On-device model download", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_hellfire).setContentTitle("HellFire Ass · ${GemmaModel.NAME}")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0).build()
    }
}

/** Downloads, verifies and test-loads a model release, then swaps it in. The current model stays in use until then. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        val release = ModelRelease(inputData.getString("commit") ?: return@withContext Result.failure(),
            inputData.getString("sha") ?: return@withContext Result.failure(), inputData.getLong("size", 0))
        val part = ModelUpdates.partFile(app).apply { parentFile!!.mkdirs() }
        try {
            val needed = release.size - part.length().coerceAtMost(release.size) + 512L * 1024 * 1024
            if (part.parentFile!!.usableSpace < needed) {
                ModelUpdates.status(app, "Not enough free storage for ${GemmaModel.NAME}: ${needed / 1_000_000} MB needed")
                return@withContext Result.retry()
            }
            runCatching { setForeground(foreground(app, "Downloading", part.length(), release.size)) }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }
            var lastShown = -1L
            val context = currentCoroutineContext()
            ModelDownloader().download(release, part, { context.ensureActive() }, { done ->
                val percent = done * 100 / release.size
                if (percent != lastShown) {
                    lastShown = percent
                    ModelUpdates.status(app, "Downloading ${GemmaModel.NAME}: $percent%", done, release.size)
                    runCatching { setForegroundAsync(foreground(app, "Downloading $percent%", done, release.size)) }
                }
            })
            ModelUpdates.status(app, "Checking that ${GemmaModel.NAME} loads on this phone", release.size, release.size)
            runCatching { setForeground(foreground(app, "Checking the model loads", release.size, release.size)) }
            if (!app.localModel.loads(part)) {
                part.delete()
                ModelUpdates.status(app, "The downloaded ${GemmaModel.NAME} file did not load in this app's model runtime. The previous model (if any) is still in use; a newer app version may be needed.", 0, 0)
                return@withContext Result.failure()
            }
            app.localModel.install(part)
            ModelUpdates.markInstalled(app, release)
            Result.success()
        } catch (e: CancellationException) {
            ModelUpdates.status(app, "Download paused; it resumes on Wi-Fi", part.length(), release.size)
            throw e
        } catch (caught: Exception) {
            com.hellfire.ass.Diagnostics.warn(caught)
            ModelUpdates.status(app, "Download interrupted; it resumes on Wi-Fi from ${part.length() / 1_000_000} MB", part.length(), release.size)
            Result.retry()
        }
    }

    private fun foreground(context: Context, text: String, done: Long, total: Long): ForegroundInfo {
        val notification = ModelUpdates.notification(context, text, done, total)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(85, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(85, notification)
    }
}

/** Daily: records a newer published file. It never downloads by itself; the owner taps Update. */
class ModelCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            ModelUpdates.markAvailable(applicationContext, ModelDownloader().latest())
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); Result.retry() }
    }
}
