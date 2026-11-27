package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.processing.EventProcessor
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.tasks.TaskTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.crypto.KeyGenerator
import kotlin.random.Random

/**
 * Three weeks of a synthetic owner's SMS, payment-app notifications, chats, news and promotions (seeded; no real
 * messages), replayed through the real capture and processing pipeline with an interruption and a re-delivery.
 * Metrics go to build/reports/assistant-replay.json. These measure HellFire's rules, not model or real-message accuracy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimelineReplayTest {
    private lateinit var db: EventDatabase
    private lateinit var app: HellFireApp

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        ReflectionHelpers.setField(app, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
    }
    @After fun close() = db.close()

    private data class Expected(var payments: Int = 0, var duplicates: Int = 0, var commitments: MutableList<Long> = mutableListOf())
    private fun at(day: LocalDate, time: String) = day.atTime(LocalTime.parse(time)).atZone(TaskTime.zone).toInstant().toEpochMilli()
    private fun sms(row: Int, at: Long, sender: String, text: String) = NotificationSnapshot("sms-provider:$row", "android.provider.Telephony.Sms", at, at,
        source = "android_sms", kind = "INCOMING", provenance = mapOf("provider_row_id" to "$row"), fields = mapOf("text" to listOf(text), "sender_hint" to listOf(sender)))
    private fun notification(key: String, pkg: String, at: Long, title: String, text: String, category: String, sender: String? = null) =
        NotificationSnapshot(key, pkg, at, at, fields = buildMap {
            put("title", listOf(title)); put("text", listOf(text)); put("category", listOf(category))
            if (sender != null) put("sender_hint", listOf(sender))
        })

    private fun timeline(today: LocalDate, expected: Expected): List<NotificationSnapshot> {
        val random = Random(27_112_026)
        val out = mutableListOf<NotificationSnapshot>()
        var row = 1000
        for (offset in 20 downTo 0) {
            val day = today.minusDays(offset.toLong())
            repeat(1 + random.nextInt(2)) { i ->
                val amount = (5 + random.nextInt(95)) * 500
                val time = at(day, "%02d:%02d".format(9 + i * 4, random.nextInt(60)))
                out += sms(row++, time, "SELCOM", "Umelipa TZS ${"%,d".format(amount)} kwa DUKA ${random.nextInt(40)}. Ref SLC${day.dayOfYear}$i")
                expected.payments++
                // Every third day the payment app also notifies about the same payment, without the reference.
                if (offset % 3 == 0 && i == 0) {
                    out += notification("selcom-$day-$i", "com.selcom.wallet", time + 90_000, "Selcom Pesa", "Umelipa TZS ${"%,d".format(amount)} kwa DUKA", "msg")
                    expected.duplicates++
                }
            }
            if (offset % 5 == 0) {
                out += sms(row++, at(day, "13:15"), "M-PESA", "JUMA ALI sent you TZS ${"%,d".format((1 + random.nextInt(20)) * 1000)}. Ref MP${day.dayOfYear}X")
                expected.payments++
            }
            if (offset == 3) {
                out += sms(row++, at(day, "08:05"), "DTB", "Dear Customer, your account has been credited with TZS 2,450,000.00 SALARY. Ref DTB${day.dayOfYear}")
                expected.payments++
            }
            if (offset % 4 == 1) {
                val sent = at(day, "18:40")
                out += notification("wa-$day", "com.whatsapp", sent, "Asha", "Nitakutumia invoice kesho saa nne asubuhi", "msg", sender = "Asha")
                val due = day.plusDays(1).atTime(10, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
                if (TaskTime.reviewable(due, System.currentTimeMillis())) expected.commitments += due
            }
            // Noise that must never become a payment or a task.
            out += notification("news-$day", "tz.co.habari", at(day, "07:00"), "Habari za leo", "Mtandao umerudi Dar es Salaam leo", "news")
            out += notification("promo-$day", "com.shop.promo", at(day, "11:00"), "Ofa!", "Punguzo 20% leo tu", "promo")
        }
        return out
    }

    private suspend fun drain() = EventProcessor(db.events(), app.finances::collect,
        { id, time, text, outgoing -> TaskScheduler.collect(app, id, time, text, outgoing) }) { app.cipher }.drain()

    @Test fun `three weeks replay with interruption and re-delivery`() = runBlocking {
        val today = Instant.now().atZone(TaskTime.zone).toLocalDate()
        val expected = Expected()
        val events = timeline(today, expected)
        val half = events.size / 2
        events.take(half).forEach { app.events.capture(it, app.packageName) }
        drain()
        // Interruption: a new processor picks up the rest, and Android re-delivers the first week's SMS rows.
        events.drop(half).forEach { app.events.capture(it, app.packageName) }
        events.filter { it.source == "android_sms" }.take(10).forEach { app.events.capture(it, app.packageName) }
        drain()

        val candidates = db.finances().allCandidates()
        val review = candidates.count { it.state == "REVIEW" }
        val duplicates = candidates.count { it.state == "DUPLICATE" }
        val falseFinance = candidates.count { it.provider !in setOf("SELCOM", "MPESA", "DTB") }
        val tasks = db.tasks().all()
        val dueTimes = tasks.map { it.dueAt }.sorted()
        val salary = candidates.single { it.provider == "DTB" }
        val unprocessed = db.events().pending().size

        File("build/reports").mkdirs()
        File("build/reports/assistant-replay.json").writeText("""
            {"synthetic": true, "days": 21, "events": ${events.size},
             "payments_expected": ${expected.payments}, "payments_in_review": $review,
             "duplicates_expected": ${expected.duplicates}, "duplicates_linked": $duplicates,
             "false_finance_candidates": $falseFinance,
             "commitments_expected": ${expected.commitments.size}, "tasks_proposed": ${tasks.size},
             "unprocessed_after_recovery": $unprocessed}
        """.trimIndent() + "\n")

        assertEquals("every distinct payment reaches review once", expected.payments, review)
        assertEquals("payment-app copies are linked as duplicates", expected.duplicates, duplicates)
        assertEquals("no candidate from chats, news or promotions", 0, falseFinance)
        assertEquals("INCOME", salary.kind); assertEquals(2_450_000, salary.amount)
        assertEquals("only the commitments become tasks, at the stated time", expected.commitments.sorted(), dueTimes)
        assertTrue(tasks.all { it.state == "REVIEW" && it.party == "OTHER" })
        assertEquals("nothing left unprocessed after the interruption", 0, unprocessed)
    }
}
