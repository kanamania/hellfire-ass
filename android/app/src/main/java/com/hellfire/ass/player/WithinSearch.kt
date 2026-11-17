package com.hellfire.ass.player

import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SearchText
import com.hellfire.ass.library.TimestampHit

/**
 * Fallback search used because the intelligence agent's `searchWithin` is not on this branch.
 * Reads the existing scene and transcript indexes only. Does not enqueue analysis.
 */
class LibrarySearchWithin(private val database: EventDatabase) {
    suspend fun searchWithin(itemId: String, query: String): List<TimestampHit> {
        val fts = SearchText.query(query) ?: return emptyList()
        return database.library().timestampSearch(fts, itemId, 40)
    }
}
