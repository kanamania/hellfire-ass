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

    @Test fun `not useful halves the daily cap for thirty days`() {
        assertEquals(6, Interruptions.cap(6, 0)); assertEquals(3, Interruptions.cap(6, 1)); assertEquals(1, Interruptions.cap(6, 5))
        val app = RuntimeEnvironment.getApplication()
        val noon = Instant.parse("2026-11-28T09:00:00Z").toEpochMilli()
        // The button reaches the receiver, which records the feedback.
        val intent = org.robolectric.Shadows.shadowOf(Interruptions.notUsefulIntent(app, "news_update", "story-1", 81)).savedIntent
        com.hellfire.ass.insights.NotUsefulReceiver().onReceive(app, intent)
        assertTrue(app.getSharedPreferences("interruption_feedback", 0).getString("news_update", "").orEmpty().isNotEmpty())
        repeat(2) { Interruptions.notUseful(app, "capture_gap", noon - 60_000) }
        // 3 a day halved twice is 1: the first notice passes, the second is held.
        assertTrue(Interruptions.allow(app, "capture_gap", 3, noon))
        Interruptions.record(app, "capture_gap", noon)
        assertFalse(Interruptions.allow(app, "capture_gap", 3, noon))
    }
}
