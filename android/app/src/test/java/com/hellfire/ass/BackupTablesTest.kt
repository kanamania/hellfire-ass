package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.*
import com.hellfire.ass.finance.*
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.security.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.*
import java.util.zip.*
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupTablesTest {
    private lateinit var source: EventDatabase
    private lateinit var target: EventDatabase
    private fun cipher() = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    @Before fun setup() {
        source = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
    }
    @After fun close() { source.close(); target.close() }
    @Test fun `portable rows restore facts provenance and payloads with a different device key`() = runBlocking {
        val oldKey = cipher(); val newKey = cipher(); val payload = "Synthetic chanzo: Kiswahili — 中文".toByteArray()
        source.events().insertCaptured(SourceEvent("source", externalId = "fixture", packageName = "fixture", occurredAt = 1,
            receivedAt = 2, kind = "POSTED", payloadHash = "fixture-hash", encryptedPayload = oldKey.encrypt(payload),
            contentAvailable = true, contentRedacted = false, securitySensitive = false))
        source.finances().addAccount(FinanceAccount("account", "Synthetic cash", "CASH"))
        source.finances().candidate(FinanceCandidate("cash-review", "source", provider = "MANUAL", reference = null, kind = "EXPENSE",
            amount = 3000, fee = 0, merchant = null, evidence = "Synthetic cash", occurredAt = 1, accountId = "account", category = "Transport"))
        source.finances().post(LedgerEntry("entry", "account", "key", "EXPENSE", -7000, "Food", "Synthetic", 1, 2, "entry"))
        source.finances().evidence(LedgerEvidence("entry", "source"))
        source.memory().insert(MemoryItem("memory", "source", "Synthetic", "Direct source", "SOURCE_EXCERPT", 100, 1, 2))
        val archive = ByteArrayOutputStream()
        val expected = ZipOutputStream(archive).use { BackupTables.write(source.openHelper.writableDatabase, it, oldKey) }
        ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { zip ->
            for (table in BackupTables.tables) {
                assertEquals("tables/$table.jsonl", zip.nextEntry!!.name)
                assertEquals(expected[table], BackupTables.readTable(target.openHelper.writableDatabase, zip, table, newKey))
            }
        }
        val restored = target.events().find("source")!!
        assertArrayEquals(payload, newKey.decrypt(restored.encryptedPayload))
        assertTrue(runCatching { oldKey.decrypt(restored.encryptedPayload) }.isFailure)
        assertEquals(-7000L, target.finances().balance("account"))
        assertEquals("account", target.finances().findCandidate("cash-review")!!.accountId)
        assertEquals("Transport", target.finances().findCandidate("cash-review")!!.category)
        assertEquals(listOf("source"), target.finances().sources("entry"))
        assertEquals("SOURCE_EXCERPT", target.memory().item("memory")!!.kind)
        target.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    @Test fun `v11 cash review backups restore without account and category selections`() = runBlocking {
        target.events().insertCaptured(SourceEvent("s", externalId = "s", packageName = "fixture", occurredAt = 1,
            receivedAt = 1, kind = "POSTED", payloadHash = "fixture", encryptedPayload = byteArrayOf(1),
            contentAvailable = true, contentRedacted = false, securitySensitive = false))
        val row = """{"id":"c","sourceId":"s","position":0,"provider":"MANUAL","reference":null,"kind":"EXPENSE","amount":3000,"fee":0,"merchant":null,"evidence":"Synthetic cash","occurredAt":1,"state":"REVIEW"}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("tables/finance_candidate.jsonl"))
            zip.write((row + "\n").toByteArray()); zip.closeEntry()
        }
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            zip.nextEntry
            assertEquals(1L, BackupTables.readTable(target.openHelper.writableDatabase, zip, "finance_candidate", cipher()))
        }
        val restored = target.finances().findCandidate("c")!!
        assertEquals(3000L, restored.amount)
        assertNull(restored.accountId); assertNull(restored.category)
        assertEquals(BackupTables.tablesFor(13), BackupTables.tablesFor(11))
    }

    @Test fun `archives cannot invent SQL tables or columns`() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip -> zip.putNextEntry(ZipEntry("row")); zip.write("{\"id\":\"x\",\"injected\":\"DROP TABLE source_event\"}\n".toByteArray()); zip.closeEntry() }
        ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { zip ->
            zip.nextEntry
            assertThrows(IllegalArgumentException::class.java) { BackupTables.readTable(target.openHelper.writableDatabase, zip, "source_event", cipher()) }
        }
        assertTrue(runCatching { BackupTables.readTable(target.openHelper.writableDatabase, ZipInputStream(ByteArrayInputStream(byteArrayOf())), "source_event; DROP", cipher()) }.isFailure)
    }
    @Test fun `restoring drafts revokes approvals and preserves uncertainty without sending`() = runBlocking {
        val dao = target.communications()
        for (id in listOf("approved", "sending", "submitted")) {
            dao.draft(com.hellfire.ass.communications.OutboundDraft(id, "EMAIL_IMAP", "account", "test@example.invalid", "", "Synthetic", 1))
            dao.approve(id, "nonce", 100)
        }
        dao.claim("sending", "nonce", 0)
        dao.delivery("submitted", "SUBMITTED", "external", null)
        BackupTables.revokeRestoredApprovals(target.openHelper.writableDatabase)
        assertEquals("DRAFT", dao.draft("approved")!!.state)
        assertEquals("UNKNOWN", dao.draft("sending")!!.state)
        assertEquals("SUBMITTED", dao.draft("submitted")!!.state)
        assertNull(dao.draft("submitted")!!.approvalId)
        assertEquals(0, dao.claim("approved", "nonce", 0))
    }
    @Test fun `passphrase derivation and chunk authentication reject wrong passwords`() {
        val salt = ByteArray(16) { it.toByte() }
        val first = BackupCrypto.derive("synthetic-passphrase".toCharArray(), salt)
        val second = BackupCrypto.derive("synthetic-passphrase".toCharArray(), salt)
        val wrong = BackupCrypto.derive("different-passphrase".toCharArray(), salt)
        val encrypted = first.encrypt("Synthetic backup".toByteArray())
        assertEquals("Synthetic backup", second.decrypt(encrypted).toString(Charsets.UTF_8))
        assertTrue(runCatching { wrong.decrypt(encrypted) }.isFailure)
        assertTrue(runCatching { BackupCrypto.derive("short".toCharArray(), salt) }.isFailure)
    }
}
