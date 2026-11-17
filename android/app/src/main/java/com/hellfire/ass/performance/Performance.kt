package com.hellfire.ass.performance

import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Snapshot of device pressure. Thermal values match android.os.PowerManager.THERMAL_STATUS_*. */
data class DeviceSignals(
    val thermal: Int = 0,
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    val lowMemory: Boolean = false,
    val memoryClassMb: Int = 256,
    val powerSave: Boolean = false,
    val foreground: Boolean = false,
)

enum class OperatingMode { INTERACTIVE, BALANCED, CONSERVE, DEFER }

/**
 * Adaptive limits derived from signals. CONSERVE shrinks work and refuses model reuse.
 * DEFER is charging, cool and not low on battery — background batches may be larger, still capped.
 */
data class OperatingProfile(
    val mode: OperatingMode,
    val allowAccelerator: Boolean,
    val reuseModels: Boolean,
    val maxHeavyResident: Int,
    val audioChunkSeconds: Int,
    val pdfPages: Int,
    val deferHeavy: Boolean,
) {
    companion object {
        const val THERMAL_SEVERE = 3
        fun of(signals: DeviceSignals): OperatingProfile {
            val severe = signals.thermal >= THERMAL_SEVERE || signals.lowMemory || signals.batteryPercent in 0..15 || signals.powerSave
            val comfortable = signals.charging && signals.thermal <= 1 && signals.batteryPercent >= 30 && !signals.lowMemory && !signals.powerSave
            val mode = when {
                severe -> OperatingMode.CONSERVE
                signals.foreground -> OperatingMode.INTERACTIVE
                comfortable -> OperatingMode.DEFER
                else -> OperatingMode.BALANCED
            }
            return OperatingProfile(
                mode = mode,
                allowAccelerator = mode != OperatingMode.CONSERVE && !signals.lowMemory,
                reuseModels = mode != OperatingMode.CONSERVE && !signals.lowMemory && signals.memoryClassMb >= 192,
                maxHeavyResident = if (mode == OperatingMode.CONSERVE || signals.lowMemory) 0 else 1,
                audioChunkSeconds = if (mode == OperatingMode.CONSERVE) 60 else 120,
                pdfPages = if (mode == OperatingMode.CONSERVE) 2 else 5,
                deferHeavy = severe && !signals.foreground,
            )
        }
    }
}

data class BackendMeasure(val ok: Boolean, val elapsedMs: Long)

/** CPU until a successful measurement exists for this file. A failed accelerator never wins. */
fun selectBackend(measured: Map<String, BackendMeasure>, fileKey: String, storedFile: String, stored: String): String {
    if (fileKey != storedFile || stored == "CPU") return "CPU"
    val chosen = measured[stored]
    return if (chosen != null && chosen.ok) stored else "CPU"
}

/** Fastest successful backend. CPU is the fallback when every accelerator fails or was not run. */
fun fastestBackend(results: Map<String, BackendMeasure>): String {
    val ok = results.filterValues { it.ok }
    return ok.minByOrNull { it.value.elapsedMs }?.key ?: "CPU"
}

enum class WorkPriority { INTERACTIVE, NORMAL, DEFERRED }

/** Which heavy runtime currently holds a cached session. Closers stay in the owning class. */
object ModelSlots {
    private val resident = mutableSetOf<String>()
    @Synchronized fun mark(role: String) { resident += role }
    @Synchronized fun drop(role: String) { resident -= role }
    @Synchronized fun snapshot(): Set<String> = resident.toSet()
    @Synchronized fun reset() { resident.clear() }
}

/**
 * Cooperative yield for the existing heavy-model lock. Interactive work sets a flag;
 * deferred holders stop at their next chunk boundary and let WorkManager resume them.
 * This is not a second queue.
 */
object ExecutionScheduler {
    private val waiting = mutableMapOf<WorkPriority, Int>()

    @Synchronized fun noteWaiting(priority: WorkPriority) { waiting[priority] = (waiting[priority] ?: 0) + 1 }
    @Synchronized fun clearWaiting(priority: WorkPriority) {
        val remaining = (waiting[priority] ?: 0) - 1
        if (remaining > 0) waiting[priority] = remaining else waiting.remove(priority)
    }
    @Synchronized fun shouldYield(holder: WorkPriority): Boolean =
        holder == WorkPriority.DEFERRED && waiting.containsKey(WorkPriority.INTERACTIVE)
    @Synchronized fun waitingSnapshot(): Set<WorkPriority> = waiting.keys.toSet()
    @Synchronized fun reset() { waiting.clear() }
}

/** Which other heavy runtimes to drop before loading [keep]. Does not close them itself. */
fun rolesToEvict(keep: String, resident: Set<String>, profile: OperatingProfile): Set<String> {
    if (!profile.reuseModels || profile.maxHeavyResident <= 0) return resident
    val others = resident - keep
    val overflow = others.size + 1 - profile.maxHeavyResident
    if (overflow <= 0) return emptySet()
    return others.sorted().take(overflow).toSet()
}

/** SHA-256 updated in chunks. A checkpoint clones digest state so a yielded read can resume in-process. */
class IncrementalDigest {
    private var digest = MessageDigest.getInstance("SHA-256")
    var bytes: Long = 0
        private set

    fun update(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        digest.update(buffer, offset, length)
        bytes += length
    }

    fun checkpoint(): MessageDigest = digest.clone() as MessageDigest

    fun restore(saved: MessageDigest, bytesRead: Long) {
        digest = saved.clone() as MessageDigest
        bytes = bytesRead
    }

    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

object SceneSampler {
    /** Same timestamps VisualReader used before scene filtering: at most 20, at least 30s apart. */
    fun uniform(durationMs: Long): List<Long> {
        if (durationMs <= 0) return emptyList()
        val step = maxOf(30_000L, durationMs / 20)
        val times = mutableListOf<Long>()
        var at = 0L
        while (at < durationMs && times.size < 20) {
            times += at
            at += step
        }
        return times
    }

    /**
     * Keeps a uniform sample when its fingerprint is missing or far from the last kept frame.
     * Near-duplicates are not OCR'd. Coverage is still a sample, never every frame.
     */
    fun select(durationMs: Long, fingerprintAt: (Long) -> Long?): List<Long> {
        var previous: Long? = null
        return uniform(durationMs).filter { at ->
            val fingerprint = fingerprintAt(at)
            val keep = fingerprint == null || previous == null || hamming(previous!!, fingerprint) >= 8
            if (keep && fingerprint != null) previous = fingerprint
            keep
        }
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}

/** 8×8 average-hash of opaque luma. Returns null if the bitmap cannot be read. */
fun fingerprintLuma(width: Int, height: Int, pixel: (Int, Int) -> Int): Long? {
    if (width <= 0 || height <= 0) return null
    val samples = IntArray(64)
    var index = 0
    for (y in 0 until 8) for (x in 0 until 8) {
        val color = pixel((x * width / 8).coerceAtMost(width - 1), (y * height / 8).coerceAtMost(height - 1))
        val r = (color shr 16) and 255
        val g = (color shr 8) and 255
        val b = color and 255
        samples[index++] = (r * 3 + g * 6 + b) / 10
    }
    val mean = samples.average()
    var bits = 0L
    samples.forEachIndexed { i, value -> if (value >= mean) bits = bits or (1L shl i) }
    return bits
}

/**
 * Skip whisper only when every 20 ms frame is below the existing speech-activity noise floor.
 * A tone above that floor is transcribed. This does not identify a speaker.
 */
object SpeechPlan {
    private const val MIN_LEVEL = 0.01
    fun needsTranscription(samples: FloatArray, sampleRate: Int = 16000): Boolean {
        if (samples.isEmpty()) return false
        val frame = (sampleRate / 50).coerceAtLeast(1)
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + frame, samples.size)
            var sum = 0.0
            for (i in offset until end) sum += samples[i] * samples[i]
            if (kotlin.math.sqrt(sum / (end - offset)) >= MIN_LEVEL) return true
            offset = end
        }
        return false
    }
}

data class QueryStat(val name: String, val count: Int, val totalMs: Long, val maxMs: Long, val rows: Int)

/** Timings only. Names are caller-supplied labels, never SQL or user text. */
object QueryTrace {
    private val stats = linkedMapOf<String, QueryStat>()
    @Synchronized fun record(name: String, elapsedMs: Long, rows: Int) {
        require(!name.contains(' ') && name.length in 1..40) { "Query trace name must be a short label" }
        val previous = stats[name]
        stats[name] = if (previous == null) QueryStat(name, 1, elapsedMs, elapsedMs, rows)
        else previous.copy(count = previous.count + 1, totalMs = previous.totalMs + elapsedMs, maxMs = maxOf(previous.maxMs, elapsedMs), rows = previous.rows + rows)
    }
    @Synchronized fun snapshot(): List<QueryStat> = stats.values.toList()
    @Synchronized fun reset() { stats.clear() }
}

data class PerfSample(val name: String, val value: Long, val atMs: Long)

/** Ring of numeric samples. [detail] is a fixed token such as CPU or WAITING_FOR_MEMORY, never content. */
object PerfLog {
    private const val CAP = 200
    private val samples = ArrayDeque<PerfSample>()
    private val details = ArrayDeque<Pair<String, String>>()
    @Synchronized fun record(name: String, value: Long, atMs: Long = System.currentTimeMillis()) {
        require(name.length in 1..40 && name.none { it.isWhitespace() })
        samples.addLast(PerfSample(name, value, atMs))
        while (samples.size > CAP) samples.removeFirst()
    }
    @Synchronized fun detail(name: String, token: String) {
        require(token.length in 1..48 && token.none { it.isWhitespace() || it == '\n' })
        details.addLast(name to token)
        while (details.size > 40) details.removeFirst()
    }
    @Synchronized fun samples(): List<PerfSample> = samples.toList()
    @Synchronized fun details(): List<Pair<String, String>> = details.toList()
    @Synchronized fun latest(name: String): Long? = samples.lastOrNull { it.name == name }?.value
    @Synchronized fun reset() { samples.clear(); details.clear() }
}

object JobBudget {
    /** Local cap so a media backlog cannot schedule one JobScheduler job per item. Not an OS constant. */
    const val MEDIA_PENDING_CAP = 20
    fun slots(pending: Int, cap: Int = MEDIA_PENDING_CAP): Int = (cap - pending).coerceAtLeast(0)
}

object PayloadCompression {
    /** Gzip only when the peer has advertised it and the body is large enough to be worth the CPU. */
    fun shouldCompress(bytes: Int, peerAcceptsGzip: Boolean): Boolean = peerAcceptsGzip && bytes >= 8_192
    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()
    fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

object StartupProfile {
    /** Classes a future on-device baseline profile should cover. Not installed in the release APK. */
    val critical = listOf(
        "com.hellfire.ass.HellFireApp",
        "com.hellfire.ass.MainActivity",
        "com.hellfire.ass.data.EventDatabase",
        "com.hellfire.ass.ui.AppNav",
        "com.hellfire.ass.ui.home.HomeScreen",
    )
}

fun visibleWindow(total: Int, pagesShown: Int, pageSize: Int = 30): Int {
    require(pagesShown >= 0 && pageSize > 0)
    return minOf(total.coerceAtLeast(0), pageSize * (pagesShown + 1))
}
