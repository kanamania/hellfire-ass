package com.hellfire.ass.news

import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.flow.Flow

/** STUB from the lead's contract commit. The runtime branch replaces this file with the real implementation. */
class NewsRepository(private val app: HellFireApp) : NewsService {
    private val dao get() = app.database.news()
    override fun settings(): NewsSettings = NewsSettings()
    override suspend fun saveSettings(settings: NewsSettings) = TODO()
    override suspend fun ensureDefaults() = TODO()
    override suspend fun refresh(force: Boolean): RefreshResult = TODO()
    override suspend fun briefing(slot: String, now: Long): NewsBriefing = TODO()
    override fun briefings(slots: List<String>, limit: Int): Flow<List<NewsBriefing>> = dao.briefings(slots, limit)
    override suspend fun stories(briefingId: String): List<StoryView> = TODO()
    override suspend fun story(eventId: String, section: String): StoryView? = TODO()
    override suspend fun breaking(now: Long): List<StoryView> = TODO()
    override suspend fun saved(): List<StoryView> = TODO()
    override suspend fun archive(query: String, from: Long?, to: Long?, limit: Int): List<StoryView> = TODO()
    override suspend fun summary(eventId: String, length: String, language: String): StorySummary = TODO()
    override suspend fun timeline(eventId: String, sinceVersion: Int): List<NewsEventVersion> = TODO()
    override suspend fun explain(eventId: String): NewsScore? = TODO()
    override suspend fun markRead(eventId: String) = TODO()
    override suspend fun markUnread(eventId: String) = TODO()
    override suspend fun setFlag(eventId: String, field: String, value: Boolean) = TODO()
    override suspend fun position(briefingId: String): NewsProgress? = TODO()
    override suspend fun savePosition(briefingId: String, position: Int, audioMs: Long) = TODO()
    override suspend fun feedback(kind: String, eventId: String, articleId: String?, otherEventId: String?, note: String) = TODO()
    override fun preferences(): Flow<List<NewsPreference>> = dao.preferences()
    override suspend fun setInterest(key: String, label: String, weight: Double) = TODO()
    override suspend fun removeInterest(key: String, kind: String) = TODO()
    override suspend fun hideSubject(key: String, label: String) = TODO()
    override suspend fun resetLearned() = TODO()
    override fun sources(): Flow<List<NewsSource>> = dao.sources()
    override suspend fun addSource(input: String, category: String, name: String?): NewsSource = TODO()
    override suspend fun setSourceState(id: String, state: String) = TODO()
    override suspend fun setSourcePriority(id: String, priority: Int) = TODO()
    override suspend fun setSourceReliability(id: String, reliability: Int) = TODO()
    override suspend fun setXToken(token: String) = TODO()
    override fun hasXToken(): Boolean = false
    override suspend fun dropIn(text: String, url: String?): DropResult = TODO()
    override suspend fun ask(question: String, now: Long): NewsAnswer? = TODO()
    override suspend fun health(days: Int): List<NewsStageCount> = TODO()
}
