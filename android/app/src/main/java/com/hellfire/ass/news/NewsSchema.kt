package com.hellfire.ass.news

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * News intelligence storage (Room v18). Public news only: no captured private data is written here.
 * Owner choices (read, saved, hidden, feedback, preferences, positions) are backed up and synced; articles,
 * events and briefings are backed up so offline briefings survive a restore.
 */

/** A pluggable source. [kind]: RSS (RSS 2.0, RSS 1.0/RDF or Atom, detected), JSON_FEED, X_ACCOUNT, X_QUERY,
 * SITE (website whose feed must be discovered first) or LICENSED (needs a contract; never fetched). */
@Entity(tableName = "news_source")
data class NewsSource(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    /** Feed URL, website URL, X handle (without @) or X search query. */
    val url: String,
    /** TANZANIA, EAST_AFRICA, INTERNATIONAL, TECHNOLOGY, SOCIAL, CUSTOM. */
    val category: String,
    /** en, sw or mixed. */
    val language: String,
    /** 0..100, owner-editable reporting-quality estimate. */
    val reliability: Int,
    /** -2..2; owner priority. */
    val priority: Int = 0,
    /** ACTIVE, MUTED, REMOVED, NEEDS_FEED, NEEDS_KEY, NEEDS_LICENSE. */
    val state: String = "ACTIVE",
    /** Official institution or newsroom account (not virality). */
    val official: Boolean = false,
    /** Wire agency this source is (REUTERS, AP, AFP), for syndication detection. */
    val agency: String? = null,
    /** Access or licensing note shown to the owner. */
    val note: String = "",
    val builtIn: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Connector cursor, e.g. the newest X post id seen. */
    val cursor: String? = null,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val nextAttemptAt: Long = 0,
    val failures: Int = 0,
    val lastError: String? = null,
    val lastCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One fetched or dropped-in item. [text] is the feed's own excerpt (at most 4000 chars), never a scraped full copy. */
@Entity(tableName = "news_article", indices = [Index(value = ["canonicalUrl"], unique = true), Index("eventId"), Index("publishedAt"), Index("sourceId")])
data class NewsArticle(
    /** sha256(canonicalUrl) hex, first 32 chars. */
    @PrimaryKey val id: String,
    val sourceId: String,
    val url: String,
    val canonicalUrl: String,
    val title: String,
    val text: String,
    val author: String? = null,
    val language: String,
    val publishedAt: Long,
    val fetchedAt: Long,
    /** NEWS, OPINION, ANALYSIS, SATIRE, ADVERTISEMENT, SOCIAL_POST, OFFICIAL_STATEMENT. */
    val contentType: String = "NEWS",
    /** Wire agency the text credits (REUTERS, AP, AFP), if any. */
    val agency: String? = null,
    /** 64-bit simhash of normalized title+text as 16 hex chars, for copy detection. */
    val fingerprint: String,
    /** JSON array of {type: IMAGE|VIDEO|AUDIO|EMBED, url, mime?, credit?, durationMs?}. Links only; nothing is rehosted. */
    val mediaJson: String = "[]",
    /** Engine features (ArticleFeatures JSON). */
    val featuresJson: String = "{}",
    /** Optional L2-normalized embedding (little-endian float32), from the owner's imported embedder. */
    val vector: ByteArray? = null,
    val eventId: String? = null,
    /** Dropped in by the owner through share/drawer. */
    val shared: Boolean = false,
)

/** One underlying real-world event (cluster of coverage). */
@Entity(tableName = "news_event", indices = [Index("updatedAt"), Index("lastArticleAt"), Index("mergedInto")])
data class NewsEvent(
    @PrimaryKey val id: String,
    val headline: String,
    val eventType: String,
    /** Comma-separated topic keys (tanzania, east_africa, global, technology, ai, smartphones, hardware, software, business, economy, science, sports, entertainment, health, politics, ...). */
    val topics: String,
    val place: String? = null,
    /** Human-readable canonical identity, e.g. "DISASTER|dar es salaam|2026-11-17|flood". */
    val signature: String,
    val firstSeenAt: Long,
    /** Time of the latest material version. */
    val updatedAt: Long,
    val lastArticleAt: Long,
    val version: Int = 1,
    /** DEVELOPING, ONGOING, RESOLVED. */
    val status: String = "DEVELOPING",
    val score: Int = 0,
    /** CRITICAL, HIGH, MEDIUM, LOW. */
    val level: String = "LOW",
    /** Factor breakdown and reasons (NewsScore JSON). */
    val scoreJson: String = "{}",
    /** Public-safety, emergency, major economic or national development: never personalized away. */
    val protectedPublic: Boolean = false,
    val independentSources: Int = 1,
    val articleCount: Int = 1,
    /** CONFIRMED, SINGLE_SOURCE, CONFLICTING, UNVERIFIED_SOCIAL. */
    val verification: String = "SINGLE_SOURCE",
    /** Aggregated clustering centroid (EventCentroid JSON). */
    val featuresJson: String = "{}",
    /** Cached StorySummary JSON for [summaryKey]. */
    val summaryJson: String? = null,
    /** "<version>|<length>|<language>" the cached summary was built for. */
    val summaryKey: String? = null,
    /** Set when the owner merged this event into another. */
    val mergedInto: String? = null,
    val breakingNotifiedVersion: Int = 0,
)

/** Versioned snapshot of an event. Version 1 is the first report; later versions exist only for material changes. */
@Entity(tableName = "news_event_version", primaryKeys = ["eventId", "version"])
data class NewsEventVersion(
    val eventId: String,
    val version: Int,
    val at: Long,
    /** NEW, MAJOR, MINOR, CORRECTION, RESOLVED. */
    val classification: String,
    /** EventSnapshot JSON (facts with evidence). */
    val factsJson: String,
    val previously: String,
    val whatsNew: String,
    val whyItMatters: String,
    /** JSON array of article ids that evidence this version. */
    val evidenceJson: String,
)

/** A generated briefing manifest. [itemsJson]: JSON array of {eventId, version, section: TOP|STORY|CHANGED, classification?}. */
@Entity(tableName = "news_briefing", indices = [Index("generatedAt")])
data class NewsBriefing(
    /** "<slot>:<yyyy-MM-dd>" or "<slot>:<yyyy-MM-dd>:<HHmm>" for custom and on-demand briefings. */
    @PrimaryKey val id: String,
    /** MORNING, EVENING, CUSTOM, ON_DEMAND, BREAKING. */
    val slot: String,
    val title: String,
    val scheduledFor: Long,
    val generatedAt: Long,
    val windowStart: Long,
    val windowEnd: Long,
    val itemsJson: String,
    val note: String = "",
)

/** The owner's per-event state, synced between phone and web. [fieldTimes] holds per-field change times for last-writer-wins. */
@Entity(tableName = "news_event_state")
data class NewsEventState(
    @PrimaryKey val eventId: String,
    val deliveredVersion: Int = 0,
    val deliveredAt: Long? = null,
    /** Highest version the owner has read; only grows (except explicit mark-unread). */
    val seenVersion: Int = 0,
    val readAt: Long? = null,
    val bookmarked: Boolean = false,
    val readLater: Boolean = false,
    val hidden: Boolean = false,
    val followed: Boolean = false,
    val fieldTimes: String = "{}",
    val updatedAt: Long,
)

/** Reader and audio position in one briefing, synced between phone and web. */
@Entity(tableName = "news_progress")
data class NewsProgress(@PrimaryKey val briefingId: String, val position: Int, val audioMs: Long = 0, val updatedAt: Long)

/** [key]: topic:<t>, place:<p>, source:<id>, term:<word>. [kind]: EXPLICIT (owner chose), INFERRED (learned), HIDDEN (owner hid the subject). */
@Entity(tableName = "news_preference", primaryKeys = ["key", "kind"])
data class NewsPreference(val key: String, val kind: String, val weight: Double, val label: String, val updatedAt: Long)

/** Owner corrections. [kind]: NOT_SAME_EVENT (article does not belong), SAME_EVENT (merge events), MORE_IMPORTANT, LESS_IMPORTANT, MISSED, WRONG_SUMMARY. */
@Entity(tableName = "news_feedback", indices = [Index("articleId"), Index("eventId")])
data class NewsFeedback(
    @PrimaryKey val id: String,
    val kind: String,
    val eventId: String,
    val articleId: String? = null,
    val otherEventId: String? = null,
    val note: String = "",
    val at: Long,
)

/** Observability log: one row per stage run. [stage]: INGEST, CLUSTER, SUMMARY, BRIEFING, NOTIFY, SYNC. */
@Entity(tableName = "news_run", indices = [Index("at")])
data class NewsRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val stage: String,
    val sourceId: String? = null,
    val ok: Boolean,
    val count: Int = 0,
    val error: String? = null,
    val microUsd: Long = 0,
    val durationMs: Long = 0,
)

data class NewsStageCount(val stage: String, val ok: Boolean, val runs: Int, val items: Long, val microUsd: Long)

@Dao
interface NewsDao {
    // --- Sources -----------------------------------------------------------------------------------------------
    @Query("SELECT * FROM news_source WHERE state != 'REMOVED' ORDER BY category, priority DESC, name") fun sources(): Flow<List<NewsSource>>
    @Query("SELECT * FROM news_source ORDER BY category, priority DESC, name") suspend fun allSources(): List<NewsSource>
    @Query("SELECT * FROM news_source WHERE id = :id") suspend fun source(id: String): NewsSource?
    @Query("SELECT * FROM news_source WHERE state = 'ACTIVE' AND nextAttemptAt <= :now ORDER BY priority DESC, nextAttemptAt") suspend fun dueSources(now: Long): List<NewsSource>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSource(source: NewsSource): Long
    @Upsert suspend fun saveSource(source: NewsSource)

    // --- Articles ----------------------------------------------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertArticle(article: NewsArticle): Long
    @Upsert suspend fun saveArticle(article: NewsArticle)
    @Query("SELECT * FROM news_article WHERE id = :id") suspend fun article(id: String): NewsArticle?
    @Query("SELECT * FROM news_article WHERE canonicalUrl = :canonicalUrl") suspend fun articleByUrl(canonicalUrl: String): NewsArticle?
    @Query("SELECT * FROM news_article WHERE eventId = :eventId ORDER BY publishedAt") suspend fun articlesFor(eventId: String): List<NewsArticle>
    @Query("SELECT * FROM news_article WHERE eventId IS NULL ORDER BY publishedAt LIMIT :limit") suspend fun unclustered(limit: Int): List<NewsArticle>
    @Query("UPDATE news_article SET eventId = :eventId WHERE id = :id") suspend fun assign(id: String, eventId: String?)
    @Query("UPDATE news_article SET eventId = :to WHERE eventId = :from") suspend fun moveArticles(from: String, to: String)

    // --- Events ------------------------------------------------------------------------------------------------
    @Upsert suspend fun saveEvent(event: NewsEvent)
    @Query("SELECT * FROM news_event WHERE id = :id") suspend fun event(id: String): NewsEvent?
    @Query("SELECT * FROM news_event WHERE id = :id") fun eventFlow(id: String): Flow<NewsEvent?>
    @Query("SELECT * FROM news_event WHERE id IN (:ids)") suspend fun events(ids: List<String>): List<NewsEvent>
    /** Clustering candidates: unmerged events with coverage since [since]. */
    @Query("SELECT * FROM news_event WHERE mergedInto IS NULL AND lastArticleAt >= :since ORDER BY lastArticleAt DESC LIMIT :limit") suspend fun activeEvents(since: Long, limit: Int = 2000): List<NewsEvent>
    @Query("SELECT * FROM news_event WHERE mergedInto IS NULL AND lastArticleAt >= :from AND firstSeenAt < :to ORDER BY score DESC, lastArticleAt DESC LIMIT :limit") suspend fun eventsBetween(from: Long, to: Long, limit: Int = 500): List<NewsEvent>
    @Query("SELECT * FROM news_event WHERE mergedInto IS NULL ORDER BY lastArticleAt DESC LIMIT :limit") fun recentEvents(limit: Int): Flow<List<NewsEvent>>

    // --- Versions ----------------------------------------------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVersion(version: NewsEventVersion)
    @Query("SELECT * FROM news_event_version WHERE eventId = :eventId ORDER BY version") suspend fun versions(eventId: String): List<NewsEventVersion>
    @Query("SELECT * FROM news_event_version WHERE eventId = :eventId AND version = :version") suspend fun version(eventId: String, version: Int): NewsEventVersion?

    // --- Briefings ---------------------------------------------------------------------------------------------
    @Upsert suspend fun saveBriefing(briefing: NewsBriefing)
    @Query("SELECT * FROM news_briefing WHERE id = :id") suspend fun briefing(id: String): NewsBriefing?
    @Query("SELECT * FROM news_briefing WHERE slot IN (:slots) ORDER BY generatedAt DESC LIMIT :limit") fun briefings(slots: List<String>, limit: Int): Flow<List<NewsBriefing>>
    @Query("SELECT * FROM news_briefing WHERE slot IN (:slots) AND generatedAt < :before ORDER BY generatedAt DESC LIMIT 1") suspend fun previousBriefing(slots: List<String>, before: Long): NewsBriefing?
    @Query("SELECT * FROM news_briefing ORDER BY generatedAt DESC LIMIT :limit") suspend fun latestBriefings(limit: Int): List<NewsBriefing>

    // --- Owner state -------------------------------------------------------------------------------------------
    @Upsert suspend fun saveState(state: NewsEventState)
    @Query("SELECT * FROM news_event_state WHERE eventId = :eventId") suspend fun state(eventId: String): NewsEventState?
    @Query("SELECT * FROM news_event_state WHERE eventId = :eventId") fun stateFlow(eventId: String): Flow<NewsEventState?>
    @Query("SELECT * FROM news_event_state WHERE eventId IN (:ids)") suspend fun states(ids: List<String>): List<NewsEventState>
    @Query("SELECT * FROM news_event_state WHERE bookmarked = 1 OR readLater = 1 ORDER BY updatedAt DESC") fun saved(): Flow<List<NewsEventState>>
    @Upsert suspend fun saveProgress(progress: NewsProgress)
    @Query("SELECT * FROM news_progress WHERE briefingId = :briefingId") suspend fun progress(briefingId: String): NewsProgress?

    // --- Preferences and feedback ------------------------------------------------------------------------------
    @Upsert suspend fun savePreference(preference: NewsPreference)
    @Query("SELECT * FROM news_preference ORDER BY kind, weight DESC") fun preferences(): Flow<List<NewsPreference>>
    @Query("SELECT * FROM news_preference") suspend fun allPreferences(): List<NewsPreference>
    @Query("DELETE FROM news_preference WHERE key = :key AND kind = :kind") suspend fun deletePreference(key: String, kind: String)
    @Query("DELETE FROM news_preference WHERE kind = 'INFERRED'") suspend fun resetInferred()
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFeedback(feedback: NewsFeedback)
    @Query("SELECT * FROM news_feedback ORDER BY at") suspend fun allFeedback(): List<NewsFeedback>

    // --- Observability -----------------------------------------------------------------------------------------
    @Insert suspend fun insertRun(run: NewsRun)
    @Query("SELECT stage, ok, COUNT(*) AS runs, SUM(count) AS items, SUM(microUsd) AS microUsd FROM news_run WHERE at >= :since GROUP BY stage, ok") suspend fun runSummary(since: Long): List<NewsStageCount>
    @Query("SELECT * FROM news_run ORDER BY at DESC LIMIT :limit") suspend fun recentRuns(limit: Int): List<NewsRun>
    @Query("DELETE FROM news_run WHERE at < :before") suspend fun pruneRuns(before: Long)

    // --- Runtime queries (owned by the runtime branch; append below) -------------------------------------------

    // --- UI queries (owned by the UI branch; append below) ------------------------------------------------------
    /** Owner grouping corrections (split or merge), for News health. */
    @Query("SELECT COUNT(*) FROM news_feedback WHERE kind IN ('NOT_SAME_EVENT', 'SAME_EVENT')") suspend fun clusterCorrections(): Int

    // --- Sync queries (owned by the web/sync branch; append below) ----------------------------------------------
}

object NewsSchema {
    /** Tables in backup order. All are restorable; news_run is a log but small and kept for cost history. */
    val tables = listOf("news_source", "news_article", "news_event", "news_event_version", "news_briefing",
        "news_event_state", "news_progress", "news_preference", "news_feedback", "news_run")

    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_source` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `url` TEXT NOT NULL, `category` TEXT NOT NULL, `language` TEXT NOT NULL, `reliability` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `state` TEXT NOT NULL, `official` INTEGER NOT NULL, `agency` TEXT, `note` TEXT NOT NULL, `builtIn` INTEGER NOT NULL, `etag` TEXT, `lastModified` TEXT, `cursor` TEXT, `lastAttemptAt` INTEGER, `lastSuccessAt` INTEGER, `nextAttemptAt` INTEGER NOT NULL, `failures` INTEGER NOT NULL, `lastError` TEXT, `lastCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_article` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `url` TEXT NOT NULL, `canonicalUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `author` TEXT, `language` TEXT NOT NULL, `publishedAt` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, `contentType` TEXT NOT NULL, `agency` TEXT, `fingerprint` TEXT NOT NULL, `mediaJson` TEXT NOT NULL, `featuresJson` TEXT NOT NULL, `vector` BLOB, `eventId` TEXT, `shared` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_news_article_canonicalUrl` ON `news_article` (`canonicalUrl`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_article_eventId` ON `news_article` (`eventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_article_publishedAt` ON `news_article` (`publishedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_article_sourceId` ON `news_article` (`sourceId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_event` (`id` TEXT NOT NULL, `headline` TEXT NOT NULL, `eventType` TEXT NOT NULL, `topics` TEXT NOT NULL, `place` TEXT, `signature` TEXT NOT NULL, `firstSeenAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastArticleAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `status` TEXT NOT NULL, `score` INTEGER NOT NULL, `level` TEXT NOT NULL, `scoreJson` TEXT NOT NULL, `protectedPublic` INTEGER NOT NULL, `independentSources` INTEGER NOT NULL, `articleCount` INTEGER NOT NULL, `verification` TEXT NOT NULL, `featuresJson` TEXT NOT NULL, `summaryJson` TEXT, `summaryKey` TEXT, `mergedInto` TEXT, `breakingNotifiedVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_event_updatedAt` ON `news_event` (`updatedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_event_lastArticleAt` ON `news_event` (`lastArticleAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_event_mergedInto` ON `news_event` (`mergedInto`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_event_version` (`eventId` TEXT NOT NULL, `version` INTEGER NOT NULL, `at` INTEGER NOT NULL, `classification` TEXT NOT NULL, `factsJson` TEXT NOT NULL, `previously` TEXT NOT NULL, `whatsNew` TEXT NOT NULL, `whyItMatters` TEXT NOT NULL, `evidenceJson` TEXT NOT NULL, PRIMARY KEY(`eventId`, `version`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_briefing` (`id` TEXT NOT NULL, `slot` TEXT NOT NULL, `title` TEXT NOT NULL, `scheduledFor` INTEGER NOT NULL, `generatedAt` INTEGER NOT NULL, `windowStart` INTEGER NOT NULL, `windowEnd` INTEGER NOT NULL, `itemsJson` TEXT NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_briefing_generatedAt` ON `news_briefing` (`generatedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_event_state` (`eventId` TEXT NOT NULL, `deliveredVersion` INTEGER NOT NULL, `deliveredAt` INTEGER, `seenVersion` INTEGER NOT NULL, `readAt` INTEGER, `bookmarked` INTEGER NOT NULL, `readLater` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `followed` INTEGER NOT NULL, `fieldTimes` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_progress` (`briefingId` TEXT NOT NULL, `position` INTEGER NOT NULL, `audioMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`briefingId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_preference` (`key` TEXT NOT NULL, `kind` TEXT NOT NULL, `weight` REAL NOT NULL, `label` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`, `kind`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_feedback` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `eventId` TEXT NOT NULL, `articleId` TEXT, `otherEventId` TEXT, `note` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_feedback_articleId` ON `news_feedback` (`articleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_feedback_eventId` ON `news_feedback` (`eventId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `news_run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `stage` TEXT NOT NULL, `sourceId` TEXT, `ok` INTEGER NOT NULL, `count` INTEGER NOT NULL, `error` TEXT, `microUsd` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_run_at` ON `news_run` (`at`)")
    }
}
