package com.hellfire.ass

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.hellfire.ass.data.EventDatabase
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMigrationTest {
    @Test fun allHistoricalSchemasMigrateThroughRealSqlcipher() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        System.loadLibrary("sqlcipher")
        for (version in 1 until EventDatabase.ALL_MIGRATIONS.last().endVersion) {
            val name = "fixture-migration-$version.db"
            val password = "synthetic-migration-$version".toByteArray()
            context.deleteDatabase(name)
            val schema = instrumentation.context.assets.open("com.hellfire.ass.data.EventDatabase/$version.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val helper = SupportOpenHelperFactory(password.copyOf()).create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                        db.execSQL("INSERT INTO source_event (id,source,externalId,packageName,occurredAt,receivedAt,kind,payloadHash,encryptedPayload,contentAvailable,contentRedacted,securitySensitive,ingestVersion,processingStatus) VALUES ('fixture','android_notification','fixture','fixture.invalid',1,2,'POSTED','fixture',X'010203',1,0,0,1,'captured')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Fixture creates one exact schema")
                }).build())
            try {
                helper.writableDatabase
            } finally { helper.close() }
            val migrated = Room.databaseBuilder(context, EventDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(password.copyOf()))
                .addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
            try {
                assertArrayEquals("schema $version", byteArrayOf(1, 2, 3), migrated.events().find("fixture")!!.encryptedPayload)
                assertEquals("schema $version", EventDatabase.ALL_MIGRATIONS.last().endVersion, migrated.openHelper.writableDatabase.version)
                assertEquals("ok", migrated.openHelper.writableDatabase.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
            } finally { migrated.close(); context.deleteDatabase(name) }
        }
    }
}
