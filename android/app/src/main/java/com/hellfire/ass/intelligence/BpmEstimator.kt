package com.hellfire.ass.intelligence

object BpmEstimator {
    fun estimate(samples: FloatArray, rate: Int = 16_000): Int? {
        if (rate < 8_000 || samples.size < rate * 4) return null
        val hop = 512
        val frames = samples.size / hop
        if (frames < 8) return null
        val onset = DoubleArray(frames)
        var previous = 0.0
        for (i in 0 until frames) {
            var energy = 0.0
            val start = i * hop
            for (j in 0 until hop) {
                val s = samples[start + j].toDouble()
                energy += s * s
            }
            energy /= hop
            onset[i] = (energy - previous).coerceAtLeast(0.0)
            previous = energy
        }
        if (onset.all { it <= 1e-8 }) return null
        var bestBpm = -1
        var best = 0.0
        for (bpm in 60..180) {
            val period = rate * 60.0 / bpm
            var onBeat = 0.0
            var offBeat = 0.0
            var beats = 0
            var t = 0.0
            while (t + period < samples.size) {
                onBeat += onsetAt(onset, hop, t)
                offBeat += onsetAt(onset, hop, t + period / 2.0)
                beats++
                t += period
            }
            if (beats == 0) continue
            val score = onBeat / beats - offBeat / beats
            if (score > best) {
                best = score
                bestBpm = bpm
            }
        }
        return bestBpm.takeIf { best > 1e-6 }
    }

    private fun onsetAt(onset: DoubleArray, hop: Int, sample: Double): Double {
        val index = (sample / hop).toInt()
        if (index !in onset.indices) return 0.0
        val next = if (index + 1 < onset.size) onset[index + 1] else 0.0
        return maxOf(onset[index], next)
    }

    fun clickTrack(bpm: Int, seconds: Int, rate: Int = 16_000): FloatArray {
        val samples = FloatArray(rate * seconds)
        val period = rate * 60 / bpm
        var at = 0
        while (at < samples.size) {
            samples[at] = 1f
            if (at + 1 < samples.size) samples[at + 1] = 0.5f
            at += period
        }
        return samples
    }
}
