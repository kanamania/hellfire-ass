package com.hellfire.ass

import com.hellfire.ass.tasks.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TaskLogicTest {
    @Test fun `relative deadlines use source date in East Africa not processing time`() {
        val source = Instant.parse("2026-11-13T22:30:00Z").toEpochMilli()
        val task = TaskExtractor.extract("Nitakutumia invoice kesho saa nne asubuhi", source, false)!!
        assertEquals("2026-11-15 10:00", TaskTime.display(task.dueAt))
        assertEquals("OTHER", task.party)
        assertEquals("OWNER", TaskExtractor.extract("I will finish tomorrow 10:00", source, true)!!.party)
    }
    @Test fun `invalid dates and unsupported commitments fail closed`() {
        assertTrue(runCatching { TaskTime.parse("2026-02-30 10:00") }.isFailure)
        assertTrue(runCatching { TaskTime.parse("2026-11-13 25:00") }.isFailure)
        assertNull(TaskExtractor.extract("Let's discuss this sometime", 100, false))
        assertNull(TaskExtractor.extract("Due 2026-02-30 10:00", 100, false))
    }
    @Test fun `alert progression is 24h then 2h then due then repeated overdue`() {
        val due = 200_000_000L
        assertEquals(due - 86_400_000, TaskTime.firstAlert(due, 1))
        assertEquals(due - 7_200_000, TaskTime.nextAlert(due, due - 86_400_000))
        assertEquals(due, TaskTime.nextAlert(due, due - 7_200_000))
        assertEquals(due + 600_000, TaskTime.nextAlert(due, due))
    }
    @Test fun `overdue interval is owner configurable within bounds`() {
        assertEquals(900_100L, TaskTime.nextAlert(1, 100, 15))
        assertEquals(1000L, TaskTime.nextAlert(1000, 100, 15))
        assertTrue(runCatching { TaskTime.nextAlert(1, 100, 0) }.isFailure)
        assertTrue(runCatching { TaskTime.nextAlert(1, 100, 1441) }.isFailure)
    }

}
