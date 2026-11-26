package com.hellfire.ass

import com.hellfire.ass.insights.Interruptions
import org.junit.Assert.*
import org.junit.Test

class InterruptionsTest {
    @Test fun `quiet hours and the daily cap hold back nudges`() {
        assertTrue(Interruptions.allowed(sentToday = 0, dailyCap = 3, quiet = false))
        assertFalse(Interruptions.allowed(sentToday = 3, dailyCap = 3, quiet = false))
        assertFalse(Interruptions.allowed(sentToday = 0, dailyCap = 3, quiet = true))
    }
}
