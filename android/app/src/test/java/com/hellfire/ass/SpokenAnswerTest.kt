package com.hellfire.ass

import com.hellfire.ass.assistant.SpokenAnswer
import org.junit.Assert.*
import org.junit.Test

class SpokenAnswerTest {
    @Test fun `autoListenAndSpeak - spoken answers drop footers and ids`() {
        val text = "All accounts · this month\n2026-11-01 00:00 to 2026-12-01 00:00 EAT (end exclusive)\nIncome TZS 0\nNet expenses TZS 15,000\n" +
            "Only confirmed payments matching these filters count. Transfers are excluded.\nEvidence IDs: 3f2a, 9b1c"
        val spoken = SpokenAnswer.of(text)
        assertFalse(spoken.contains("Evidence IDs"))
        assertFalse(spoken.contains("Only confirmed payments"))
        assertTrue(spoken.length <= 320)
        assertTrue(SpokenAnswer.of("x ".repeat(400)).length <= 320)
    }
    @Test fun `spoken yes and no in English and Kiswahili`() {
        for (yes in listOf("yes", "Ndiyo.", "sawa", "confirm it", "OK")) assertEquals(yes, true, SpokenAnswer.confirmation(yes))
        for (no in listOf("no", "Hapana", "cancel", "acha")) assertEquals(no, false, SpokenAnswer.confirmation(no))
        assertNull(SpokenAnswer.confirmation("what about tomorrow"))
        assertNull(SpokenAnswer.confirmation("nominate Asha")) // "no" only as a whole word
    }
}
