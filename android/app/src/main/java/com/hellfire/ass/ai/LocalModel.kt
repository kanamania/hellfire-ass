package com.hellfire.ass.ai

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One bounded inference at a time. Each request releases its engine and conversation. */
class LocalModel(private val context: Context) {
    private val mutex = HeavyModelGate.mutex
    val modelFile get() = File(context.filesDir, "models/assistant.litertlm")
    val configured get() = modelFile.isFile
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            modelFile.parentFile!!.mkdirs()
            val temp = File(modelFile.parentFile, "assistant.part")
            try {
                context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(65536); var size = 0L
                    while (true) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                        require(n > 0); size += n; require(size <= 12L * 1024 * 1024 * 1024); output.write(buffer, 0, n) }
                } }
                require(temp.length() in 1024..(12L * 1024 * 1024 * 1024)) { "Select a compatible .litertlm model" }
                java.nio.file.Files.move(temp.toPath(), modelFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                ModelUpdates.markImported(context)
            } finally { temp.delete() }
        }
    }

    /** True when the runtime can open [file] on CPU. Used before a downloaded model replaces the current one. */
    suspend fun loads(file: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = context.cacheDir.path, maxNumTokens = 512)).use { it.initialize() }
            }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }.isSuccess
        }
    }

    /** Atomically replaces the model with a verified file; a running inference finishes on the old one first. */
    suspend fun install(file: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            modelFile.parentFile!!.mkdirs()
            java.nio.file.Files.move(file.toPath(), modelFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }
    suspend fun generate(prompt: String, gpu: Boolean = false): ModelAnswer = withContext(Dispatchers.IO) {
        require(prompt.length <= 12_000) { "Input exceeds the local request limit" }
        mutex.withLock {
            check(context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus < android.os.PowerManager.THERMAL_STATUS_SEVERE) { "Device must cool before local inference" }
            check(configured) { "No local model imported. Core capture and finance still work." }
            val started = SystemClock.elapsedRealtime()
            val config = EngineConfig(modelPath = modelFile.path, backend = if (gpu) Backend.GPU() else Backend.CPU(),
                cacheDir = context.cacheDir.path, maxNumTokens = 4096)
            val text = Engine(config).use { engine ->
                engine.initialize()
                engine.createConversation(ConversationConfig(automaticToolCalling = false)).use { conversation ->
                    conversation.sendMessage(prompt).toString()
                }
            }
            ModelAnswer(text, SystemClock.elapsedRealtime() - started, Debug.getPss(), if (gpu) "GPU" else "CPU")
        }
    }
    /** The owner removed the model: it is not downloaded again until they ask. */
    suspend fun remove() = withContext(Dispatchers.IO) {
        ModelUpdates.cancel(context)
        mutex.withLock { modelFile.delete() }
        ModelUpdates.markRemoved(context)
    }
}

data class ModelAnswer(val text: String, val elapsedMs: Long, val pssKb: Long, val backend: String)

object HeavyModelGate { val mutex = Mutex() }
