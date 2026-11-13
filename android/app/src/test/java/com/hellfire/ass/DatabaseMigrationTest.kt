package com.hellfire.ass

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseMigrationTest {
    @Test fun `version one rows and encrypted bytes survive migration and gain pending jobs`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "synthetic-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.hellfire.ass.data.EventDatabase/1.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO source_event (id, source, externalId, packageName, occurredAt, receivedAt, kind, payloadHash, encryptedPayload, contentAvailable, contentRedacted, securitySensitive, ingestVersion, processingStatus) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("legacy-fixture", "android_notification", "fixture-external", "fixture.app", 1L, 2L,
                    "POSTED", "fixture-hash", byteArrayOf(1, 2, 3), 1, 0, 0, 1, "captured"))
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, EventDatabase::class.java, name)
            .addMigrations(EventDatabase.MIGRATION_1_2, EventDatabase.MIGRATION_2_3, EventDatabase.MIGRATION_3_4, EventDatabase.MIGRATION_4_5, EventDatabase.MIGRATION_5_6, EventDatabase.MIGRATION_6_7, EventDatabase.MIGRATION_7_8).build()
        try {
            val event = migrated.events().find("legacy-fixture")!!
            assertArrayEquals(byteArrayOf(1, 2, 3), event.encryptedPayload)
            assertEquals("PENDING", migrated.events().processing(event.id)!!.state)
            assertEquals(1, migrated.events().audits().first().single().captured)
            assertEquals(8, migrated.openHelper.writableDatabase.version)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
