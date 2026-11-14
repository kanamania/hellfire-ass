package com.hellfire.ass.security

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.*
import java.util.Base64
import java.util.zip.*

/** Fixed table/column allowlists; archives cannot supply executable SQL. */
object BackupTables {
    /** Backup schema written by this version. Schema 8 archives (before the v10 database) still restore. */
    const val SCHEMA = 10
    private val schema8 = listOf("source_event", "event_processing", "capture_audit", "notification_policy", "finance_account",
        "finance_candidate", "ledger_entry", "ledger_evidence", "merchant_alias", "communication_message", "outbound_draft",
        "action_audit", "assistant_task", "daily_review", "media_item", "transcript_segment", "speech_span", "memory_item")
    val tables = schema8 + "ai_usage"
    fun tablesFor(schema: Int): List<String> = when (schema) {
        8 -> schema8
        SCHEMA -> tables
        else -> throw IllegalArgumentException("Backup schema requires a matching app version")
    }
    /** Columns added after schema 8. Older archives may omit exactly these; they restore as the column default (NULL). */
    val addedColumns = mapOf("assistant_task" to setOf("completedAt"), "media_item" to setOf("durationMs"))
    fun write(db: SupportSQLiteDatabase, zip: ZipOutputStream, cipher: PayloadCipher): Map<String, Long> {
        val counts = linkedMapOf<String, Long>()
        var bytesWritten = 0L
        for (table in tables) {
            var count = 0L
            zip.putNextEntry(ZipEntry("tables/$table.jsonl"))
            db.query("SELECT * FROM $table").use { rows ->
                while (rows.moveToNext()) {
                    val row = JSONObject()
                    for (i in 0 until rows.columnCount) {
                        val name = rows.getColumnName(i)
                        row.put(name, when (rows.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> rows.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> rows.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob", Base64.getEncoder().encodeToString(
                                if (table == "source_event" && name == "encryptedPayload") cipher.decrypt(rows.getBlob(i)) else rows.getBlob(i)))
                            else -> rows.getString(i)
                        })
                    }
                    val bytes = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 4 * 1024 * 1024) { "Backup row exceeds 4 MiB" }
                    bytesWritten += bytes.size; require(bytesWritten <= 1024L * 1024 * 1024) { "Database export exceeds 1 GiB" }
                    zip.write(bytes); count++
                }
            }
            zip.closeEntry(); counts[table] = count
        }
        return counts
    }
    class Budget(var remaining: Long = 1024L * 1024 * 1024)
    fun readTable(db: SupportSQLiteDatabase, zip: ZipInputStream, table: String, cipher: PayloadCipher, budget: Budget = Budget()): Long {
        require(table in tables)
        val columns = db.query("SELECT * FROM $table LIMIT 0").use { it.columnNames.toList() }
        var keys: List<String>? = null
        var insert: androidx.sqlite.db.SupportSQLiteStatement? = null
        var count = 0L
        val entryInput = java.io.BufferedInputStream(zip, 65536)
        try {
            while (true) {
                val line = StringBuilder()
                while (true) {
                    val char = entryInput.read()
                    if (char < 0 || char == 10) break
                    require(line.length < 4 * 1024 * 1024) { "Backup row too large" }
                    require(--budget.remaining >= 0) { "Database restore exceeds 1 GiB" }
                    line.append(char.toChar())
                }
                if (line.isEmpty()) break
                val bytes = ByteArray(line.length) { line[it].code.toByte() }
                val row = JSONObject(bytes.toString(Charsets.UTF_8))
                val rowKeys = row.keys().asSequence().toSet()
                if (keys == null) {
                    // Every row of a table has the same columns. Unknown columns are refused; only columns added after
                    // schema 8 may be missing, and they take their SQL default.
                    require(rowKeys.all { it in columns } && (columns - rowKeys).all { it in addedColumns[table].orEmpty() }) { "Backup columns differ from current schema" }
                    keys = columns.filter { it in rowKeys }
                    insert = db.compileStatement("INSERT INTO $table (${keys.joinToString(",")}) VALUES (${keys.joinToString(",") { "?" }})")
                }
                require(rowKeys == keys.toSet()) { "Backup columns differ between rows" }
                val statement = insert!!
                statement.clearBindings()
                keys.forEachIndexed { index, name ->
                    val value = row.get(name)
                    when {
                        value == JSONObject.NULL -> statement.bindNull(index + 1)
                        value is JSONObject -> {
                            require(value.keys().asSequence().toSet() == setOf("blob"))
                            val blob = Base64.getDecoder().decode(value.getString("blob"))
                            statement.bindBlob(index + 1, if (table == "source_event" && name == "encryptedPayload") cipher.encrypt(blob) else blob)
                            blob.fill(0)
                        }
                        value is Int || value is Long -> statement.bindLong(index + 1, (value as Number).toLong())
                        value is Double -> statement.bindDouble(index + 1, value)
                        value is String -> statement.bindString(index + 1, value)
                        else -> error("Unsupported backup value")
                    }
                }
                statement.executeInsert(); count++; require(count <= 2_000_000) { "Backup table row limit exceeded" }
            }
        } finally { insert?.close() }
        return count
    }
    fun revokeRestoredApprovals(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE outbound_draft SET state='DRAFT' WHERE state='APPROVED'")
        db.execSQL("UPDATE outbound_draft SET state='UNKNOWN', error='RESTORED_DELIVERY_UNCONFIRMED' WHERE state='SENDING'")
        db.execSQL("UPDATE outbound_draft SET approvalId=NULL, approvedAt=NULL")
    }
    fun copy(from: SupportSQLiteDatabase, to: SupportSQLiteDatabase) {
        for (table in tables) {
            from.query("SELECT * FROM $table").use { rows ->
                val statement = to.compileStatement("INSERT INTO $table (${rows.columnNames.joinToString(",")}) VALUES (${rows.columnNames.joinToString(",") { "?" }})")
                statement.use { insert -> while (rows.moveToNext()) {
                    insert.clearBindings()
                    for (index in 0 until rows.columnCount) when (rows.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> insert.bindNull(index + 1)
                        Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(index + 1, rows.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(index + 1, rows.getDouble(index))
                        Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(index + 1, rows.getBlob(index))
                        else -> insert.bindString(index + 1, rows.getString(index))
                    }
                    insert.executeInsert()
                } }
            }
        }
    }
}
