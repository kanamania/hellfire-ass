package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.*
import com.hellfire.ass.finance.*
import com.hellfire.ass.ui.Fmt
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Logic added for the UI redesign (step 3): display formatting and the category-only ledger correction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RedesignLogicTest {
    private lateinit var db: EventDatabase
    private lateinit var repo: FinanceRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        repo = FinanceRepository(db)
    }
    @After fun close() = db.close()

    private suspend fun candidate(id: String, fee: Long = 0) {
        db.events().insertCaptured(SourceEvent(id, externalId = id, packageName = "fixture", occurredAt = 1, receivedAt = 1,
            kind = "POSTED", payloadHash = "fixture", encryptedPayload = byteArrayOf(1), contentAvailable = true,
            contentRedacted = false, securitySensitive = false))
        db.finances().candidate(FinanceCandidate(id, id, provider = "MPESA", reference = "REF-$id", kind = "EXPENSE",
            amount = 15000, fee = fee, merchant = null, evidence = "Synthetic fixture", occurredAt = 1))
    }

    @Test fun `amounts format with grouping, true minus and compact units`() {
        assertEquals("12,000", Fmt.amount(12000))
        assertEquals("−7,000", Fmt.amount(-7000))
        assertEquals("+150,000", Fmt.signed(150000))
        assertEquals("−20,000", Fmt.outflow(20000))
        assertEquals("452k", Fmt.compact(452000))
        assertEquals("2.62M", Fmt.compact(2620000))
        assertEquals("1.5k", Fmt.compact(1500))
        assertEquals("1M", Fmt.compact(1000000))
    }

    @Test fun `recategorising changes only the label, keeps the balance and is audited`() = runBlocking {
        db.finances().addAccount(FinanceAccount("a", "Synthetic wallet", "MPESA"))
        candidate("c1", fee = 300)
        repo.approve("c1", "a", "EXPENSE", "Other", "Synthetic purchase")
        val before = db.finances().balance("a")
        val main = db.finances().allEntries().single { it.kind == "EXPENSE" }
        repo.recategorize(main.id, "Food")
        assertEquals("Food", db.finances().entry(main.id)!!.category)
        assertEquals(before, db.finances().balance("a"))
        assertEquals(main.amount, db.finances().entry(main.id)!!.amount)
        assertEquals(1, db.communications().audits("OWNER_LEDGER_RECATEGORIZE").size)
    }

    @Test fun `fee and correction entries cannot be recategorised`() = runBlocking {
        db.finances().addAccount(FinanceAccount("a", "Synthetic wallet", "MPESA"))
        candidate("c1", fee = 300)
        repo.approve("c1", "a", "EXPENSE", "Other", "Synthetic purchase")
        val fee = db.finances().allEntries().single { it.kind == "FEE" }
        assertTrue(runCatching { repo.recategorize(fee.id, "Food") }.isFailure)
        val main = db.finances().allEntries().single { it.kind == "EXPENSE" }
        repo.reverse(main.id)
        val correction = db.finances().allEntries().first { it.reversesId == main.id }
        assertTrue(runCatching { repo.recategorize(correction.id, "Food") }.isFailure)
        assertEquals("Fees", db.finances().entry(fee.id)!!.category)
    }
}
