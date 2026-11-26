package com.hellfire.ass

import com.hellfire.ass.assistant.AskRouting
import com.hellfire.ass.assistant.QueryDates
import com.hellfire.ass.tasks.TaskTime
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AskRoutingTest {
    @Test fun `taarifaYaMatumiziIsFinance`() {
        assertTrue(AskRouting.skipNews("nipe taarifa ya matumizi wiki hii"))
        assertTrue(AskRouting.skipNews("habari za kazi zangu leo"))
        assertFalse(AskRouting.skipNews("news about the budget"))
        assertFalse(AskRouting.skipNews("habari za dar es salaam leo"))
    }
    @Test fun `briefOnlyWithoutMoneyWords`() {
        assertTrue(AskRouting.brief("morning brief"))
        assertTrue(AskRouting.brief("nipe muhtasari"))
        assertFalse(AskRouting.brief("muhtasari wa matumizi mwezi huu"))
        assertFalse(AskRouting.brief("briefcase receipt"))
    }
    @Test fun `tasksTodayIncludesGenericPhrasing`() {
        for (q in listOf("tasks today", "any task today?", "what do i have today?", "nina nini kesho", "reminders tomorrow", "majukumu ya wiki hii", "kazi zangu leo"))
            assertTrue(q, AskRouting.tasks(q))
        assertTrue(AskRouting.withAgenda("what do i have today?"))
        assertTrue(AskRouting.withAgenda("tasks and meetings tomorrow"))
        assertFalse(AskRouting.withAgenda("tasks tomorrow"))
        assertFalse(AskRouting.tasks("spending today"))
    }
    @Test fun `followUpDoesNotStealTasks`() {
        assertTrue(AskRouting.financeFollowUp("na mwezi uliopita?"))
        assertTrue(AskRouting.financeFollowUp("and last week"))
        assertFalse(AskRouting.financeFollowUp("and tasks tomorrow"))
        assertFalse(AskRouting.financeFollowUp("je, nina miadi gani kesho"))
        assertFalse(AskRouting.financeFollowUp("na kazi zangu leo"))
        assertFalse(AskRouting.financeFollowUp("and the news today"))
    }
    @Test fun `keshokutwaIsTwoDays`() {
        val now = Instant.parse("2026-11-13T07:00:00Z")
        assertEquals("2026-11-15 00:00", TaskTime.display(QueryDates.range("tasks keshokutwa", now).first))
        assertEquals("2026-11-14 00:00", TaskTime.display(QueryDates.range("tasks kesho", now).first))
        val months = QueryDates.range("spending over the months", now)
        assertEquals("2026-11-13 00:00", TaskTime.display(months.first)) // "months" is not "month"
    }
}
