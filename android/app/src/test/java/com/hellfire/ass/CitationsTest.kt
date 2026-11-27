package com.hellfire.ass

import com.hellfire.ass.assistant.Citations
import org.junit.Assert.*
import org.junit.Test

class CitationsTest {
    private val supplied = listOf("3f2a9b1c-0000", "77aa88bb-1111", "cal:9988")

    @Test fun `answers keep only cited supplied sources and flag invented ones`() {
        val ok = Citations.check("Asha agreed to TZS 400,000 [3f2a9b1c-0000] and Friday [cal:9988].", supplied)
        assertEquals(listOf("3f2a9b1c-0000", "cal:9988"), ok.sources)
        assertEquals(0, ok.invented)
        val bad = Citations.check("Paid on Monday [deadbeef-9999].", supplied)
        assertEquals(1, bad.invented)
        assertTrue(bad.text.contains("[not a supplied source]"))
        assertTrue(bad.text.contains("not supported by your saved evidence"))
        assertEquals(supplied, bad.sources)
        assertEquals(supplied, Citations.check("No citations at all.", supplied).sources)
    }
}
