package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.finance.FinanceParser
import com.hellfire.ass.intelligence.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PredeviceGoldenTest {
    private val golden get() = JSONObject(javaClass.classLoader!!.getResourceAsStream("predevice/golden.json")!!.bufferedReader().use { it.readText() })

    @Test fun bilingualFinanceMatchesWholeIntegerGoldenAmounts() {
        val cases = golden.getJSONArray("finance")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val actual = FinanceParser.parse(case.getString("sender"), case.getString("text"))
            if (case.isNull("expected")) assertNull(case.getString("id"), actual)
            else {
                val expected = case.getJSONObject("expected")
                assertNotNull(case.getString("id"), actual)
                assertEquals(expected.getString("provider"), actual!!.provider)
                assertEquals(expected.getString("kind"), actual.kind)
                assertEquals(expected.getLong("amount"), actual.amount)
                assertEquals(expected.getLong("fee"), actual.fee)
                assertEquals(expected.getString("reference"), actual.reference)
            }
        }
    }

    @Test fun bilingualMediaQueriesMatchKindsCategoriesAndDateGoldens() {
        val fixture = golden
        val now = ZonedDateTime.parse(fixture.getString("now"))
        val cases = fixture.getJSONArray("queries")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONObject("expected")
            val actual = QueryParser.parse(case.getString("text"), now.toInstant().toEpochMilli())
            fun strings(key: String) = expected.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            assertEquals(case.getString("text"), strings("kinds"), actual.kinds.map { it.name }.toSet())
            assertEquals(case.getString("text"), strings("categories"), actual.categories)
            val dates = when (expected.optString("date")) {
                "last-year" -> now.withYear(2025).withDayOfYear(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli() to now.withDayOfYear(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
                "today" -> now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli() to now.toInstant().toEpochMilli()
                "yesterday" -> now.minusDays(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli() to now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
                "last-week" -> now.minusDays(7).toInstant().toEpochMilli() to now.toInstant().toEpochMilli()
                else -> null to null
            }
            assertEquals(case.getString("text"), dates.first, actual.fromMs)
            assertEquals(case.getString("text"), dates.second, actual.toMs)
            assertTrue(case.getString("text"), actual.text.isBlank())
        }
    }

    @Test fun mediaSamplingMatchesGoldenTimeline() {
        val expected = golden.getJSONObject("media")
        fun times(key: String) = expected.getJSONArray(key).let { a -> (0 until a.length()).map { a.getLong(it) } }
        assertEquals(times("fast_schedule_ms"), Sampling.schedule(30_000, SampleMode.FAST))
        assertEquals(times("balanced_schedule_ms"), Sampling.schedule(12_000, SampleMode.BALANCED, listOf(7_000)))
        assertTrue(BpmEstimator.estimate(BpmEstimator.clickTrack(120, 8))!! in 116..124)
    }
}
