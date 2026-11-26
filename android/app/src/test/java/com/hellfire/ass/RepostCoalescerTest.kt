package com.hellfire.ass

import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.notifications.RepostCoalescer
import org.junit.Assert.*
import org.junit.Test

class RepostCoalescerTest {
    private fun post(key: String, ongoing: Boolean, category: String? = null, kind: String = "POSTED") =
        NotificationSnapshot(key, "com.example.player", 1, 1, kind = kind, ongoing = ongoing,
            fields = if (category != null) mapOf("category" to listOf(category)) else emptyMap())

    @Test fun `ongoing re-posts are coalesced but messages and removals are kept`() {
        val c = RepostCoalescer(windowMillis = 600_000)
        // 1,000 progress updates over 30 minutes keep one per ten minutes.
        val kept = (0 until 1000).count { c.keep(post("download", ongoing = true, category = "progress"), it * 1_800L) }
        assertEquals(3, kept)
        assertTrue(c.keep(post("download", ongoing = true, kind = "REMOVED"), 1_800_001))
        assertTrue(c.keep(post("download", ongoing = true), 1_800_002)) // a new run starts fresh after removal
        assertTrue((0 until 50).all { c.keep(post("chat", ongoing = false, category = "msg"), it.toLong()) })
        assertEquals(2, (0 until 2).count { c.keep(post("nav", ongoing = false, category = "navigation"), it * 600_000L) })
    }
}
