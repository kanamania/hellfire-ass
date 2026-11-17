package com.hellfire.ass.library

import com.hellfire.ass.data.SearchText

/**
 * Within-item timestamp search for the players agent.
 * Call [searchWithin] with a media_item id and the user's words.
 */
class LibraryTimestampSearch(private val dao: LibraryDao) {
    suspend fun searchWithin(itemId: String, query: String): List<TimestampHit> {
        val fts = SearchText.query(query) ?: return emptyList()
        return runCatching { dao.timestampSearch(fts, itemId, 40) }.getOrDefault(emptyList())
            .sortedBy { it.startMs }
    }
}

suspend fun LibraryDao.searchWithin(itemId: String, query: String): List<TimestampHit> =
    LibraryTimestampSearch(this).searchWithin(itemId, query)
