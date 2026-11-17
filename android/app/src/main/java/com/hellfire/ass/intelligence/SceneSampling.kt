package com.hellfire.ass.intelligence

import android.os.PowerManager
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SampleMode { FAST, BALANCED, DEEP }

data class RgbFrame(val atMs: Long, val pixels: IntArray)

object SceneMath {
    fun histogram(pixels: IntArray, bins: Int = 8): IntArray {
        val hist = IntArray(bins * 3)
        if (pixels.isEmpty()) return hist
        val div = 256 / bins
        for (p in pixels) {
            hist[((p shr 16) and 0xff) / div]++
            hist[bins + ((p shr 8) and 0xff) / div]++
            hist[bins * 2 + (p and 0xff) / div]++
        }
        return hist
    }

    fun luma(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0
        var sum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        }
        return sum / pixels.size
    }

    fun histogramDistance(a: IntArray, b: IntArray): Double {
        var inter = 0.0
        var total = 0.0
        val n = min(a.size, b.size)
        for (i in 0 until n) {
            inter += min(a[i], b[i])
            total += max(a[i], b[i])
        }
        if (total == 0.0) return 0.0
        return 1.0 - inter / total
    }

    fun boundaries(frames: List<RgbFrame>, histogramCut: Double = 0.35, lumaCut: Double = 0.18): List<Long> {
        if (frames.size < 2) return emptyList()
        val out = ArrayList<Long>()
        var prevHist = histogram(frames.first().pixels)
        var prevLuma = luma(frames.first().pixels)
        for (i in 1 until frames.size) {
            val hist = histogram(frames[i].pixels)
            val luma = luma(frames[i].pixels)
            val cut = histogramDistance(prevHist, hist) >= histogramCut || abs(luma - prevLuma) >= lumaCut
            if (cut && (out.isEmpty() || frames[i].atMs - out.last() >= 1_000)) out += frames[i].atMs
            prevHist = hist
            prevLuma = luma
        }
        return out
    }
}

object Sampling {
    fun schedule(durationMs: Long, mode: SampleMode, sceneChangesMs: List<Long> = emptyList()): List<Long> {
        val end = durationMs.coerceAtLeast(0)
        val step = when (mode) {
            SampleMode.FAST -> 10_000L
            SampleMode.BALANCED -> 5_000L
            SampleMode.DEEP -> 1_000L
        }
        val times = linkedSetOf<Long>()
        var at = 0L
        while (at <= end) {
            times += at
            if (step == 0L) break
            val next = at + step
            if (next == at) break
            at = next
            if (times.size > 50_000) break
        }
        if (mode != SampleMode.FAST) sceneChangesMs.filter { it in 0..end }.forEach { times += it }
        if (mode == SampleMode.DEEP) {
            sceneChangesMs.filter { it in 0..end }.forEach { change ->
                listOf(-2_000L, -1_000L, 1_000L, 2_000L).forEach { delta ->
                    val sample = change + delta
                    if (sample in 0..end) times += sample
                }
            }
        }
        return times.sorted()
    }
}

data class AnalysisCheckpoint(val doneMs: Long = 0, val audioDoneMs: Long = 0, val phase: String = "frames", val sceneChangesMs: List<Long> = emptyList(), val histogram: List<Int> = emptyList(), val luma: Double? = null) {
    fun encode(): String = JSONObject().put("doneMs", doneMs).put("audioDoneMs", audioDoneMs).put("phase", phase)
        .put("scenes", org.json.JSONArray(sceneChangesMs)).put("histogram", org.json.JSONArray(histogram)).put("luma", luma ?: JSONObject.NULL).toString()

    companion object {
        fun decode(json: String): AnalysisCheckpoint = runCatching {
            val o = JSONObject(if (json.isBlank()) "{}" else json)
            val scenes = o.optJSONArray("scenes")
            val changes = if (scenes == null) emptyList() else (0 until scenes.length()).map { scenes.optLong(it) }
            AnalysisCheckpoint(o.optLong("doneMs"), o.optLong("audioDoneMs"), o.optString("phase").ifBlank { "frames" }, changes,
                o.optJSONArray("histogram")?.let { h -> (0 until h.length()).map { h.getInt(it) } }.orEmpty(), if (o.has("luma") && !o.isNull("luma")) o.getDouble("luma") else null)
        }.getOrDefault(AnalysisCheckpoint())

        fun skipFrame(checkpoint: AnalysisCheckpoint, atMs: Long) = checkpoint.phase != "frames" || atMs < checkpoint.doneMs
    }
}

data class DeviceSnapshot(val charging: Boolean, val batteryPercent: Int, val thermalStatus: Int, val storageLow: Boolean)

data class RunPolicy(val chargingOnly: Boolean, val minBattery: Int, val pauseAtModerate: Boolean, val storageNotLow: Boolean)

object DeviceGate {
    fun blockReason(policy: RunPolicy, device: DeviceSnapshot): String? {
        if (policy.chargingOnly && !device.charging) return "waiting for charging"
        if (device.batteryPercent in 0..100 && device.batteryPercent < policy.minBattery) return "battery below minimum"
        val limit = if (policy.pauseAtModerate) PowerManager.THERMAL_STATUS_MODERATE else PowerManager.THERMAL_STATUS_SEVERE
        if (device.thermalStatus >= limit) return "device too warm"
        if (policy.storageNotLow && device.storageLow) return "storage low"
        return null
    }
}

data class Timing(val name: String, val elapsedMs: Long?, val note: String)

object BenchmarkFormat {
    fun format(rows: List<Timing>): String = rows.joinToString("\n") { row ->
        if (row.elapsedMs == null) "${row.name}: not measured. ${row.note}" else "${row.name}: ${row.elapsedMs} ms. ${row.note}"
    }
}

object QueryGrounding {
    private val stop = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "from", "is", "are", "was", "were", "this", "that", "it", "as", "by", "at", "be")
    fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.length >= 4 && it !in stop }.toSet()
    fun accepts(draft: String, evidence: String): Boolean {
        val claimed = words(draft)
        if (claimed.isEmpty()) return false
        val known = words(evidence)
        return claimed.all { it in known }
    }
}
