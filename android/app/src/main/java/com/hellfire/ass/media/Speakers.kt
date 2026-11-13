package com.hellfire.ass.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kaldi-style 80-bin log-mel filterbank (25 ms Povey window, 10 ms shift, pre-emphasis 0.97, 20 Hz–8 kHz)
 * with per-utterance mean normalization, the input most WeSpeaker/3D-Speaker embedding models expect at 16 kHz.
 */
object Fbank {
    const val BINS = 80
    const val RATE = 16000
    private const val FRAME = 400
    private const val SHIFT = 160
    private const val FFT = 512
    private val window = DoubleArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).pow(0.85) }
    private fun mel(hz: Double) = 1127.0 * ln(1 + hz / 700)
    private val filters: Array<DoubleArray> = run {
        val low = mel(20.0); val high = mel(RATE / 2.0); val step = (high - low) / (BINS + 1)
        Array(BINS) { bin ->
            val left = low + bin * step; val center = left + step; val right = center + step
            DoubleArray(FFT / 2 + 1) { k ->
                val m = mel(k * RATE.toDouble() / FFT)
                when { m <= left || m >= right -> 0.0; m <= center -> (m - left) / (center - left); else -> (right - m) / (right - center) }
            }
        }
    }

    fun frames(samples: Int) = if (samples < FRAME) 0 else 1 + (samples - FRAME) / SHIFT

    fun compute(samples: FloatArray): Array<FloatArray> {
        val count = frames(samples.size)
        val out = Array(count) { FloatArray(BINS) }
        val re = DoubleArray(FFT); val im = DoubleArray(FFT)
        for (f in 0 until count) {
            val start = f * SHIFT
            var mean = 0.0
            for (i in 0 until FRAME) { re[i] = samples[start + i] * 32768.0; mean += re[i] }
            mean /= FRAME
            for (i in 0 until FRAME) re[i] -= mean
            for (i in FRAME - 1 downTo 1) re[i] -= 0.97 * re[i - 1]
            re[0] -= 0.97 * re[0]
            for (i in 0 until FRAME) re[i] *= window[i]
            re.fill(0.0, FRAME, FFT); im.fill(0.0)
            fft(re, im)
            for (b in 0 until BINS) {
                var energy = 0.0
                val w = filters[b]
                for (k in 0..FFT / 2) if (w[k] != 0.0) energy += w[k] * (re[k] * re[k] + im[k] * im[k])
                out[f][b] = ln(max(energy, 1.1920929e-7)).toFloat()
            }
        }
        if (count > 0) for (b in 0 until BINS) {
            val mean = out.sumOf { it[b].toDouble() } / count
            for (row in out) row[b] = (row[b] - mean).toFloat()
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val c = cos(angle * k); val s = sin(angle * k)
                val a = i + k; val b = a + len / 2
                val tr = re[b] * c - im[b] * s; val ti = re[b] * s + im[b] * c
                re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
            }
            len = len shl 1
        }
    }
}

/** Unsupervised grouping of L2-normalized speaker embeddings; groups are anonymous and thresholds are uncalibrated. */
object SpeakerClusters {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / sqrt(na * nb)).toFloat()
    }

    /** Centroid-linkage agglomerative clustering; merges while the closest pair has cosine >= [threshold]. Labels follow first appearance. */
    fun cluster(embeddings: List<FloatArray>, threshold: Float = 0.5f): IntArray {
        val sums = embeddings.map { it.copyOf() }.toMutableList()
        val members = embeddings.indices.map { mutableListOf(it) }.toMutableList()
        while (sums.size > 1) {
            var best = -2f; var bi = -1; var bj = -1
            for (i in sums.indices) for (j in i + 1 until sums.size) {
                val score = cosine(sums[i], sums[j]); if (score > best) { best = score; bi = i; bj = j }
            }
            if (best < threshold) break
            for (k in sums[bi].indices) sums[bi][k] += sums[bj][k]
            members[bi].addAll(members[bj]); sums.removeAt(bj); members.removeAt(bj)
        }
        val labels = IntArray(embeddings.size)
        members.sortedBy { it.min() }.forEachIndexed { label, group -> group.forEach { labels[it] = label } }
        return labels
    }

    fun centroid(embeddings: List<FloatArray>): FloatArray = FloatArray(embeddings.first().size) { i -> embeddings.sumOf { it[i].toDouble() }.toFloat() }
}

/**
 * Finds names spoken in a transcript and proposes them as placeholder labels for voice groups.
 * A self-introduction ("my name is Juma", "naitwa Asha") scores 2 for the speaking group; a direct address
 * ("Juma, …", "…, Asha?", "asante Juma") scores 1 for the next different group to speak. A name is proposed only
 * with score >= 2, at least twice any competing name for that group, and for one group only.
 */
object SpeakerNames {
    private const val NAME = "([A-Z][a-z]{1,20})"
    private val intro = Regex("(?i:\\b(?:my name is|i am|i'm|i’m|mimi ni|naitwa|jina langu ni)\\s+)$NAME\\b")
    private val greeting = Regex("^\\s*(?i:hi|hello|hey|thanks|thank you|asante|habari|karibu|pole|shikamoo)\\s*,?\\s+$NAME\\b")
    private val leading = Regex("^\\s*$NAME\\s*,")
    private val trailing = Regex(",\\s*$NAME\\s*[?.!]*\\s*$")
    private val stop = setOf("I", "Im", "The", "This", "That", "Yes", "No", "Okay", "Ok", "So", "Well", "And", "But", "Sorry", "Please",
        "Sir", "Madam", "Mama", "Baba", "Bwana", "Ndiyo", "Hapana", "Sawa", "Asante", "Karibu", "Habari", "Hello", "Hi", "God", "Mungu",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Today", "Tomorrow", "Everyone", "Guys", "Here", "There", "Not", "Going", "Fine", "Good")

    fun introduced(text: String): String? = intro.find(text)?.groupValues?.get(1)?.takeIf { it !in stop }
    fun addressed(text: String): String? = sequenceOf(greeting, leading, trailing).mapNotNull { it.find(text)?.groupValues?.get(1) }.firstOrNull { it !in stop }

    /** [turns] are (voice group, text) in time order. Returns group -> (name, score). [skip] (e.g. the owner group) is never named. */
    fun assign(turns: List<Pair<Int, String>>, skip: Int? = null): Map<Int, Pair<String, Int>> {
        val scores = mutableMapOf<Int, MutableMap<String, Int>>()
        fun add(group: Int, name: String, points: Int) { scores.getOrPut(group) { mutableMapOf() }.merge(name, points, Int::plus) }
        turns.forEachIndexed { i, (group, text) ->
            introduced(text)?.let { add(group, it, 2) }
            addressed(text)?.let { name -> turns.drop(i + 1).firstOrNull { it.first != group }?.let { add(it.first, name, 1) } }
        }
        val candidates = scores.filterKeys { it != skip }.mapNotNull { (group, names) ->
            val ranked = names.entries.sortedByDescending { it.value }
            val top = ranked.first(); val second = ranked.getOrNull(1)?.value ?: 0
            if (top.value >= 2 && top.value >= 2 * second) group to (top.key to top.value) else null
        }
        // A name may label only one group: keep the strongest claim, drop ties.
        return candidates.groupBy { it.second.first }.values.mapNotNull { claims ->
            val best = claims.maxOf { it.second.second }
            claims.filter { it.second.second == best }.singleOrNull()
        }.toMap()
    }
}
