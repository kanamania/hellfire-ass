package com.hellfire.ass

import com.hellfire.ass.processing.MissedCalls
import org.junit.Assert.*
import org.junit.Test

class MissedCallsTest {
    @Test fun `missedCallNotificationCreatesReviewTask - caller read from either layout`() {
        assertEquals("Asha", MissedCalls.caller("missed_call", "Asha", "Missed call"))
        assertEquals("+255 712 345 678", MissedCalls.caller(null, "Missed call", "+255 712 345 678"))
        assertEquals("Juma", MissedCalls.caller("call", "Simu uliyokosa", "Juma"))
        assertNull(MissedCalls.caller("call", "Asha", "Incoming call"))
        assertNull(MissedCalls.caller("msg", "Asha", "See you at 5"))
        assertNull(MissedCalls.caller("missed_call", "Missed call", ""))
    }
}
