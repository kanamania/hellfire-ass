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

    private val friday10 = Instant.parse("2026-11-13T07:00:00Z").toEpochMilli() // Friday 10:00 EAT
    private fun due(text: String, owner: Boolean = true, at: Long = friday10) =
        TaskExtractor.extract(text, at, outgoing = true, requireCommitment = !owner)?.let { TaskTime.display(it.dueAt) }

    @Test fun `pmSuffixShiftsHour`() {
        assertEquals("2026-11-14 17:30", due("call Asha tomorrow 5:30pm"))
        assertEquals("2026-11-14 17:30", due("call Asha tomorrow 5:30 pm"))
        assertEquals("2026-11-14 15:00", due("call Asha tomorrow at 3pm"))
        assertEquals("2026-11-14 09:15", due("call Asha tomorrow 9:15 a.m."))
        assertEquals("2026-11-14 00:00", due("call Asha tomorrow 12am"))
        assertEquals("2026-11-14 12:00", due("call Asha tomorrow 12 pm"))
    }
    @Test fun `relative and time-only owner times`() {
        assertEquals("2026-11-13 10:20", due("check the oven in 20 minutes"))
        assertEquals("2026-11-13 12:00", due("call Juma in two hours"))
        assertEquals("2026-11-13 10:30", due("call Juma in half an hour"))
        assertEquals("2026-11-13 11:00", due("mpigie Juma baada ya saa moja"))
        assertEquals("2026-11-13 10:15", due("zima jiko baada ya dakika kumi na tano"))
        assertEquals("2026-11-13 17:00", due("pay rent at 17:00"))
        assertEquals("2026-11-14 08:00", due("pay rent at 08:00"))   // already passed today: tomorrow
        assertEquals("2026-11-13 17:00", due("pay rent 5pm"))
        assertNull(due("pay rent"))
    }
    @Test fun `captured messages still need a day`() {
        assertNull(due("I will call you at 17:00", owner = false))
        assertEquals("2026-11-14 17:00", due("I will call you tomorrow at 17:00", owner = false))
    }
    @Test fun `day parts and Swahili periods`() {
        assertEquals("2026-11-13 20:00", due("call mama tonight"))
        assertEquals("2026-11-13 20:00", due("mpigie mama leo usiku"))
        assertEquals("2026-11-14 18:00", due("tukutane kesho jioni"))
        assertEquals("2026-11-14 08:00", due("call the bank tomorrow morning".replace("morning", "in the morning")))
        assertEquals("2026-11-14 20:30", due("tukutane kesho saa 2:30 usiku"))
        assertEquals("2026-11-14 10:00", due("Nitakutumia invoice kesho saa nne asubuhi", owner = false))
    }
    @Test fun `weekdays roll to next week only when meant`() {
        assertEquals("2026-11-20 09:00", due("pay rent on friday"))        // Friday 09:00 already passed today
        assertEquals("2026-11-13 15:00", due("pay rent on friday 15:00"))  // still ahead today
        assertEquals("2026-11-20 09:00", due("pay rent next friday"))
        assertEquals("2026-11-17 09:00", due("pay rent next tuesday"))
        assertEquals("2026-11-13 09:00", due("I will pay on friday", owner = false)) // captured text keeps the stated day
    }
    @Test fun `day of month`() {
        assertEquals("2026-12-01 09:00", due("pay rent on the 1st at 9:00"))
        assertEquals("2026-11-15 09:00", due("lipa kodi tarehe 15"))
        assertEquals("2026-12-13 09:00", due("pay rent on the 13th at 9:00")) // today 09:00 already passed
        assertEquals("2026-12-31 09:00", due("pay rent on the 31st"))         // November has no 31st
    }
    @Test fun `nounsWithTaAreNotCommitments`() {
        assertNull(TaskExtractor.extract("habari ya leo: mtandao umerudi", friday10, false))
        assertNull(TaskExtractor.extract("leo utalii umeongezeka", friday10, false))
        assertNotNull(TaskExtractor.extract("nitakupigia leo", friday10, false))
        assertNotNull(TaskExtractor.extract("tutaonana kesho", friday10, false))
    }
}
