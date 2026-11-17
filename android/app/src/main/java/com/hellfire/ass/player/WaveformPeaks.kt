package com.hellfire.ass.player

/** Peak envelope from a streaming PCM16-LE decode. Holds peaks only, never the audio. */
object WaveformPeaks {
    const val BUCKETS = 200

    /** Buckets are counted in frames (one sample per channel), so stereo spreads across the whole width. */
    class Accumulator(val buckets: Int, private val framesPerBucket: Long, channels: Int = 1) {
        private val peaks = IntArray(buckets)
        private var sampleIndex = 0L
        private var filled = 0

        /** Interleaved channel count. A decoder may report it only after the first output format change. */
        var channels: Int = channels.coerceAtLeast(1)
            set(value) { field = value.coerceAtLeast(1) }

        val framesFed: Long get() = sampleIndex / channels

        fun feedPcm16le(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var index = offset
            val end = offset + length - 1
            while (index + 1 <= end) {
                val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
                val abs = kotlin.math.abs(sample.toShort().toInt())
                val bucket = (sampleIndex / channels / framesPerBucket).toInt().coerceIn(0, buckets - 1)
                if (abs > peaks[bucket]) peaks[bucket] = abs
                if (bucket + 1 > filled) filled = bucket + 1
                sampleIndex++
                index += 2
            }
        }

        fun finish(): FloatArray {
            val out = FloatArray(buckets)
            val used = filled.coerceAtLeast(1)
            for (i in 0 until used) out[i] = peaks[i] / 32768f
            return out
        }
    }

    fun accumulator(totalPcmBytes: Long, buckets: Int = BUCKETS, channels: Int = 1): Accumulator {
        require(buckets > 0)
        val frames = (totalPcmBytes / 2 / channels.coerceAtLeast(1)).coerceAtLeast(1)
        return Accumulator(buckets, ((frames + buckets - 1) / buckets).coerceAtLeast(1), channels)
    }

    /** Accumulator for a decoded stream of known length. [totalFrames] is samples per channel. */
    fun forStream(sampleRate: Int, durationUs: Long?, channels: Int, buckets: Int = BUCKETS): Pair<Accumulator, Long> {
        val rate = sampleRate.coerceAtLeast(1).toLong()
        val totalFrames = if (durationUs != null && durationUs > 0) rate * durationUs / 1_000_000 else rate * 60
        val frames = totalFrames.coerceAtLeast(rate)
        return Accumulator(buckets, ((frames + buckets - 1) / buckets).coerceAtLeast(1), channels) to frames
    }

    fun fromPcm16le(bytes: ByteArray, buckets: Int = BUCKETS): FloatArray {
        val acc = accumulator(bytes.size.toLong(), buckets)
        acc.feedPcm16le(bytes)
        return acc.finish()
    }
}
