package com.hellfire.ass.security

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import java.util.UUID

data class BackupSelection(
    val itemIds: Set<String> = emptySet(),
    val kinds: Set<String> = emptySet(),
    val collectionIds: Set<String> = emptySet(),
) {
    fun isFull() = itemIds.isEmpty() && kinds.isEmpty() && collectionIds.isEmpty()
}

/** Copies selected library rows and the relationships that hang off them. Search indexes are rebuilt by the caller. */
object SelectiveRestore {
    fun resolve(db: SupportSQLiteDatabase, selection: BackupSelection): List<String> {
        val chosen = linkedSetOf<String>()
        db.query("SELECT id, contentKind FROM media_item WHERE storage = 'MANAGED' AND state != 'DELETING'").use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val kind = rows.getString(1)
                if (selection.isFull() || id in selection.itemIds || kind in selection.kinds) chosen += id
            }
        }
        if (selection.collectionIds.isNotEmpty()) {
            val args = selection.collectionIds.toTypedArray()
            val marks = args.joinToString(",") { "?" }
            db.query("SELECT itemId FROM collection_member WHERE collectionId IN ($marks)", args).use { rows ->
                while (rows.moveToNext()) chosen += rows.getString(0)
            }
        }
        chosen.forEach { UUID.fromString(it) }
        return chosen.toList()
    }

    fun copy(from: SupportSQLiteDatabase, to: SupportSQLiteDatabase, ids: List<String>) {
        if (ids.isEmpty()) return
        ids.forEach { UUID.fromString(it) }
        val marks = ids.joinToString(",") { "?" }
        val args = ids.toTypedArray()
        copyWhere(from, to, "source_event", "id IN (SELECT sourceId FROM media_item WHERE id IN ($marks) AND sourceId IS NOT NULL)", args)
        copyWhere(from, to, "media_item", "id IN ($marks)", args)
        copyWhere(from, to, "transcript_segment", "mediaId IN ($marks)", args)
        copyWhere(from, to, "speech_span", "mediaId IN ($marks)", args)
        copyWhere(from, to, "media_category", "id IN (SELECT categoryId FROM media_item_category WHERE itemId IN ($marks))", args)
        copyWhere(from, to, "media_category", "id IN (SELECT parentId FROM media_category WHERE parentId IS NOT NULL AND id IN (SELECT categoryId FROM media_item_category WHERE itemId IN ($marks)))", args)
        copyWhere(from, to, "media_analysis", "mediaId IN ($marks)", args)
        copyWhere(from, to, "media_segment", "mediaId IN ($marks)", args)
        copyWhere(from, to, "media_item_category", "itemId IN ($marks)", args)
        copyWhere(from, to, "media_item_tag", "itemId IN ($marks)", args)
        copyWhere(from, to, "media_collection", "id IN (SELECT collectionId FROM collection_member WHERE itemId IN ($marks))", args)
        copyWhere(from, to, "collection_member", "itemId IN ($marks)", args)
        copyWhere(from, to, "media_bookmark", "mediaId IN ($marks)", args)
        copyWhere(from, to, "playback_state", "mediaId IN ($marks)", args)
        copyWhere(from, to, "play_history", "mediaId IN ($marks)", args)
        copyWhere(from, to, "inbox_item", "id IN (SELECT inboxId FROM inbox_member WHERE itemId IN ($marks))", args)
        copyWhere(from, to, "inbox_member", "itemId IN ($marks)", args)
        copyWhere(from, to, "drawer_message", "inboxId IN (SELECT inboxId FROM inbox_member WHERE itemId IN ($marks))", args)
        copyWhere(from, to, "action_record", "itemId IN ($marks) OR inboxId IN (SELECT inboxId FROM inbox_member WHERE itemId IN ($marks))", args + args)
        copyWhere(from, to, "saved_location", "itemId IN ($marks) OR inboxId IN (SELECT inboxId FROM inbox_member WHERE itemId IN ($marks))", args + args)
    }

    private fun copyWhere(from: SupportSQLiteDatabase, to: SupportSQLiteDatabase, table: String, where: String, args: Array<String>) {
        require(table in BackupTables.tables || table == "source_event")
        from.query("SELECT * FROM $table WHERE $where", args).use { rows ->
            if (!rows.moveToFirst()) return
            val names = rows.columnNames.toList()
            val sql = "INSERT OR IGNORE INTO $table (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }})"
            to.compileStatement(sql).use { insert ->
                bindRow(insert, rows)
                while (rows.moveToNext()) bindRow(insert, rows)
            }
        }
    }

    private fun bindRow(insert: SupportSQLiteStatement, rows: Cursor) {
        insert.clearBindings()
        for (index in 0 until rows.columnCount) when (rows.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> insert.bindNull(index + 1)
            Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(index + 1, rows.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(index + 1, rows.getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(index + 1, rows.getBlob(index))
            else -> insert.bindString(index + 1, rows.getString(index))
        }
        insert.executeInsert()
    }
}
