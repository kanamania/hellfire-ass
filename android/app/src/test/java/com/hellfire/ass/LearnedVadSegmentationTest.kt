package com.hellfire.ass

import com.hellfire.ass.media.SpeechActivity
import org.junit.Assert.*
import org.junit.Test

class LearnedVadSegmentationTest {
    @Test fun recurrentFramesPreserveSamplesAcrossOddPcmReads() {
        val samples = ShortArray(512 * 100) { if (it < 512 * 20) 8192 else 0 }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v -> bytes[i*2] = v.toByte(); bytes[i*2+1] = (v.toInt() shr 8).toByte() }
        fun run(chunk: Int): Pair<List<Pair<Long, Long>>, Int> {
            var frames = 0
            val detector = SpeechActivity(frameProbability = { frame ->
                assertEquals(512, frame.size)
                assertEquals(if (frames < 20) 0.25f else 0f, frame[0], 0f)
                frames++
                if (frames <= 20) 0.9f else 0.1f
            })
            var offset = 0
            while (offset < bytes.size) { val count = minOf(chunk, bytes.size-offset); detector.accept(bytes, offset, count); offset += count }
            return detector.finish() to frames
        }
        assertEquals(listOf(0L to 640L) to 100, run(1024))
        assertEquals(run(1024), run(777))
    }

    @Test fun unavailableProbabilitiesUseAdaptiveFallbackWithoutFalseSpeech() {
        val detector = SpeechActivity(frameProbability = { null })
        detector.accept(ByteArray(16000 * 2 * 4), 0, 16000 * 2 * 4)
        assertTrue(detector.finish().isEmpty())
    }
}
