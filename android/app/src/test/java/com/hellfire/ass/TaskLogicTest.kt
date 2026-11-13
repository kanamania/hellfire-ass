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
    @Test fun `relative day words match whole words only`() {
        val source = Instant.parse("2026-11-13T04:00:00Z").toEpochMilli() // Friday 07:00 EAT
        assertEquals("2026-11-16 09:00", TaskTime.display(TaskExtractor.extract("Call Leonard on monday", source, false)!!.dueAt))
        assertEquals("2026-11-15 09:00", TaskTime.display(TaskExtractor.extract("Tutaonana keshokutwa", source, false)!!.dueAt))
        assertEquals("2026-11-15 09:00", TaskTime.display(TaskExtractor.extract("Tutaonana kesho kutwa", source, false)!!.dueAt))
        assertEquals("2026-11-15 09:00", TaskTime.display(TaskExtractor.extract("Pay the day after tomorrow", source, false)!!.dueAt))
        assertNull(TaskExtractor.extract("Napoleon and Cleopatra documentary", source, false))
    }
    @Test fun `deadlines more than a week old are not review items`() {
        val now = Instant.parse("2026-11-13T07:00:00Z").toEpochMilli()
        assertTrue(TaskTime.reviewable(now - 6 * 86_400_000L, now))
        assertFalse(TaskTime.reviewable(now - 8 * 86_400_000L, now))
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
