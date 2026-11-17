package com.hellfire.ass.media

import android.net.Uri
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ai.HeavyModelGate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class LocalSpeech(private val app: HellFireApp) {
    val model get() = File(app.filesDir, "models/speech.ggml")
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        HeavyModelGate.mutex.withLock {
            model.parentFile!!.mkdirs()
            val part = File(model.parentFile, "speech.part")
            try {
                app.contentResolver.openInputStream(uri)!!.use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(65536); var size = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                        require(n > 0); size += n; require(size <= 4L * 1024 * 1024 * 1024); output.write(buffer, 0, n) }
                } }
                require(part.length() > 1024) { "Select a compatible multilingual whisper.cpp GGML model" }
                java.nio.file.Files.move(part.toPath(), model.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                app.getSharedPreferences("model-catalog", 0).edit().remove("installed-SPEECH").apply()
            } finally { part.delete() }
        }
    }
    suspend fun transcribe(samples: FloatArray, language: String): JSONObject = withContext(Dispatchers.Default) {
        require(language in setOf("auto", "sw", "en"))
        HeavyModelGate.mutex.withLock {
            check(model.isFile) { "TRANSCRIPTION_MODEL_REQUIRED" }
            val handle = WhisperNative.load(model.path)
            try {
                coroutineScope {
                    ensureActive(); WhisperNative.reset(handle)
                    val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { WhisperNative.abort(handle) }
                    }
                    try {
                        val result = WhisperNative.transcribe(handle, samples, language)
                        ensureActive()
                        JSONObject(result.toString(Charsets.UTF_8))
                    } finally { withContext(NonCancellable) { watcher.cancelAndJoin() } }
                }
            } finally { WhisperNative.release(handle) }
        }
    }
}
