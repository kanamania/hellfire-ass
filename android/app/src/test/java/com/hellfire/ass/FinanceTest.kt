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
    @Test fun `a second source of the same reference waits as evidence instead of a second review item`() = runBlocking {
        db.finances().addAccount(FinanceAccount("a", "Synthetic wallet", "MPESA"))
        for (source in listOf("sms", "notification")) {
            db.events().insertCaptured(SourceEvent(source, externalId = source, packageName = "fixture", occurredAt = 1, receivedAt = 1,
                kind = "POSTED", payloadHash = "fixture", encryptedPayload = byteArrayOf(1), contentAvailable = true,
                contentRedacted = false, securitySensitive = false))
            repo.collect(source, 1, "M-PESA", "Umelipa TZS 15,000 ref SYNTHREF99 kwa duka")
        }
        val first = com.hellfire.ass.events.NotificationNormalizer.sha256("finance:sms:0")
        val second = com.hellfire.ass.events.NotificationNormalizer.sha256("finance:notification:0")
        assertEquals("DUPLICATE", db.finances().findCandidate(second)!!.state)
        assertEquals(1, db.finances().reviewCount())
        repo.approve(first, "a", "EXPENSE", "Food", "Synthetic purchase")
        assertEquals("DUPLICATE_LINKED", db.finances().findCandidate(second)!!.state)
        val entry = db.finances().byKey(com.hellfire.ass.events.NotificationNormalizer.sha256("a|MPESA|SYNTHREF99"))!!
        assertEquals(setOf("sms", "notification"), db.finances().sources(entry.id).toSet())
        assertEquals(1, db.finances().entryCount())
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
    @Test fun `overflow approvals fail without partial entries`() = runBlocking {
        db.finances().addAccount(FinanceAccount("a", "Synthetic source", "MPESA"))
        candidate("huge", fee = 1, amount = Long.MAX_VALUE)
        assertTrue(runCatching { repo.approve("huge", "a", "INCOME", "Income", "Synthetic") }.isFailure)
        assertEquals(0, db.finances().entryCount())
        db.finances().addAccount(FinanceAccount("b", "Other account", "NMB"))
        assertTrue(runCatching { repo.approve("huge", "b", "EXPENSE", "Other", "Synthetic") }.isFailure)
    }
    @Test fun `receipt source never restricts the owner's chosen account`() = runBlocking {
        db.finances().addAccount(FinanceAccount("wallet", "My wallet"))
        candidate("receipt")
        repo.approve("receipt", "wallet", "EXPENSE", "Transport", "Owner choice")
        assertEquals(-15000L, db.finances().balance("wallet"))
        assertEquals("MANUAL", db.finances().account("wallet")!!.provider)
    }

    @Test fun `cash account category and EAT date survive reload and approval`() = runBlocking {
        db.finances().addAccount(FinanceAccount("cash", "Cash"))
        db.finances().addAccount(FinanceAccount("other", "Other"))
        val at = com.hellfire.ass.tasks.TaskTime.parse("2026-11-14 00:15")
        candidate("cash-entry")
        val original = db.finances().findCandidate("cash-entry")!!
        db.finances().candidate(original.copy(id = "manual", position = 1, provider = "MANUAL", reference = null,
            occurredAt = at, accountId = "cash", category = "Transport"))
        val saved = db.finances().findCandidate("manual")!!
        assertEquals("cash", saved.accountId)
        assertEquals("Transport", saved.category)
        assertEquals("2026-11-14T00:15+03:00[Africa/Dar_es_Salaam]", java.time.Instant.ofEpochMilli(saved.occurredAt).atZone(com.hellfire.ass.tasks.TaskTime.zone).toString())
        repo.approve(saved.id, saved.accountId!!, saved.kind, saved.category!!, saved.evidence)
        assertEquals(-15000L, db.finances().balance("cash"))
        assertEquals(0L, db.finances().balance("other"))
        assertEquals(at, db.finances().allEntries().single().occurredAt)
    }

    @Test fun `v11 migration retains accounts and review evidence with unset new selections`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "finance-11-12.db"
        MigrationFixture.create(context, name, 11) { old ->
            old.execSQL("INSERT INTO finance_account (id, label, provider, currency, createdAt) VALUES ('a', 'Wallet', 'MPESA', 'TZS', 1)")
            old.execSQL("INSERT INTO source_event (id, source, externalId, packageName, occurredAt, receivedAt, kind, payloadHash, encryptedPayload, contentAvailable, contentRedacted, securitySensitive, ingestVersion, processingStatus) VALUES ('s', 'owner_entry', 's', 'fixture', 1, 1, 'POSTED', 'h', X'01', 1, 0, 0, 1, 'captured')")
            old.execSQL("INSERT INTO finance_candidate (id, sourceId, position, provider, reference, kind, amount, fee, merchant, evidence, occurredAt, state) VALUES ('c', 's', 0, 'MANUAL', NULL, 'EXPENSE', 3000, 0, NULL, 'Synthetic cash', 1, 'REVIEW')")
        }
        val migrated = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try {
            val saved = migrated.finances().findCandidate("c")!!
            assertEquals(3000L, saved.amount)
            assertEquals("Synthetic cash", saved.evidence)
            assertNull(saved.accountId); assertNull(saved.category)
            assertEquals("Wallet", migrated.finances().account("a")!!.label)
            assertEquals(EventDatabase.ALL_MIGRATIONS.last().endVersion, migrated.openHelper.writableDatabase.version)
        } finally { migrated.close(); context.deleteDatabase(name) }
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
