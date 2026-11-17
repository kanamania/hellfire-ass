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
        val selected = ids.toSet()
        val inboxes = strings(from, "SELECT DISTINCT inboxId FROM inbox_member WHERE itemId IN ($marks)", args).toSet()
        copyWhere(from, to, "source_event", "id IN (SELECT sourceId FROM media_item WHERE id IN ($marks) AND sourceId IS NOT NULL)", args)
        copyWhere(from, to, "source_event", "id IN (SELECT sourceId FROM transcript_segment WHERE mediaId IN ($marks))", args)
        copyWhere(from, to, "media_item", "id IN ($marks)", args)
        copyWhere(from, to, "transcript_segment", "mediaId IN ($marks)", args)
        copyWhere(from, to, "speech_span", "mediaId IN ($marks)", args)
        // Categories referenced by item links and bookmarks, with every ancestor, parents first.
        val categories = categoryAncestry(from, strings(from, "SELECT categoryId FROM media_item_category WHERE itemId IN ($marks)", args) +
            strings(from, "SELECT categoryId FROM media_bookmark WHERE mediaId IN ($marks) AND categoryId IS NOT NULL", args))
        for (id in categories) copyWhere(from, to, "media_category", "id = ?", arrayOf(id))
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
        // References to members or inbox items that were not selected are cleared instead of failing the restore.
        val keep = mapOf("itemId" to selected, "inboxId" to inboxes)
        copyWhere(from, to, "action_record", "inboxId IN (SELECT inboxId FROM inbox_member WHERE itemId IN ($marks))", args, mapOf("itemId" to selected))
        copyWhere(from, to, "saved_location", "itemId IN ($marks) OR inboxId IN (SELECT inboxId FROM inbox_member WHERE itemId IN ($marks))", args + args, keep)
    }

    private fun strings(db: SupportSQLiteDatabase, sql: String, args: Array<String>): List<String> = db.query(sql, args).use { rows ->
        buildList { while (rows.moveToNext()) if (!rows.isNull(0)) add(rows.getString(0)) }
    }

    /** [ids] plus all ancestors, ordered so each parent precedes its children. */
    internal fun categoryAncestry(db: SupportSQLiteDatabase, ids: Collection<String>): List<String> {
        val parentOf = linkedMapOf<String, String?>()
        val pending = ArrayDeque(ids.distinct())
        while (pending.isNotEmpty() && parentOf.size < 10_000) {
            val id = pending.removeFirst()
            if (id in parentOf) continue
            val parent = db.query("SELECT parentId FROM media_category WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
            parentOf[id] = parent
            if (parent != null && parent !in parentOf) pending.addLast(parent)
        }
        fun depth(id: String): Int { var d = 0; var p = parentOf[id]; val seen = mutableSetOf(id); while (p != null && seen.add(p)) { d++; p = parentOf[p] }; return d }
        return parentOf.keys.sortedBy(::depth)
    }

    private fun copyWhere(from: SupportSQLiteDatabase, to: SupportSQLiteDatabase, table: String, where: String, args: Array<String>,
        nullUnless: Map<String, Set<String>> = emptyMap()) {
        require(table in BackupTables.tables || table == "source_event")
        from.query("SELECT * FROM $table WHERE $where", args).use { rows ->
            if (!rows.moveToFirst()) return
            val names = rows.columnNames.toList()
            val sql = "INSERT OR IGNORE INTO $table (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }})"
            to.compileStatement(sql).use { insert ->
                bindRow(insert, rows, nullUnless)
                while (rows.moveToNext()) bindRow(insert, rows, nullUnless)
            }
        }
    }

    private fun bindRow(insert: SupportSQLiteStatement, rows: Cursor, nullUnless: Map<String, Set<String>>) {
        insert.clearBindings()
        for (index in 0 until rows.columnCount) when (rows.getType(index)) {
            Cursor.FIELD_TYPE_STRING -> nullUnless[rows.getColumnName(index)].let { allowed ->
                if (allowed != null && rows.getString(index) !in allowed) insert.bindNull(index + 1) else insert.bindString(index + 1, rows.getString(index))
            }
            Cursor.FIELD_TYPE_NULL -> insert.bindNull(index + 1)
            Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(index + 1, rows.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(index + 1, rows.getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(index + 1, rows.getBlob(index))
            else -> insert.bindString(index + 1, rows.getString(index))
        }
        insert.executeInsert()
    }
}
