package com.hellfire.ass.media

import android.net.Uri
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ai.HeavyModelGate
import com.hellfire.ass.performance.DeviceProbe
import com.hellfire.ass.performance.ModelSlots
import com.hellfire.ass.performance.OperatingProfile
import com.hellfire.ass.performance.PerfLog
import com.hellfire.ass.performance.rolesToEvict
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class LocalSpeech(private val app: HellFireApp) {
    private var handle = 0L
    private var handleKey = ""
    val model get() = File(app.filesDir, "models/speech.ggml")

    suspend fun releaseCached() = HeavyModelGate.mutex.withLock { closeCached() }

    /** Caller must already hold [HeavyModelGate]. */
    fun closeCached() {
        if (handle != 0L) runCatching { WhisperNative.release(handle) }
        handle = 0L
        handleKey = ""
        ModelSlots.drop("SPEECH")
    }
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        HeavyModelGate.mutex.withLock {
            closeCached()
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
                val profile = OperatingProfile.of(DeviceProbe.signals(app))
                rolesToEvict("SPEECH", ModelSlots.snapshot(), profile).forEach { role ->
                    when (role) {
                        "LANGUAGE" -> app.localModel.closeCached()
                        "EMBEDDING" -> app.semantic.closeCached()
                        "SPEAKER" -> app.speakers.closeCached()
                    }
                }
                val key = "${model.path}:${DeviceProbe.fileKey(model)}"
                val reuse = profile.reuseModels
                if (!reuse || handle == 0L || handleKey != key) {
                    closeCached()
                    handle = WhisperNative.open(model.path)
                    handleKey = key
                    if (reuse) ModelSlots.mark("SPEECH")
                }
                val active = handle
                try {
                    coroutineScope {
                        ensureActive(); WhisperNative.reset(active)
                        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { WhisperNative.abort(active) }
                        }
                        try {
                            val started = android.os.SystemClock.elapsedRealtime()
                            val result = WhisperNative.transcribe(active, samples, language)
                            ensureActive()
                            PerfLog.record("whisper_ms", android.os.SystemClock.elapsedRealtime() - started)
                            JSONObject(result.toString(Charsets.UTF_8))
                        } finally { withContext(NonCancellable) { watcher.cancelAndJoin() } }
                    }
                } finally { if (!reuse) closeCached() }
            }
    }
}
