package com.hellfire.ass

import com.hellfire.ass.assistant.LocalAssistant
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class LocalAssistantTest {
    private val assistant = LocalAssistant(Clock.fixed(Instant.parse("2026-11-13T22:30:00Z"), ZoneOffset.UTC))
    @Test fun `time uses East Africa and correct date rollover`() {
        assertEquals("Dar es Salaam: 01:30, 14 Nov 2026", assistant.answer("TIME"))
    }
    @Test fun `greeting works offline with whitespace`() {
        assertTrue(assistant.answer(" Hello ").contains("offline"))
        assertTrue(assistant.answer("habari").contains("bila intaneti"))
    }
    @Test fun `unsupported requests do not claim access`() {
        assertTrue(assistant.answer("How much did I spend?").contains("not implemented"))
        assertTrue(assistant.answer("").contains("no model"))
    }
}
