package com.hellfire.ass.sharing

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcmMute {
    /** Zeroes little-endian interleaved PCM16 frames whose time falls in any half-open range. Returns the next sample index. */
    fun zero16(input: ByteArray, sampleRate: Int, channels: Int, rangesMs: List<Pair<Long, Long>>, startSample: Long = 0): ByteArray {
        val out = input.copyOf()
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val written = ByteBuffer.allocate(out.size).order(ByteOrder.LITTLE_ENDIAN)
        zero16(buffer, written, sampleRate, channels, rangesMs, startSample)
        written.flip()
        val bytes = ByteArray(written.remaining())
        written.get(bytes)
        return bytes
    }

    fun zero16(input: ByteBuffer, output: ByteBuffer, sampleRate: Int, channels: Int, rangesMs: List<Pair<Long, Long>>, startSample: Long): Long {
        require(sampleRate > 0 && channels > 0)
        val frame = channels * 2
        var sample = startSample
        while (input.remaining() >= frame) {
            val timeMs = sample * 1000L / sampleRate
            val mute = rangesMs.any { timeMs >= it.first && timeMs < it.second }
            if (mute) {
                input.position(input.position() + frame)
                repeat(frame) { output.put(0) }
            } else {
                repeat(frame) { output.put(input.get()) }
            }
            sample++
        }
        return sample
    }
}
