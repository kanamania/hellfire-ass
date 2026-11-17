package com.hellfire.ass

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import com.hellfire.ass.assistant.*
import com.hellfire.ass.contacts.*
import com.hellfire.ass.data.*
import com.hellfire.ass.finance.*
import com.hellfire.ass.insights.*
import com.hellfire.ass.security.*
import com.hellfire.ass.tasks.*
import com.hellfire.ass.ui.SharedContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReviewImprovementsTest {
    private lateinit var db: EventDatabase
    private val now = Instant.parse("2026-11-16T09:00:00Z")
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build() }
    @After fun close() { db.close() }
    private suspend fun source(id: String) {
        db.events().insertCaptured(SourceEvent(id, externalId = id, source = "owner_entry", packageName = "fixture", occurredAt = 1, receivedAt = 1,
            kind = "POSTED", payloadHash = "fixture", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
    }
    @Test fun `conflicting periods and unsupported comparisons request clarification`() {
        for (question in listOf("spending this month and last month", "spending today this week", "spending 2026-11-01 this month", "compare spending this month")) {
            assertTrue(question, runCatching { FinanceQuestion.parse(question, now, emptyList(), emptyList()) }.isFailure)
        }
        val first = FinanceQuestion.parse("spending from Cash last month", now, listOf(FinanceAccount("a", "Cash")), emptyList())
        val next = FinanceQuestion.parse("and income?", now, emptyList(), emptyList(), first)
        assertTrue(next.income); assertEquals(first.start, next.start); assertEquals(first.end, next.end); assertEquals("a", next.accountId)
    }
    @Test fun `natural question keywords retrieve saved indexed evidence without FTS operators`() = runBlocking {
        source("older")
        db.events().insertSearch(SourceSearch("older", "Synthetic Juma promised to repay on Tuesday"))
        val words = SearchText.keywords("When did Juma promise to repay?")
        assertEquals(listOf("juma", "promise", "repay"), words)
        assertEquals("older", db.events().search(SearchText.query(words.joinToString(" "))!!, 20).single().eventId)
        val partial = SearchText.anyQuery(SearchText.keywords("Juma missing OR \"quotation\""))!!
        assertFalse(partial.contains("\"")); assertEquals("older", db.events().search(partial, 20).single().eventId)
    }
    @Test fun `manual and picked same-name people never adopt or steal each others handles`() = runBlocking {
        val links = ContactLinks(db)
        val typed = links.save(ContactChoice("Juma", handles = listOf("0712345678")))
        val picked = links.save(ContactChoice("Juma", "lookup-other", listOf("0754000111")))
        assertNotEquals(typed, picked)
        assertNull(db.contacts().find(typed)!!.lookupKey)
        assertTrue(runCatching { links.save(ContactChoice("Juma", "lookup-third", listOf("0712345678"))) }.isFailure)
        assertEquals(typed, db.people().all().single { it.handle == "tel:712345678" }.personKey)
        assertTrue(runCatching { links.namePerson(typed, ContactChoice("Juma", personKey = picked), emptyList(), "Juma") }.isFailure)
    }
    @Test fun `payment proofs group by posted ledger without deleting sources`() = runBlocking {
        source("sms"); source("notification")
        db.finances().addAccount(FinanceAccount("a", "Cash"))
        db.finances().post(LedgerEntry("e", "a", "key", "EXPENSE", -100, "Food", "Fixture", 1, 1, "group"))
        db.finances().evidence(LedgerEvidence("e", "sms")); db.finances().evidence(LedgerEvidence("e", "notification"))
        val groups = db.finances().evidenceGroups(listOf("sms", "notification"))
        assertEquals(2, groups.size); assertEquals(setOf("group"), groups.map { it.groupId }.toSet())
        assertNotNull(db.events().find("sms")); assertNotNull(db.events().find("notification"))
        assertEquals(-100L, db.finances().balance("a"))
    }
    @Test fun `fuel and account queries retain filters in date followups`() {
        val accounts = listOf(FinanceAccount("a", "M-Pesa"), FinanceAccount("b", "CRDB Current"))
        val q = FinanceQuestion.parse("How much did I spend on fuel from my mpesa this month?", now, accounts, listOf("Fuel", "Food"))
        assertEquals("Fuel", q.category); assertEquals("a", q.accountId)
        val next = FinanceQuestion.parse("And last month?", now, accounts, listOf("Fuel"), q)
        assertEquals("2026-10-01 00:00", TaskTime.display(next.start)); assertEquals("Fuel", next.category); assertEquals("a", next.accountId)
        assertTrue(runCatching { FinanceQuestion.parse("spending on entertainment this month", now, accounts, listOf("Fuel")) }.isFailure)
        assertTrue(runCatching { FinanceQuestion.parse("spending from an unknown wallet", now, accounts, listOf("Fuel")) }.isFailure)
    }
    @Test fun `periods and quoted merchant filters are explicit`() {
        val q = FinanceQuestion.parse("spending at merchant \"Cafe Juma\" last 7 days", now, emptyList(), emptyList())
        assertEquals("cafe juma", q.merchant); assertEquals("2026-11-10 00:00", TaskTime.display(q.start))
        assertEquals("2025-01-01 00:00", TaskTime.display(QueryDates.range("last year", now).first))
        assertTrue(runCatching { QueryDates.range("past 0 days", now) }.isFailure)
    }
    @Test fun `filtered totals include only matching ledger and evidence`() = runBlocking {
        val repo = FinanceRepository(db)
        db.finances().addAccount(FinanceAccount("a", "Cash")); db.finances().addAccount(FinanceAccount("b", "Bank"))
        for ((id, account, category) in listOf(Triple("fuel", "a", "Fuel"), Triple("food", "a", "Food"), Triple("fuel-bank", "b", "Fuel"))) {
            source(id)
            db.finances().post(LedgerEntry(id, account, id, "EXPENSE", -5000, category, "Synthetic station", 10, 10, id))
            db.finances().evidence(LedgerEvidence(id, id))
        }
        assertEquals(15000L, repo.summary(0, 100).expenses)
        val result = repo.summary(0, 100, "Fuel", "a")
        assertEquals(5000L, result.expenses); assertEquals(listOf("fuel"), result.sourceIds)
        repo.reverse("fuel")
        assertEquals(0L, repo.summary(0, Long.MAX_VALUE, "Fuel", "a").expenses)
    }
    @Test fun `same-name contacts stay separate through aliases tasks and merge`() = runBlocking {
        source("s"); db.tasks().insert(AssistantTask("t", "s", "Call", 100, party = "OWNER", evidence = "synthetic", createdAt = 1, updatedAt = 1))
        val links = ContactLinks(db)
        val one = links.save(ContactChoice("Juma", "lookup-one", listOf("0712345678")))
        val two = links.save(ContactChoice("Juma", "lookup-two", listOf("0754000111")))
        assertNotEquals(one, two)
        assertTrue(runCatching { links.save(ContactChoice("Juma")) }.isFailure)
        links.task("t", one)
        val names = db.people().all().associate { it.handle to it.name }
        val ids = db.people().all().associate { it.handle to it.personKey!! }
        assertNotEquals(People.personKey("tel:712345678", names, ids), People.personKey("tel:754000111", names, ids))
        assertEquals(one, links.namePerson(one, ContactChoice("Juma A"), emptyList(), "Juma"))
        assertEquals(one, db.tasks().task("t")!!.personKey)
        links.merge(one, two)
        assertEquals(two, db.tasks().task("t")!!.personKey); assertNull(db.contacts().find(one))
    }
    @Test fun `separating selected records changes associations and no money`() = runBlocking {
        source("s"); db.tasks().insert(AssistantTask("t", "s", "Call", 100, party = "OWNER", evidence = "synthetic", createdAt = 1, updatedAt = 1))
        db.finances().addAccount(FinanceAccount("a", "Cash"))
        db.finances().post(LedgerEntry("e", "a", "key", "EXPENSE", -900, "Food", "Fixture", 1, 1, "e"))
        val links = ContactLinks(db)
        val original = links.save(ContactChoice("Juma", handles = listOf("0712345678", "0754000111")))
        links.task("t", original); links.ledger("e", original)
        val separate = links.separate(original, "tel:754000111", "Juma", setOf("t"), setOf("e"))
        assertNotEquals(original, separate); assertEquals(separate, db.tasks().task("t")!!.personKey)
        assertEquals(separate, db.finances().entry("e")!!.personKey); assertEquals(-900L, db.finances().balance("a"))
    }
    @Test fun `failed commands and cancellation never report completed`() = runBlocking {
        assertFalse(runConfirmedCommand { require(false) { "Invalid date" }; "Done" }.completed)
        assertTrue(runConfirmedCommand { "Saved" }.completed)
        assertTrue(runCatching { runConfirmedCommand { throw CancellationException() } }.exceptionOrNull() is CancellationException)
    }
    @Test fun `review suggestions never post payments`() = runBlocking {
        source("s"); db.finances().addAccount(FinanceAccount("a", "Cash"))
        db.finances().candidate(FinanceCandidate("c", "s", provider = "MANUAL", reference = null, kind = "EXPENSE", amount = 100, fee = 0, merchant = null, evidence = "Fixture", occurredAt = 1))
        db.finances().suggestReview("c", "a", "Food")
        assertEquals("REVIEW", db.finances().findCandidate("c")!!.state); assertEquals("a", db.finances().findCandidate("c")!!.accountId)
        assertEquals(0, db.finances().entryCount())
    }
    @Test fun `inbox searches old saved bodies and pages beyond one hundred`() = runBlocking {
        repeat(130) { n -> source("s$n"); db.communications().message(com.hellfire.ass.communications.CommunicationMessage("m$n", "EMAIL_IMAP", "a", "x$n", "s$n", "thread", "sender", "owner", "", if (n == 0) "old needle" else "recent", n.toLong(), "INCOMING")) }
        assertEquals(100, db.communications().inbox("", 100).first().size)
        assertEquals(130, db.communications().inbox("", 200).first().size)
        assertEquals("m0", db.communications().inbox("needle", 100).first().single().id)
    }
    @Test fun `unanswered requests survive later greetings and respect thread snooze`() {
        val at = now.toEpochMilli(); val h = 3_600_000L
        val request = Interaction("mail:j@example.com", "j@example.com", "Email", true, at - 8*h, "Can you send the invoice?", "q", thread = "invoice")
        val greeting = request.copy(at = at - 6*h, preview = "Good morning", sourceId = "hello")
        val other = request.copy(at = at - h, incoming = false, preview = "Here is another topic", thread = "other")
        val owed = Waiting.compute(listOf(request, greeting, other), emptyMap(), at)
        assertEquals("q", owed.iOwe.single().sourceId)
        assertTrue(Waiting.compute(listOf(request), emptyMap(), at, mapOf("snooze:mail:j@example.com|invoice" to at + h)).iOwe.isEmpty())
    }
    @Test fun `recurring tasks choose the next future occurrence and configurable intensity`() {
        val task = AssistantTask("t", "s", "Daily", TaskTime.parse("2026-11-14 15:00"), party = "OWNER", evidence = "Fixture", state = "ACTIVE", createdAt = 1, updatedAt = 1, repeatRule = "DAILY", snoozeMinutes = 30)
        assertEquals("2026-11-16 15:00", TaskTime.display(TaskScheduler.nextOccurrence(task, TaskTime.parse("2026-11-16 12:00"))!!.dueAt))
        assertEquals(1440, TaskScheduler.repeatInterval(task.copy(alertProfile = "GENTLE"), 10))
        assertNull(TaskScheduler.nextOccurrence(task.copy(repeatRule = "NONE"), now.toEpochMilli()))
    }
    @Test fun `backup estimates support more than seven GiB and reject unsafe sizes`() {
        assertTrue(BackupReadiness.stagingBytes(8L * 1024 * 1024 * 1024, 1000) > 8L * 1024 * 1024 * 1024)
        assertTrue(runCatching { BackupReadiness.stagingBytes(-1, 0) }.isFailure)
        assertTrue(runCatching { BackupReadiness.stagingBytes(BackupReadiness.MAX_ORIGINALS + 1, 0) }.isFailure)
    }
    @Test fun `sharing rejects file paths and accepts only bounded provider uris`() {
        assertTrue(runCatching { SharedContent.files(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private/data"))) }.isFailure)
        assertEquals(listOf(Uri.parse("content://fixture/one")), SharedContent.files(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://fixture/one"))))
    }
    @Test fun `schema fifteen upgrades preserve contacts and set reminder defaults`() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); val name = "review-migrate.db"
        MigrationFixture.create(context, name, 15) { old ->
            old.execSQL("INSERT INTO person_contact VALUES ('n:asha','lookup','Asha',1)")
            old.execSQL("INSERT INTO person_alias VALUES ('tel:712345678','Asha',1)")
        }
        val migrated = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try { assertEquals(18, migrated.openHelper.writableDatabase.version); assertEquals("n:asha", migrated.people().all().single().personKey); assertEquals("lookup", migrated.contacts().all().single().lookupKey) }
        finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun `Kiswahili resources follow the explicit language choice`() {
        val app = RuntimeEnvironment.getApplication()
        try {
            com.hellfire.ass.ui.UiLanguage.code = "sw"
            val localized = com.hellfire.ass.ui.UiLanguage.context(app)
            assertEquals("Fedha", localized.getString(com.hellfire.ass.ui.UiLabels.id("Money")!!))
            assertEquals("Majukumu", localized.getString(com.hellfire.ass.ui.UiLabels.id("Tasks")!!))
            assertNull(com.hellfire.ass.ui.UiLabels.id("Synthetic private message"))
        } finally { com.hellfire.ass.ui.UiLanguage.code = "en" }
    }
    @Test fun `international identities do not collide on their last nine digits`() {
        assertNotEquals(Handles.key("+447123456789"), Handles.key("+337123456789"))
        assertEquals(Handles.key("+255712345678"), Handles.key("0712345678"))
        assertEquals(Handles.key("+447123456789"), Handles.key("447123456789@c.us"))
    }
    @Test fun `Unicode names and legacy restored debt links normalize consistently`() = runBlocking {
        db.people().save(PersonAlias("tel:712345678", "ÁSHA", 1))
        db.debts().insert(DebtEntry("d", "ÁSHA", 1000, "LENT", "Fixture", 1, createdAt = 1))
        ContactSchema.backfill(db.openHelper.writableDatabase)
        assertEquals("n:ásha", db.people().all().single().personKey)
        assertEquals("n:ásha", db.debts().all().single().personKey)
        assertEquals("n:ásha", db.contacts().all().single().personKey)
    }
    @Test fun `chunked archive size limits can grow without weakening individual import bounds`() {
        val context = RuntimeEnvironment.getApplication()
        val file = java.io.File.createTempFile("synthetic-review", ".hfm", context.cacheDir)
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = PayloadCipher(key)
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        try {
            assertTrue(runCatching { com.hellfire.ass.media.ChunkedMedia.write(bytes.inputStream(), file, java.util.UUID.randomUUID().toString(), false, cipher, maxBytes = 65_536) }.isFailure)
            val result = com.hellfire.ass.media.ChunkedMedia.write(bytes.inputStream(), file, java.util.UUID.randomUUID().toString(), false, cipher, maxBytes = BackupReadiness.MAX_ARCHIVE)
            com.hellfire.ass.media.ChunkedMedia.Reader(file, cipher, result.id, result.size).use { assertArrayEquals(bytes, it.stream().readBytes()) }
        } finally { file.delete() }
    }

    @Test fun `person filters distinguish saved identities and include associated fees`() = runBlocking {
        val people = listOf(PersonContact("p1", null, "Asha", 1), PersonContact("p2", null, "Juma", 1))
        val query = FinanceQuestion.parse("spending for person \"Asha\" this month", now, emptyList(), emptyList(), people = people)
        assertEquals("p1", query.personKey)
        assertTrue(runCatching { FinanceQuestion.parse("spending for person \"Asha\" today", now, emptyList(), emptyList(), people = people + PersonContact("p3", null, "Asha", 1)) }.isFailure)
        db.finances().addAccount(FinanceAccount("a", "Cash"))
        db.finances().post(LedgerEntry("e", "a", "k", "EXPENSE", -1000, "Food", "Asha", 1, 1, "e", personKey = "p1"))
        db.finances().post(LedgerEntry("f", "a", "f", "FEE", -100, "Fees", "Asha", 1, 1, "e"))
        assertEquals(1100L, FinanceRepository(db).summary(0, 100, personKey = "p1").expenses)
        assertEquals(0L, FinanceRepository(db).summary(0, 100, personKey = "p2").expenses)
    }

    @Test fun `done undo done reactivates one recurring successor`() = runBlocking {
        source("s")
        val task = AssistantTask("t", "s", "Daily", TaskTime.parse("2026-11-16 15:00"), party = "OWNER", evidence = "fixture", state = "ACTIVE", createdAt = 1, updatedAt = 1, repeatRule = "DAILY")
        db.tasks().insert(task)
        val next = TaskScheduler.retainNext(db.tasks(), task, now.toEpochMilli())!!
        db.tasks().close(next.id, "REJECTED", now.toEpochMilli())
        assertEquals(next.id, TaskScheduler.retainNext(db.tasks(), task, now.toEpochMilli())!!.id)
        assertEquals("ACTIVE", db.tasks().task(next.id)!!.state)
        assertEquals(2, db.tasks().all().size)
    }

}
