package com.hellfire.ass

import com.hellfire.ass.media.Fbank
import com.hellfire.ass.media.SpeakerClusters
import com.hellfire.ass.media.SpeakerNames
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeakerTest {
    private fun tone(hz: Double, seconds: Double = 1.0) = FloatArray((seconds * 16000).toInt()) { (0.3 * sin(2 * PI * hz * it / 16000)).toFloat() }
    private fun melBin(hz: Double): Int { // same mel spacing as Fbank: 80 bins between 20 Hz and 8 kHz
        fun mel(f: Double) = 1127.0 * kotlin.math.ln(1 + f / 700)
        return ((mel(hz) - mel(20.0)) / ((mel(8000.0) - mel(20.0)) / 81) - 1).let { kotlin.math.round(it).toInt() }
    }

    @Test fun `fbank frames use 25 ms windows with 10 ms shift and per-utterance mean normalization`() {
        assertEquals(0, Fbank.frames(399)); assertEquals(1, Fbank.frames(400)); assertEquals(98, Fbank.frames(16000))
        val feats = Fbank.compute(tone(1000.0))
        assertEquals(98, feats.size); assertTrue(feats.all { it.size == 80 })
        for (b in 0 until 80) assertEquals(0.0, feats.sumOf { it[b].toDouble() } / feats.size, 1e-3)
        assertTrue(feats.all { row -> row.all { it.isFinite() } })
    }
    @Test fun `fbank energy peaks in the mel bin of the input tone`() {
        // Mean normalization removes a steady tone, so compare a tone half against a quiet half.
        for (hz in listOf(300.0, 1000.0, 3000.0)) {
            val signal = FloatArray(16000) { 1e-4f * ((it * 7919) % 13 - 6) } + tone(hz)
            val feats = Fbank.compute(signal)
            val loud = feats.last()
            val peak = loud.indices.maxBy { loud[it] }
            assertTrue("$hz Hz peaked at $peak, expected ~${melBin(hz)}", kotlin.math.abs(peak - melBin(hz)) <= 1)
        }
    }
    @Test fun `clustering groups similar voices and keeps distinct ones apart`() {
        val a = floatArrayOf(1f, 0.05f, 0f); val a2 = floatArrayOf(0.95f, 0.1f, 0.02f)
        val b = floatArrayOf(0f, 1f, 0.1f); val b2 = floatArrayOf(0.05f, 0.9f, 0.05f)
        val c = floatArrayOf(0f, 0f, 1f)
        assertArrayEquals(intArrayOf(0, 1, 0, 2, 1), SpeakerClusters.cluster(listOf(a, b, a2, c, b2)))
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), SpeakerClusters.cluster(listOf(a, b, a2, c, b2), threshold = -1f))
        assertArrayEquals(intArrayOf(0), SpeakerClusters.cluster(listOf(a)))
        assertEquals(1f, SpeakerClusters.cosine(a, floatArrayOf(2f, 0.1f, 0f)), 1e-6f)
        assertEquals(0f, SpeakerClusters.cosine(a, FloatArray(3)), 0f)
        assertArrayEquals(floatArrayOf(1.95f, 0.15f, 0.02f), SpeakerClusters.centroid(listOf(a, a2)), 1e-6f)
    }
    @Test fun `names are caught from introductions and direct address in English and Swahili`() {
        assertEquals("Juma", SpeakerNames.introduced("Good morning, my name is Juma and I sell maize"))
        assertEquals("Asha", SpeakerNames.introduced("Habari, naitwa Asha."))
        assertNull(SpeakerNames.introduced("I am going to the market"))
        assertNull(SpeakerNames.introduced("I'm Sorry about that"))
        assertEquals("Juma", SpeakerNames.addressed("Juma, what do you think?"))
        assertEquals("Asha", SpeakerNames.addressed("What do you think, Asha?"))
        assertEquals("Neema", SpeakerNames.addressed("Asante Neema"))
        assertNull(SpeakerNames.addressed("Okay, let us start"))
        assertNull(SpeakerNames.addressed("we met juma yesterday"))
    }
    @Test fun `placeholder names need enough evidence, one group per name, and skip the owner`() {
        // Group 0 is the owner; group 1 introduces itself; group 2 is addressed twice.
        val turns = listOf(0 to "Welcome everyone.", 1 to "Thanks. My name is Juma.", 0 to "Asha, your view?", 2 to "I agree.",
            1 to "Me too.", 0 to "Thank you, Asha.", 2 to "Sure.")
        assertEquals(mapOf(1 to ("Juma" to 2), 2 to ("Asha" to 2)), SpeakerNames.assign(turns, skip = 0))
        // One address is not enough.
        assertTrue(SpeakerNames.assign(listOf(0 to "Asha, your view?", 1 to "Fine.")).isEmpty())
        // Owner group is never named even if introduced.
        assertTrue(SpeakerNames.assign(listOf(0 to "I'm Emanuel.", 1 to "Hello."), skip = 0).isEmpty())
        // Two groups claiming the same name equally: neither is named.
        assertTrue(SpeakerNames.assign(listOf(1 to "My name is Juma", 2 to "My name is Juma")).isEmpty())
        // A competing name with comparable evidence blocks the guess.
        assertTrue(SpeakerNames.assign(listOf(1 to "My name is Juma", 0 to "Ali, go on", 1 to "Ok", 0 to "Ali, continue", 1 to "Yes")).isEmpty())
    }
}
