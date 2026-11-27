package com.hellfire.ass

import com.hellfire.ass.assistant.ScreenText
import com.hellfire.ass.assistant.ScreenText.Node
import org.junit.Assert.*
import org.junit.Test

class ScreenTextTest {
    @Test fun `assistContextOptIn - visible text only, passwords skipped, bounded`() {
        val screen = listOf(Node(null, false, listOf(
            Node("Invoice INV-204 · TZS 450,000", false),
            Node("secret-pass", true),
            Node("Due 2026-12-01", false, listOf(Node("Invoice INV-204 · TZS 450,000", false))),
            Node("x", false))))
        assertEquals("Invoice INV-204 · TZS 450,000\nDue 2026-12-01", ScreenText.collect(screen))
        assertTrue(ScreenText.collect(listOf(Node("a".repeat(10_000), false))).length <= ScreenText.LIMIT)
        assertEquals("", ScreenText.collect(emptyList()))
    }
}
