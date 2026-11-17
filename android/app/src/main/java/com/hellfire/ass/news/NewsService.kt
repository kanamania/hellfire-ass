package com.hellfire.ass.news

import kotlinx.coroutines.flow.Flow

/*
 * CONTRACT between the runtime (NewsRepository implements NewsService) and the UI / assistant / sync code.
 * Do not rename, remove or change parameters of members declared here; adding members with defaults is fine
 * (coordinate through the lead). The UI only talks to NewsService.
 */

/** Owner settings, stored encrypted in SecretStore under "news-settings". Times are "HH:mm" in [zone]. */
data class NewsSettings(
    val enabled: Boolean = true,
    val zone: String = "Africa/Dar_es_Salaam",
    val morning: String = "07:00",
    val evening: String = "19:00",
    /** Extra scheduled briefings ("HH:mm"), titled "Briefing". */
    val extra: List<String> = emptyList(),
    val length: String = "STANDARD",
    val maxStories: Int = 12,
    val breakingAlerts: Boolean = true,
    /** Story updates for followed stories ("What Changed" notifications). */
    val updateAlerts: Boolean = true,
    val quietStart: String = "22:00",
    val quietEnd: String = "06:30",
    /** Critical public-interest alerts may override quiet hours. */
    val criticalOverridesQuiet: Boolean = true,
    val autoplay: Boolean = false,
    val speechRate: Float = 1.0f,
    /** en or sw: summaries in this language; translation needs an AI model. */
    val language: String = "en",
    /** Allow the AI router (cloud only if the owner enabled cloud AI) to write summaries and translations. */
    val aiSummaries: Boolean = false,
    /** Ingestion only on unmetered networks. */
    val wifiOnly: Boolean = false,
    val weights: ScoreWeights = ScoreWeights(),
)

/** Everything one reader card needs. [section]: TOP, STORY, CHANGED or "" outside a briefing. [delta] is the latest material version since the owner last saw it. */
data class StoryView(
    val event: NewsEvent,
    val state: NewsEventState?,
    val articles: List<NewsArticle>,
    val sources: List<NewsSource>,
    val summary: StorySummary,
    val versions: List<NewsEventVersion>,
    val section: String,
    val delta: NewsEventVersion?,
)

data class RefreshResult(val sourcesTried: Int, val sourcesFailed: Int, val articlesNew: Int, val eventsNew: Int, val eventsUpdated: Int, val errors: List<String>)

/** [eventId]: the event the dropped content now belongs to. [created]: a new event was made. [duplicate]: the exact article was already known. */
data class DropResult(val eventId: String?, val created: Boolean, val duplicate: Boolean, val message: String)

/** Answer for the assistant: text plus the events it is based on. */
data class NewsAnswer(val text: String, val eventIds: List<String>)

interface NewsService {
    fun settings(): NewsSettings
    suspend fun saveSettings(settings: NewsSettings)

    /** Seeds built-in sources once (idempotent). */
    suspend fun ensureDefaults()
    /** Fetches due sources, clusters, scores and updates events; sends breaking/update alerts. Never throws for one bad source. */
    suspend fun refresh(force: Boolean = false): RefreshResult

    /** Builds (or returns the already built) briefing for [slot] at [now]. ON_DEMAND always builds a new one. Marks its items delivered. */
    suspend fun briefing(slot: String, now: Long = System.currentTimeMillis()): NewsBriefing
    fun briefings(slots: List<String>, limit: Int = 30): Flow<List<NewsBriefing>>
    suspend fun stories(briefingId: String): List<StoryView>
    suspend fun story(eventId: String, section: String = ""): StoryView?
    /** Breaking (CRITICAL/HIGH within the last 24 h), Saved (bookmarked or read later) and Archive (search) lists. */
    suspend fun breaking(now: Long = System.currentTimeMillis()): List<StoryView>
    suspend fun saved(): List<StoryView>
    suspend fun archive(query: String, from: Long? = null, to: Long? = null, limit: Int = 50): List<StoryView>

    /** Summary for [length]/[language]; uses the AI router only if settings allow, else extractive. Cached per event version. */
    suspend fun summary(eventId: String, length: String, language: String): StorySummary
    /** Versions after [sinceVersion] ("What Changed" timeline). */
    suspend fun timeline(eventId: String, sinceVersion: Int): List<NewsEventVersion>
    /** Why the event has its importance score. */
    suspend fun explain(eventId: String): NewsScore?

    suspend fun markRead(eventId: String)
    suspend fun markUnread(eventId: String)
    /** [field]: bookmarked, readLater, hidden, followed. */
    suspend fun setFlag(eventId: String, field: String, value: Boolean)
    suspend fun position(briefingId: String): NewsProgress?
    suspend fun savePosition(briefingId: String, position: Int, audioMs: Long = 0)

    /** [kind] as in NewsFeedback; NOT_SAME_EVENT splits [articleId] out; SAME_EVENT merges [otherEventId] into [eventId]. */
    suspend fun feedback(kind: String, eventId: String, articleId: String? = null, otherEventId: String? = null, note: String = "")
    fun preferences(): Flow<List<NewsPreference>>
    suspend fun setInterest(key: String, label: String, weight: Double)
    suspend fun removeInterest(key: String, kind: String)
    /** Hides a subject (topic/place/term) from ordinary ranking; critical public-interest news still shows. */
    suspend fun hideSubject(key: String, label: String)
    suspend fun resetLearned()

    fun sources(): Flow<List<NewsSource>>
    /** Adds an RSS/Atom/JSON feed URL, a website (feed discovered), an X handle (@name) or X query (x:query). */
    suspend fun addSource(input: String, category: String = "CUSTOM", name: String? = null): NewsSource
    /** [state]: ACTIVE, MUTED, REMOVED. */
    suspend fun setSourceState(id: String, state: String)
    suspend fun setSourcePriority(id: String, priority: Int)
    suspend fun setSourceReliability(id: String, reliability: Int)
    /** Bearer token for X API v2; stored encrypted. Blank removes it. */
    suspend fun setXToken(token: String)
    fun hasXToken(): Boolean

    /** Universal drawer / share: a URL, headline, post text or OCR text from a screenshot. */
    suspend fun dropIn(text: String, url: String? = null): DropResult
    /** Natural-language news question for Ask/assistant ("what happened in Tanzania this week", "what changed in the story I heard yesterday"). */
    suspend fun ask(question: String, now: Long = System.currentTimeMillis()): NewsAnswer?

    /** Health and cost overview for the last [days] days. */
    suspend fun health(days: Int = 7): List<NewsStageCount>
}
