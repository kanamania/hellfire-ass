package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.finance.FinanceParser
import com.hellfire.ass.finance.ProviderBalance
import com.hellfire.ass.security.BackupTables
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.widgets.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

/** Room v11 and the data behind the home screen widgets (§8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WidgetDataTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `migration 10 to 11 adds promise fields, backfills direction from messages and creates budget and balance tables`() = runBlocking {
        val name = "migration-10-11.db"
        MigrationFixture.create(context, name, 10) { db ->
            for ((id, source) in listOf("sms" to "android_sms", "mine" to "owner_entry"))
                db.execSQL("INSERT INTO source_event (id, source, externalId, packageName, occurredAt, receivedAt, kind, payloadHash, encryptedPayload, contentAvailable, contentRedacted, securitySensitive, ingestVersion, processingStatus) VALUES ('$id', '$source', '$id', 'p', 1, 1, 'POSTED', 'h', X'01', 1, 0, 0, 1, 'captured')")
            for ((id, source, party) in listOf(Triple("owe", "sms", "OWNER"), Triple("owed", "sms", "OTHER"), Triple("unclear", "sms", "UNKNOWN"), Triple("typed", "mine", "OWNER")))
                db.execSQL("INSERT INTO assistant_task (id, sourceId, title, dueAt, timezone, party, evidence, state, createdAt, updatedAt, alertCount) VALUES ('$id', '$source', 'T', 100, 'Africa/Dar_es_Salaam', '$party', 'T', 'ACTIVE', 1, 1, 0)")
        }
        val db = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals("I_OWE", db.tasks().task("owe")!!.direction)
            assertEquals("OWED_TO_ME", db.tasks().task("owed")!!.direction)
            assertEquals("NONE", db.tasks().task("unclear")!!.direction)
            assertEquals("owner-typed tasks are not promises by default", "NONE", db.tasks().task("typed")!!.direction)
            db.tasks().task("owe")!!.let { assertEquals("TASK", it.kind); assertNull(it.amount); assertFalse(it.repeatMonthly) }
            db.widgetData().budget(com.hellfire.ass.finance.CategoryBudget("Food", "2026-11", 400_000))
            assertEquals(400_000L, db.widgetData().budgetsFor("2026-11").single().amount)
            db.widgetData().observeBalance(ProviderBalance("MPESA", 100, 10, "sms"))
            assertEquals(EventDatabase.ALL_MIGRATIONS.last().endVersion, db.openHelper.writableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `balances are read from Kiswahili and English provider messages`() {
        assertEquals("MPESA" to 412_800L, FinanceParser.balance("MPESA", "TSh 12,000.00 paid to Lipa namba 5543210. Salio jipya ni TSh 412,800.00"))
        assertEquals("MPESA" to 562_800L, FinanceParser.balance("M-PESA", "You have received TSh 150,000.00. New balance TSh 562,800"))
        assertEquals("AIRTEL" to 96_200L, FinanceParser.balance("AirtelMoney", "Airtel Money: You have paid TSh 20,000. Balance TSh 96,200"))
        assertNull("no provider", FinanceParser.balance("Shop", "Balance TSh 5,000"))
        assertNull("no balance", FinanceParser.balance("MPESA", "You have paid TSh 5,000"))
    }

    @Test fun `an older or replayed SMS never overwrites a newer balance`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        try {
            db.widgetData().observeBalance(ProviderBalance("MPESA", 500, 200, "new"))
            db.widgetData().observeBalance(ProviderBalance("MPESA", 900, 100, "old"))
            assertEquals(500L, db.widgetData().balance("MPESA")!!.amount)
            db.widgetData().observeBalance(ProviderBalance("MPESA", 700, 300, "newer"))
            assertEquals(700L, db.widgetData().balance("MPESA")!!.amount)
        } finally { db.close() }
    }

    @Test fun `only an active repeating bill schedules next month's bill`() {
        val due = LocalDate.of(2026, 11, 30).atTime(9, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
        val bill = AssistantTask("rent", "s", "Rent", due, party = "OWNER", evidence = "Rent", state = "ACTIVE", createdAt = 0, updatedAt = 0,
            kind = "BILL", repeatMonthly = true, amount = 600_000)
        val next = TaskScheduler.nextBill(bill, due)!!
        assertEquals(LocalDate.of(2026, 12, 30).atTime(9, 0).atZone(TaskTime.zone).toInstant().toEpochMilli(), next.dueAt)
        assertEquals(600_000L, next.amount); assertEquals("ACTIVE", next.state); assertNotEquals(bill.id, next.id)
        assertNull(TaskScheduler.nextBill(bill.copy(repeatMonthly = false), due))
        assertNull(TaskScheduler.nextBill(bill.copy(kind = "TASK"), due))
        assertNull(TaskScheduler.nextBill(bill.copy(state = "REVIEW"), due))
    }

    @Test fun `widget rules - evening switch, pace, health, categories and masking`() {
        fun at(h: Int) = LocalDate.of(2026, 11, 13).atTime(h, 5).atZone(TaskTime.zone).toInstant().toEpochMilli()
        assertFalse(isEvening(at(20))); assertTrue(isEvening(at(21)))
        val mid = LocalDate.of(2026, 11, 13)
        assertEquals(Pace.NoBudget, budgetPace(100, null, mid))
        assertEquals(Pace.Ahead, budgetPace(312_000, 400_000, mid))
        assertEquals(Pace.OnPace, budgetPace(170_000, 400_000, mid))
        assertEquals(Pace.Over, budgetPace(410_000, 400_000, mid))
        assertEquals(Health.Good, captureHealth(3, 3)); assertEquals(Health.Partial, captureHealth(4, 3)); assertEquals(Health.Bad, captureHealth(2, 0))
        assertEquals(listOf("Food" to 5L, "Transport" to 4L, "Family" to 3L, "Airtime" to 2L, "Other" to 3L),
            topCategories(mapOf("Food" to 5L, "Transport" to 4L, "Family" to 3L, "Airtime" to 2L, "Rent" to 2L, "Fuel" to 1L, "Refund" to -5L)))
        assertEquals(MASK, masked("12,000", false)); assertEquals("12,000", masked("12,000", true))
    }

    @Test fun `widget privacy starts hidden and hide-all wins`() {
        assertFalse(WidgetPrefs.visible(context, "money"))
        WidgetPrefs.setShows(context, "money", true); assertTrue(WidgetPrefs.visible(context, "money"))
        WidgetPrefs.setHideAll(context, true); assertFalse(WidgetPrefs.visible(context, "money"))
        WidgetPrefs.revealWallets(context, System.currentTimeMillis() + 60_000); assertFalse(WidgetPrefs.walletsRevealed(context))
        WidgetPrefs.setHideAll(context, false); assertTrue(WidgetPrefs.walletsRevealed(context))
        assertFalse(WidgetPrefs.walletsRevealed(context, System.currentTimeMillis() + 120_000))
    }

    @Test fun `schema 10 backups restore into v11 with default promise fields`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        try {
            db.events().insertCaptured(com.hellfire.ass.data.SourceEvent("s", externalId = "s", packageName = "p", occurredAt = 1, receivedAt = 1, kind = "POSTED",
                payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
            val row = """{"id":"t","sourceId":"s","title":"Pay","dueAt":1,"timezone":"Africa/Dar_es_Salaam","party":"OWNER","evidence":"Pay","state":"ACTIVE","createdAt":1,"updatedAt":2,"nextAlertAt":null,"lastAlertAt":null,"alertCount":0,"completedAt":null}"""
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("t")); it.write((row + "\n").toByteArray()); it.closeEntry() }
            val zip = ZipInputStream(ByteArrayInputStream(out.toByteArray())).also { it.nextEntry }
            BackupTables.readTable(db.openHelper.writableDatabase, zip, "assistant_task", PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
            db.tasks().task("t")!!.let { assertEquals("NONE", it.direction); assertEquals("TASK", it.kind); assertFalse(it.repeatMonthly) }
            assertEquals(listOf("category_budget", "provider_balance"), BackupTables.tablesFor(13) - BackupTables.tablesFor(10).toSet())
            assertEquals(14, BackupTables.SCHEMA)
        } finally { db.close() }
    }
}
