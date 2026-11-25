package com.hellfire.ass

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.hellfire.ass.ai.*
import com.hellfire.ass.assistant.*
import com.hellfire.ass.data.*
import com.hellfire.ass.finance.*
import com.hellfire.ass.memory.*
import com.hellfire.ass.security.*
import com.hellfire.ass.ui.SharedSaver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.time.Instant
import javax.crypto.KeyGenerator
import java.io.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EverydaySkillsTest {
    private lateinit var db: EventDatabase
    private lateinit var skills: AiSkills
    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher by lazy { PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) }
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build(); skills = AiSkills(db) }
    @After fun close() { db.close() }
    private suspend fun source(id: String, pkg: String = "fixture") {
        db.events().insertCaptured(SourceEvent(id, source = "owner_entry", externalId = id, packageName = pkg, occurredAt = 1, receivedAt = 1,
            kind = "POSTED", payloadHash = "fixture", encryptedPayload = cipher.encrypt("{}".toByteArray()), contentAvailable = true, contentRedacted = false, securitySensitive = false))
    }
    @Test fun `learned skills stay pending until owner activation`() = runBlocking {
        source("s")
        val id = skills.suggest("Invoice wording", "GUIDANCE", "invoice", "Use Kiswahili for invoices", "Owner corrected a saved note", "s")
        assertTrue(skills.guidance("invoice for Juma").isEmpty())
        val initial = db.skills().find(id)!!
        skills.save(initial.copy(state = "ACTIVE"))
        assertEquals(id, skills.guidance("invoice for Juma").single().id)
        assertEquals(0, db.finances().entryCount())
        skills.save(db.skills().find(id)!!.copy(state = "DISABLED"))
        assertTrue(skills.guidance("invoice").isEmpty())
    }
    @Test fun `suggestion dedup never overwrites owner changes or reactivates disabled rules`() = runBlocking {
        val id = skills.suggest("Tone", "GUIDANCE", "invoice", "Be brief", "Owner note", null)
        skills.save(db.skills().find(id)!!.copy(advice = "Be detailed", state = "DISABLED"))
        skills.suggest("Tone", "GUIDANCE", "invoice", "Be brief", "New observation", null)
        assertEquals("Be detailed", db.skills().find(id)!!.advice)
        assertEquals("DISABLED", db.skills().find(id)!!.state)
    }
    @Test fun `usage means an actual application with revision context and dedup`() = runBlocking {
        source("s")
        val id = skills.suggest("Tone", "GUIDANCE", "invoice", "Be brief", "Owner note", "s")
        skills.save(db.skills().find(id)!!.copy(state = "ACTIVE"))
        val rule = db.skills().find(id)!!
        repeat(2) { skills.record(rule, "Ask", "request-1", "s", "Included in answer") }
        assertEquals(1, db.skills().history(id, 100).size)
        skills.save(rule.copy(advice = "Use a formal greeting"))
        skills.record(rule, "Ask", "stale", "s", "Stale UI must not count")
        assertEquals(1, db.skills().history(id, 100).size)
        val current = db.skills().find(id)!!
        skills.record(current, "Ask", "request-2", "s", "Included in answer")
        assertEquals(setOf(rule.revision, current.revision), db.skills().history(id, 100).map { it.revision }.toSet())
        db.skills().delete(id)
        assertTrue(db.skills().history(id, 100).isEmpty()); assertNotNull(db.events().find("s"))
    }
    @Test fun `payment skill suggests only valid account and never posts`() = runBlocking {
        source("s"); db.finances().addAccount(FinanceAccount("cash", "Cash"))
        val id = skills.suggest("Fuel", "PAYMENT_DEFAULT", "MPESA|merchant:fixture", JSONObject().put("account", "cash").put("category", "Fuel").toString(), "Confirmed review choice", "s")
        assertNull(skills.payment("MPESA|merchant:fixture", "c", "s"))
        skills.save(db.skills().find(id)!!.copy(state = "ACTIVE"))
        assertEquals("Fuel", skills.payment("MPESA|merchant:fixture", "c", "s")!!.second.second)
        assertNull(skills.payment("MPESA|merchant:other", "c", "s")); assertEquals(0, db.finances().entryCount())
        assertEquals(1, db.skills().history(id, 100).size)
        db.openHelper.writableDatabase.execSQL("DELETE FROM finance_account WHERE id='cash'")
        assertNull(skills.payment("MPESA|merchant:fixture", "next", "s"))
    }
    @Test fun `false suggestion skills are exact app and text scoped and retain sources`() = runBlocking {
        source("s", "fixture.one"); source("next", "fixture.one"); source("other", "fixture.two")
        skills.feedback("IGNORE_TASK", "s", "Send report tomorrow", "Owner rejected this suggestion")
        val rule = db.skills().watch().first().single()
        assertFalse(skills.ignored("IGNORE_TASK", "next", "Send report tomorrow"))
        skills.save(rule.copy(state = "ACTIVE"))
        assertTrue(skills.ignored("IGNORE_TASK", "next", " Send   report tomorrow "))
        assertFalse(skills.ignored("IGNORE_TASK", "other", "Send report tomorrow"))
        assertFalse(skills.ignored("IGNORE_TASK", "next", "Send report next week"))
        assertFalse(skills.ignored("IGNORE_FINANCE", "next", "Send report tomorrow"))
        assertNotNull(db.events().find("next"))
    }
    @Test fun `skill validation refuses executable kinds and malformed payment choices`() {
        val base = AiSkill("x", "Test", "GUIDANCE", "topic", "Keep it short", "Owner preference", null)
        assertTrue(runCatching { AiSkills.validate(base.copy(kind = "SEND_MESSAGE")) }.isFailure)
        assertTrue(runCatching { AiSkills.validate(base.copy(kind = "PAYMENT_DEFAULT")) }.isFailure)
        assertTrue(runCatching { AiSkills.validate(base.copy(state = "APPROVED_SEND")) }.isFailure)
    }
    @Test fun `memory search pages beyond fifty and keeps original correction history`() = runBlocking {
        source("s")
        repeat(120) { n -> db.memory().insert(MemoryItem("m$n", "s", "Topic $n", if (n == 0) "old needle" else "Other", "OWNER_NOTE", 100, n.toLong(), null)) }
        assertEquals(50, db.memory().browse("", "", 50).first().size)
        assertEquals(120, db.memory().browse("", "", 150).first().size)
        assertEquals("m0", db.memory().browse("needle", "", 50).first().single().id)
        db.memory().revise("m0", "Topic 0", "Corrected", 200)
        assertEquals("old needle", db.memory().revisions("m0").first().single().text)
        assertEquals("Corrected", db.memory().browse("Corrected", "OWNER_CORRECTION", 50).first().single().text)
        db.memory().forget("m0"); assertTrue(db.memory().revisions("m0").first().isEmpty())
    }
    @Test fun `shared text retries save one source and one reminder proposal`() = runBlocking {
        val events = EventRepository(db.events()) { cipher }
        val first = SharedSaver.saveText(db, events, "com.hellfire.ass", "fixture-share", 1000, "Create reminder", "Call the synthetic supplier", "Fixture")
        val second = SharedSaver.saveText(db, events, "com.hellfire.ass", "fixture-share", 1000, "Create reminder", "Call the synthetic supplier", "Fixture")
        assertEquals(first, second)
        assertEquals(1, db.tasks().all().size)
        assertEquals("REVIEW", db.tasks().all().single().state)
        assertEquals(1, db.events().count().first())
    }
    @Test fun `shared note retries do not duplicate and authentication content is refused`() = runBlocking {
        val events = EventRepository(db.events()) { cipher }
        repeat(2) { SharedSaver.saveText(db, events, "com.hellfire.ass", "fixture-note", 1000, "Save note", "Synthetic owner note", "Fixture") }
        assertEquals(1, db.memory().items().first().size)
        assertTrue(runCatching { SharedSaver.saveText(db, events, "com.hellfire.ass", "otp", 1000, "Save note", "Your OTP is 123456", "Fixture") }.isFailure)
        assertEquals(1, db.memory().items().first().size)
        val a = SharedSaver.key("id", "Save note", "text", "topic", listOf(Uri.parse("content://fixture/one")))
        assertNotEquals(a, SharedSaver.key("id", "Save note", "text", "topic", listOf(Uri.parse("content://fixture/two"))))
    }
    @Test fun `month comparisons retain category and account and use deterministic deltas`() = runBlocking {
        val accounts = listOf(FinanceAccount("cash", "Cash"))
        db.finances().addAccount(accounts.single())
        val now = Instant.parse("2026-11-17T09:00:00Z")
        val (current, previous) = FinanceComparisons.questions("compare spending on Fuel from Cash this month vs last month", now, accounts, listOf("Fuel"), emptyList())
        assertEquals("Fuel", previous.category); assertEquals("cash", previous.accountId)
        for ((id, at, amount) in listOf(Triple("old", previous.start + 1000, -1000L), Triple("new", current.start + 1000, -1500L))) {
            source(id); db.finances().post(LedgerEntry(id, "cash", id, "EXPENSE", amount, "Fuel", "Fixture", at, at, id)); db.finances().evidence(LedgerEvidence(id, id))
        }
        val report = FinanceComparisons.report(FinanceRepository(db), current, previous)
        assertTrue(report.first.contains("Difference: TZS 500")); assertEquals(setOf("old", "new"), report.second.toSet())
        assertEquals(previous, FinanceComparisons.questions("why was spending on Fuel from Cash higher this month", now, accounts, listOf("Fuel"), emptyList()).second)
    }
    @Test fun `catalog has pinned verified formats rather than arbitrary identifiers`() {
        assertEquals(6, ModelCatalog.entries.size)
        assertEquals(6, ModelCatalog.entries.map { it.id }.distinct().size)
        for (model in ModelCatalog.entries) {
            assertTrue(model.url.startsWith("https://huggingface.co/") || model.url.startsWith("https://storage.googleapis.com/mediapipe-models/"))
            assertTrue(model.release.sha256.matches(Regex("[0-9a-f]{64}"))); assertTrue(model.release.size > 1024)
            assertFalse(model.url.contains("/main/")); assertFalse(model.url.contains("/latest/"))
        }
        assertTrue(runCatching { ModelCatalog.entry("https://untrusted.example/model") }.isFailure)
    }
    @Test fun `schema sixteen upgrades with old records intact and new tables empty`() = runBlocking {
        val name = "synthetic-skills-17.db"
        MigrationFixture.create(context, name, 16) { old ->
            old.execSQL("INSERT INTO finance_account VALUES ('cash','Cash','CASH','TZS',1)")
        }
        val migrated = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try { assertEquals(com.hellfire.ass.security.BackupTables.SCHEMA, migrated.openHelper.writableDatabase.version); assertEquals("Cash", migrated.finances().account("cash")!!.label); assertTrue(migrated.skills().watch().first().isEmpty()) }
        finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun `backup round trip retains skills provenance usage and correction versions`() = runBlocking {
        source("s")
        db.memory().insert(MemoryItem("m", "s", "Topic", "Old text", "OWNER_NOTE", 100, 1, 1))
        db.memory().revise("m", "Topic", "New text", 2)
        val id = skills.suggest("Guide", "GUIDANCE", "invoice", "Use source evidence", "Owner correction", "s")
        skills.save(db.skills().find(id)!!.copy(state = "ACTIVE")); skills.record(db.skills().find(id)!!, "Ask", "request", "s", "Included")
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { BackupTables.write(db.openHelper.writableDatabase, it, cipher) }
        val restored = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        try {
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> for (table in BackupTables.tables) { assertEquals("tables/$table.jsonl", zip.nextEntry!!.name); BackupTables.readTable(restored.openHelper.writableDatabase, zip, table, cipher) } }
            BackupTables.revokeRestoredApprovals(restored.openHelper.writableDatabase)
            assertEquals("PENDING", restored.skills().find(id)!!.state)
            assertEquals(1, restored.skills().history(id, 100).size)
            assertEquals("Old text", restored.memory().revisions("m").first().single().text)
            assertFalse("ai_skill" in BackupTables.tablesFor(16))
        } finally { restored.close() }
    }
    @Test fun `new observed advice is pending and approval replaces the older active version`() = runBlocking {
        val first = skills.suggest("Tone", "GUIDANCE", "invoice", "Be brief", "Owner note", null)
        skills.save(db.skills().find(first)!!.copy(state = "ACTIVE"))
        val update = skills.suggest("Tone", "GUIDANCE", "invoice", "Include a greeting", "Owner correction", null)
        assertNotEquals(first, update)
        assertEquals(first, skills.guidance("invoice").single().id)
        skills.save(db.skills().find(update)!!.copy(state = "ACTIVE"))
        assertEquals(update, skills.guidance("invoice").single().id)
        assertEquals("DISABLED", db.skills().find(first)!!.state)
    }
    @Test fun `topic qualifiers prevent a rule for one person applying to another`() = runBlocking {
        val id = skills.suggest("Asha invoices", "GUIDANCE", "invoice Asha", "Use the formal name", "Owner preference", null)
        skills.save(db.skills().find(id)!!.copy(state = "ACTIVE"))
        assertTrue(skills.guidance("invoice for Juma").isEmpty())
        assertEquals(id, skills.guidance("invoice for Asha").single().id)
        assertTrue(runCatching { AiSkills.validate(AiSkill("x", "Unsafe", "GUIDANCE", "invoice", "Your OTP is 123456", "Owner note", null)) }.isFailure)
    }
    @Test fun `skill audit sources remain accessible but are excluded from future AI retrieval`() = runBlocking {
        val events = EventRepository(db.events()) { cipher }
        val snapshot = com.hellfire.ass.events.NotificationSnapshot("audit", "owner.skill", 1, 1, source = "owner_skill_use", fields = mapOf("text" to listOf("Synthetic invoice advice")))
        val stored = events.store(snapshot, "com.hellfire.ass")!!
        assertNotNull(db.events().find(stored.id))
        assertTrue(db.events().search("invoice*", 20).isEmpty())
        assertTrue(db.semantic().pending("synthetic-model", 20).isEmpty())
    }
    @Test fun `text import failure rolls back the source and never reports a half-created note`() = runBlocking {
        source("seed")
        val key = "fixture-collision"
        val id = java.util.UUID.nameUUIDFromBytes(key.toByteArray()).toString()
        db.memory().insert(MemoryItem(id, "seed", "Existing", "Keep", "OWNER_NOTE", 100, 1, 1))
        val events = EventRepository(db.events()) { cipher }
        assertTrue(runCatching { SharedSaver.saveText(db, events, "com.hellfire.ass", key, 1000, "Save note", "New fixture", "Fixture") }.isFailure)
        assertEquals(1, db.events().count().first())
        assertEquals("Keep", db.memory().item(id)!!.text)
    }
    @Test fun `a default that was not applied does not increment usage`() = runBlocking {
        source("s"); db.finances().addAccount(FinanceAccount("cash", "Cash"))
        val id = skills.suggest("Default", "PAYMENT_DEFAULT", "MPESA|sender:fixture", JSONObject().put("account", "cash").put("category", "Food").toString(), "Owner choice", "s")
        skills.save(db.skills().find(id)!!.copy(state = "ACTIVE"))
        skills.payment("MPESA|sender:fixture", "candidate", "s", applied = false)
        assertTrue(db.skills().history(id, 10).isEmpty())
    }

    @Test fun `false suggestion rules keep account and sender scopes separate`() = runBlocking {
        source("s"); source("next"); source("other")
        val text = "Synthetic commitment tomorrow"
        skills.feedback("IGNORE_TASK", "s", text, "Owner rejected this source", "fixture|account-a|sender-a")
        val rule = db.skills().watch().first().single()
        skills.save(rule.copy(state = "ACTIVE"))
        assertTrue(skills.ignored("IGNORE_TASK", "next", text, "fixture|account-a|sender-a"))
        assertFalse(skills.ignored("IGNORE_TASK", "other", text, "fixture|account-a|sender-b"))
        assertFalse(skills.ignored("IGNORE_TASK", "other", text, "fixture|account-b|sender-a"))
    }
    @Test fun `finance collection consults approved suppression without deleting capture or posting money`() = runBlocking {
        source("s")
        val text = "Umelipa TZS 15,000 ref SYNTHREF99 kwa duka"
        skills.feedback("IGNORE_FINANCE", "s", text, "Owner rejected a repeated false receipt")
        val rule = db.skills().watch().first().single()
        val repository = FinanceRepository(db) { source, body -> skills.ignored("IGNORE_FINANCE", source, body) }
        skills.save(rule.copy(state = "ACTIVE"))
        repository.collect("s", 1, "M-PESA", text)
        assertEquals(0, db.finances().candidates().first().size)
        assertEquals(1, db.skills().history(rule.id, 10).size)
        assertNotNull(db.events().find("s")); assertEquals(0, db.finances().entryCount())
        skills.save(db.skills().find(rule.id)!!.copy(state = "DISABLED"))
        repository.collect("s", 1, "M-PESA", text)
        assertEquals(1, db.finances().candidates().first().size)
        assertEquals(0, db.finances().entryCount())
    }
    @Test fun `usage evidence is protected until the owning skill is removed`() = runBlocking {
        source("s")
        val id = skills.suggest("Guide", "GUIDANCE", "invoice", "Use a greeting", "Owner preference", null)
        skills.save(db.skills().find(id)!!.copy(state = "ACTIVE"))
        skills.record(db.skills().find(id)!!, "Ask", "request", "s", "Included")
        assertTrue(runCatching { db.events().deleteAll() }.isFailure)
        db.skills().delete(id)
        db.events().deleteAll()
        assertEquals(0, db.events().count().first())
    }

    @Test fun `draft autosaves preserve the latest edit despite reordered IO jobs`() = runBlocking {
        val store = SecretStore(context) { cipher }
        store.remove("ui-drafts")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val jobs = (1..100).map { n -> com.hellfire.ass.ui.PrivateDrafts.write(store, scope, "memory", "Synthetic private draft $n") }
            jobs.joinAll()
            assertEquals("Synthetic private draft 100", com.hellfire.ass.ui.PrivateDrafts.read(store, "memory"))
            val bytes = java.io.File(context.noBackupFilesDir, "ui-drafts.credentials.enc").readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains("Synthetic private draft"))
        } finally { scope.cancel(); store.remove("ui-drafts") }
    }
    @Test fun `clearing a draft preserves other forms and removes the last private draft file`() = runBlocking {
        val store = SecretStore(context) { cipher }
        store.remove("ui-drafts")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            listOf(com.hellfire.ass.ui.PrivateDrafts.write(store, scope, "memory", "Memory fixture"), com.hellfire.ass.ui.PrivateDrafts.write(store, scope, "reconcile", "Account fixture")).joinAll()
            com.hellfire.ass.ui.PrivateDrafts.write(store, scope, "memory", "").join()
            assertNull(com.hellfire.ass.ui.PrivateDrafts.read(store, "memory"))
            assertEquals("Account fixture", com.hellfire.ass.ui.PrivateDrafts.read(store, "reconcile"))
            com.hellfire.ass.ui.PrivateDrafts.write(store, scope, "reconcile", "").join()
            assertNull(store.read("ui-drafts"))
        } finally { scope.cancel(); store.remove("ui-drafts") }
    }

}
