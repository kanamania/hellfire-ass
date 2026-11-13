package com.hellfire.ass.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.net.Uri
import android.os.PowerManager
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.audio.MicrophoneService
import com.hellfire.ass.memory.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Owner-imported ONNX speaker-embedding model (input [batch, frames, 80] fbank, e.g. WeSpeaker / 3D-Speaker).
 * Labels are automatic guesses written only over UNKNOWN or earlier AUTO labels; manual labels are never replaced.
 */
class SpeakerModel(private val app: HellFireApp) {
    private val mutex = Mutex()
    val modelFile get() = File(app.filesDir, "models/speaker.onnx")
    val configured get() = modelFile.isFile

    private fun modelId(file: File = modelFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun <T> session(file: File = modelFile, block: (OrtSession, String) -> T): T =
        OrtEnvironment.getEnvironment().createSession(file.path).use { session ->
            val name = session.inputNames.singleOrNull() ?: error("Speaker model must have exactly one input")
            val shape = (session.inputInfo.getValue(name).info as? TensorInfo)?.shape ?: error("Speaker model input must be a tensor")
            require(shape.size == 3 && shape[2] == Fbank.BINS.toLong()) { "Speaker model input must be [batch, frames, 80] filterbank features" }
            block(session, name)
        }

    private fun embed(session: OrtSession, input: String, samples: FloatArray): FloatArray {
        val features = Fbank.compute(samples)
        require(features.size >= 50) { "Segment too short for a speaker embedding" }
        val buffer = FloatBuffer.allocate(features.size * Fbank.BINS).apply { features.forEach { put(it) }; rewind() }
        return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), buffer, longArrayOf(1, features.size.toLong(), Fbank.BINS.toLong())).use { tensor ->
            session.run(mapOf(input to tensor)).use { result -> VectorMath.normalize(flatten(result.get(0).value)) }
        }
    }

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.map { flatten(it) }.reduce { a, b -> a + b }
        else -> error("Speaker model output must be a float tensor")
    }

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            modelFile.parentFile!!.mkdirs()
            val temp = File(modelFile.parentFile, "speaker.part")
            try {
                app.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(65536); var size = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                        size += n; require(size <= 300L * 1024 * 1024) { "Speaker model exceeds 300 MiB" }; output.write(buffer, 0, n) }
                } }
                val probe = FloatArray(Fbank.RATE * 2) { (kotlin.math.sin(it * 0.0785) * 0.1).toFloat() }
                val dimension = runCatching { session(temp) { s, name -> embed(s, name, probe).size } }.getOrDefault(0)
                require(dimension in 16..4096) { "Select an ONNX speaker-embedding model that takes [batch, frames, 80] filterbank input." }
                java.nio.file.Files.move(temp.toPath(), modelFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) { mutex.withLock { modelFile.delete(); app.secrets.remove(OWNER) } }

    fun status(): String = when {
        !configured -> "No speaker model imported. Speaker labels stay manual."
        app.secrets.read(OWNER) == null -> "Speaker model ready; owner voice not enrolled."
        else -> "Speaker model ready; owner voice enrolled."
    }

    /** Enrolls the owner's voice from the newest visible 60-second test recording. */
    suspend fun enrollOwner(): String = withContext(Dispatchers.IO) {
        require(configured) { "Import a speaker model first." }
        val file = MicrophoneService.recordingDirectory(app).listFiles { f -> f.name.endsWith(".pcm.enc") }?.maxByOrNull { it.lastModified() }
        requireNotNull(file) { "Record a test sample of your own voice first (Setup → microphone test)." }
        require(file.length() <= MicrophoneService.MAX_BYTES + 29L)
        val bytes = app.cipher.decrypt(file.readBytes())
        val samples = try { FloatArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 255) or (bytes[2 * it + 1].toInt() shl 8)).toShort() / 32768f } }
            finally { bytes.fill(0) }
        require(samples.size >= Fbank.RATE * 5) { "Use a test sample with at least 5 seconds of your voice." }
        mutex.withLock {
            val vector = session { s, name -> embed(s, name, samples) }
            samples.fill(0f)
            app.secrets.save(OWNER, JSONObject().put("model", modelId()).put("vector", Base64.getEncoder().encodeToString(VectorMath.encode(vector)))
                .put("enrolledAt", System.currentTimeMillis()))
        }
        "Owner voice enrolled from your latest test sample. Matching is a guess; manual labels always win."
    }

    /** Groups transcript segments of [item] by voice and writes AUTO labels. */
    suspend fun label(item: MediaItem): String = withContext(Dispatchers.IO) {
        require(configured) { "Import a speaker model first." }
        require(app.getSystemService(PowerManager::class.java).currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) { "Let the device cool before speaker analysis." }
        val segments = app.database.media().transcript(item.id).filter { it.id.startsWith("${item.id}:speech:") && it.endMs - it.startMs >= MIN_MS }
        require(segments.isNotEmpty()) { "Transcribe this recording first; segments under 1 second are skipped." }
        mutex.withLock {
            val model = modelId()
            val owner = app.secrets.read(OWNER)?.takeIf { it.optString("model") == model }?.let { VectorMath.decode(Base64.getDecoder().decode(it.getString("vector"))) }
            val vectors = session { s, name ->
                segments.map { segment ->
                    currentCoroutineContext().ensureActive()
                    val length = minOf(segment.endMs - segment.startMs, MAX_MS)
                    val chunk = AudioDecoder.chunk(EncryptedMediaSource(app.media.reader(item)), segment.startMs, ((length + 999) / 1000).toInt())
                    val samples = chunk.samples.copyOf(minOf(chunk.samples.size, (length * Fbank.RATE / 1000).toInt()))
                    try { embed(s, name, samples) } finally { samples.fill(0f); chunk.samples.fill(0f) }
                }
            }
            val labels = SpeakerClusters.cluster(vectors, CLUSTER_THRESHOLD)
            val groups = labels.max() + 1
            val ownerScores = (0 until groups).map { group ->
                owner?.let { SpeakerClusters.cosine(SpeakerClusters.centroid(vectors.filterIndexed { i, _ -> labels[i] == group }), it) }
            }
            val ownerGroup = ownerScores.withIndex().filter { (it.value ?: -1f) >= OWNER_THRESHOLD }.maxByOrNull { it.value!! }?.index
            val names = SpeakerNames.assign(segments.mapIndexed { i, segment -> labels[i] to segment.text }, skip = ownerGroup)
            var written = 0
            segments.forEachIndexed { i, segment ->
                if (segment.speaker != "UNKNOWN" && !segment.speaker.startsWith(AUTO)) return@forEachIndexed
                val label = if (labels[i] == ownerGroup) "$AUTO OWNER? ${String.format(Locale.ROOT, "%.2f", ownerScores[labels[i]])}"
                    else "$AUTO SPEAKER ${labels[i] + 1}" + (names[labels[i]]?.let { " · ${it.first}?" } ?: "")
                app.database.media().speaker(segment.id, label); written++
            }
            "Grouped ${segments.size} segments into $groups voice group(s); $written unlabeled segment(s) updated. " +
                (if (names.isEmpty()) "" else "Placeholder names from the conversation: ${names.entries.sortedBy { it.key }.joinToString { "group ${it.key + 1} ${it.value.first}?" }}. ") +
                (when { owner == null -> "Owner voice not enrolled for this model."; ownerGroup == null -> "No group matched the owner voice."; else -> "Owner voice matched group ${ownerGroup + 1}." }) +
                " Automatic labels are unverified guesses; whole segments may contain more than one speaker."
        }
    }

    private companion object {
        const val OWNER = "owner-voice"
        const val AUTO = "AUTO"
        const val MIN_MS = 1000L
        const val MAX_MS = 30_000L
        const val CLUSTER_THRESHOLD = 0.5f
        const val OWNER_THRESHOLD = 0.6f
    }
}
