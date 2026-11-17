package com.hellfire.ass.library

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** NEWEST = recently added (import/index time); TAKEN = capture date (taken, else file modified, else added); MODIFIED = file modified. */
enum class LibrarySort { NEWEST, TAKEN, MODIFIED, NAME, SIZE, KIND, RECENTLY_PLAYED }

/** Tag the library uses to keep a picture out of the normal list (similar-image review "Hide"). */
const val HIDDEN_TAG = "hidden"

data class LibraryFilter(
    val kinds: List<String> = listOf(ContentKind.IMAGE.name, ContentKind.VIDEO.name, ContentKind.AUDIO.name),
    val favoritesOnly: Boolean = false,
    val categoryId: String? = null,
    val tag: String? = null,
    val collectionId: String? = null,
    val nameContains: String = "",
    val sort: LibrarySort = LibrarySort.NEWEST,
    /** Leave out items tagged [HIDDEN_TAG] unless that tag is the filter. */
    val includeHidden: Boolean = false,
    val limit: Int = 60,
    val offset: Int = 0,
)

object LibraryQuery {
    fun sql(filter: LibraryFilter): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val kinds = filter.kinds.distinct().filter { runCatching { ContentKind.valueOf(it) }.isSuccess }
        val kindSql = if (kinds.isEmpty()) "0" else {
            kinds.forEach { args += it }
            "m.contentKind IN (${kinds.joinToString(",") { "?" }})"
        }
        args += if (filter.favoritesOnly) 1 else 0
        args += filter.categoryId
        args += filter.categoryId
        val tag = filter.tag?.trim()?.lowercase()?.ifBlank { null }
        args += tag
        args += tag
        args += filter.collectionId
        args += filter.collectionId
        args += if (filter.includeHidden || tag == HIDDEN_TAG) 1 else 0
        val name = filter.nameContains.trim()
        val nameSql = if (name.isEmpty()) "1" else {
            args += like(name)
            "m.name LIKE ? ESCAPE '\\'"
        }
        val sort = when (filter.sort) {
            LibrarySort.NAME -> "NAME"
            LibrarySort.SIZE -> "SIZE"
            LibrarySort.KIND -> "KIND"
            LibrarySort.RECENTLY_PLAYED -> "PLAYED"
            LibrarySort.NEWEST -> "NEWEST"
            LibrarySort.TAKEN -> "TAKEN"
            LibrarySort.MODIFIED -> "MODIFIED"
        }
        repeat(6) { args += sort }
        args += filter.limit.coerceIn(1, 200)
        args += filter.offset.coerceAtLeast(0)
        val statement = """
            SELECT m.* FROM media_item m
            LEFT JOIN playback_state p ON p.mediaId = m.id
            WHERE m.state NOT IN ('DELETING', 'SYNC_REMOVED') AND $kindSql
            AND (? = 0 OR m.favorite = 1)
            AND (? IS NULL OR EXISTS (SELECT 1 FROM media_item_category l WHERE l.itemId = m.id AND l.categoryId = ? AND l.state = 'ASSIGNED'))
            AND (? IS NULL OR EXISTS (SELECT 1 FROM media_item_tag t WHERE t.itemId = m.id AND t.tag = ? AND t.state = 'ASSIGNED'))
            AND (? IS NULL OR EXISTS (SELECT 1 FROM collection_member c WHERE c.itemId = m.id AND c.collectionId = ?))
            AND (? = 1 OR NOT EXISTS (SELECT 1 FROM media_item_tag h WHERE h.itemId = m.id AND h.tag = '$HIDDEN_TAG' AND h.state = 'ASSIGNED'))
            AND $nameSql
            ORDER BY
            CASE WHEN ? = 'NAME' THEN m.name END COLLATE NOCASE ASC,
            CASE WHEN ? = 'SIZE' THEN m.size END DESC,
            CASE WHEN ? = 'KIND' THEN m.contentKind END ASC,
            CASE WHEN ? = 'PLAYED' THEN p.lastPlayedAt END DESC,
            CASE WHEN ? = 'TAKEN' THEN COALESCE(m.recordedAt, m.modifiedAt, m.importedAt) END DESC,
            CASE WHEN ? = 'MODIFIED' THEN COALESCE(m.modifiedAt, m.importedAt) END DESC,
            m.importedAt DESC, m.id DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return SimpleSQLiteQuery(statement, args.toTypedArray())
    }

    private fun like(raw: String): String {
        val escaped = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}
