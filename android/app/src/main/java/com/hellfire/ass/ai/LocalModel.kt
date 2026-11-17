package com.hellfire.ass.ai

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.*
import com.hellfire.ass.performance.BackendMeasure
import com.hellfire.ass.performance.DeviceProbe
import com.hellfire.ass.performance.ExecutionScheduler
import com.hellfire.ass.performance.ModelSlots
import com.hellfire.ass.performance.OperatingProfile
import com.hellfire.ass.performance.PerfLog
import com.hellfire.ass.performance.WorkPriority
import com.hellfire.ass.performance.rolesToEvict
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One bounded inference at a time. Each request releases its engine and conversation. */
class LocalModel(private val context: Context) {
    private val mutex = HeavyModelGate.mutex
    private var engine: Engine? = null
    private var engineKey: String? = null
    val modelFile get() = File(context.filesDir, "models/assistant.litertlm")
    val configured get() = modelFile.isFile

    suspend fun releaseCached() = mutex.withLock { closeCached() }

    /** Caller must already hold [HeavyModelGate]. */
    fun closeCached() {
        runCatching { engine?.close() }
        engine = null
        engineKey = null
        ModelSlots.drop("LANGUAGE")
    }
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeCached()
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
                context.getSharedPreferences("model-catalog", 0).edit().remove("installed-LANGUAGE").apply()
            } finally { temp.delete() }
        }
    }

    /** True when the runtime can open [file] on CPU. Used before a downloaded model replaces the current one. */
    suspend fun loads(file: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeCached()
            runCatching {
                Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = context.cacheDir.path, maxNumTokens = 512)).use { it.initialize() }
            }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }.isSuccess
        }
    }

    /** Atomically replaces the model with a verified file; a running inference finishes on the old one first. */
    suspend fun install(file: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeCached()
            modelFile.parentFile!!.mkdirs()
            java.nio.file.Files.move(file.toPath(), modelFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }
    suspend fun generate(prompt: String, gpu: Boolean = false, backend: String? = null): ModelAnswer = withContext(Dispatchers.IO) {
        require(prompt.length <= 12_000) { "Input exceeds the local request limit" }
        ExecutionScheduler.noteWaiting(WorkPriority.INTERACTIVE)
        var waiting = true
        try {
            mutex.withLock {
                ExecutionScheduler.clearWaiting(WorkPriority.INTERACTIVE)
                waiting = false
                check(context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus < android.os.PowerManager.THERMAL_STATUS_SEVERE) { "Device must cool before local inference" }
                check(configured) { "No local model imported. Core capture and finance still work." }
                val profile = OperatingProfile.of(DeviceProbe.signals(context).copy(foreground = true))
                val requested = backend ?: if (gpu) "GPU" else DeviceProbe.selectedLanguageBackend(context, modelFile)
                val chosen = if (requested != "CPU" && !profile.allowAccelerator && backend == null && !gpu) "CPU" else requested
                releaseOthers("LANGUAGE", profile)
                val started = SystemClock.elapsedRealtime()
                var produced = infer(chosen, prompt, profile.reuseModels)
                if (produced == null && chosen != "CPU") {
                    DeviceProbe.clearLanguageBackend(context)
                    produced = infer("CPU", prompt, profile.reuseModels)
                }
                val (text, used) = produced ?: error("Local inference failed. Check the model and free memory.")
                val elapsed = SystemClock.elapsedRealtime() - started
                PerfLog.record("inference_ms", elapsed)
                PerfLog.record("inference_pss_kb", Debug.getPss())
                PerfLog.detail("inference_backend", used)
                ModelAnswer(text, elapsed, Debug.getPss(), used)
            }
        } finally { if (waiting) ExecutionScheduler.clearWaiting(WorkPriority.INTERACTIVE) }
    }

    /** Loads each LiteRT backend once with a fixed probe prompt. Output text is discarded. */
    suspend fun measureBackends(): Map<String, BackendMeasure> = withContext(Dispatchers.IO) {
        check(configured) { "No local model imported." }
        mutex.withLock {
            check(context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus < android.os.PowerManager.THERMAL_STATUS_SEVERE) { "Device must cool before local inference" }
            val profile = OperatingProfile.of(DeviceProbe.signals(context).copy(foreground = true))
            releaseOthers("LANGUAGE", profile)
            val results = linkedMapOf<String, BackendMeasure>()
            for (name in DeviceProbe.litertBackends()) {
                closeCached()
                val started = SystemClock.elapsedRealtime()
                val ok = try {
                    Engine(EngineConfig(modelPath = modelFile.path, backend = DeviceProbe.backend(name), cacheDir = context.cacheDir.path, maxNumTokens = 128)).use { probe ->
                        probe.initialize()
                        probe.createConversation(ConversationConfig(automaticToolCalling = false)).use { it.sendMessage("Reply with OK.") }
                    }
                    true
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { com.hellfire.ass.Diagnostics.warn(error); false }
                results[name] = BackendMeasure(ok, SystemClock.elapsedRealtime() - started)
            }
            DeviceProbe.storeLanguageMeasurement(context, modelFile, results)
            results
        }
    }

    private fun releaseOthers(keep: String, profile: OperatingProfile) {
        rolesToEvict(keep, ModelSlots.snapshot(), profile).forEach { role ->
            when (role) {
                "SPEECH" -> (context.applicationContext as? com.hellfire.ass.HellFireApp)?.speech?.closeCached()
                "EMBEDDING" -> (context.applicationContext as? com.hellfire.ass.HellFireApp)?.semantic?.closeCached()
                "SPEAKER" -> (context.applicationContext as? com.hellfire.ass.HellFireApp)?.speakers?.closeCached()
            }
        }
    }

    /** Returns null when [name] fails so the caller can fall back to CPU. Token budget stays 4096. */
    private fun infer(name: String, prompt: String, reuse: Boolean): Pair<String, String>? {
        val key = "${modelFile.path}:${DeviceProbe.fileKey(modelFile)}:$name"
        val opened = if (reuse && engineKey == key && engine?.isInitialized() == true) engine!! else {
            closeCached()
            val created = Engine(EngineConfig(modelPath = modelFile.path, backend = DeviceProbe.backend(name), cacheDir = context.cacheDir.path, maxNumTokens = 4096))
            val ready = runCatching { created.initialize(); created }.onFailure { runCatching { created.close() }; com.hellfire.ass.Diagnostics.warn(it) }.getOrNull() ?: return null
            if (reuse) { engine = ready; engineKey = key; ModelSlots.mark("LANGUAGE") }
            ready
        }
        return try {
            val text = opened.createConversation(ConversationConfig(automaticToolCalling = false)).use { it.sendMessage(prompt).toString() }
            if (!reuse) runCatching { opened.close() }
            text to name
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (engine === opened) closeCached() else runCatching { opened.close() }
            throw cancelled
        } catch (error: Exception) {
            if (engine === opened) closeCached() else runCatching { opened.close() }
            com.hellfire.ass.Diagnostics.warn(error)
            null
        }
    }
    /** The owner removed the model: it is not downloaded again until they ask. */
    suspend fun remove() = withContext(Dispatchers.IO) {
        ModelUpdates.cancel(context)
        if (context.getSharedPreferences("model-catalog", 0).getString("target-role", "") == "LANGUAGE")
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(ModelCatalog.WORK).result.get()
        mutex.withLock { closeCached(); modelFile.delete() }
        ModelUpdates.markRemoved(context)
    }
}

data class ModelAnswer(val text: String, val elapsedMs: Long, val pssKb: Long, val backend: String)

object HeavyModelGate { val mutex = Mutex() }
