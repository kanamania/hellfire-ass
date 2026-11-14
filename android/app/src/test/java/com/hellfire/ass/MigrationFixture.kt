package com.hellfire.ass

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Builds a real SQLite file with an exported Room schema version, for migration tests. */
object MigrationFixture {
    fun create(context: Context, name: String, version: Int, seed: (SQLiteDatabase) -> Unit) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.hellfire.ass.data.EventDatabase/$version.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            seed(db)
            db.version = version
        }
    }
}
