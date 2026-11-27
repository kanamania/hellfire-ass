package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.insights.Interruptions
import com.hellfire.ass.news.NewsRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InterruptionsBudgetTest {
    @Test fun `news follow-ups stop at the daily cap and start again the next day`() {
        val app = RuntimeEnvironment.getApplication()
        val noon = Instant.parse("2026-11-27T09:00:00Z").toEpochMilli() // 12:00 EAT, outside quiet hours
        repeat(NewsRepository.UPDATE_DAILY_CAP) {
            assertTrue(Interruptions.allow(app, "news_update", NewsRepository.UPDATE_DAILY_CAP, noon))
            Interruptions.record(app, "news_update", noon)
        }
        assertFalse(Interruptions.allow(app, "news_update", NewsRepository.UPDATE_DAILY_CAP, noon))
        assertTrue(Interruptions.allow(app, "news_update", NewsRepository.UPDATE_DAILY_CAP, noon + 86_400_000))
        assertTrue(Interruptions.allow(app, "capture_gap", 3, noon)) // kinds have separate budgets
    }
}
