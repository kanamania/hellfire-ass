package com.hellfire.ass.media

/** Experimental energy-based intervals only; this does not identify a speaker or prove participation. */
class SpeechActivity(private val sampleRate: Int = 16000) {
    private var frames = 0L
    private var started: Long? = null
    private var lastLoud = 0L
    private val spans = mutableListOf<Pair<Long, Long>>()
    fun accept(bytes: ByteArray, offset: Int, size: Int) {
        var sum = 0.0
        var count = 0
        for (i in offset until offset + size - 1 step 2) {
            val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble() / 32768
            sum += sample * sample; count++
        }
        if (count == 0) return
        val at = frames * 1000 / sampleRate
        frames += count
        if (kotlin.math.sqrt(sum / count) >= 0.02) {
            if (started == null) started = (at - 30_000).coerceAtLeast(0)
            lastLoud = frames * 1000 / sampleRate
        } else if (started != null && at - lastLoud >= 2000) {
            spans += started!! to lastLoud
            started = null
        }
    }
    fun finish(): List<Pair<Long, Long>> {
        started?.let { spans += it to lastLoud; started = null }
        return spans.toList()
    }
}
