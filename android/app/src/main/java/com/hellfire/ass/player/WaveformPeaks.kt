package com.hellfire.ass.player

/** Peak envelope from a streaming PCM16-LE decode. Holds peaks only, never the audio. */
object WaveformPeaks {
    const val BUCKETS = 200

    class Accumulator(val buckets: Int, private val samplesPerBucket: Int) {
        private val peaks = IntArray(buckets)
        private var sampleIndex = 0L
        private var filled = 0

        fun feedPcm16le(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var index = offset
            val end = offset + length - 1
            while (index + 1 <= end) {
                val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
                val abs = kotlin.math.abs(sample.toShort().toInt())
                val bucket = (sampleIndex / samplesPerBucket).toInt().coerceIn(0, buckets - 1)
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

    fun accumulator(totalPcmBytes: Long, buckets: Int = BUCKETS): Accumulator {
        require(buckets > 0)
        val samples = (totalPcmBytes / 2).coerceAtLeast(1)
        val per = ((samples + buckets - 1) / buckets).toInt().coerceAtLeast(1)
        return Accumulator(buckets, per)
    }

    fun fromPcm16le(bytes: ByteArray, buckets: Int = BUCKETS): FloatArray {
        val acc = accumulator(bytes.size.toLong(), buckets)
        acc.feedPcm16le(bytes)
        return acc.finish()
    }
}
