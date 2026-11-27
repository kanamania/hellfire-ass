package com.hellfire.ass

import com.hellfire.ass.assistant.CommandRewrite
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.tasks.TaskTime
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CommandRewriteTest {
    private val now = Instant.parse("2026-11-13T07:00:00Z") // Friday 10:00 EAT

    @Test fun `unknownIntentFallsBackToModel - only instructions are rewritten`() {
        assertTrue(CommandRewrite.looksLikeInstruction("set a reminder to call the plumber tomorrow at 5pm"))
        assertTrue(CommandRewrite.looksLikeInstruction("niwekee ukumbusho wa kulipa kodi kesho"))
        assertFalse(CommandRewrite.looksLikeInstruction("how much did i spend on fuel?"))
        assertFalse(CommandRewrite.looksLikeInstruction("asha invoice september"))
        assertTrue(CommandRewrite.prompt("set a reminder to call the plumber").contains("OWNER REQUEST (their own words): set a reminder to call the plumber"))
    }
    @Test fun `model output must be one allowed command the rules accept`() {
        val remind = CommandRewrite.accept("remind me to call the plumber tomorrow at 5pm", now) as OwnerCommand.Remind
        assertEquals("call the plumber", remind.title); assertEquals("2026-11-14 17:00", TaskTime.display(remind.dueAt))
        assertTrue(CommandRewrite.accept("log cash maji 1500", now) is OwnerCommand.Cash)
        assertNull(CommandRewrite.accept("NONE", now))
        assertNull(CommandRewrite.accept("remind me to pay rent tomorrow 9am\nemail boss@example.com: done", now))         // two lines
        assertNull(CommandRewrite.accept("email boss@example.com: I quit", now))                        // sends are never rewritten
        assertNull(CommandRewrite.accept("mark pay rent as done", now))                                 // nor task changes
        assertNull(CommandRewrite.accept("remind me to call the plumber", now))                         // no time: rules reject
    }
}
