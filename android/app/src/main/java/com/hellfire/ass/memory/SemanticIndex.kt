package com.hellfire.ass.memory

import android.net.Uri
import android.os.PowerManager
import android.content.Context
import androidx.room.*
import androidx.work.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ai.HeavyModelGate
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit

/** Rebuildable cache of L2-normalized source embeddings; excluded from backups. An empty vector marks a source with no indexable text. */
@Entity(tableName = "semantic_vector", indices = [Index("model")], foreignKeys = [ForeignKey(
    entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)])
class SemanticVector(@PrimaryKey val sourceId: String, val model: String, val vector: ByteArray, val createdAt: Long)

@Dao
interface SemanticDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(values: List<SemanticVector>)
    @Query("SELECT e.* FROM source_event e WHERE e.contentAvailable = 1 AND e.securitySensitive = 0 AND e.source != 'owner_skill_use' AND NOT EXISTS (SELECT 1 FROM semantic_vector v WHERE v.sourceId = e.id AND v.model = :model) ORDER BY e.receivedAt DESC LIMIT :limit")
    suspend fun pending(model: String, limit: Int): List<SourceEvent>
    @Query("SELECT COUNT(*) FROM source_event e WHERE e.contentAvailable = 1 AND e.securitySensitive = 0 AND e.source != 'owner_skill_use' AND NOT EXISTS (SELECT 1 FROM semantic_vector v WHERE v.sourceId = e.id AND v.model = :model)")
    suspend fun pendingCount(model: String): Int
    @Query("SELECT COUNT(*) FROM semantic_vector WHERE model = :model AND length(vector) > 0") suspend fun indexedCount(model: String): Int
    @Query("SELECT * FROM semantic_vector WHERE model = :model AND length(vector) > 0 AND sourceId > :after ORDER BY sourceId LIMIT :limit")
    suspend fun page(model: String, after: String, limit: Int): List<SemanticVector>
    @Query("DELETE FROM semantic_vector WHERE model != :model") suspend fun dropOtherModels(model: String)
    @Query("DELETE FROM semantic_vector") suspend fun clear()
}

object SemanticSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS semantic_vector (sourceId TEXT NOT NULL, model TEXT NOT NULL, vector BLOB NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(sourceId), FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_vector_model ON semantic_vector(model)")
    }
}

object VectorMath {
    fun normalize(values: FloatArray): FloatArray {
        val norm = kotlin.math.sqrt(values.fold(0.0) { sum, v -> sum + v * v })
        return if (norm == 0.0 || norm.isNaN()) FloatArray(0) else FloatArray(values.size) { (values[it] / norm).toFloat() }
    }
    fun encode(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat) }.array()
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
    fun dot(a: FloatArray, b: FloatArray): Float { require(a.size == b.size); var sum = 0f; for (i in a.indices) sum += a[i] * b[i]; return sum }
    /** Highest-scoring [k] ids, best first. */
    fun top(k: Int, scored: Sequence<Pair<String, Float>>): List<Pair<String, Float>> {
        val heap = PriorityQueue<Pair<String, Float>>(k + 1, compareBy { it.second })
        for (item in scored) { heap.offer(item); if (heap.size > k) heap.poll() }
        return heap.sortedByDescending { it.second }
    }
}

/** On-device meaning search over saved sources with an owner-imported MediaPipe text-embedder model. */
class SemanticIndex(private val app: HellFireApp) {
    private val mutex = Mutex()
    private val dao get() = app.database.semantic()
    private var cachedId: Triple<Long, Long, String>? = null
    val modelFile get() = File(app.filesDir, "models/text_embedder.tflite")
    val configured get() = modelFile.isFile

    private fun modelId(): String {
        val key = modelFile.length() to modelFile.lastModified()
        cachedId?.let { if (it.first == key.first && it.second == key.second) return it.third }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(modelFile).use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }.also { cachedId = Triple(key.first, key.second, it) }
    }

    private suspend inline fun <T> withEmbedder(file: File = modelFile, block: (TextEmbedder) -> T): T = HeavyModelGate.mutex.withLock {
        check(app.getSystemService(PowerManager::class.java).currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) { "Device must cool before local inference" }
        val buffer = FileInputStream(file).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        val options = TextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).build())
            .setL2Normalize(true).setQuantize(false).build()
        val embedder = try { TextEmbedder.createFromOptions(app, options) }
            catch (e: UnsatisfiedLinkError) { throw IllegalStateException("Semantic search needs an arm64-v8a device", e) }
        embedder.use(block)
    }
    private fun embed(embedder: TextEmbedder, text: String): FloatArray =
        VectorMath.normalize(embedder.embed(text.take(2000)).embeddingResult().embeddings().first().floatEmbedding())

    fun sourceText(event: SourceEvent): String {
        val fields = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8)).getJSONObject("fields")
        return fields.keys().asSequence().flatMap { key -> fields.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().asSequence() }
            .filter { it.isNotBlank() }.joinToString("\n")
    }

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            modelFile.parentFile!!.mkdirs()
            val temp = File(modelFile.parentFile, "text_embedder.part")
            try {
                app.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(65536); var size = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                        size += n; require(size <= 512L * 1024 * 1024) { "Embedding model exceeds 512 MiB" }; output.write(buffer, 0, n) }
                } }
                val works = runCatching { withEmbedder(temp) { embed(it, "HellFire semantic index check").isNotEmpty() } }.getOrDefault(false)
                require(works) { "Select a MediaPipe text-embedder .tflite model (arm64-v8a device required)." }
                java.nio.file.Files.move(temp.toPath(), modelFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                cachedId = null
                app.getSharedPreferences("model-catalog", 0).edit().remove("installed-EMBEDDING").apply()
                dao.dropOtherModels(modelId())
            } finally { temp.delete() }
        }
        SemanticIndexWork.schedule(app)
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        mutex.withLock {
            WorkManager.getInstance(app).cancelUniqueWork(SemanticIndexWork.NAME).result.get()
            modelFile.delete(); cachedId = null; dao.clear()
        }
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        if (!configured) "No embedding model imported. Search uses literal matching only."
        else mutex.withLock { val model = modelId(); "${dao.indexedCount(model)} sources indexed, ${dao.pendingCount(model)} pending." }
    }

    /** Embeds up to [max] unindexed non-sensitive sources, newest first. Returns the number processed. */
    suspend fun indexPending(max: Int = 500): Int = withContext(Dispatchers.IO) {
        require(max >= 0)
        if (max == 0 || !configured) return@withContext 0
        if (app.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return@withContext 0
        mutex.withLock {
            val model = modelId()
            dao.dropOtherModels(model)
            var done = 0
            var batch = dao.pending(model, minOf(32, max))
            if (batch.isEmpty()) return@withLock 0
            withEmbedder { embedder ->
                while (batch.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    if (app.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) break
                    val now = System.currentTimeMillis()
                    dao.put(batch.map { event ->
                        val text = sourceText(event)
                        SemanticVector(event.id, model, if (text.isBlank()) ByteArray(0) else VectorMath.encode(embed(embedder, text)), now)
                    })
                    done += batch.size
                    if (done >= max) break
                    batch = dao.pending(model, minOf(32, max - done))
                }
            }
            done
        }
    }

    /** Best [limit] sources by cosine similarity at or above [minScore]. Scores are uncalibrated. */
    suspend fun search(query: String, limit: Int = 10, minScore: Float = 0.35f): List<Pair<String, Float>> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        mutex.withLock {
            val model = modelId()
            val q = withEmbedder { embed(it, query) }
            if (q.isEmpty()) return@withLock emptyList()
            val scored = mutableListOf<Pair<String, Float>>()
            var after = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = dao.page(model, after, 500)
                if (page.isEmpty()) break
                // Keep only the running best to bound memory on large indexes.
                page.forEach { v -> VectorMath.decode(v.vector).takeIf { it.size == q.size }?.let { scored += v.sourceId to VectorMath.dot(q, it) } }
                if (scored.size > 5000) { val best = VectorMath.top(limit, scored.asSequence()); scored.clear(); scored += best }
                after = page.last().sourceId
            }
            VectorMath.top(limit, scored.asSequence()).filter { it.second >= minScore }
        }
    }
}

/** Meaning-search indexing can wait for power; source capture and rules do not. */
object SemanticIndexWork {
    const val NAME = "semantic-index"
    suspend fun schedule(context: Context) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SemanticIndexWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()).result.get()
        Unit
    }
}

class SemanticIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            (applicationContext as HellFireApp).semantic.indexPending()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); Result.retry() }
    }
}
