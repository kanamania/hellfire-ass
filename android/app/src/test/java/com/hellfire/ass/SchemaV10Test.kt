package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.*
import com.hellfire.ass.security.BackupTables
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.tasks.AssistantTask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

/** Room v10 (§6 data gaps): task completion time, media length, AI usage log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SchemaV10Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun cipher() = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Test fun `migration 9 to 10 keeps rows, backfills audited completion times and adds the usage log`() = runBlocking {
        val name = "migration-9-10.db"
        MigrationFixture.create(context, name, 9) { db ->
            db.execSQL("INSERT INTO source_event (id, source, externalId, packageName, occurredAt, receivedAt, kind, payloadHash, encryptedPayload, contentAvailable, contentRedacted, securitySensitive, ingestVersion, processingStatus) VALUES ('s', 'owner_entry', 'x', 'p', 1, 1, 'POSTED', 'h', X'01', 1, 0, 0, 1, 'captured')")
            for ((id, state) in listOf("audited" to "DONE", "unaudited" to "DONE", "active" to "ACTIVE"))
                db.execSQL("INSERT INTO assistant_task (id, sourceId, title, dueAt, timezone, party, evidence, state, createdAt, updatedAt, alertCount) VALUES ('$id', 's', 'T', 100, 'Africa/Dar_es_Salaam', 'OWNER', 'T', '$state', 1, 1, 0)")
            db.execSQL("INSERT INTO action_audit (id, draftId, action, state, occurredAt) VALUES ('a1', 'audited', 'OWNER_TASK_DONE', 'DONE', 150)")
            db.execSQL("INSERT INTO action_audit (id, draftId, action, state, occurredAt) VALUES ('a2', 'active', 'OWNER_TASK_DONE', 'DONE', 160)")
            db.execSQL("INSERT INTO media_item (id, sourceId, name, mime, kind, contentHash, size, importedAt, processedMs, state, summary, summaryState) VALUES ('m', 's', 'Call', 'audio/aac', 'CALL_RECORDING', 'hash', 10, 1, 0, 'RETAINED', '', 'NONE')")
        }
        val db = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals(150L, db.tasks().task("audited")!!.completedAt)
            assertNull(db.tasks().task("unaudited")!!.completedAt)
            assertNull("only done tasks are backfilled", db.tasks().task("active")!!.completedAt)
            assertNull(db.media().item("m")!!.durationMs)
            assertEquals("Call", db.media().item("m")!!.name)
            db.aiUsage().insert(AiUsage(requestId = "r", at = 5, task = "Chat", provider = "OpenAI", model = "m", result = "SUCCESS", inputTokens = 10, outputTokens = 2, microUsd = 30))
            assertEquals(1, db.aiUsage().period(0, 10).size)
            assertEquals(10, db.openHelper.writableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `done sets the completion time and undo clears it`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        try {
            db.events().insertCaptured(SourceEvent("s", externalId = "s", packageName = "p", occurredAt = 1, receivedAt = 1, kind = "POSTED",
                payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
            db.tasks().insert(AssistantTask("t", "s", "Pay", 1000, party = "OWNER", evidence = "Pay", state = "ACTIVE", createdAt = 1, updatedAt = 1))
            db.tasks().close("t", "DONE", 2000)
            assertEquals(2000L, db.tasks().task("t")!!.completedAt)
            assertEquals(1, db.tasks().reopen("t", 3000, 2500))
            assertNull(db.tasks().task("t")!!.completedAt)
            db.tasks().close("t", "REJECTED", 4000)
            assertNull("only done records a completion time", db.tasks().task("t")!!.completedAt)
        } finally { db.close() }
    }

    private fun archive(row: String): ZipInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("tables/assistant_task.jsonl")); zip.write((row + "\n").toByteArray()); zip.closeEntry() }
        return ZipInputStream(ByteArrayInputStream(out.toByteArray())).also { it.nextEntry }
    }

    @Test fun `schema 8 backups restore without the new columns, but archives still cannot invent or drop core columns`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        try {
            db.events().insertCaptured(SourceEvent("s", externalId = "s", packageName = "p", occurredAt = 1, receivedAt = 1, kind = "POSTED",
                payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
            val old = """{"id":"t","sourceId":"s","title":"Pay","dueAt":1,"timezone":"Africa/Dar_es_Salaam","party":"OWNER","evidence":"Pay","state":"DONE","createdAt":1,"updatedAt":2,"nextAlertAt":null,"lastAlertAt":null,"alertCount":0}"""
            assertEquals(1L, BackupTables.readTable(db.openHelper.writableDatabase, archive(old), "assistant_task", cipher()))
            assertNull(db.tasks().task("t")!!.completedAt)
            assertEquals(BackupTables.tables, BackupTables.tablesFor(BackupTables.SCHEMA))
            assertFalse("ai_usage" in BackupTables.tablesFor(8))
            assertTrue(runCatching { BackupTables.tablesFor(7) }.isFailure)
            val invented = old.replace("\"alertCount\":0", "\"alertCount\":0,\"evil\":1").replace("\"id\":\"t\"", "\"id\":\"t2\"")
            assertTrue(runCatching { BackupTables.readTable(db.openHelper.writableDatabase, archive(invented), "assistant_task", cipher()) }.isFailure)
            val missingCore = old.replace("\"title\":\"Pay\",", "").replace("\"id\":\"t\"", "\"id\":\"t3\"")
            assertTrue(runCatching { BackupTables.readTable(db.openHelper.writableDatabase, archive(missingCore), "assistant_task", cipher()) }.isFailure)
        } finally { db.close() }
    }
}
