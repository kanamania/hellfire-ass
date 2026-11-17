package com.hellfire.ass.player

import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.library.LibraryTimestampSearch
import com.hellfire.ass.library.TimestampHit

/** Read-only timestamp lookup over saved scenes and transcripts. */
class LibrarySearchWithin(private val database: EventDatabase) {
    suspend fun searchWithin(itemId: String, query: String): List<TimestampHit> =
        LibraryTimestampSearch(database.library()).searchWithin(itemId, query)
}
