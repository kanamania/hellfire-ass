package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.finance.*
import com.hellfire.ass.reports.moneyTotals
import com.hellfire.ass.reports.posted
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.reports.totalsByDay
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Owner-stated account balances and the Money calendar totals (owner requests after step 7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BalanceAndCalendarTest {
    private lateinit var db: EventDatabase
    private lateinit var repo: FinanceRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        repo = FinanceRepository(db)
    }
    @After fun close() = db.close()
    private suspend fun evidence(id: String) = db.events().insertCaptured(SourceEvent(id, source = "owner_entry", externalId = id, packageName = "owner.balance",
        occurredAt = 1, receivedAt = 1, kind = "POSTED", payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true,
        contentRedacted = false, securitySensitive = false))

    @Test fun `opening and corrected balances post the difference with evidence and never count as income or spending`() = runBlocking {
        db.finances().addAccount(FinanceAccount("m", "M-Pesa", "MPESA"))
        evidence("e1"); evidence("e2"); evidence("e3")
        assertEquals(412_800L, repo.setBalance("m", 412_800, 1_000, "e1"))
        assertEquals("Opening balance", db.finances().allEntries().single().category)
        assertEquals(412_800L, db.finances().balance("m"))
        assertEquals(-12_000L, repo.setBalance("m", 400_800, 2_000, "e2"))
        assertEquals("Balance correction", db.finances().allEntries().last().category)
        assertEquals(400_800L, db.finances().balance("m"))
        assertEquals("unchanged balance adds nothing", 0L, repo.setBalance("m", 400_800, 3_000, "e3"))
        assertEquals(2, db.finances().entryCount())
        val summary = repo.summary(0, 10_000)
        assertEquals(0L, summary.income); assertEquals(0L, summary.expenses)
        val t = moneyTotals(posted(db.finances().allEntries(), db.finances().allAccounts()))
        assertEquals(0L, t.income); assertEquals(0L, t.expenses)
        assertEquals(2, db.communications().audits("OWNER_BALANCE_SET").size)
        db.finances().allEntries().forEach { assertEquals(1, db.finances().sources(it.id).size) }
    }

    @Test fun `a balance needs existing evidence and a known account`() = runBlocking {
        db.finances().addAccount(FinanceAccount("m", "M-Pesa", "MPESA"))
        assertTrue(runCatching { repo.setBalance("m", 1_000, 1, "missing") }.isFailure)
        evidence("e1")
        assertTrue(runCatching { repo.setBalance("nope", 1_000, 1, "e1") }.isFailure)
        assertEquals(0, db.finances().entryCount())
    }

    @Test fun `a balance as of a past time accounts for later entries`() = runBlocking {
        db.finances().addAccount(FinanceAccount("m", "M-Pesa", "MPESA"))
        evidence("e1")
        db.finances().post(LedgerEntry("x", "m", "k", "EXPENSE", -5_000, "Food", "Later", 5_000, 5_000, "x"))
        repo.setBalance("m", 100_000, 1_000, "e1")
        assertEquals("balance at the stated time, then the later expense", 95_000L, db.finances().balance("m"))
    }

    @Test fun `calendar totals split spending and income by East Africa day`() {
        val d1 = LocalDate.of(2026, 11, 13); val d2 = d1.plusDays(1)
        fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
        val accounts = listOf(FinanceAccount("m", "M-Pesa", "MPESA"))
        val entries = listOf(
            LedgerEntry("a", "m", "a", "EXPENSE", -7_000, "Food", "Lunch", at(d1, 23), 0, "a"),
            LedgerEntry("b", "m", "b", "INCOME", 150_000, "Income", "Neema", at(d2, 0), 0, "b"),
            LedgerEntry("c", "m", "c", "FEE", -300, "Fees", "Fee", at(d2, 9), 0, "c"),
            LedgerEntry("d", "m", "d", "BALANCE", 50_000, "Opening balance", "Opening", at(d2, 10), 0, "d"),
        )
        val days = totalsByDay(posted(entries, accounts))
        assertEquals(7_000L, days[d1]!!.spent); assertEquals(0L, days[d1]!!.received)
        assertEquals("fees and balances are not spending or income", 0L, days[d2]!!.spent)
        assertEquals(150_000L, days[d2]!!.received)
    }
}
