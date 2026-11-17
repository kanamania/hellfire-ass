package com.hellfire.ass.ai

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Curated runtime formats; an arbitrary Hugging Face model is not necessarily compatible. */
data class CatalogModel(val id: String, val name: String, val role: String, val publisher: String, val license: String,
    val url: String, val source: String, val release: ModelRelease, val description: String)

object ModelCatalog {
    const val WORK = "catalog-model-download"
    private fun hf(id: String, name: String, role: String, repo: String, file: String, commit: String, hash: String, size: Long, license: String, description: String) =
        CatalogModel(id, name, role, repo.substringBefore('/'), license, "https://huggingface.co/$repo/resolve/$commit/$file", "https://huggingface.co/$repo", ModelRelease(commit, hash, size), description)
    val entries = listOf(
        hf("gemma4-e2b", GemmaModel.NAME, "LANGUAGE", GemmaModel.REPO, GemmaModel.FILE, GemmaModel.PINNED.commit, GemmaModel.PINNED.sha256, GemmaModel.PINNED.size, "Apache-2.0", "Default · LiteRT-LM · CPU/GPU. Device speed and Kiswahili quality need verification."),
        hf("qwen3-06b", "Qwen3 0.6B", "LANGUAGE", "litert-community/Qwen3-0.6B", "Qwen3-0.6B.litertlm", "8d42ab5f1db14cad987fac57aeb17d070f9e180d", "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108", 613722264, "Apache-2.0", "Smaller language model · generic LiteRT-LM format. Quality and runtime compatibility require a load check."),
        hf("gemma4-e4b", "Gemma 4 E4B", "LANGUAGE", "litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm", "f254b5ba6e36f7d89cc7892e268e9877bc20f0a0", "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", 3659530240, "Apache-2.0", "Larger language model · more storage and working memory. No device performance claim."),
        hf("whisper-base", "Whisper Base multilingual", "SPEECH", "ggerganov/whisper.cpp", "ggml-base.bin", "5359861c739e955e79d9a303bcbc70fb988958b1", "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", 147951465, "MIT", "Smaller multilingual transcription model; supports English/Kiswahili selection. Accuracy needs testing."),
        hf("whisper-small", "Whisper Small multilingual", "SPEECH", "ggerganov/whisper.cpp", "ggml-small.bin", "5359861c739e955e79d9a303bcbc70fb988958b1", "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", 487601967, "MIT", "Larger multilingual transcription model; more memory and analysis time."),
        CatalogModel("use", "Universal Sentence Encoder", "EMBEDDING", "Google MediaPipe", "See publisher model terms", "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite", "https://ai.google.dev/edge/mediapipe/solutions/text/text_embedder", ModelRelease("1", "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749", 6120274), "MediaPipe TextEmbedder · English semantic search. Kiswahili retrieval is not established; literal search remains available.")
    )
    fun entry(id: String) = entries.single { it.id == id }
    fun download(context: Context, id: String) {
        val entry = entry(id)
        if (id == "gemma4-e2b") { ModelUpdates.download(context, GemmaModel.PINNED); return }
        if (entry.role == "LANGUAGE") { ModelUpdates.cancel(context); ModelUpdates.suppressAutomaticDownload(context) }
        context.getSharedPreferences("model-catalog", 0).edit().putString("target-role", entry.role).apply()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CatalogDownloadWorker>()
            .setInputData(workDataOf("id" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }
    fun status(context: Context, message: String) { context.getSharedPreferences("model-catalog", 0).edit().putString("status", message).apply() }
}

class CatalogDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        val model = ModelCatalog.entries.firstOrNull { it.id == inputData.getString("id") } ?: return@withContext Result.failure()
        val part = File(app.filesDir, "models/catalog-${model.id}.part").apply { parentFile!!.mkdirs() }
        try {
            require(part.parentFile!!.usableSpace >= model.release.size - part.length().coerceAtMost(model.release.size) + 512L * 1024 * 1024) { "Not enough storage" }
            val notification = ModelUpdates.notification(app, model.name, part.length(), model.release.size)
            setForeground(ForegroundInfo(86, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            ModelCatalog.status(app, "Downloading ${model.name}. Wi-Fi is required; partial bytes are retained for retry.")
            val context = currentCoroutineContext()
            var last = -1L
            ModelDownloader().download(model.release, part, { context.ensureActive() }, { done ->
                val percent = done * 100 / model.release.size
                if (percent != last) {
                    last = percent; setProgressAsync(workDataOf("percent" to percent.toInt(), "model" to model.name))
                    ModelCatalog.status(app, "${model.name}: $percent%")
                }
            }, model.url)
            ModelCatalog.status(app, "Verifying runtime compatibility: ${model.name}")
            when (model.role) {
                "LANGUAGE" -> { require(app.localModel.loads(part)) { "Model did not load; previous model retained" }; app.localModel.install(part)
                    if (model.id == "gemma4-e2b") ModelUpdates.markInstalled(app, model.release) else ModelUpdates.markImported(app) }
                "SPEECH" -> { HeavyModelGate.mutex.lock(); try { val handle = com.hellfire.ass.media.WhisperNative.open(part.path); require(handle != 0L); com.hellfire.ass.media.WhisperNative.release(handle) } finally { HeavyModelGate.mutex.unlock() }; app.speech.import(Uri.fromFile(part)) }
                "EMBEDDING" -> app.semantic.import(Uri.fromFile(part))
            }
            part.delete()
            app.getSharedPreferences("model-catalog", 0).edit().putString("installed-${model.role}", model.id).apply()
            ModelCatalog.status(app, "Installed and loaded: ${model.name}. Device accuracy and speed still need evaluation.")
            Result.success()
        } catch (e: CancellationException) { ModelCatalog.status(app, "Download paused. Select this model again to resume."); throw e }
        catch (e: Exception) { com.hellfire.ass.Diagnostics.warn(e); ModelCatalog.status(app, if (e is IllegalArgumentException) e.message ?: "Model rejected; previous model retained." else "Download interrupted. Check Wi-Fi/storage and retry; previous model retained."); Result.failure() }
    }
}
