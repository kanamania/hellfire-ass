package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.EventRepository
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.finance.*
import com.hellfire.ass.insights.*
import com.hellfire.ass.security.BackupTables
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.templates.*
import com.hellfire.ass.widgets.SourceStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import javax.crypto.KeyGenerator

/** Schema v14 and the logic behind templates, the brief, waiting-on, people, debts, recurring bills, expiry dates and capture gaps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DailyOutputsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val hour = 3_600_000L
    private fun at(text: String) = TaskTime.parse(text)
    private fun memoryDb() = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
    private fun task(id: String, title: String, due: String, state: String = "ACTIVE", kind: String = "TASK", amount: Long? = null) =
        AssistantTask(id, "s", title, at(due), party = "OWNER", evidence = title, state = state, createdAt = 1, updatedAt = 1, amount = amount, kind = kind)

    @Test fun `migration 13 to 14 adds templates, debts and person names, and backups carry them`() = runBlocking {
        val name = "migration-13-14.db"
        MigrationFixture.create(context, name, 13) { }
        val db = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try {
            db.templates().save(ActionTemplate("t", "Month start", TemplateItems.encode(listOf(TemplateItem("EXPENSE", "Rent", 500_000))), 1, 1))
            db.debts().insert(DebtEntry("d", "Juma", 50_000, "LENT", "", 1, null, null, 1))
            db.people().save(PersonAlias("tel:712345678", "Juma", 1))
            assertEquals(17, db.openHelper.writableDatabase.version)
            assertEquals("Month start", db.templates().all().single().name)
            assertEquals(listOf("action_template", "debt_entry", "person_alias"), BackupTables.tablesFor(14) - BackupTables.tablesFor(13).toSet())
            assertEquals(BackupTables.tablesFor(16) - "person_contact", BackupTables.tablesFor(14))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `template lines round-trip and reject bad input`() {
        val items = listOf(TemplateItem("TASK", "Pay water", 20_000, dueInDays = 5, dueTime = "08:30", bill = true),
            TemplateItem("EXPENSE", "Rent", 500_000, "Rent", "acc"), TemplateItem("INCOME", "Salary"))
        assertEquals(items, TemplateItems.decode(TemplateItems.encode(items)))
        assertEquals("2026-11-21 08:30", TaskTime.display(TemplateItems.dueAt(items[0], at("2026-11-16 22:00"))))
        for (bad in listOf(TemplateItem("OTHER", "x"), TemplateItem("TASK", " "), TemplateItem("EXPENSE", "x", 0), TemplateItem("TASK", "x", dueInDays = -1), TemplateItem("TASK", "x", dueTime = "25:00")))
            assertTrue(bad.toString(), runCatching { TemplateItems.encode(listOf(bad)) }.isFailure)
        assertTrue(runCatching { TemplateItems.encode(emptyList()) }.isFailure)
    }

    @Test fun `using a template schedules tasks, posts payments that have an account and leaves the rest to confirm`() = runBlocking {
        val db = memoryDb()
        try {
            val cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
            val events = EventRepository(db.events()) { cipher }
            val runner = TemplateRunner(db, FinanceRepository(db), "com.hellfire.ass") { events.capture(it, "com.hellfire.ass") }
            db.finances().addAccount(FinanceAccount("mpesa", "M-Pesa", "MPESA"))
            val items = listOf(TemplateItem("EXPENSE", "Rent", 500_000, "Rent", "mpesa"), TemplateItem("INCOME", "Salary", 1_200_000, "Income", "mpesa"),
                TemplateItem("EXPENSE", "Fuel", 60_000, "Fuel"), TemplateItem("TASK", "Pay water bill", 20_000, dueInDays = 5, dueTime = "08:30", bill = true))
            val template = ActionTemplate("t", "Month start", TemplateItems.encode(items), 1, 1)
            db.templates().save(template)
            val used = at("2026-11-16 10:00")
            assertEquals(TemplateResult(tasks = 1, posted = 2, toConfirm = 1), runner.run(template, items, used, now = used))
            assertEquals(700_000L, db.finances().balance("mpesa"))
            val entries = db.finances().allEntries()
            assertEquals(setOf("Rent" to -500_000L, "Salary" to 1_200_000L), entries.map { it.description to it.amount }.toSet())
            assertTrue("each payment keeps the template run as evidence", entries.all { it.occurredAt == used && db.finances().sources(it.id).size == 1 })
            val waiting = db.finances().allCandidates().single { it.state == "REVIEW" }
            assertEquals("Fuel", waiting.evidence); assertEquals(60_000L, waiting.amount); assertNull(waiting.accountId)
            val added = db.tasks().all().single()
            assertEquals("ACTIVE", added.state); assertEquals("BILL", added.kind); assertEquals(20_000L, added.amount)
            assertEquals("2026-11-21 08:30", TaskTime.display(added.dueAt)); assertNotNull(added.nextAlertAt)
            assertEquals(1, db.templates().find("t")!!.uses)
            assertTrue("a payment line needs an amount", runCatching { runner.run(template, listOf(TemplateItem("EXPENSE", "Fuel")), used) }.isFailure)
            assertEquals(2, db.finances().entryCount())
        } finally { db.close() }
    }

    @Test fun `spoken Swahili amounts are understood, including compound numbers`() {
        assertEquals(listOf("bodaboda" to 15_000L), CashParser.parse("Nimelipa bodaboda elfu kumi na tano."))
        assertEquals(listOf("umeme" to 5_500L, "soda mbili" to 3_000L), CashParser.parse("umeme elfu tano na mia tano na soda mbili elfu tatu"))
        assertEquals(listOf("kodi" to 250_000L), CashParser.parse("kodi laki mbili na elfu hamsini"))
        assertEquals(listOf("gari" to 1_000_000L), CashParser.parse("gari milioni moja"))
        assertEquals(listOf("bodaboda" to 3000L, "chakula" to 7000L), CashParser.parse("bodaboda 3000 na chakula elfu saba"))
        for (bad in listOf("elfu tano", "chakula", "chakula elfu", "chakula tano")) assertTrue(bad, runCatching { CashParser.parse(bad) }.isFailure)
        assertEquals(listOf("Kutoka kwa Juma" to 50_000L), CashParser.parseIncome("50,000 kutoka kwa Juma"))
        assertEquals(listOf("mshahara" to 500_000L), CashParser.parseIncome("mshahara laki tano"))
    }

    @Test fun `spoken commands accept the assistant's name, income and templates`() {
        val now = Instant.parse("2026-11-16T07:00:00Z")
        val cash = AssistantCommands.parse("HellFire, nimelipa bodaboda elfu tano.", now) as OwnerCommand.Cash
        assertEquals("EXPENSE", cash.kind); assertEquals(listOf("bodaboda" to 5_000L), cash.items)
        val income = AssistantCommands.parse("nimepokea 50000 kutoka kwa Juma", now) as OwnerCommand.Cash
        assertEquals("INCOME", income.kind); assertEquals(50_000L, income.items.single().second)
        assertEquals("salary" to 500_000L, (AssistantCommands.parse("log income salary 500,000", now) as OwnerCommand.Cash).items.single())
        assertEquals("Month start", (AssistantCommands.parse("use template Month start", now) as OwnerCommand.UseTemplate).query)
        assertEquals("mwanzo wa mwezi", (AssistantCommands.parse("tumia kiolezo mwanzo wa mwezi", now) as OwnerCommand.UseTemplate).query)
        assertNull("still a question", AssistantCommands.parse("income this month", now))
        assertNull(AssistantCommands.parse("hellfire", now))
    }

    @Test fun `numbers and addresses from different channels resolve to one handle`() {
        for (raw in listOf("+255712345678", "0712 345 678", "255712345678@c.us")) assertEquals(raw, "tel:712345678", Handles.key(raw))
        assertEquals("mail:asha@example.com", Handles.key("Asha Juma <Asha@Example.com>"))
        assertEquals("group:12036304", Handles.key("12036304@g.us"))
        assertEquals("name:m-pesa", Handles.key("M-PESA")); assertEquals("short:15055", Handles.key("15055")); assertNull(Handles.key("  "))
        assertTrue(Handles.person("tel:712345678")); assertTrue(Handles.person("mail:asha@example.com"))
        for (key in listOf("name:m-pesa", "short:15055", "group:1", "mail:no-reply@bank.co.tz", "mail:notifications@github.com")) assertFalse(key, Handles.person(key))
        assertEquals("Asha Juma", Handles.display("Asha Juma <asha@example.com>")); assertEquals("+255712345678", Handles.display("255712345678@c.us"))
    }

    @Test fun `waiting-on uses the latest message per person across channels`() {
        val now = at("2026-11-16 12:00")
        fun msg(raw: String, channel: String, incoming: Boolean, hoursAgo: Long, text: String, group: Boolean = false) =
            Interaction(Handles.key(raw)!!, raw, channel, incoming, now - hoursAgo * hour, text, "s-$raw-$hoursAgo", group = group)
        val list = listOf(
            msg("+255712345678", "SMS", true, 30, "Uko wapi?"), msg("255712345678@c.us", "WhatsApp", false, 29, "Niko njiani"), // answered on another channel
            msg("+255754000111", "SMS", true, 6, "Nitumie ile invoice"),                                                         // reply owed
            msg("+255754000222", "SMS", true, 1, "Sawa"),                                                                        // too recent
            msg("asha@example.com", "Email", false, 50, "Can you confirm the price?"),                                           // waiting on Asha
            msg("neema@example.com", "Email", false, 50, "Asante sana"),                                                         // nothing asked
            msg("no-reply@bank.co.tz", "Email", true, 10, "Statement ready"), msg("M-PESA", "SMS", true, 10, "Umepokea"),
            msg("255700999888@c.us", "WhatsApp", true, 10, "Group chatter", group = true),
        )
        val waiting = Waiting.compute(list, mapOf("mail:asha@example.com" to "Asha"), now)
        assertEquals(listOf("tel:754000111"), waiting.iOwe.map { it.personKey })
        assertEquals(listOf("Asha"), waiting.theyOwe.map { it.name })
        assertTrue("cleared rows stay hidden", Waiting.compute(list, emptyMap(), now, mapOf("tel:754000111" to now - 6 * hour)).iOwe.isEmpty())
        assertEquals("a newer message shows again", 1, Waiting.compute(list, emptyMap(), now, mapOf("tel:754000111" to now - 7 * hour)).iOwe.size)
        val joined = Waiting.compute(listOf(msg("+255754000111", "SMS", true, 6, "Nitumie invoice"), msg("juma@example.com", "Email", false, 5, "Hii hapa")),
            mapOf("tel:754000111" to "Juma", "mail:juma@example.com" to "Juma"), now)
        assertTrue("an email reply settles the SMS once both belong to one person", joined.iOwe.isEmpty())
    }

    @Test fun `people join handles that share a name`() {
        val list = listOf(Interaction("tel:712345678", "+255712345678", "SMS", true, 5, "a", "s1"), Interaction("mail:juma@example.com", "Juma H <juma@example.com>", "Email", false, 9, "b", "s2"),
            Interaction("tel:754000111", "+255754000111", "SMS", true, 7, "c", "s3"), Interaction("name:m-pesa", "M-PESA", "SMS", true, 99, "d", "s4"))
        val people = People.build(list, mapOf("tel:712345678" to "Juma", "mail:juma@example.com" to "Juma"))
        assertEquals(listOf("Juma", "+255754000111"), people.map { it.name })
        people[0].let { assertEquals("n:juma", it.key); assertEquals(1, it.incoming); assertEquals(1, it.outgoing); assertEquals(setOf("SMS", "Email"), it.channels); assertEquals(2, it.handles.size) }
        assertFalse(people[1].named)
    }

    @Test fun `debts net per person and suggest matching repayments`() {
        fun entry(id: Long, person: String, kind: String, amount: Long, due: Long? = null) = DebtEntry("$id", person, Debts.signed(kind, amount), kind, "", 100, due, null, id)
        val balances = Debts.balances(listOf(entry(1, "Juma", "LENT", 50_000, due = 200), entry(2, "juma ", "REPAID_TO_ME", 20_000), entry(3, "Asha", "BORROWED", 100_000, due = 5000),
            entry(4, "Neema", "LENT", 10_000), entry(5, "Neema", "REPAID_TO_ME", 10_000)))
        assertEquals("settled people are left out", listOf("Asha" to -100_000L, "juma" to 30_000L), balances.map { it.person to it.balance })
        assertEquals(200L, balances[1].dueAt); assertTrue(balances[1].overdue(1000)); assertFalse(balances[0].overdue(1000))
        fun candidate(id: String, kind: String, evidence: String, state: String = "APPROVED") =
            FinanceCandidate(id, "src-$id", provider = "MPESA", reference = null, kind = kind, amount = 20_000, fee = 0, merchant = null, evidence = evidence, occurredAt = 500, state = state)
        val candidates = listOf(candidate("a", "INCOME", "Umepokea TSh 20,000 kutoka kwa JUMA HAMISI"), candidate("b", "EXPENSE", "Umetuma kwa JUMA"),
            candidate("c", "INCOME", "from Jumanne Traders"), candidate("d", "EXPENSE", "Sent to Asha Ali"), candidate("e", "INCOME", "from Juma", state = "REVIEW"))
        assertEquals(setOf("a" to "REPAID_TO_ME", "d" to "I_REPAID"), Debts.suggestRepayments(balances, candidates, 0, emptySet()).map { it.candidate.id to it.kind }.toSet())
        assertTrue(Debts.suggestRepayments(balances, candidates, 0, setOf("src-a", "src-d")).isEmpty())
        assertEquals(-5L, Debts.signed("BORROWED", 5)); assertEquals(5L, Debts.signed("I_REPAID", 5))
        assertTrue(Debts.reminderText("Juma Hamisi", 30_000, null, swahili = true).contains("TZS 30,000"))
    }

    @Test fun `monthly payments are detected, and one-offs, stopped, daily or reversed ones are not`() {
        fun entry(id: String, description: String, day: String, amount: Long = 50_000, kind: String = "EXPENSE", reverses: String? = null) =
            LedgerEntry(id, "a", id, kind, if (reverses == null) -amount else amount, "Utilities", description, at("$day 10:00"), 1, id, reverses)
        val now = at("2026-11-16 10:00")
        val lunches = (1..9).map { entry("l$it", "Lunch", "2026-${"%02d".format(9 + (it - 1) / 3)}-${"%02d".format(it + 1)}", 7_000) }
        val found = Recurring.detect(lunches + listOf(
            entry("r1", "Rent", "2026-09-01", 500_000), entry("r2", "rent ", "2026-10-01", 500_000), entry("r3", "Rent", "2026-11-02", 500_000),
            entry("d1", "DSTV", "2026-06-05"), entry("d2", "DSTV", "2026-07-05"), entry("d3", "DSTV", "2026-08-05"),
            entry("g1", "Gym", "2026-08-03"), entry("g2", "Gym", "2026-10-03"), entry("g3", "Gym", "2026-11-03"),
            entry("n1", "Netflix", "2026-08-10", 20_000), entry("n2", "Netflix", "2026-09-10", 22_000), entry("n3", "Netflix", "2026-10-10", 25_000),
            entry("x1", "Expense", "2026-09-01"), entry("x2", "Expense", "2026-10-01"), entry("x3", "Expense", "2026-11-01"),
            entry("w1", "Water", "2026-09-01"), entry("w2", "Water", "2026-10-01"), entry("w3", "Water", "2026-11-01"),
            entry("w3r", "Reversal: Water", "2026-11-02", kind = "ADJUSTMENT", reverses = "w3"),
        ), now)
        assertEquals(listOf("Netflix", "Rent"), found.map { it.label })
        assertEquals(22_000L, found[0].amount); assertTrue(found[0].varies); assertFalse(found[1].varies)
        assertEquals("2026-12-02 10:00", TaskTime.display(found[1].nextAt))
        val bills = listOf(task("b1", "Rent", "2026-12-01 09:00", kind = "BILL", amount = 500_000), task("b2", "LUKU", "2026-11-20 09:00", kind = "BILL"),
            task("b3", "School fees", "2027-01-15 09:00", kind = "BILL", amount = 900_000))
        assertEquals(listOf("Netflix"), Recurring.untracked(found, bills).map { it.label })
        val forecast = Recurring.forecast(bills, balance = 300_000, now = now)
        assertEquals(500_000L, forecast.total); assertEquals(1, forecast.unpriced); assertEquals(200_000L, forecast.shortfall); assertEquals(listOf("b2", "b1"), forecast.due.map { it.id })
        assertEquals(0L, Recurring.forecast(bills, 800_000, now).shortfall)
    }

    @Test fun `expiry dates are read from scanned document text`() {
        val today = LocalDate.of(2026, 11, 16)
        ExpiryFinder.find("PASSPORT\nDate of expiry\n12 MAR /MARS 31", today).single().let { assertEquals(LocalDate.of(2031, 3, 12), it.date); assertEquals("Passport", it.label) }
        ExpiryFinder.find("Insurance cover note. Valid until 30/06/2027.", today).single().let { assertEquals(LocalDate.of(2027, 6, 30), it.date); assertEquals("Insurance", it.label) }
        ExpiryFinder.find("Leseni ya udereva inaisha 2028-01-05", today).single().let { assertEquals(LocalDate.of(2028, 1, 5), it.date); assertEquals("Driving licence", it.label) }
        assertEquals(LocalDate.of(2027, 2, 28), ExpiryFinder.find("EXP DATE 02/2027", today).single().date)
        assertEquals(LocalDate.of(2027, 12, 1), ExpiryFinder.find("Permit expires on December 1, 2027", today).single().date)
        assertTrue("past dates are ignored", ExpiryFinder.find("Date of expiry 01/01/2020", today).isEmpty())
        assertTrue("other dates are ignored", ExpiryFinder.find("Date of issue 05/05/2030", today).isEmpty())
        assertEquals(LocalDate.of(2027, 5, 13), ExpiryFinder.remindOn(LocalDate.of(2027, 6, 12), today))
        assertEquals(LocalDate.of(2026, 11, 23), ExpiryFinder.remindOn(LocalDate.of(2026, 11, 30), today))
        assertEquals(LocalDate.of(2026, 11, 18), ExpiryFinder.remindOn(LocalDate.of(2026, 11, 18), today))
    }

    @Test fun `a scanned expiry date becomes one task proposal without the document number`() = runBlocking {
        val db = memoryDb()
        try {
            db.events().insertCaptured(SourceEvent("ocr", externalId = "ocr", packageName = "owner.media", occurredAt = 1, receivedAt = 1, kind = "POSTED",
                payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
            val now = at("2026-11-16 10:00"); val text = "PASSPORT No AB1234567\nDate of expiry 12 MAR 2031"
            assertEquals(1, ExpiryFinder.collect(db, "ocr", "media-1", "passport.jpg", text, now))
            assertEquals("the same page read again adds nothing", 0, ExpiryFinder.collect(db, "ocr", "media-1", "passport.jpg", text, now))
            val proposal = db.tasks().all().single()
            assertEquals("REVIEW", proposal.state); assertEquals("Renew Passport · expires 12 Mar 2031", proposal.title)
            assertEquals("2031-02-10 09:00", TaskTime.display(proposal.dueAt))
            assertFalse(proposal.evidence.contains("AB1234567"))
        } finally { db.close() }
    }

    @Test fun `capture gaps report broken, silent and stale sources only`() {
        val now = at("2026-11-16 12:00")
        val healthy = listOf(SourceStatus("Notifications", true), SourceStatus("SMS", true), SourceStatus("Email", true, now - hour), SourceStatus("WhatsApp", true, now - 8 * hour))
        assertEquals(listOf("WhatsApp"), CaptureGaps.find(now, healthy, now - hour, now - 2 * hour, now - 2 * hour + 1000).map { it.source })
        val broken = CaptureGaps.find(now, listOf(SourceStatus("Notifications", true), SourceStatus("SMS", true), SourceStatus("Email", false)), now - 13 * hour, now - 3 * hour, now - 9 * hour)
        assertEquals(listOf("Email", "Notifications", "SMS"), broken.map { it.source })
        assertTrue("nothing captured yet and a just-arrived SMS are not gaps",
            CaptureGaps.find(now, listOf(SourceStatus("Notifications", true), SourceStatus("SMS", true)), null, now - 60_000, null).isEmpty())
        assertEquals(listOf("Notification access is off"), CaptureGaps.find(now, listOf(SourceStatus("Notifications", false)), now - 20 * hour, null, null).map { it.message })
    }

    @Test fun `the brief gathers tasks, bills, replies, debts and capture problems`() {
        val now = at("2026-11-16 08:00")
        val tasks = listOf(task("t1", "Call Juma", "2026-11-16 15:00"), task("t2", "Send report", "2026-11-14 09:00"), task("t3", "Visit bank", "2026-11-17 10:00"),
            task("b1", "Rent", "2026-11-20 09:00", kind = "BILL", amount = 500_000), task("p1", "Proposal", "2026-11-18 09:00", state = "REVIEW"))
        val waiting = WaitingOn(listOf(WaitingItem("tel:1", "Asha", "SMS", now - 30 * hour, "?", "s", null)), emptyList())
        val debts = listOf(DebtBalance("Juma", 30_000, 1, at("2026-11-10 09:00")), DebtBalance("Neema", -10_000, 1, null))
        val inputs = BriefInputs(false, now, tasks, 12_000, 0, 2, 1, waiting, debts, Recurring.forecast(tasks, 100_000, now), listOf(CaptureGap("SMS", "SMS permission is off")))
        val morning = Briefs.build(inputs)
        assertEquals(listOf("Today", "Bills", "Money", "Replies", "Debts", "To review", "Capture"), morning.sections.map { it.title })
        assertEquals("2 due today · 1 reply owed · 1 bill this week · capture needs attention", morning.headline)
        assertTrue("overdue first", morning.sections[0].lines.first().warn)
        assertTrue("shortfall is flagged", morning.sections.first { it.title == "Bills" }.lines.last().warn)
        assertTrue(morning.sections.first { it.title == "Debts" }.lines.first().detail!!.contains("Juma"))
        assertTrue(morning.text().contains("Reply to Asha"))
        val evening = Briefs.build(inputs.copy(evening = true, now = at("2026-11-16 21:30")))
        assertEquals(listOf("Today's money", "Still open", "Tomorrow", "Replies", "Capture"), evening.sections.map { it.title })
        assertEquals("totals, payments to confirm and the cash question", 3, evening.sections[0].lines.size)
        assertEquals(listOf("hellfire://task/t2", "hellfire://task/t1"), evening.sections[1].lines.map { it.link })
        assertEquals("Nothing urgent today", Briefs.build(inputs.copy(tasks = emptyList(), waiting = WaitingOn(emptyList(), emptyList()), gaps = emptyList(),
            forecast = Recurring.forecast(emptyList(), 0, now))).headline)
    }

    @Test fun `the morning brief is scheduled once a day, in the morning only`() {
        assertEquals(at("2026-11-16 07:00"), TaskScheduler.briefTime(7, null, at("2026-11-16 05:30")))
        assertEquals("late morning: now", at("2026-11-16 09:10"), TaskScheduler.briefTime(7, "2026-11-15", at("2026-11-16 09:10")))
        assertEquals("already shown today", at("2026-11-17 07:00"), TaskScheduler.briefTime(7, "2026-11-16", at("2026-11-16 07:01")))
        assertEquals("first run in the afternoon", at("2026-11-17 07:00"), TaskScheduler.briefTime(7, null, at("2026-11-16 14:00")))
        assertNull(TaskScheduler.briefTime(null, null, at("2026-11-16 05:30")))
    }
}
