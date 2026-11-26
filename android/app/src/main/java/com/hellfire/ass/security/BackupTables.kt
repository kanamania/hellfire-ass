package com.hellfire.ass.security

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.*
import java.util.Base64
import java.util.zip.*
import com.hellfire.ass.calendar.CalendarSchema
import com.hellfire.ass.guard.GuardSchema
import com.hellfire.ass.news.NewsSchema

/** Fixed table/column allowlists; archives cannot supply executable SQL. */
object BackupTables {
    /** Backup schema written by this version. Schema 8 archives (before the v10 database) still restore. Full-text indexes and embeddings are rebuilt, not backed up. */
    const val SCHEMA = 22
    private val schema8 = listOf("source_event", "event_processing", "capture_audit", "notification_policy", "finance_account",
        "finance_candidate", "ledger_entry", "ledger_evidence", "merchant_alias", "communication_message", "outbound_draft",
        "action_audit", "assistant_task", "daily_review", "media_item", "transcript_segment", "speech_span", "memory_item")
    private val schema10 = schema8 + "ai_usage"
    private val schema11 = schema10 + listOf("category_budget", "provider_balance")
    private val schema14 = schema11 + listOf("action_template", "debt_entry", "person_alias")
    private val schema16 = schema14 + "person_contact"
    private val schema17 = schema16 + listOf("ai_skill", "skill_use", "memory_revision")
    /** v18 library tables, parents before children (restore inserts with foreign keys on). */
    private val schema18 = schema17 + listOf("media_category", "media_collection", "media_analysis", "media_segment", "media_item_category",
        "media_item_tag", "collection_member", "media_bookmark", "playback_state", "play_history", "processing_job", "inbox_item",
        "inbox_member", "drawer_message", "action_record", "saved_location", "share_export", "privacy_finding", "sync_record",
        "sync_conflict", "backup_manifest", "backup_item")
    private val schema19 = schema18 + NewsSchema.tables
    private val schema20 = schema19 + GuardSchema.tables
    val tables = schema20 + CalendarSchema.tables
    fun tablesFor(schema: Int): List<String> = when (schema) {
        8 -> schema8
        10 -> schema10
        11, 12, 13 -> schema11
        14 -> schema14
        15, 16 -> schema16
        17 -> schema17
        18 -> schema18
        19 -> schema19
        20 -> schema20
        // Schema 22 only added indexes, so schema 21 archives hold the same tables.
        21, SCHEMA -> tables
        else -> throw IllegalArgumentException("Backup schema requires a matching app version")
    }
    /** Columns added after schema 8. Older archives may omit exactly these; they restore as the column default (NULL, NONE, TASK, 0). */
    val addedColumns = mapOf("assistant_task" to setOf("completedAt", "direction", "amount", "kind", "repeatMonthly", "personKey", "repeatRule", "alertProfile", "snoozeMinutes"), "ledger_entry" to setOf("personKey"), "media_item" to setOf("durationMs", "storage", "contentKind",
        "externalUri", "availability", "origin", "favorite", "modifiedAt", "width", "height", "originalMetadata", "syncEnabled"),
        "person_alias" to setOf("personKey"), "debt_entry" to setOf("personKey"),
        "finance_candidate" to setOf("accountId", "category", "duplicateOf"))
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
                                if (table in setOf("source_event", "guard_evidence") && name == "encryptedPayload") cipher.decrypt(rows.getBlob(i)) else rows.getBlob(i)))
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
                            statement.bindBlob(index + 1, if (table in setOf("source_event", "guard_evidence") && name == "encryptedPayload") cipher.encrypt(blob) else blob)
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
        db.execSQL("UPDATE calendar_change SET state='RESTORED', approvalId=NULL, approvedAt=NULL WHERE state IN ('PROPOSED','APPROVED','APPLYING','UNKNOWN')")
        db.execSQL("UPDATE calendar_link SET state='RESTORED'")
        // Device consent and relay sessions must never transfer through a backup.
        db.execSQL("UPDATE guard_state SET armed=0,active=0,pendingDisarm=0,needsResume=0,status='RESTORED_DISARMED'")
        db.execSQL("UPDATE ai_skill SET state='PENDING' WHERE state='ACTIVE'")
        db.execSQL("UPDATE outbound_draft SET state='DRAFT' WHERE state='APPROVED'")
        db.execSQL("UPDATE outbound_draft SET state='UNKNOWN', error='RESTORED_DELIVERY_UNCONFIRMED' WHERE state='SENDING'")
        db.execSQL("UPDATE outbound_draft SET approvalId=NULL, approvedAt=NULL")
        db.execSQL("UPDATE action_record SET state='PROPOSED' WHERE state='APPROVED'")
        // In-flight work resumes from its checkpoint; external URIs from the old device must be re-checked before use.
        db.execSQL("UPDATE processing_job SET state='PENDING', claimToken=NULL WHERE state IN ('PREPARING','PROCESSING','INDEXING')")
        db.execSQL("UPDATE sync_record SET state='PENDING' WHERE state='UPLOADING'")
        db.execSQL("UPDATE media_item SET availability='UNVERIFIED' WHERE storage='EXTERNAL'")
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
