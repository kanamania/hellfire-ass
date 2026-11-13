package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.*
import com.hellfire.ass.finance.*
import com.hellfire.ass.ai.CandidateContracts
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FinanceTest {
    private lateinit var db: EventDatabase
    private lateinit var repo: FinanceRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        repo = FinanceRepository(db)
    }
    @After fun close() = db.close()
    private suspend fun candidate(id: String, ref: String? = "SYNTHETIC-REF", fee: Long = 0, amount: Long = 15000) {
        db.events().insertCaptured(SourceEvent(id, externalId = id, packageName = "fixture", occurredAt = 1, receivedAt = 1,
            kind = "POSTED", payloadHash = "fixture", encryptedPayload = byteArrayOf(1), contentAvailable = true,
            contentRedacted = false, securitySensitive = false))
        db.finances().candidate(FinanceCandidate(id, id, provider = "MPESA", reference = ref, kind = "EXPENSE",
            amount = amount, fee = fee, merchant = null, evidence = "Synthetic fixture", occurredAt = 1))
    }
    @Test fun `integer money rejects malformed fractional negative and overflowing values`() {
        assertEquals(15000, Money.tzs("15,000.00"))
        for (text in listOf("15,00", "-1", "0", "1.25", "1e6", "9223372036854775808")) {
            assertTrue(text, runCatching { Money.tzs(text) }.isFailure)
        }
    }
    @Test fun `source duplicates share one ledger entry and retain provenance`() = runBlocking {
        db.finances().addAccount(FinanceAccount("a", "Synthetic wallet", "MPESA"))
        candidate("sms"); candidate("notification")
        repo.approve("sms", "a", "EXPENSE", "Food", "Synthetic purchase")
        assertTrue(repo.approve("notification", "a", "EXPENSE", "Food", "Same purchase").contains("Duplicate"))
        assertEquals(-15000, db.finances().balance("a"))
        assertEquals(1, db.finances().entryCount())
        assertEquals("DUPLICATE_LINKED", db.finances().findCandidate("notification")!!.state)
        assertTrue(runCatching { db.events().deleteAll() }.isFailure)
    }
    @Test fun `fees are separate and own transfer credits balance the debit`() = runBlocking {
        db.finances().addAccount(FinanceAccount("a", "Synthetic source", "MPESA"))
        db.finances().addAccount(FinanceAccount("b", "Synthetic destination", "CASH"))
        candidate("transfer", fee = 500)
        repo.approve("transfer", "a", "OWN_TRANSFER", "Own transfer", "Synthetic", "b")
        assertEquals(-15500, db.finances().balance("a"))
        assertEquals(15000, db.finances().balance("b"))
        assertEquals(3, db.finances().entryCount())
        val original = db.finances().byKey(com.hellfire.ass.events.NotificationNormalizer.sha256("a|MPESA|SYNTHETIC-REF"))!!
        repo.reverse(original.id)
        assertEquals(0, db.finances().balance("a"))
        assertEquals(0, db.finances().balance("b"))
        assertEquals(6, db.finances().entryCount())
        assertTrue(runCatching { repo.reverse(original.id) }.isFailure)
    }
    @Test fun `overflow and wrong provider approvals fail without partial entries`() = runBlocking {
        db.finances().addAccount(FinanceAccount("a", "Synthetic source", "MPESA"))
        candidate("huge", fee = 1, amount = Long.MAX_VALUE)
        assertTrue(runCatching { repo.approve("huge", "a", "INCOME", "Income", "Synthetic") }.isFailure)
        assertEquals(0, db.finances().entryCount())
        db.finances().addAccount(FinanceAccount("b", "Other provider", "NMB"))
        assertTrue(runCatching { repo.approve("huge", "b", "EXPENSE", "Other", "Synthetic") }.isFailure)
    }
    @Test fun `approved edits preserve candidates and summaries exclude transfers but include fees refunds and reversals`() = runBlocking {
        db.finances().addAccount(FinanceAccount("a", "Synthetic wallet", "MPESA"))
        db.finances().addAccount(FinanceAccount("b", "Synthetic cash", "CASH"))
        candidate("expense", "EXPENSE")
        repo.approve("expense", "a", "EXPENSE", "Food", "Owner correction", amount = 7000, fee = 100, occurredAt = 10)
        assertEquals(15000, db.finances().findCandidate("expense")!!.amount)
        candidate("transfer", "TRANSFER", fee = 200)
        repo.approve("transfer", "a", "OWN_TRANSFER", "Transfer", "Own transfer", "b")
        candidate("refund", "REFUND", amount = 1000)
        repo.approve("refund", "a", "REFUND", "Food", "Refund")
        assertEquals(6300, repo.summary(0, 100).expenses)
        val first = db.finances().byKey(com.hellfire.ass.events.NotificationNormalizer.sha256("a|MPESA|EXPENSE"))!!
        repo.reverse(first.id)
        assertEquals(-800, repo.summary(0, Long.MAX_VALUE).expenses)
        assertTrue(repo.summary(0, Long.MAX_VALUE).sourceIds.contains("expense"))
    }
    @Test fun `candidate recognizers label synthetic provider samples and reject failures`() {
        for (provider in listOf("MPESA", "AIRTEL", "MIXX", "HALOPESA", "CRDB", "NMB", "NBC")) {
            val text = "Umelipa TZS 15,000.00 ref SYNTHETIC-123 ada TZS 500.00"
            val value = FinanceParser.parse(provider, text)!!
            assertEquals(provider, value.provider)
            assertEquals(15000, value.amount)
            assertEquals(500, value.fee)
            assertEquals("EXPENSE", value.kind)
            assertNull(FinanceParser.parse(provider, "Failed payment TZS 15000 ref SYNTHETIC-123"))
        }
        assertEquals("REFUND", FinanceParser.parse("MPESA", "Reversed payment TZS 15000 ref SYNTHETIC-123")!!.kind)
    }
    @Test fun `Swahili cash entries require complete parse and owner review`() {
        assertEquals(listOf("bodaboda" to 3000L, "chakula" to 7000L), CashParser.parse("Nimelipa bodaboda elfu tatu na chakula elfu saba"))
        assertTrue(runCatching { CashParser.parse("chakula maybe") }.isFailure)
    }
    @Test fun `model finance contract rejects floats unknown actions and invalid currency`() {
        val valid = """{"schema_version":1,"currency":"TZS","kind":"EXPENSE","provider":"MPESA","reference":"SYNTHETIC-123","amount_minor":15000,"fee_minor":0,"evidence":"Synthetic evidence"}"""
        assertEquals(15000, CandidateContracts.finance(valid).amount)
        for (bad in listOf(valid.replace("15000", "15000.5"), valid.replace("EXPENSE", "SEND_MONEY"), valid.replace("TZS", "USD"), valid + " trailing")) {
            assertTrue(runCatching { CandidateContracts.finance(bad) }.isFailure)
        }
    }
}
