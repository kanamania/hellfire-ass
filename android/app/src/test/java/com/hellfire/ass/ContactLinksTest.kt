package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.contacts.*
import com.hellfire.ass.data.*
import com.hellfire.ass.finance.*
import com.hellfire.ass.insights.*
import com.hellfire.ass.security.*
import com.hellfire.ass.tasks.AssistantTask
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
class ContactLinksTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build() }
    @After fun close() { db.close() }
    private suspend fun seed() {
        db.events().insertCaptured(SourceEvent("s", externalId = "synthetic", packageName = "fixture", occurredAt = 1, receivedAt = 2,
            kind = "POSTED", payloadHash = "fixture", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
        db.tasks().insert(AssistantTask("t", "s", "Synthetic task", 100, party = "OWNER", evidence = "fixture", createdAt = 1, updatedAt = 1))
        db.finances().addAccount(FinanceAccount("a", "Synthetic cash", "CASH"))
        db.finances().post(LedgerEntry("e", "a", "synthetic-key", "EXPENSE", -5000, "Food", "Synthetic", 1, 2, "e"))
    }
    @Test fun `migration 14 to 15 preserves populated rows balances and defaults links to null`() = runBlocking {
        val name = "synthetic-14-15.db"
        MigrationFixture.create(context, name, 14) { old ->
            old.execSQL("INSERT INTO finance_account VALUES ('a','Synthetic cash','CASH','TZS',1)")
            old.execSQL("INSERT INTO ledger_entry VALUES ('e','a','key','EXPENSE',-5000,'Food','Synthetic',1,2,'e',NULL)")
            old.execSQL("INSERT INTO source_event VALUES ('s','owner_entry','synthetic','fixture',1,2,'POSTED','hash',X'01',1,0,0,1,'captured')")
            old.execSQL("INSERT INTO assistant_task (id,sourceId,title,dueAt,timezone,party,evidence,state,createdAt,updatedAt,nextAlertAt,lastAlertAt,alertCount) VALUES ('t','s','Synthetic',100,'Africa/Dar_es_Salaam','OWNER','fixture','ACTIVE',1,1,NULL,NULL,0)")
            old.execSQL("INSERT INTO person_alias VALUES ('tel:712345678','Synthetic Asha',1)")
        }
        val migrated = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals(15, migrated.openHelper.writableDatabase.version)
            assertEquals(-5000L, migrated.finances().balance("a"))
            assertNull(migrated.finances().entry("e")!!.personKey)
            assertNull(migrated.tasks().task("t")!!.personKey)
            assertEquals("Synthetic Asha", migrated.people().all().single().name)
            assertTrue(migrated.contacts().all().isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun `two numbers and an email join SMS WhatsApp and email with the existing normalizer`() = runBlocking {
        val key = ContactLinks(db).save(ContactChoice("Synthetic Asha", "lookup-a", listOf("+255 712 345 678", "0687654321", "INFO@example.invalid")))
        val aliases = db.people().all().associate { it.handle to it.name }
        assertEquals(setOf("tel:712345678", "tel:687654321", "mail:info@example.invalid"), aliases.keys)
        val messages = listOf("0712345678" to "SMS", "255687654321@c.us" to "WhatsApp", "Asha <info@example.invalid>" to "Email").mapIndexed { i, (raw, channel) ->
            Interaction(Handles.key(raw)!!, raw, channel, true, i.toLong(), "fixture", "s$i")
        }
        val person = People.build(messages, aliases).single()
        assertEquals(key, person.key)
        assertEquals(3, person.incoming)
        assertEquals(setOf("SMS", "WhatsApp", "Email"), person.channels)
        assertEquals("lookup-a", db.contacts().find(key)!!.lookupKey)
    }
    @Test fun `editing and clearing person links changes no money and audits each actual change`() = runBlocking {
        seed()
        val links = ContactLinks(db)
        val first = links.save(ContactChoice("Synthetic Asha", "a"))
        val second = links.save(ContactChoice("Synthetic Juma", "b"))
        val before = db.finances().entry("e")!!
        links.ledger("e", first); links.ledger("e", second); links.ledger("e", second); links.ledger("e", null)
        links.task("t", first); links.task("t", null)
        assertEquals(before, db.finances().entry("e"))
        assertEquals(-5000L, db.finances().balance("a"))
        assertNull(db.tasks().task("t")!!.personKey)
        assertEquals(3, db.communications().audits("OWNER_LEDGER_PERSON").size)
        assertEquals(2, db.communications().audits("OWNER_TASK_PERSON").size)
    }
    @Test fun `approved payment and duplicate evidence retain the selected person without a second debit`() = runBlocking {
        seed()
        val links = ContactLinks(db)
        val key = links.save(ContactChoice("Synthetic vendor", "vendor"))
        val repository = FinanceRepository(db)
        for (id in listOf("c1", "c2")) {
            db.finances().candidate(FinanceCandidate(id, "s", position = if (id == "c1") 1 else 2, provider = "MPESA", reference = "fixture-ref",
                kind = "EXPENSE", amount = 2000, fee = 0, merchant = "123456", evidence = "Synthetic", occurredAt = 1))
            repository.approve(id, "a", "EXPENSE", "Food", "Synthetic", personKey = key)
        }
        assertEquals(-7000L, db.finances().balance("a"))
        assertEquals(2, db.finances().entryCount())
        assertEquals(key, db.finances().allEntries().single { it.personKey != null }.personKey)
        assertEquals(1, db.communications().audits("OWNER_LEDGER_PERSON").size)
    }

    @Test fun `typed names need no contact access and preserve an existing selected contact`() = runBlocking {
        val links = ContactLinks(db)
        val key = links.save(ContactChoice("  Synthetic Asha  "))
        assertEquals("n:synthetic asha", key)
        assertNull(db.contacts().find(key)!!.lookupKey)
        assertTrue(db.people().all().isEmpty())
        links.save(ContactChoice("Synthetic Asha", "lookup-a", listOf("0712345678")))
        links.save(ContactChoice("Synthetic Asha"))
        assertEquals("lookup-a", db.contacts().find(key)!!.lookupKey)
        assertEquals(1, db.people().all().size)
    }
    @Test fun `naming a conversation preserves explicit task payment and debt associations`() = runBlocking {
        seed()
        val links = ContactLinks(db)
        val old = links.save(ContactChoice("Old name"))
        links.task("t", old); links.ledger("e", old)
        db.debts().insert(DebtEntry("d", "Old name", 5000, "LENT", "fixture", 1, createdAt = 1))
        val key = links.namePerson(old, ContactChoice("Synthetic Asha", "lookup-a", listOf("0687654321")), listOf("tel:712345678"), "Old name")
        assertEquals(key, db.tasks().task("t")!!.personKey)
        assertEquals(key, db.finances().entry("e")!!.personKey)
        assertEquals("Synthetic Asha", db.debts().all().single().person)
        assertEquals(-5000L, db.finances().balance("a"))
        assertEquals(2, db.people().all().size)
        assertNull(db.contacts().find(old))
        assertEquals(1, db.communications().audits("OWNER_PERSON_RENAME").size)
    }
    @Test fun `schema 15 backup round trip retains only selected contact links and aliases`() = runBlocking {
        seed()
        val links = ContactLinks(db)
        val key = links.save(ContactChoice("Synthetic Asha", "lookup-a", listOf("0712345678", "asha@example.invalid")))
        links.task("t", key); links.ledger("e", key)
        val restored = restore(15)
        try {
            assertEquals(15, BackupTables.SCHEMA)
            assertEquals(db.contacts().all(), restored.contacts().all())
            assertEquals(db.people().all(), restored.people().all())
            assertEquals(key, restored.tasks().task("t")!!.personKey)
            assertEquals(key, restored.finances().entry("e")!!.personKey)
            assertEquals(-5000L, restored.finances().balance("a"))
        } finally { restored.close() }
    }
    @Test fun `schema 14 backup restores populated tasks and ledger without person columns or contact table`() = runBlocking {
        seed()
        db.people().save(PersonAlias("tel:712345678", "Synthetic Asha", 1))
        val restored = restore(14)
        try {
            assertEquals(-5000L, restored.finances().balance("a"))
            assertNull(restored.tasks().task("t")!!.personKey)
            assertNull(restored.finances().entry("e")!!.personKey)
            assertEquals("Synthetic Asha", restored.people().all().single().name)
            assertTrue(restored.contacts().all().isEmpty())
            for (version in listOf(8, 10, 11, 12, 13, 14)) assertFalse("v$version", "person_contact" in BackupTables.tablesFor(version))
        } finally { restored.close() }
    }
    private fun restore(schema: Int): EventDatabase {
        val cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        // The fixture source bytes are not device ciphertext; use a separate empty payload for this archive.
        db.openHelper.writableDatabase.execSQL("UPDATE source_event SET encryptedPayload = ?", arrayOf(cipher.encrypt("synthetic".toByteArray())))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { BackupTables.write(db.openHelper.writableDatabase, it, cipher) }
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val table = entry.name.removePrefix("tables/").removeSuffix(".jsonl")
                    val text = input.readBytes().toString(Charsets.UTF_8)
                    if (table !in BackupTables.tablesFor(schema)) continue
                    zip.putNextEntry(ZipEntry(entry.name))
                    val rows = text.lineSequence().filter(String::isNotBlank).joinToString("\n") { line ->
                        JSONObject(line).apply { if (schema == 14 && table in setOf("assistant_task", "ledger_entry")) remove("personKey") }.toString()
                    }
                    if (rows.isNotEmpty()) zip.write((rows + "\n").toByteArray())
                    zip.closeEntry()
                }
            }
        }
        val target = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { input ->
            for (table in BackupTables.tablesFor(schema)) {
                assertEquals("tables/$table.jsonl", input.nextEntry!!.name)
                BackupTables.readTable(target.openHelper.writableDatabase, input, table, cipher)
            }
        }
        return target
    }
}
