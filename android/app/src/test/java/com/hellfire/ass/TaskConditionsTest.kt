package com.hellfire.ass

import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.insights.DebtBalance
import com.hellfire.ass.insights.Interaction
import com.hellfire.ass.tasks.TaskConditions
import com.hellfire.ass.tasks.TaskTime
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TaskConditionsTest {
    private val now = Instant.parse("2026-11-13T07:00:00Z") // Friday 10:00 EAT

    @Test fun `conditional reminders parse in English and Kiswahili`() {
        val en = AssistantCommands.parse("remind me to call Juma on monday if Juma hasn't paid", now) as OwnerCommand.Remind
        assertEquals("call Juma", en.title)
        assertEquals("DEBT_UNPAID:juma", en.condition)
        assertEquals("2026-11-16 09:00", TaskTime.display(en.dueAt))
        assertTrue(en.summary.contains("only if Juma still hasn't paid you back"))
        val sw = AssistantCommands.parse("nikumbushe kumfuatilia Asha kesho saa nne asubuhi kama Asha hajajibu", now) as OwnerCommand.Remind
        assertEquals("NO_REPLY:asha", sw.condition)
        assertEquals("kumfuatilia Asha", sw.title)
        assertNull((AssistantCommands.parse("remind me to pay rent tomorrow", now) as OwnerCommand.Remind).condition)
    }
    @Test fun `conditions are met only when HellFire can see it`() {
        val set = now.toEpochMilli()
        assertTrue(TaskConditions.met("DEBT_UNPAID:juma", listOf(DebtBalance("Juma Ali", 0, set, null)), emptyList(), emptyMap(), set))
        assertFalse(TaskConditions.met("DEBT_UNPAID:juma", listOf(DebtBalance("Juma Ali", 50_000, set, null)), emptyList(), emptyMap(), set))
        assertFalse(TaskConditions.met("DEBT_UNPAID:juma", emptyList(), emptyList(), emptyMap(), set)) // unknown: remind anyway
        val reply = Interaction("tel:712345678", "+255712345678", "SMS", true, set + 60_000, "Nimeona", "s1")
        assertTrue(TaskConditions.met("NO_REPLY:asha", emptyList(), listOf(reply), mapOf("tel:712345678" to "Asha"), set))
        assertFalse(TaskConditions.met("NO_REPLY:asha", emptyList(), listOf(reply.copy(at = set - 60_000)), mapOf("tel:712345678" to "Asha"), set))
        assertFalse(TaskConditions.met("NO_REPLY:asha", emptyList(), listOf(reply.copy(incoming = false)), mapOf("tel:712345678" to "Asha"), set))
    }
}
