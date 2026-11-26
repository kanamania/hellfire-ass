package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.calendar.*
import com.hellfire.ass.data.*
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.security.*
import com.hellfire.ass.tasks.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.*
import java.util.zip.*
import javax.crypto.KeyGenerator
import android.provider.CalendarContract.Events

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalendarIntegrationTest {
    private lateinit var db: EventDatabase
    private lateinit var repo: CalendarRepository
    private lateinit var provider: FakeCalendar
    private lateinit var cipher: PayloadCipher
    private var now = 1_800_000_000_000L
    private lateinit var source: String
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val events = EventRepository(db.events()) { cipher }
        provider = FakeCalendar()
        repo = CalendarRepository(db, events, provider, "com.hellfire.ass") { now }
        source = events.store(NotificationSnapshot("fixture", "fixture.sender", now, now, source = "synthetic_calendar_test", fields = mapOf("text" to listOf("Meet tomorrow"))), "com.hellfire.ass")!!.id
        db.tasks().insert(AssistantTask("task", source, "Synthetic meeting", now + 60_000, party = "OWNER", evidence = "Meet tomorrow", state = "ACTIVE", createdAt = now, updatedAt = now))
        Unit
    }
    @After fun close() { db.close() }
    private suspend fun prepare(op: String = "CREATE") = repo.prepare("task", op, 7, "Synthetic meeting", now + 60_000, now + 3_660_000, "Africa/Dar_es_Salaam")
    private suspend fun apply(change: CalendarChange) = repo.applyApproved(change.id, repo.approve(change.id))
    private suspend fun expectFailure(block: suspend () -> Unit) { assertTrue(runCatching { block() }.isFailure) }

    @Test fun `creation needs fresh owner approval and is never replayed`() = runBlocking {
        val c = prepare()
        expectFailure { repo.applyApproved(c.id, "invented") }
        assertEquals(0, provider.writes)
        val approval = repo.approve(c.id)
        expectFailure { repo.applyApproved(c.id, "wrong") }
        assertEquals("APPLIED", repo.applyApproved(c.id, approval))
        expectFailure { repo.applyApproved(c.id, approval) }
        expectFailure { prepare() }
        assertEquals(1, provider.writes)
        assertEquals(source, db.calendar().change(c.id)!!.sourceId)
        assertEquals("LINKED", db.calendar().link("task")!!.state)
        assertEquals(1, db.communications().audits("OWNER_APPROVAL").size)
        assertEquals(2, db.communications().audits("CALENDAR_CREATE").size)
    }
    @Test fun `two approved create proposals for the same task cannot duplicate its event`() = runBlocking {
        val first = prepare(); val second = prepare()
        assertEquals("APPLIED", apply(first))
        assertTrue(apply(second).startsWith("NOT_APPLIED"))
        assertEquals(1, provider.writes)
    }
    @Test fun `morning and evening briefs include local appointments and overlap warnings`() {
        val morning = TaskTime.parse("2026-11-25 08:00")
        val a = CalendarEvent(1, 7, "Morning fixture", morning + 1000, morning + 5000, sourceId = source)
        val b = a.copy(id = 2, title = "Overlap fixture", start = morning + 2000)
        val tomorrow = a.copy(id = 3, title = "Tomorrow fixture", start = morning + 86_400_000, end = morning + 86_405_000)
        val i = com.hellfire.ass.insights.BriefInputs(false, morning, emptyList(), 0, 0, 0, 0,
            com.hellfire.ass.insights.WaitingOn(emptyList(), emptyList()), emptyList(), com.hellfire.ass.insights.BillForecast(emptyList(), 0, 0, 0, 0), emptyList(),
            CalendarAgenda("AVAILABLE", listOf(a, b, tomorrow)))
        val brief = com.hellfire.ass.insights.Briefs.build(i)
        assertTrue(brief.text().contains("Morning fixture"))
        assertTrue(brief.sections.first { it.title == "Today's appointments" }.lines.all { it.warn && it.link == "hellfire://source/$source" })
        val evening = com.hellfire.ass.insights.Briefs.build(i.copy(evening = true))
        assertEquals("Tomorrow fixture", evening.sections.first { it.title == "Tomorrow's appointments" }.lines.single().text.substringAfter(" · "))
    }
    @Test fun `review tasks invalid dates destinations and operations cannot be proposed`() = runBlocking {
        expectFailure { repo.prepare("task", "SEND", 7, "title", now, now + 100, "UTC") }
        expectFailure { repo.prepare("task", "CREATE", 99, "title", now, now + 100, "UTC") }
        expectFailure { repo.prepare("task", "CREATE", 7, "", now, now + 100, "UTC") }
        expectFailure { repo.prepare("task", "CREATE", 7, "title", now, now, "UTC") }
        expectFailure { repo.prepare("task", "CREATE", 7, "title", now, now + 100, "invented/zone") }
        db.tasks().close("task", "REVIEW", ++now)
        expectFailure { prepare() }
        assertEquals(0, provider.writes)
    }
    @Test fun `proposal and approval expire after five minutes`() = runBlocking {
        val first = prepare(); now += 300_001
        expectFailure { repo.approve(first.id) }
        val c = prepare(); val token = repo.approve(c.id); now += 300_001
        expectFailure { repo.applyApproved(c.id, token) }
        assertEquals(0, provider.writes)
    }
    @Test fun `task edits calendar renames and revoked write permission invalidate pending writes`() = runBlocking {
        val c = prepare(); val token = repo.approve(c.id)
        db.tasks().setPerson("task", null, now + 1)
        assertTrue(repo.applyApproved(c.id, token).startsWith("NOT_APPLIED"))
        assertEquals(0, provider.writes)
        repo.reconcile("task")
        val next = prepare(); provider.choices = listOf(DeviceCalendar(7, "Changed account"))
        assertTrue(apply(next).startsWith("NOT_APPLIED")); assertEquals(0, provider.writes)
        repo.reconcile("task"); provider.choices = listOf(DeviceCalendar(7, "Synthetic local calendar"))
        val denied = prepare(); provider.write = false
        assertTrue(apply(denied).startsWith("NOT_APPLIED")); assertEquals(0, provider.writes)
    }
    @Test fun `edit and cancellation each have their own approval provenance and audit`() = runBlocking {
        apply(prepare())
        val edit = prepare("UPDATE")
        expectFailure { repo.applyApproved(edit.id, "missing") }
        assertEquals("APPLIED", apply(edit))
        val cancel = prepare("CANCEL")
        assertEquals("APPLIED", apply(cancel))
        assertEquals("CANCELED", db.calendar().link("task")!!.state)
        assertEquals(Events.STATUS_CANCELED, provider.rows.values.single().status)
        assertEquals(3, provider.writes)
        for (action in listOf("CALENDAR_CREATE", "CALENDAR_UPDATE", "CALENDAR_CANCEL")) assertEquals(2, db.communications().audits(action).size)
    }
    @Test fun `provider success followed by failure is reconciled without another write`() = runBlocking {
        val c = prepare(); provider.failAfter = true
        assertTrue(apply(c).startsWith("UNKNOWN"))
        expectFailure { prepare() }
        assertEquals("Calendar result verified.", repo.reconcile("task"))
        assertEquals("APPLIED", db.calendar().change(c.id)!!.state)
        assertEquals(1, provider.writes)
    }
    @Test fun `process restart revokes approval and marks in flight writes unconfirmed`() = runBlocking {
        val c = prepare(); val token = repo.approve(c.id)
        repo.recover()
        expectFailure { repo.applyApproved(c.id, token) }
        val fresh = repo.approve(c.id)
        db.calendar().claim(c.id, fresh, now - 1)
        provider.apply(c)
        repo.recover()
        assertEquals("UNKNOWN", db.calendar().change(c.id)!!.state)
        repo.reconcile("task")
        assertEquals(1, provider.writes)
    }
    @Test fun `stale provider edit is refused and requires a new preview`() = runBlocking {
        apply(prepare())
        val c = prepare("UPDATE")
        val old = provider.rows.values.single()
        provider.rows[old.id] = old.copy(title = "Changed elsewhere")
        assertTrue(apply(c).startsWith("NOT_APPLIED"))
        assertEquals("APPLIED", apply(prepare("UPDATE")))
        assertEquals(2, provider.writes)
    }
    @Test fun `agenda captures encrypted source snapshots and redacts security content`() = runBlocking {
        provider.agenda = CalendarAgenda("AVAILABLE", listOf(CalendarEvent(1, 7, "OTP verification 123456", now, now + 1000), CalendarEvent(2, 7, "Synthetic appointment", now + 500, now + 2000)))
        val agenda = repo.agenda(now - 1, now + 10_000)
        assertFalse(agenda.text().contains("123456"))
        assertTrue(agenda.text().contains("OVERLAP"))
        val event = db.events().find(agenda.events[0].sourceId!!)!!
        assertTrue(event.securitySensitive)
        assertFalse(String(cipher.decrypt(event.encryptedPayload)).contains("123456"))
        assertEquals(agenda.events.map { it.sourceId }, repo.agenda(now - 1, now + 10_000).events.map { it.sourceId })
        db.events().finish(source, "DONE", "FIXTURE", null, now)
        var finance = 0; var tasks = 0
        com.hellfire.ass.processing.EventProcessor(db.events(), { _, _, _, _ -> finance++ }, { _, _, _, _ -> tasks++ }) { cipher }.drain()
        assertEquals(0, finance); assertEquals(0, tasks)
        provider.throwRead = true
        assertEquals("READ_PERMISSION_MISSING", repo.agenda(now - 1, now + 10_000).status)
    }
    @Test fun `half open overlaps ignore free all day and adjacent instances`() {
        val a = CalendarEvent(1, 7, "A", 10, 20)
        val b = CalendarEvent(2, 7, "B", 20, 30)
        assertTrue(CalendarAgendaLogic.conflicts(listOf(a, b, a.copy(id = 3, allDay = true), a.copy(id = 4, busy = false))).isEmpty())
        val c = a.copy(id = 5, start = 19, end = 21)
        assertEquals(setOf(a, b, c), CalendarAgendaLogic.conflicts(listOf(a, b, c)))
        val dayStart = TaskTime.parse("2026-11-25 00:00")
        val allDay = CalendarEvent(1, 7, "Day", java.time.Instant.parse("2026-11-25T00:00:00Z").toEpochMilli(), java.time.Instant.parse("2026-11-26T00:00:00Z").toEpochMilli(), allDay = true)
        assertTrue(CalendarAgendaLogic.inWindow(allDay, dayStart, dayStart + 86_400_000))
        assertFalse(CalendarAgendaLogic.inWindow(allDay, dayStart - 86_400_000, dayStart))
    }
    @Test fun `schema 20 migrates with task provenance preserved`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "calendar-v20-test"
        MigrationFixture.create(context, name, 20) { old ->
            old.execSQL("INSERT INTO source_event (id,source,externalId,packageName,occurredAt,receivedAt,kind,payloadHash,encryptedPayload,contentAvailable,contentRedacted,securitySensitive,ingestVersion,processingStatus) VALUES ('legacy-source','synthetic_calendar_test','legacy-external','fixture',1,1,'POSTED','hash',X'010203',1,0,0,1,'captured')")
            old.execSQL("INSERT INTO assistant_task (id,sourceId,title,dueAt,timezone,party,evidence,state,createdAt,updatedAt,alertCount) VALUES ('legacy-task','legacy-source','Existing commitment',2,'Africa/Dar_es_Salaam','OWNER','Synthetic evidence','ACTIVE',1,1,0)")
        }
        val migrated = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try {
            assertNull(migrated.calendar().link("none"))
            assertEquals("legacy-source", migrated.tasks().task("legacy-task")!!.sourceId)
            assertEquals("Existing commitment", migrated.tasks().task("legacy-task")!!.title)
            assertArrayEquals(byteArrayOf(1, 2, 3), migrated.events().find("legacy-source")!!.encryptedPayload)
            assertEquals(23, migrated.openHelper.writableDatabase.version)
            assertTrue(BackupTables.tablesFor(20).none { it.startsWith("calendar_") })
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun `calendar backup retains history disables old device links and refuses unknown columns`() = runBlocking {
        apply(prepare()); val edit = prepare("UPDATE"); repo.approve(edit.id)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { BackupTables.write(db.openHelper.writableDatabase, it, cipher) }
        val target = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        try {
            ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip -> BackupTables.tables.forEach { table ->
                assertEquals("tables/$table.jsonl", zip.nextEntry!!.name)
                BackupTables.readTable(target.openHelper.writableDatabase, zip, table, cipher)
            } }
            BackupTables.revokeRestoredApprovals(target.openHelper.writableDatabase)
            assertEquals("RESTORED", target.calendar().link("task")!!.state)
            assertEquals("RESTORED", target.calendar().change(edit.id)!!.state)
            assertNull(target.calendar().change(edit.id)!!.approvalId)
            val malformed = ByteArrayOutputStream()
            ZipOutputStream(malformed).use { it.putNextEntry(ZipEntry("table")); it.write("{\"taskId\":\"task\",\"unknown\":1}\n".toByteArray()) }
            ZipInputStream(ByteArrayInputStream(malformed.toByteArray())).use { zip -> zip.nextEntry
                expectFailure { BackupTables.readTable(target.openHelper.writableDatabase, zip, "calendar_link", cipher) }
            }
        } finally { target.close() }
    }

    private class FakeCalendar : CalendarProvider {
        var write = true
        var failAfter = false
        var throwRead = false
        var writes = 0
        var choices = listOf(DeviceCalendar(7, "Synthetic local calendar"))
        var agenda = CalendarAgenda("AVAILABLE")
        val rows = mutableMapOf<Long, ProviderEvent>()
        override fun canRead() = !throwRead
        override fun canWrite() = write
        override fun calendars() = choices
        override fun instances(start: Long, end: Long): CalendarAgenda { if (throwRead) throw SecurityException(); return agenda }
        override fun event(id: Long) = rows[id]
        override fun find(marker: String) = rows.values.filter { it.marker == marker }
        override fun apply(change: CalendarChange): Long {
            check(write)
            val id = change.eventId ?: 100L
            if (change.operation != "CREATE") require(rows[id]?.fingerprint == change.expectedFingerprint)
            writes++
            rows[id] = ProviderEvent(id, change.calendarId, change.title, change.start, change.end, change.timezone, "Source link", change.marker,
                "com.hellfire.ass", if (change.operation == "CANCEL") Events.STATUS_CANCELED else Events.STATUS_CONFIRMED, false, false, false)
            if (failAfter) error("Synthetic failure after write")
            return id
        }
    }
}
