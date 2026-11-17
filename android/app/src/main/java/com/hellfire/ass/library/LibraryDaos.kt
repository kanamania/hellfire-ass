package com.hellfire.ass.library

import androidx.room.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.TranscriptSegment
import kotlinx.coroutines.flow.Flow

/*
 * DAOs for schema v18, split by feature area so agents edit different files/interfaces. All FTS `query`
 * parameters must come from com.hellfire.ass.data.SearchText.query(userText) (never raw user text).
 * Paging is keyset (pass the last row's importedAt/id; start with Long.MAX_VALUE and "").
 */

data class KindCount(val contentKind: String, val count: Int)
data class TagCount(val tag: String, val count: Int)
data class SearchHitRef(val ref: String, val itemId: String, val kind: String, val text: String)
/** A timestamped match inside an audio/video item. kind = SEGMENT (media_segment) or TRANSCRIPT (transcript_segment). */
data class TimestampHit(val mediaId: String, val startMs: Long, val endMs: Long, val text: String, val kind: String, val ref: String)
data class CategoryLink(@Embedded val category: MediaCategory, val linkOrigin: String, val linkState: String, val confidence: Double?)
data class StateCount(val state: String, val count: Int)

@Dao
interface LibraryDao {
    // ---- items (both MANAGED and EXTERNAL) ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertItem(item: MediaItem): Long
    @Update suspend fun updateItem(item: MediaItem)
    @Query("SELECT * FROM media_item WHERE id = :id") suspend fun item(id: String): MediaItem?
    @Query("SELECT * FROM media_item WHERE id = :id") fun observeItem(id: String): Flow<MediaItem?>
    @Query("SELECT * FROM media_item WHERE id IN (:ids)") suspend fun items(ids: List<String>): List<MediaItem>
    @Query("SELECT * FROM media_item WHERE externalUri = :uri") suspend fun byExternalUri(uri: String): MediaItem?
    /** Every item (managed or external) with this content hash: duplicate detection. */
    @Query("SELECT * FROM media_item WHERE contentHash = :hash ORDER BY importedAt") suspend fun byHash(hash: String): List<MediaItem>
    @Query("SELECT * FROM media_item WHERE contentHash IN (SELECT contentHash FROM media_item WHERE contentHash IS NOT NULL GROUP BY contentHash HAVING COUNT(*) > 1) ORDER BY contentHash, importedAt LIMIT :limit")
    suspend fun duplicates(limit: Int): List<MediaItem>

    /** One keyset page, newest import first. kinds: ContentKind names (pass all for no filter); storage: StorageMode names. */
    @Query("SELECT * FROM media_item WHERE state != 'DELETING' AND contentKind IN (:kinds) AND storage IN (:storage) AND (:favoritesOnly = 0 OR favorite = 1) AND (importedAt < :beforeAt OR (importedAt = :beforeAt AND id < :beforeId)) ORDER BY importedAt DESC, id DESC LIMIT :limit")
    suspend fun page(kinds: List<String>, storage: List<String>, favoritesOnly: Boolean, beforeAt: Long, beforeId: String, limit: Int): List<MediaItem>
    @Query("SELECT * FROM media_item WHERE state != 'DELETING' ORDER BY importedAt DESC, id DESC LIMIT :limit") fun recent(limit: Int): Flow<List<MediaItem>>
    @Query("SELECT * FROM media_item WHERE favorite = 1 AND state != 'DELETING' ORDER BY importedAt DESC LIMIT :limit") fun favorites(limit: Int): Flow<List<MediaItem>>
    @Query("SELECT m.* FROM media_item m JOIN playback_state p ON p.mediaId = m.id ORDER BY p.lastPlayedAt DESC LIMIT :limit") fun recentlyPlayed(limit: Int): Flow<List<MediaItem>>
    @Query("SELECT contentKind, COUNT(*) AS count FROM media_item WHERE state != 'DELETING' GROUP BY contentKind") fun kindCounts(): Flow<List<KindCount>>
    @Query("UPDATE media_item SET favorite = :favorite WHERE id = :id") suspend fun setFavorite(id: String, favorite: Boolean)
    @Query("UPDATE media_item SET availability = :availability WHERE id = :id") suspend fun setAvailability(id: String, availability: String)
    @Query("UPDATE media_item SET contentHash = :hash WHERE id = :id") suspend fun setHash(id: String, hash: String)
    @Query("UPDATE media_item SET syncEnabled = :enabled WHERE id = :id AND storage = 'MANAGED'") suspend fun setSyncEnabled(id: String, enabled: Boolean): Int
    @Query("UPDATE media_item SET originalMetadata = :json, width = :width, height = :height WHERE id = :id") suspend fun setMetadata(id: String, json: String?, width: Int?, height: Int?)
    /** External entries still lacking a content hash (hash them in bounded background batches). */
    @Query("SELECT * FROM media_item WHERE storage = 'EXTERNAL' AND contentHash IS NULL AND availability = 'AVAILABLE' LIMIT :limit") suspend fun unhashedExternal(limit: Int): List<MediaItem>
    @Query("SELECT * FROM media_item WHERE storage = 'EXTERNAL' AND externalUri LIKE :prefix || '%' ORDER BY id") suspend fun externalUnder(prefix: String): List<MediaItem>

    // ---- analysis ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAnalysis(analysis: MediaAnalysis)
    @Query("SELECT * FROM media_analysis WHERE mediaId = :mediaId ORDER BY createdAt DESC") fun analyses(mediaId: String): Flow<List<MediaAnalysis>>
    @Query("SELECT * FROM media_analysis WHERE mediaId = :mediaId AND analyzer = :analyzer ORDER BY createdAt DESC LIMIT 1") suspend fun latestAnalysis(mediaId: String, analyzer: String): MediaAnalysis?
    @Query("UPDATE media_analysis SET editedJson = :editedJson, editedAt = :at WHERE id = :id") suspend fun editAnalysis(id: String, editedJson: String?, at: Long)
    @Transaction suspend fun saveAnalysis(analysis: MediaAnalysis) {
        insertAnalysis(analysis); deleteSearch(analysis.id)
        if (analysis.text.isNotBlank()) insertSearch(LibrarySearch(analysis.id, analysis.mediaId, "ANALYSIS", analysis.text))
    }

    // ---- scenes / segments ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSegment(segment: MediaSegment)
    @Query("SELECT * FROM media_segment WHERE mediaId = :mediaId ORDER BY startMs") fun segments(mediaId: String): Flow<List<MediaSegment>>
    @Query("SELECT * FROM media_segment WHERE mediaId = :mediaId AND startMs < :endMs AND endMs > :startMs ORDER BY startMs") suspend fun segmentsIn(mediaId: String, startMs: Long, endMs: Long): List<MediaSegment>
    @Transaction suspend fun saveSegment(segment: MediaSegment) {
        insertSegment(segment); deleteSearch(segment.id)
        insertSearch(LibrarySearch(segment.id, segment.mediaId, "SEGMENT", listOf(segment.title, segment.description, segment.labels).joinToString(" ")))
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEmbedding(embedding: MediaEmbedding)
    @Query("SELECT * FROM media_embedding WHERE model = :model AND mediaId > :afterMediaId ORDER BY mediaId LIMIT :limit") suspend fun embeddings(model: String, afterMediaId: String, limit: Int): List<MediaEmbedding>

    // ---- categories ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCategory(category: MediaCategory): Long
    @Update suspend fun updateCategory(category: MediaCategory)
    @Query("SELECT * FROM media_category WHERE id = :id") suspend fun category(id: String): MediaCategory?
    @Query("SELECT * FROM media_category WHERE mergedInto IS NULL ORDER BY name COLLATE NOCASE") fun categories(): Flow<List<MediaCategory>>
    @Query("SELECT * FROM media_category WHERE parentId IS :parentId AND mergedInto IS NULL ORDER BY name COLLATE NOCASE") suspend fun children(parentId: String?): List<MediaCategory>
    /** Existing live sibling with this name (case-insensitive): reuse it instead of creating a duplicate. */
    @Query("SELECT * FROM media_category WHERE parentId IS :parentId AND name = :name COLLATE NOCASE AND mergedInto IS NULL LIMIT 1") suspend fun findCategory(parentId: String?, name: String): MediaCategory?
    /** AI suggestion: never replaces an existing link (so USER corrections and exclusions survive re-indexing). */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun suggestCategory(link: ItemCategory): Long
    /** User assignment or exclusion (origin USER): always wins. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setCategory(link: ItemCategory)
    @Query("DELETE FROM media_item_category WHERE itemId = :itemId AND origin = 'AI'") suspend fun clearAiCategories(itemId: String)
    @Query("SELECT c.*, l.origin AS linkOrigin, l.state AS linkState, l.confidence AS confidence FROM media_item_category l JOIN media_category c ON c.id = l.categoryId WHERE l.itemId = :itemId ORDER BY c.name")
    fun itemCategories(itemId: String): Flow<List<CategoryLink>>
    @Query("SELECT m.* FROM media_item m JOIN media_item_category l ON l.itemId = m.id WHERE l.categoryId = :categoryId AND l.state = 'ASSIGNED' AND m.state != 'DELETING' ORDER BY m.importedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun inCategory(categoryId: String, limit: Int, offset: Int): List<MediaItem>
    @Query("INSERT OR IGNORE INTO media_item_category (itemId, categoryId, origin, state, confidence, updatedAt) SELECT itemId, :into, origin, state, confidence, :now FROM media_item_category WHERE categoryId = :from")
    suspend fun copyLinks(from: String, into: String, now: Long)
    @Query("DELETE FROM media_item_category WHERE categoryId = :id") suspend fun deleteLinks(id: String)
    @Query("UPDATE media_category SET parentId = :into, updatedAt = :now WHERE parentId = :from") suspend fun reparent(from: String, into: String, now: Long)
    @Query("UPDATE media_bookmark SET categoryId = :into WHERE categoryId = :from") suspend fun moveBookmarks(from: String, into: String)
    @Query("UPDATE media_category SET mergedInto = :into, updatedAt = :now WHERE id = :from") suspend fun markMerged(from: String, into: String, now: Long)
    /** Merge [from] into [into]: links, children and bookmarks move; [from] is kept as a mergedInto redirect. */
    @Transaction suspend fun mergeCategory(from: String, into: String, now: Long) {
        require(from != into)
        copyLinks(from, into, now); deleteLinks(from); reparent(from, into, now); moveBookmarks(from, into); markMerged(from, into, now)
    }

    // ---- tags ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun suggestTag(tag: ItemTag): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setTag(tag: ItemTag)
    @Query("SELECT * FROM media_item_tag WHERE itemId = :itemId ORDER BY tag") fun tags(itemId: String): Flow<List<ItemTag>>
    @Query("SELECT tag, COUNT(*) AS count FROM media_item_tag WHERE state = 'ASSIGNED' GROUP BY tag ORDER BY count DESC, tag") fun allTags(): Flow<List<TagCount>>
    @Query("SELECT m.* FROM media_item m JOIN media_item_tag t ON t.itemId = m.id WHERE t.tag = :tag AND t.state = 'ASSIGNED' ORDER BY m.importedAt DESC LIMIT :limit")
    suspend fun withTag(tag: String, limit: Int): List<MediaItem>
    @Query("DELETE FROM media_item_tag WHERE itemId = :itemId AND origin = 'AI'") suspend fun clearAiTags(itemId: String)

    // ---- collections / albums / playlists ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCollection(collection: MediaCollection): Long
    @Update suspend fun updateCollection(collection: MediaCollection)
    @Query("DELETE FROM media_collection WHERE id = :id") suspend fun deleteCollection(id: String)
    @Query("SELECT * FROM media_collection WHERE kind IN (:kinds) ORDER BY name COLLATE NOCASE") fun collections(kinds: List<String>): Flow<List<MediaCollection>>
    @Query("SELECT m.* FROM collection_member c JOIN media_item m ON m.id = c.itemId WHERE c.collectionId = :collectionId ORDER BY c.position, c.addedAt")
    fun members(collectionId: String): Flow<List<MediaItem>>
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM collection_member WHERE collectionId = :collectionId") suspend fun nextPosition(collectionId: String): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMember(member: CollectionMember): Long
    @Transaction suspend fun addToCollection(collectionId: String, itemId: String, now: Long) {
        insertMember(CollectionMember(collectionId, itemId, nextPosition(collectionId), now))
    }
    @Query("UPDATE collection_member SET position = :position WHERE collectionId = :collectionId AND itemId = :itemId") suspend fun moveMember(collectionId: String, itemId: String, position: Long)
    @Query("DELETE FROM collection_member WHERE collectionId = :collectionId AND itemId = :itemId") suspend fun removeMember(collectionId: String, itemId: String)

    // ---- full-text and timestamp search ----
    @Insert suspend fun insertSearch(row: LibrarySearch)
    @Query("DELETE FROM library_search WHERE ref = :ref") suspend fun deleteSearch(ref: String)
    @Query("SELECT ref, itemId, kind, text FROM library_search WHERE text MATCH :query LIMIT :limit") suspend fun search(query: String, limit: Int): List<SearchHitRef>
    /** Scene and transcript passages matching [query], optionally inside one item ([mediaId] null = all items). */
    @Query("""SELECT g.mediaId AS mediaId, g.startMs AS startMs, g.endMs AS endMs, s.text AS text, 'SEGMENT' AS kind, g.id AS ref
        FROM library_search s JOIN media_segment g ON g.id = s.ref WHERE s.text MATCH :query AND (:mediaId IS NULL OR g.mediaId = :mediaId)
        UNION ALL
        SELECT t.mediaId AS mediaId, t.startMs AS startMs, t.endMs AS endMs, t.text AS text, 'TRANSCRIPT' AS kind, t.id AS ref
        FROM source_search s JOIN transcript_segment t ON t.sourceId = s.eventId WHERE s.text MATCH :query AND (:mediaId IS NULL OR t.mediaId = :mediaId)
        ORDER BY mediaId, startMs LIMIT :limit""")
    suspend fun timestampSearch(query: String, mediaId: String?, limit: Int): List<TimestampHit>
    @Query("SELECT * FROM transcript_segment WHERE mediaId = :mediaId AND startMs < :endMs AND endMs >= :startMs ORDER BY startMs") suspend fun transcriptIn(mediaId: String, startMs: Long, endMs: Long): List<TranscriptSegment>

    @Query("DELETE FROM library_search") suspend fun clearSearch()
    @Query("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'SEGMENT', title || ' ' || description || ' ' || labels FROM media_segment") suspend fun indexSegments()
    @Query("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'BOOKMARK', title || ' ' || note FROM media_bookmark") suspend fun indexBookmarks()
    @Query("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, mediaId, 'ANALYSIS', text FROM media_analysis WHERE text != ''") suspend fun indexAnalyses()
    @Query("INSERT INTO library_search (ref, itemId, kind, text) SELECT id, id, 'INBOX', title || ' ' || summary || ' ' || notes FROM inbox_item WHERE lifecycle != 'DELETED'") suspend fun indexInbox()
    /** Rebuild library_search from its source tables (after restore or migration). */
    @Transaction suspend fun rebuildSearch() { clearSearch(); indexSegments(); indexBookmarks(); indexAnalyses(); indexInbox() }
}

@Dao
interface PlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBookmark(bookmark: MediaBookmark)
    @Query("INSERT INTO library_search (ref, itemId, kind, text) VALUES (:ref, :itemId, 'BOOKMARK', :text)") suspend fun indexBookmark(ref: String, itemId: String, text: String)
    @Query("DELETE FROM library_search WHERE ref = :ref") suspend fun unindex(ref: String)
    /** Create or update a bookmark and its search text. */
    @Transaction suspend fun saveBookmark(bookmark: MediaBookmark) {
        require(bookmark.startMs >= 0 && (bookmark.endMs == null || bookmark.endMs >= bookmark.startMs))
        insertBookmark(bookmark); unindex(bookmark.id); indexBookmark(bookmark.id, bookmark.mediaId, "${bookmark.title} ${bookmark.note}")
    }
    @Query("DELETE FROM media_bookmark WHERE id = :id") suspend fun removeBookmark(id: String)
    @Transaction suspend fun deleteBookmark(id: String) { removeBookmark(id); unindex(id) }
    @Query("SELECT * FROM media_bookmark WHERE id = :id") suspend fun bookmark(id: String): MediaBookmark?
    /** Visible markers for the timeline; includeHidden for the bookmark manager. */
    @Query("SELECT * FROM media_bookmark WHERE mediaId = :mediaId AND (:includeHidden = 1 OR hidden = 0) ORDER BY startMs")
    fun bookmarks(mediaId: String, includeHidden: Boolean): Flow<List<MediaBookmark>>
    @Query("SELECT * FROM media_bookmark WHERE favorite = 1 ORDER BY updatedAt DESC LIMIT :limit") fun favoriteBookmarks(limit: Int): Flow<List<MediaBookmark>>
    @Query("UPDATE media_bookmark SET origin = 'USER', updatedAt = :now WHERE id = :id") suspend fun keepAsManual(id: String, now: Long)
    @Query("UPDATE media_bookmark SET hidden = :hidden, updatedAt = :now WHERE id = :id") suspend fun setHidden(id: String, hidden: Boolean, now: Long)
    @Query("DELETE FROM media_bookmark WHERE mediaId = :mediaId AND origin = 'AI'") suspend fun clearAiBookmarks(mediaId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePlayback(state: PlaybackState)
    @Query("SELECT * FROM playback_state WHERE mediaId = :mediaId") suspend fun playback(mediaId: String): PlaybackState?
    @Query("UPDATE playback_state SET positionMs = :positionMs, lastPlayedAt = :now, completed = :completed WHERE mediaId = :mediaId") suspend fun updatePosition(mediaId: String, positionMs: Long, completed: Boolean, now: Long): Int
    @Insert suspend fun insertHistory(entry: PlayHistory)
    @Query("UPDATE play_history SET endedAt = :endedAt, toMs = :toMs WHERE id = :id") suspend fun endHistory(id: String, endedAt: Long, toMs: Long)
    @Query("SELECT * FROM play_history ORDER BY startedAt DESC LIMIT :limit") fun history(limit: Int): Flow<List<PlayHistory>>
}

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(job: ProcessingJob): Long
    @Query("SELECT * FROM processing_job WHERE id = :id") suspend fun job(id: String): ProcessingJob?
    @Query("SELECT * FROM processing_job WHERE type = :type AND itemId IS :itemId") suspend fun find(type: String, itemId: String?): ProcessingJob?
    /** Queue a job; an existing (type, itemId) job is kept, and requeued if it had finished or failed. */
    @Transaction suspend fun enqueue(job: ProcessingJob): ProcessingJob {
        if (insert(job) != -1L) return job
        val existing = find(job.type, job.itemId)!!
        if (existing.state == JobState.COMPLETED.name || existing.state == JobState.FAILED.name) requeue(existing.id, job.priority, job.updatedAt)
        return find(job.type, job.itemId)!!
    }
    @Query("UPDATE processing_job SET state = 'PENDING', priority = MIN(priority, :priority), attempts = 0, error = NULL, notBefore = 0, claimToken = NULL, updatedAt = :now WHERE id = :id")
    suspend fun requeue(id: String, priority: Int, now: Long)
    @Query("UPDATE processing_job SET state = 'PREPARING', claimToken = :token, updatedAt = :now WHERE id = (SELECT id FROM processing_job WHERE state IN ('PENDING', 'RETRY_PENDING') AND notBefore <= :now AND (:type IS NULL OR type = :type) ORDER BY priority, createdAt LIMIT 1)")
    suspend fun markClaimed(token: String, type: String?, now: Long): Int
    @Query("SELECT * FROM processing_job WHERE claimToken = :token") suspend fun claimed(token: String): ProcessingJob?
    /** Atomically take the most urgent runnable job (state → PREPARING), or null. [token] must be unique per claim (UUID). */
    @Transaction suspend fun claim(token: String, now: Long, type: String? = null): ProcessingJob? =
        if (markClaimed(token, type, now) == 1) claimed(token) else null
    @Query("UPDATE processing_job SET state = :state, progress = :progress, checkpoint = :checkpoint, updatedAt = :now WHERE id = :id AND claimToken = :token")
    suspend fun checkpoint(id: String, token: String, state: String, progress: Double, checkpoint: String, now: Long): Int
    @Query("UPDATE processing_job SET state = 'COMPLETED', progress = 1.0, error = NULL, claimToken = NULL, updatedAt = :now WHERE id = :id AND claimToken = :token")
    suspend fun complete(id: String, token: String, now: Long): Int
    /** Record a failure: RETRY_PENDING until [maxAttempts] is reached, then FAILED. The checkpoint is kept for resumption. */
    @Query("UPDATE processing_job SET attempts = attempts + 1, error = :error, claimToken = NULL, notBefore = :retryAt, updatedAt = :now, state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'RETRY_PENDING' END WHERE id = :id")
    suspend fun fail(id: String, error: String, retryAt: Long, maxAttempts: Int, now: Long)
    /** After process death: running jobs go back to PENDING and resume from their checkpoint. */
    @Query("UPDATE processing_job SET state = 'PENDING', claimToken = NULL WHERE state IN ('PREPARING', 'PROCESSING', 'INDEXING')") suspend fun recoverInterrupted(): Int
    @Query("UPDATE processing_job SET state = 'PENDING', claimToken = NULL WHERE type = :type AND state IN ('PREPARING', 'PROCESSING', 'INDEXING')") suspend fun recoverType(type: String): Int
    @Query("UPDATE processing_job SET state = 'PAUSED', updatedAt = :now WHERE state IN ('PENDING', 'RETRY_PENDING') AND (:id IS NULL OR id = :id)") suspend fun pause(id: String?, now: Long): Int
    @Query("UPDATE processing_job SET state = 'PENDING', updatedAt = :now WHERE state = 'PAUSED' AND (:id IS NULL OR id = :id)") suspend fun resume(id: String?, now: Long): Int
    @Query("UPDATE processing_job SET state = 'PENDING', attempts = 0, error = NULL, notBefore = 0, updatedAt = :now WHERE state = 'FAILED' AND (:id IS NULL OR id = :id)") suspend fun retryFailed(id: String?, now: Long): Int
    @Query("UPDATE processing_job SET priority = :priority, updatedAt = :now WHERE id = :id") suspend fun prioritize(id: String, priority: Int, now: Long)
    @Query("DELETE FROM processing_job WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM processing_job WHERE state != 'COMPLETED' ORDER BY CASE WHEN state IN ('PREPARING','PROCESSING','INDEXING') THEN 0 ELSE 1 END, priority, createdAt LIMIT :limit")
    fun queue(limit: Int): Flow<List<ProcessingJob>>
    @Query("SELECT * FROM processing_job WHERE itemId = :itemId ORDER BY createdAt") fun forItem(itemId: String): Flow<List<ProcessingJob>>
    @Query("SELECT state, COUNT(*) AS count FROM processing_job GROUP BY state") fun counts(): Flow<List<StateCount>>
}

@Dao
interface InboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(item: InboxItem): Long
    @Update suspend fun update(item: InboxItem)
    @Query("SELECT * FROM inbox_item WHERE id = :id") suspend fun item(id: String): InboxItem?
    @Query("SELECT * FROM inbox_item WHERE id = :id") fun observe(id: String): Flow<InboxItem?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMember(member: InboxMember): Long
    @Query("INSERT INTO library_search (ref, itemId, kind, text) VALUES (:id, :id, 'INBOX', :text)") suspend fun index(id: String, text: String)
    @Query("DELETE FROM library_search WHERE ref = :id") suspend fun unindex(id: String)
    /** Record a capture and its content items in one transaction (items must already exist in media_item). */
    @Transaction suspend fun saveCapture(item: InboxItem, itemIds: List<String>) {
        insert(item)
        itemIds.forEachIndexed { index, id -> insertMember(InboxMember(item.id, id, index)) }
        index(item.id, "${item.title} ${item.summary} ${item.notes}")
    }
    /** Save title/summary/notes edits and refresh the search text. */
    @Transaction suspend fun saveEdits(item: InboxItem) {
        update(item); unindex(item.id)
        if (item.lifecycle != "DELETED") index(item.id, "${item.title} ${item.summary} ${item.notes}")
    }
    @Query("SELECT m.* FROM inbox_member i JOIN media_item m ON m.id = i.itemId WHERE i.inboxId = :inboxId ORDER BY i.position") fun contents(inboxId: String): Flow<List<MediaItem>>
    @Query("SELECT m.* FROM inbox_member i JOIN media_item m ON m.id = i.itemId WHERE i.inboxId = :inboxId ORDER BY i.position") suspend fun contentsNow(inboxId: String): List<MediaItem>
    /** Keyset page by capture time for one lifecycle (ACTIVE, ARCHIVED, TRASHED). */
    @Query("SELECT * FROM inbox_item WHERE lifecycle = :lifecycle AND (:favoritesOnly = 0 OR favorite = 1) AND (capturedAt < :beforeAt OR (capturedAt = :beforeAt AND id < :beforeId)) ORDER BY capturedAt DESC, id DESC LIMIT :limit")
    suspend fun page(lifecycle: String, favoritesOnly: Boolean, beforeAt: Long, beforeId: String, limit: Int): List<InboxItem>
    @Query("SELECT * FROM inbox_item WHERE lifecycle = 'ACTIVE' ORDER BY capturedAt DESC LIMIT :limit") fun recent(limit: Int): Flow<List<InboxItem>>
    @Query("UPDATE inbox_item SET lifecycle = :lifecycle, trashedAt = CASE WHEN :lifecycle = 'TRASHED' THEN :now ELSE NULL END, updatedAt = :now WHERE id = :id")
    suspend fun setLifecycle(id: String, lifecycle: String, now: Long)
    @Query("UPDATE inbox_item SET processingState = :state, updatedAt = :now WHERE id = :id") suspend fun setProcessingState(id: String, state: String, now: Long)
    @Query("UPDATE inbox_item SET syncState = :state, updatedAt = :now WHERE id = :id") suspend fun setSyncState(id: String, state: String, now: Long)
    @Query("UPDATE inbox_item SET backupState = :state, updatedAt = :now WHERE id = :id") suspend fun setBackupState(id: String, state: String, now: Long)
    @Query("SELECT * FROM inbox_item WHERE lifecycle = 'TRASHED' AND trashedAt < :before") suspend fun trashedBefore(before: Long): List<InboxItem>
    @Query("DELETE FROM inbox_item WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT i.* FROM inbox_item i JOIN inbox_member m ON m.inboxId = i.id WHERE m.itemId = :itemId") suspend fun capturesOf(itemId: String): List<InboxItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMessage(message: DrawerMessage): Long
    @Query("SELECT * FROM drawer_message WHERE sessionId = :sessionId ORDER BY createdAt") fun session(sessionId: String): Flow<List<DrawerMessage>>
    @Query("SELECT * FROM drawer_message WHERE inboxId = :inboxId ORDER BY createdAt") fun conversation(inboxId: String): Flow<List<DrawerMessage>>
    @Query("SELECT * FROM drawer_message WHERE sessionId = :sessionId AND inContext = 1 ORDER BY createdAt DESC LIMIT :limit") suspend fun context(sessionId: String, limit: Int): List<DrawerMessage>
    /** Clears the model context only; saved items and the history stay. */
    @Query("UPDATE drawer_message SET inContext = 0 WHERE sessionId = :sessionId") suspend fun clearContext(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAction(action: ActionRecord): Long
    @Query("UPDATE action_record SET state = :state, result = :result, error = :error, updatedAt = :now WHERE id = :id") suspend fun setActionState(id: String, state: String, result: String?, error: String?, now: Long)
    @Query("SELECT * FROM action_record WHERE inboxId = :inboxId ORDER BY createdAt") fun actions(inboxId: String): Flow<List<ActionRecord>>
    @Query("SELECT * FROM action_record WHERE itemId = :itemId ORDER BY createdAt") fun actionsForItem(itemId: String): Flow<List<ActionRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLocation(location: SavedLocation)
    @Query("SELECT * FROM saved_location ORDER BY updatedAt DESC") fun locations(): Flow<List<SavedLocation>>
    @Query("SELECT * FROM saved_location WHERE id = :id") suspend fun location(id: String): SavedLocation?
    @Query("DELETE FROM saved_location WHERE id = :id") suspend fun deleteLocation(id: String)
}

@Dao
interface ShareDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveExport(export: ShareExport)
    @Query("SELECT * FROM share_export WHERE id = :id") suspend fun export(id: String): ShareExport?
    @Query("SELECT * FROM share_export WHERE itemId = :itemId ORDER BY createdAt DESC") fun exports(itemId: String): Flow<List<ShareExport>>
    @Query("SELECT * FROM share_export WHERE outputPath IS NOT NULL AND createdAt < :before") suspend fun staleOutputs(before: Long): List<ShareExport>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveFindings(findings: List<PrivacyFinding>)
    @Query("SELECT * FROM privacy_finding WHERE exportId = :exportId ORDER BY container, field") suspend fun findings(exportId: String): List<PrivacyFinding>
}

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: SyncRecord)
    @Query("SELECT * FROM sync_record WHERE entityType = :type AND entityId = :id") suspend fun record(type: String, id: String): SyncRecord?
    /** Mark an entity changed locally (new record, or reset to PENDING); [deleted] queues a tombstone. Remote id, version and chunk progress are kept. */
    @Transaction suspend fun markChanged(type: String, id: String, deleted: Boolean, now: Long) {
        val existing = record(type, id)
        save(existing?.copy(state = "PENDING", deleted = deleted, attempts = 0, nextAttemptAt = 0, error = null, updatedAt = now)
            ?: SyncRecord(type, id, deleted = deleted, updatedAt = now))
    }
    /** Durable outbound queue: due PENDING/FAILED records, oldest change first. */
    @Query("SELECT * FROM sync_record WHERE state IN ('PENDING', 'FAILED') AND nextAttemptAt <= :now ORDER BY updatedAt LIMIT :limit") suspend fun due(now: Long, limit: Int): List<SyncRecord>
    @Query("UPDATE sync_record SET state = 'UPLOADING', bytesTotal = :total, bytesDone = :done, uploadSession = :session WHERE entityType = :type AND entityId = :id")
    suspend fun progress(type: String, id: String, total: Long, done: Long, session: String?)
    @Query("UPDATE sync_record SET state = 'SYNCED', remoteId = :remoteId, version = :version, localHash = :hash, remoteUpdatedAt = :remoteUpdatedAt, uploadSession = NULL, error = NULL, attempts = 0, syncedAt = :now WHERE entityType = :type AND entityId = :id")
    suspend fun synced(type: String, id: String, remoteId: String?, version: Long, hash: String?, remoteUpdatedAt: Long?, now: Long)
    @Query("UPDATE sync_record SET state = 'FAILED', attempts = attempts + 1, error = :error, nextAttemptAt = :retryAt WHERE entityType = :type AND entityId = :id")
    suspend fun failed(type: String, id: String, error: String, retryAt: Long)
    /** After process death an interrupted upload resumes from bytesDone with its uploadSession. */
    @Query("UPDATE sync_record SET state = 'PENDING' WHERE state = 'UPLOADING'") suspend fun recoverInterrupted(): Int
    @Query("UPDATE sync_record SET state = 'PAUSED' WHERE state IN ('PENDING', 'FAILED')") suspend fun pauseAll(): Int
    @Query("UPDATE sync_record SET state = 'PENDING', nextAttemptAt = 0 WHERE state = 'PAUSED'") suspend fun resumeAll(): Int
    @Query("SELECT state, COUNT(*) AS count FROM sync_record GROUP BY state") fun counts(): Flow<List<StateCount>>
    @Query("SELECT COALESCE(SUM(bytesTotal - bytesDone), 0) FROM sync_record WHERE state IN ('PENDING', 'UPLOADING', 'FAILED')") fun pendingBytes(): Flow<Long>
    @Query("DELETE FROM sync_record WHERE deleted = 1 AND state = 'SYNCED'") suspend fun purgeSentTombstones(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveConflict(conflict: SyncConflict)
    @Query("SELECT * FROM sync_conflict WHERE resolvedAt IS NULL ORDER BY detectedAt") fun openConflicts(): Flow<List<SyncConflict>>
    @Query("UPDATE sync_conflict SET resolvedAt = :now, resolution = :resolution WHERE id = :id") suspend fun resolve(id: String, resolution: String, now: Long)

    // ---- backups ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveManifest(manifest: BackupManifest)
    @Query("SELECT * FROM backup_manifest ORDER BY createdAt DESC LIMIT :limit") fun manifests(limit: Int): Flow<List<BackupManifest>>
    @Query("SELECT * FROM backup_manifest WHERE state = 'VERIFIED' ORDER BY createdAt DESC LIMIT 1") suspend fun lastVerified(): BackupManifest?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBackupItems(items: List<BackupItemRecord>)
    @Query("UPDATE backup_item SET verifiedAt = :now WHERE manifestId = :manifestId") suspend fun verifyBackupItems(manifestId: String, now: Long)
    /** Managed originals with no verified backup of their current content hash. */
    @Query("SELECT m.* FROM media_item m WHERE m.storage = 'MANAGED' AND NOT EXISTS (SELECT 1 FROM backup_item b WHERE b.itemId = m.id AND b.contentHash = m.contentHash AND b.verifiedAt IS NOT NULL) ORDER BY m.importedAt LIMIT :limit")
    suspend fun notBackedUp(limit: Int): List<MediaItem>
    @Query("SELECT MAX(b.verifiedAt) FROM backup_item b JOIN media_item m ON m.id = b.itemId AND m.contentHash = b.contentHash WHERE b.itemId = :itemId")
    suspend fun lastVerifiedBackup(itemId: String): Long?
}
