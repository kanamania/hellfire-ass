package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AssistantCommandsTest {
    private val now = Instant.parse("2026-11-13T07:00:00Z") // 10:00 EAT, Friday
    private fun parse(text: String) = AssistantCommands.parse(text, now)
    private fun fails(text: String) = assertTrue(text, runCatching { parse(text) }.exceptionOrNull() is IllegalArgumentException)

    @Test fun `questions are not commands`() {
        for (q in listOf("spending today", "tasks tomorrow", "morning brief", "search invoice", "balances", "what do you know about Asha"))
            assertNull(q, parse(q))
    }
    @Test fun `reminders parse date, time and clean title in English and Swahili`() {
        val r = parse("Remind me to call Asha tomorrow at 15:30") as OwnerCommand.Remind
        assertEquals("call Asha", r.title)
        assertEquals("2026-11-14 15:30", TaskTime.display(r.dueAt))
        val sw = parse("nikumbushe kulipa kodi kesho") as OwnerCommand.Remind
        assertEquals("kulipa kodi", sw.title)
        assertEquals("2026-11-14 09:00", TaskTime.display(sw.dueAt))
        val weekday = parse("remind me to submit report on Monday 08:00") as OwnerCommand.Remind
        assertEquals("submit report", weekday.title)
        assertEquals("2026-11-16 08:00", TaskTime.display(weekday.dueAt))
        fails("remind me to call Asha")            // no date
        fails("remind me to call Asha today 06:00") // already passed
        fails("remind me tomorrow")                 // no title
    }
    @Test fun `memory notes split optional subject`() {
        val m = parse("remember Asha: prefers calls after 17:00") as OwnerCommand.Remember
        assertEquals("Asha", m.subject); assertEquals("prefers calls after 17:00", m.text)
        assertEquals("Note", (parse("kumbuka kwamba gari linahitaji service") as OwnerCommand.Remember).subject)
    }
    @Test fun `cash commands reuse the cash parser and stay review-only`() {
        val c = parse("log cash bodaboda 3000 na chakula elfu saba") as OwnerCommand.Cash
        assertEquals(listOf("bodaboda" to 3000L, "chakula" to 7000L), c.items)
        assertEquals(1, (parse("Nimelipa maji 1500") as OwnerCommand.Cash).items.size)
        fails("log cash lunch")
    }
    @Test fun `drafts validate channel recipient and header injection`() {
        val e = parse("email asha@example.invalid about Invoice 12: Please find it attached.") as OwnerCommand.Draft
        assertEquals("EMAIL_IMAP", e.channel); assertEquals("asha@example.invalid", e.recipient)
        assertEquals("Invoice 12", e.subject); assertEquals("Please find it attached.", e.body)
        assertTrue(e.summary.contains("sending needs separate approval"))
        val w = parse("whatsapp +255 712 000 000: Nimefika") as OwnerCommand.Draft
        assertEquals("WHATSAPP_BUSINESS", w.channel); assertEquals("255712000000", w.recipient)
        fails("whatsapp 1234567890123456: hi")
        assertNull(parse("email asha@example.invalid:   "))
        fails("email asha@example.invalid: " + "x".repeat(12001))
        assertNull(parse("email asha@example.invalid about X\nBcc: other@example.invalid: hi"))
    }
    @Test fun `task actions carry query and new due time but no task id until resolved`() {
        val d = parse("mark pay rent as done") as OwnerCommand.TaskAction
        assertEquals("pay rent", d.query); assertEquals("DONE", d.action); assertNull(d.taskId)
        assertEquals("DONE", (parse("maliza kulipa kodi") as OwnerCommand.TaskAction).action)
        assertEquals("SNOOZE", (parse("snooze pay rent") as OwnerCommand.TaskAction).action)
        val r = parse("reschedule pay rent to tomorrow 11:00") as OwnerCommand.TaskAction
        assertEquals("pay rent", r.query); assertEquals("2026-11-14 11:00", TaskTime.display(r.dueAt!!))
        fails("reschedule pay rent to whenever")
        fails("snooze x")
    }
    @Test fun `active task lookup ignores review and closed tasks`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        try {
            db.events().insertCaptured(SourceEvent("source", externalId="fixture", packageName="fixture", occurredAt=1, receivedAt=1,
                kind="INCOMING", payloadHash="fixture", encryptedPayload=byteArrayOf(1), contentAvailable=true, contentRedacted=false, securitySensitive=false))
            fun task(id: String, title: String) = AssistantTask(id, "source", title, 1000, party="OWNER", evidence="Synthetic", createdAt=1, updatedAt=1)
            db.tasks().insert(task("a", "Pay Rent")); db.tasks().insert(task("b", "Pay rent deposit")); db.tasks().insert(task("c", "Call Asha"))
            assertTrue(db.tasks().activeMatching("pay rent").isEmpty())
            db.tasks().activate("a", "Pay Rent", 1000, "OWNER", 500, 1)
            assertEquals(listOf("a"), db.tasks().activeMatching("pay rent").map { it.id })
            db.tasks().activate("b", "Pay rent deposit", 2000, "OWNER", 500, 1)
            assertEquals(2, db.tasks().activeMatching("PAY RENT").size)
            db.tasks().close("a", "DONE", 5)
            assertEquals(listOf("b"), db.tasks().activeMatching("pay rent").map { it.id })
        } finally { db.close() }
    }
}
