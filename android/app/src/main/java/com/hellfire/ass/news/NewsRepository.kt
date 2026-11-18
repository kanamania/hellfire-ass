package com.hellfire.ass.news

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ai.AiTask
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class NewsRepository(
    private val app: HellFireApp,
    private val http: NewsHttp = HttpsNewsHttp(),
    private val poster: NewsPoster = AndroidNewsPoster(app),
    private val database: com.hellfire.ass.data.EventDatabase = app.database,
    private val embedding: suspend (String) -> Pair<String, FloatArray>? = { text ->
        val model = app.semantic.embeddingModel()
        val vector = if (model == null) null else app.semantic.embedText(text)
        if (vector != null && model == app.semantic.embeddingModel()) model!! to vector else null
    },
) : NewsService {
    private val dao get() = database.news()
    private val connectors by lazy { listOf(FeedConnector(http), SiteConnector(http), XConnector(http) { xToken() }) }

    override fun settings(): NewsSettings {
        val saved = app.secrets.read(SETTINGS) ?: return NewsSettings()
        return NewsSettings(
            enabled = saved.optBoolean("enabled", true),
            zone = saved.optString("zone", "Africa/Dar_es_Salaam"),
            morning = saved.optString("morning", "07:00"),
            evening = saved.optString("evening", "19:00"),
            extra = saved.optJSONArray("extra")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            length = saved.optString("length", "STANDARD"),
            maxStories = saved.optInt("maxStories", 12),
            breakingAlerts = saved.optBoolean("breakingAlerts", true),
            updateAlerts = saved.optBoolean("updateAlerts", true),
            quietStart = saved.optString("quietStart", "22:00"),
            quietEnd = saved.optString("quietEnd", "06:30"),
            criticalOverridesQuiet = saved.optBoolean("criticalOverridesQuiet", true),
            autoplay = saved.optBoolean("autoplay", false),
            speechRate = saved.optDouble("speechRate", 1.0).toFloat(),
            language = saved.optString("language", "en"),
            aiSummaries = saved.optBoolean("aiSummaries", false),
            wifiOnly = saved.optBoolean("wifiOnly", false),
            weights = ScoreWeights(
                saved.optDouble("wImpact", 30.0), saved.optDouble("wRelevance", 20.0), saved.optDouble("wRecency", 15.0),
                saved.optDouble("wCorroboration", 15.0), saved.optDouble("wReliability", 10.0), saved.optDouble("wNovelty", 10.0),
            ),
        )
    }

    override suspend fun saveSettings(settings: NewsSettings) {
        app.secrets.save(SETTINGS, JSONObject()
            .put("enabled", settings.enabled).put("zone", settings.zone).put("morning", settings.morning).put("evening", settings.evening)
            .put("extra", JSONArray(settings.extra)).put("length", settings.length).put("maxStories", settings.maxStories)
            .put("breakingAlerts", settings.breakingAlerts).put("updateAlerts", settings.updateAlerts)
            .put("quietStart", settings.quietStart).put("quietEnd", settings.quietEnd)
            .put("criticalOverridesQuiet", settings.criticalOverridesQuiet).put("autoplay", settings.autoplay)
            .put("speechRate", settings.speechRate.toDouble()).put("language", settings.language)
            .put("aiSummaries", settings.aiSummaries).put("wifiOnly", settings.wifiOnly)
            .put("wImpact", settings.weights.impact).put("wRelevance", settings.weights.relevance).put("wRecency", settings.weights.recency)
            .put("wCorroboration", settings.weights.corroboration).put("wReliability", settings.weights.reliability).put("wNovelty", settings.weights.novelty))
        NewsWork.schedule(app)
    }

    override suspend fun ensureDefaults() {
        val now = System.currentTimeMillis()
        if (dao.source(NewsCatalog.SHARE_ID) == null) dao.insertSource(NewsCatalog.share(now))
        NewsCatalog.defaults(now, hasXToken()).forEach { dao.insertSource(it) }
    }

    override suspend fun refresh(force: Boolean): RefreshResult {
        ensureDefaults()
        val now = System.currentTimeMillis()
        val prefs = settings()
        if (!force && prefs.wifiOnly && !NewsNet.unmetered(app)) {
            return RefreshResult(0, 0, 0, 0, 0, listOf("Waiting for an unmetered network"))
        }
        val due = dao.allSources().filter { it.state == "ACTIVE" && (force || it.nextAttemptAt <= now) && it.kind != "LICENSED" && it.kind != "SHARE" }
        var failed = 0
        var articlesNew = 0
        val errors = ArrayList<String>()
        val touched = linkedSetOf<String>()
        val originalVersions = dao.activeEvents(now - 7L * 86_400_000L).associate { it.id to it.version }
        for (source in due) {
            val started = System.currentTimeMillis()
            val connector = connectors.firstOrNull { it.supports(source.kind) }
            if (connector == null) {
                failed++
                errors += "${source.name}: no connector"
                continue
            }
            val result = try { connector.fetch(source, now) } catch (error: Exception) {
                failed++
                val message = error.message ?: error.javaClass.simpleName
                errors += "${source.name}: $message"
                val failures = source.failures + 1
                dao.saveSource(source.copy(lastAttemptAt = now, failures = failures, lastError = message,
                    nextAttemptAt = now + NewsBackoff.delay(source.id, source.kind, failures), updatedAt = now))
                dao.insertRun(NewsRun(at = now, stage = "INGEST", sourceId = source.id, ok = false, error = message, durationMs = System.currentTimeMillis() - started))
                continue
            }
            val next = result.source.copy(
                lastAttemptAt = now,
                lastSuccessAt = if (result.error == null) now else result.source.lastSuccessAt,
                failures = if (result.error == null) 0 else result.source.failures + 1,
                lastError = result.error,
                lastCount = result.items.size,
                nextAttemptAt = result.retryAt ?: now + NewsBackoff.delay(result.source.id, result.source.kind, if (result.error == null) 0 else result.source.failures + 1),
                updatedAt = now,
            )
            dao.saveSource(next)
            if (result.error != null) { failed++; errors += "${source.name}: ${result.error}" }
            for (item in result.items) {
                if (storeItem(source, item, now, touched)) articlesNew++
            }
            dao.insertRun(NewsRun(at = now, stage = "INGEST", sourceId = source.id, ok = result.error == null, count = result.items.size,
                error = result.error, durationMs = System.currentTimeMillis() - started))
        }
        var eventsNew = 0
        var eventsUpdated = 0
        for (eventId in touched) {
            val before = originalVersions[eventId] ?: 0
            val after = dao.event(eventId)
            if (before == 0 && after != null) eventsNew++ else if (after != null && after.version > before) eventsUpdated++
        }
        dao.pruneRuns(now - 30L * 86_400_000L)
        return RefreshResult(due.size, failed, articlesNew, eventsNew, eventsUpdated, errors)
    }

    override suspend fun briefing(slot: String, now: Long): NewsBriefing {
        ensureDefaults()
        val prefs = settings()
        val zone = zone(prefs)
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val id = when (slot) {
            "ON_DEMAND" -> "ON_DEMAND:$now"
            "CUSTOM" -> "CUSTOM:${local.toLocalDate()}:${local.toLocalTime().withSecond(0).withNano(0)}"
            else -> "$slot:${local.toLocalDate()}"
        }
        if (slot != "ON_DEMAND") dao.briefing(id)?.let { return it }
        val window = window(slot, now, prefs)
        val events = dao.eventsBetween(window.first, window.last + 1, 400)
        val states = dao.states(events.map { it.id }).associateBy { it.eventId }
        val interests = interests()
        val candidates = events.map { event ->
            val topics = event.topics.split(',').filter { it.isNotBlank() }.toSet()
            BriefCandidate(event.id, event.version, event.score, event.level, event.protectedPublic, topics, event.firstSeenAt, event.updatedAt,
                dao.versions(event.id).filter { it.version > 1 }.associate { it.version to it.classification },
                NewsScoring.interestMatch(topics, setOfNotNull(event.place), emptySet(), interests))
        }
        val items = NewsBriefings.build(candidates, states, window, BriefConfig(prefs.maxStories, length = prefs.length))
        items.forEach { item ->
            val state = states[item.eventId] ?: NewsEventState(item.eventId, updatedAt = now)
            if (item.version > state.deliveredVersion) {
                dao.saveState(state.copy(deliveredVersion = item.version, deliveredAt = now, updatedAt = now))
            }
        }
        val briefing = NewsBriefing(id, slot, title(slot, now, zone), now, now, window.first, window.last,
            JSONArray(items.map { JSONObject().put("eventId", it.eventId).put("version", it.version).put("section", it.section).put("classification", it.classification ?: JSONObject.NULL) }).toString())
        dao.saveBriefing(briefing)
        dao.insertRun(NewsRun(at = now, stage = "BRIEFING", ok = true, count = items.size))
        return briefing
    }

    override fun briefings(slots: List<String>, limit: Int): Flow<List<NewsBriefing>> = dao.briefings(slots, limit)

    override suspend fun stories(briefingId: String): List<StoryView> {
        val briefing = dao.briefing(briefingId) ?: return emptyList()
        val items = JSONArray(briefing.itemsJson)
        val out = ArrayList<StoryView>()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            story(item.getString("eventId"), item.optString("section"))?.let { out += it }
        }
        return out
    }

    override suspend fun story(eventId: String, section: String): StoryView? {
        val event = resolve(eventId) ?: return null
        val articles = dao.articlesFor(event.id)
        val sources = articles.mapNotNull { dao.source(it.sourceId) }.distinctBy { it.id }
        val versions = dao.versions(event.id)
        val state = dao.state(event.id)
        val prefs = settings()
        val summary = summary(event.id, prefs.length, prefs.language)
        val seen = state?.seenVersion ?: 0
        val delta = versions.lastOrNull { it.version > seen && it.classification != "NONE" }
        return StoryView(event, state, articles, sources, summary, versions, section, delta)
    }

    override suspend fun breaking(now: Long): List<StoryView> {
        val out = ArrayList<StoryView>()
        for (event in dao.eventsBetween(now - 86_400_000L, now + 1, 100).filter { it.level == "CRITICAL" || it.level == "HIGH" }.sortedByDescending { it.score })
            story(event.id, "TOP")?.let { out += it }
        return out
    }

    override suspend fun saved(): List<StoryView> {
        val out = ArrayList<StoryView>()
        for (state in dao.savedNow()) story(state.eventId, "")?.let { out += it }
        return out
    }

    override suspend fun archive(query: String, from: Long?, to: Long?, limit: Int): List<StoryView> {
        val q = query.trim().lowercase()
        val out = ArrayList<StoryView>()
        for (event in dao.topEvents(500)) {
            if (out.size == limit) break
            if (from != null && event.lastArticleAt < from) continue
            if (to != null && event.lastArticleAt >= to) continue
            if (q.isNotBlank() && q !in event.headline.lowercase() && q !in event.topics.lowercase() && q !in event.place.orEmpty().lowercase() && q !in event.signature.lowercase()) continue
            story(event.id, "")?.let { out += it }
        }
        return out
    }

    override suspend fun summary(eventId: String, length: String, language: String): StorySummary {
        val event = resolve(eventId) ?: return StorySummary("", emptyList(), emptyList(), "", "", "", emptyList(), emptyList(), emptySet(), emptyList(), language, "EXTRACTIVE")
        val key = "${event.version}|$length|$language"
        event.summaryJson?.takeIf { event.summaryKey == key }?.let { return StorySummary.fromJson(it) }
        val articles = dao.articlesFor(event.id)
        val pairs = articles.map { article -> inputOf(article).let { it to NewsText.features(it) } }
        val snapshot = runCatching { EventSnapshot.fromJson(dao.versions(event.id).lastOrNull()?.factsJson ?: "") }.getOrElse { NewsFacts.snapshot(pairs) }
        val built = if (settings().aiSummaries && articles.none { it.shared }) aiSummary(event.headline, pairs, snapshot, length, language) else null
        val summary = built ?: NewsSummaries.extractive(event.headline, pairs, snapshot, dao.versions(event.id), length, language)
        dao.saveEvent(event.copy(summaryJson = summary.toJson(), summaryKey = key))
        return summary
    }

    override suspend fun timeline(eventId: String, sinceVersion: Int): List<NewsEventVersion> =
        dao.versions(resolve(eventId)?.id ?: eventId).filter { it.version > sinceVersion }

    override suspend fun explain(eventId: String): NewsScore? =
        resolve(eventId)?.scoreJson?.let { runCatching { NewsScore.fromJson(it) }.getOrNull() }

    override suspend fun markRead(eventId: String) = flagVersion(eventId, read = true)
    override suspend fun markUnread(eventId: String) = flagVersion(eventId, read = false)

    override suspend fun setFlag(eventId: String, field: String, value: Boolean) {
        val id = resolve(eventId)?.id ?: return
        val now = System.currentTimeMillis()
        val state = dao.state(id) ?: NewsEventState(id, updatedAt = now)
        val times = JSONObject(state.fieldTimes.ifBlank { "{}" }).put(field, now)
        val next = when (field) {
            "bookmarked" -> state.copy(bookmarked = value)
            "readLater" -> state.copy(readLater = value)
            "hidden" -> state.copy(hidden = value)
            "followed" -> state.copy(followed = value)
            else -> return
        }
        dao.saveState(next.copy(fieldTimes = times.toString(), updatedAt = now))
    }

    override suspend fun position(briefingId: String): NewsProgress? = dao.progress(briefingId)
    override suspend fun savePosition(briefingId: String, position: Int, audioMs: Long) {
        dao.saveProgress(NewsProgress(briefingId, position, audioMs, System.currentTimeMillis()))
    }

    override suspend fun feedback(kind: String, eventId: String, articleId: String?, otherEventId: String?, note: String) {
        val now = System.currentTimeMillis()
        val id = resolve(eventId)?.id ?: eventId
        dao.insertFeedback(NewsFeedback(UUID.randomUUID().toString(), kind, id, articleId, otherEventId, note, now))
        when (kind) {
            "NOT_SAME_EVENT" -> if (articleId != null) {
                dao.assign(articleId, null)
                val fresh = NewsClusterer.newEventId(articleId)
                dao.assign(articleId, fresh)
                recompute(id, now)
                recompute(fresh, now)
            }
            "SAME_EVENT" -> if (otherEventId != null) merge(otherEventId, id, now)
        }
        recompute(id, now)
    }

    override fun preferences(): Flow<List<NewsPreference>> = dao.preferences()
    override suspend fun setInterest(key: String, label: String, weight: Double) {
        dao.savePreference(NewsPreference(key, "EXPLICIT", weight, label, System.currentTimeMillis()))
    }
    override suspend fun removeInterest(key: String, kind: String) { dao.deletePreference(key, kind) }
    override suspend fun hideSubject(key: String, label: String) {
        dao.savePreference(NewsPreference(key, "HIDDEN", 1.0, label, System.currentTimeMillis()))
    }
    override suspend fun resetLearned() { dao.resetInferred() }

    override fun sources(): Flow<List<NewsSource>> = dao.sources()

    override suspend fun addSource(input: String, category: String, name: String?): NewsSource {
        val now = System.currentTimeMillis()
        val trimmed = input.trim()
        val (kind, url) = when {
            trimmed.startsWith("@") -> "X_ACCOUNT" to trimmed.removePrefix("@")
            trimmed.startsWith("x:") -> "X_QUERY" to trimmed.removePrefix("x:")
            trimmed.startsWith("https://") -> "RSS" to trimmed
            else -> "SITE" to if (trimmed.startsWith("http://")) trimmed.replaceFirst("http://", "https://") else "https://$trimmed"
        }
        val source = NewsSource(id = "src-" + articleId(url).take(16), name = name ?: trimmed, kind = kind, url = url, category = category,
            language = "mixed", reliability = 60, state = if (kind.startsWith("X_") && !hasXToken()) "NEEDS_KEY" else "ACTIVE",
            note = if (kind.startsWith("X_")) NewsCatalog.X_NOTE else "", createdAt = now, updatedAt = now)
        dao.saveSource(source)
        return source
    }

    override suspend fun setSourceState(id: String, state: String) {
        val source = dao.source(id) ?: return
        dao.saveSource(source.copy(state = state, updatedAt = System.currentTimeMillis()))
    }
    override suspend fun setSourcePriority(id: String, priority: Int) {
        val source = dao.source(id) ?: return
        dao.saveSource(source.copy(priority = priority.coerceIn(-2, 2), updatedAt = System.currentTimeMillis()))
    }
    override suspend fun setSourceReliability(id: String, reliability: Int) {
        val source = dao.source(id) ?: return
        dao.saveSource(source.copy(reliability = reliability.coerceIn(0, 100), updatedAt = System.currentTimeMillis()))
    }
    override suspend fun setXToken(token: String) {
        if (token.isBlank()) app.secrets.remove(X_TOKEN) else app.secrets.save(X_TOKEN, JSONObject().put("token", token))
    }
    override fun hasXToken(): Boolean = xToken() != null

    override suspend fun dropIn(text: String, url: String?): DropResult {
        val safe = com.hellfire.ass.events.NotificationNormalizer.normalize(
            com.hellfire.ass.events.NotificationSnapshot("news-share", "owner.news", 0, 0, fields = mapOf("text" to listOf(text))), app.packageName)
        require(safe != null && !safe.securitySensitive && !safe.contentRedacted) { "Remove authentication codes before checking against news." }
        ensureDefaults()
        val now = System.currentTimeMillis()
        val canonical = url?.let { NewsText.canonicalUrl(it) }
        if (canonical != null) dao.articleByUrl(canonical)?.let { return DropResult(it.eventId, false, true, "Already saved") }
        val item = FeedItem(text.lineSequence().firstOrNull { it.isNotBlank() }?.take(180) ?: "Shared item", canonical ?: "https://shared.local/${articleId(text)}",
            text = text.take(4000), publishedAt = now)
        val source = dao.source(NewsCatalog.SHARE_ID) ?: NewsCatalog.share(now).also { dao.insertSource(it) }
        val created = linkedSetOf<String>()
        val fresh = storeItem(source, item, now, created, shared = true)
        val eventId = created.firstOrNull() ?: canonical?.let { dao.articleByUrl(it)?.eventId }
        return DropResult(eventId, fresh && eventId != null, !fresh && eventId != null, if (fresh) "Added to news" else "Already saved")
    }

    override suspend fun ask(question: String, now: Long): NewsAnswer? {
        val parsed = NewsAsk.parse(question, now, zone(settings())) ?: return null
        val matched = ArrayList<NewsEvent>()
        for (event in dao.eventsBetween(parsed.from, parsed.to, 500)) {
            val topics = event.topics.split(',')
            val placeOk = parsed.places.isEmpty() || parsed.places.any { it == event.place || it in event.signature }
            val topicOk = parsed.topics.isEmpty() || parsed.topics.any { it in topics }
            val termOk = parsed.terms.isEmpty() || parsed.terms.any { event.headline.lowercase().contains(it) }
            val state = dao.state(event.id)
            val savedOk = !parsed.saved || state?.let { it.bookmarked || it.readLater } == true
            val importantOk = !parsed.important || event.level in setOf("CRITICAL", "HIGH")
            val socialOk = !parsed.social || dao.articlesFor(event.id).any { it.contentType == "SOCIAL_POST" }
            val changedOk = !parsed.changed || ((state?.seenVersion ?: 0) > 0 && dao.versions(event.id).any {
                it.version > state!!.seenVersion && it.classification in setOf("MAJOR", "CORRECTION", "RESOLVED")
            })
            if (placeOk && topicOk && termOk && savedOk && importantOk && socialOk && changedOk && state?.hidden != true) matched += event
        }
        val events = matched
        if (events.isEmpty()) return NewsAnswer("No saved briefing matches that.", emptyList())
        val lines = events.take(6).map { event ->
            val change = if (parsed.changed) dao.versions(event.id).lastOrNull { it.classification != "NEW" }?.whatsNew else null
            if (change.isNullOrBlank()) event.headline else "${event.headline} — $change"
        }
        val prefix = if (parsed.social) "Social posts are unverified reports.\n" else "From saved news (${parsed.window}):\n"
        return NewsAnswer(prefix + lines.joinToString("\n"), events.take(6).map { it.id })
    }

    override suspend fun health(days: Int): List<NewsStageCount> = dao.runSummary(System.currentTimeMillis() - days * 86_400_000L)

    private suspend fun storeItem(source: NewsSource, item: FeedItem, now: Long, touched: MutableSet<String>, shared: Boolean = false): Boolean {
        if (item.url.isBlank() || item.title.isBlank()) return false
        val canonical = NewsText.canonicalUrl(item.url)
        if (dao.articleByUrl(canonical) != null) return false
        val input = ArticleInput(articleId(canonical), source.id, source.name, item.title, item.text, item.url, item.publishedAt ?: now,
            source.reliability, source.agency, source.official, source.kind.startsWith("X_"), item.language)
        val features = NewsText.features(input)
        val encoded = try { embedding("${item.title}\n${item.text.take(1800)}") }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { com.hellfire.ass.Diagnostics.warn(error); null }
        val model = encoded?.first?.takeIf { it.isNotBlank() }
        val vector = encoded?.second?.takeIf { model != null && it.size in 1..4096 && it.all(Float::isFinite) }
            ?.let(com.hellfire.ass.memory.VectorMath::normalize)?.takeIf { it.isNotEmpty() }
        val since = now - 7L * 86_400_000L
        val candidates = dao.activeEvents(since, 300).mapNotNull { event -> centroidOf(event)?.let { centroid ->
            if (model != null && JSONObject(event.featuresJson).optString("embeddingModel") == model) centroid else centroid.copy(vector = null)
        } }
        val decision = NewsClusterer.assign(input.id, features, input.publishedAt, vector, candidates, constraints())
        val eventId = decision.eventId ?: NewsClusterer.newEventId(input.id)
        val article = NewsArticle(input.id, source.id, item.url, canonical, item.title, item.text.take(4000), item.author,
            features.language, input.publishedAt, now, features.contentType, features.agency, features.fingerprint.toString(16),
            JSONArray(item.media.map { JSONObject().put("type", it.type).put("url", it.url).put("mime", it.mime ?: JSONObject.NULL) }).toString(),
            JSONObject(features.toJson()).put("embeddingModel", if (vector == null) JSONObject.NULL else model).toString(),
            vector = vector?.let(com.hellfire.ass.memory.VectorMath::encode), eventId = eventId, shared = shared)
        if (dao.insertArticle(article) == -1L) return false
        touched += eventId
        // Publish the centroid before the next article in this same refresh is clustered.
        recompute(eventId, now)
        return true
    }

    private suspend fun recompute(eventId: String, now: Long) {
        val articles = dao.articlesFor(eventId)
        if (articles.isEmpty()) return
        val pairs = articles.map { article -> inputOf(article).let { it to NewsText.features(it) } }
        val features = pairs.map { it.second }
        val snapshot = NewsFacts.snapshot(pairs)
        val existing = dao.event(eventId)
        val versions = if (existing == null) emptyList() else dao.versions(eventId)
        val previous = versions.lastOrNull()?.factsJson?.let { runCatching { EventSnapshot.fromJson(it) }.getOrNull() }
        val delta = if (existing == null) null else NewsFacts.delta(previous, snapshot, features.first().eventType)
        val classification = if (existing == null) "NEW" else delta?.classification ?: "NONE"
        val version = if (existing == null) 1 else if (classification == "NONE") existing.version else existing.version + 1
        if (existing == null || classification != "NONE") {
            val text = delta?.whatsNew?.ifBlank { null } ?: pairs.maxBy { it.first.sourceReliability }.first.title
            dao.insertVersion(NewsEventVersion(eventId, version, now, classification, snapshot.toJson(),
                delta?.previously.orEmpty(), text, delta?.whyItMatters.orEmpty(), JSONArray(articles.map { it.id }).toString()))
        }
        var centroid = null as EventCentroid?
        val embeddingModel = articles.sortedByDescending { it.fetchedAt }.firstNotNullOfOrNull { article ->
            JSONObject(article.featuresJson).opt("embeddingModel")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
        }
        pairs.forEachIndexed { index, (input, feat) ->
            val article = articles[index]
            val vector = if (embeddingModel != null && JSONObject(article.featuresJson).optString("embeddingModel") == embeddingModel)
                article.vector?.let { bytes -> runCatching { com.hellfire.ass.memory.VectorMath.decode(bytes) }.getOrNull() }
                    ?.takeIf { it.size in 1..4096 && it.all(Float::isFinite) } else null
            centroid = NewsClusterer.add(centroid, eventId, input.id, feat, input.publishedAt, vector)
        }
        val topics = features.flatMap { it.topics }.distinct().joinToString(",")
        val place = features.flatMap { it.places }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val headline = NewsText.neutralHeadline(pairs.maxBy { it.first.sourceReliability }.first.title)
        val prefs = settings()
        val interests = interests()
        val score = NewsScoring.score(ScoreInput(features.first().eventType, features.flatMap { it.topics }.toSet(), features.flatMap { it.places }.toSet(),
            features.flatMap { it.entities }.toSet(), snapshot, pairs.map { it.first.sourceReliability }, features.map { it.contentType },
            articles.maxOf { it.publishedAt }, features.any { it.urgent }, features.any { it.publicSafety }, classification,
            feedbackBias(eventId)), interests, now, prefs.weights)
        val event = NewsEvent(eventId, headline, features.first().eventType, topics, place, centroid?.let { NewsClusterer.signature(it) } ?: eventId,
            existing?.firstSeenAt ?: articles.minOf { it.publishedAt }, if (classification == "NONE") existing?.updatedAt ?: now else now,
            articles.maxOf { it.publishedAt }, version, if (snapshot.resolved) "RESOLVED" else "DEVELOPING", score.total, score.level, score.toJson(),
            score.protectedPublic, snapshot.independentSources, articles.size, snapshot.verification,
            JSONObject(centroid?.toJson() ?: "{}").put("embeddingModel", embeddingModel ?: JSONObject.NULL).toString(),
            existing?.summaryJson, if (classification == "NONE") existing?.summaryKey else null, existing?.mergedInto, existing?.breakingNotifiedVersion ?: 0)
        dao.saveEvent(event)
        val state = dao.state(eventId)
        val latest = dao.version(eventId, version)
        val decision = NewsAlerts.decide(event, state, latest, prefs, now)
        if (decision != null) {
            if (!decision.quiet) poster.post(decision.notice)
            dao.saveEvent(event.copy(breakingNotifiedVersion = version))
        }
    }

    private suspend fun merge(fromId: String, intoId: String, now: Long) {
        if (fromId == intoId) return
        dao.moveArticles(fromId, intoId)
        dao.event(fromId)?.let { dao.saveEvent(it.copy(mergedInto = intoId, updatedAt = now)) }
        recompute(intoId, now)
    }

    private suspend fun flagVersion(eventId: String, read: Boolean) {
        val event = resolve(eventId) ?: return
        val now = System.currentTimeMillis()
        val state = dao.state(event.id) ?: NewsEventState(event.id, updatedAt = now)
        dao.saveState(if (read) state.copy(seenVersion = maxOf(state.seenVersion, event.version), readAt = now, deliveredVersion = maxOf(state.deliveredVersion, event.version), updatedAt = now)
        else state.copy(seenVersion = 0, readAt = null, updatedAt = now))
    }

    private suspend fun resolve(eventId: String): NewsEvent? {
        val event = dao.event(eventId) ?: return null
        return if (event.mergedInto != null) dao.event(event.mergedInto) ?: event else event
    }

    private suspend fun constraints(): ClusterConstraints {
        val rows = dao.allFeedback()
        return ClusterConstraints(
            rows.filter { it.kind == "NOT_SAME_EVENT" && it.articleId != null }.map { it.articleId!! to it.eventId }.toSet(),
            rows.filter { it.kind == "SAME_EVENT" && it.articleId != null }.associate { it.articleId!! to it.eventId },
            rows.mapNotNull { if (it.otherEventId != null && it.kind == "NOT_SAME_EVENT") it.eventId to it.otherEventId else null }.toSet(),
        )
    }

    private suspend fun interests(): List<Interest> = dao.allPreferences().map { Interest(it.key, it.kind, it.weight) }
    private suspend fun feedbackBias(eventId: String): Int = dao.allFeedback().count { it.eventId == eventId && it.kind == "MORE_IMPORTANT" } -
        dao.allFeedback().count { it.eventId == eventId && it.kind == "LESS_IMPORTANT" }

    private suspend fun aiSummary(headline: String, pairs: List<Pair<ArticleInput, ArticleFeatures>>, snapshot: EventSnapshot, length: String, language: String): StorySummary? {
        val prompt = NewsSummaries.prompt(headline, pairs, snapshot, length, language)
        val output = runCatching { app.ai.generate(AiTask.SUMMARY, prompt).generation.text }.getOrNull() ?: return null
        return runCatching { NewsSummaries.parseModel(output, pairs, snapshot, language, "AI") }.getOrNull()
    }

    private suspend fun inputOf(article: NewsArticle): ArticleInput {
        val source = dao.source(article.sourceId)
        return ArticleInput(article.id, article.sourceId, source?.name ?: article.sourceId, article.title, article.text, article.url,
            article.publishedAt, sourceReliability = source?.reliability ?: 50, sourceOfficial = source?.official ?: false,
            language = article.language, sourceAgency = article.agency, social = article.contentType == "SOCIAL_POST")
    }

    private fun centroidOf(event: NewsEvent): EventCentroid? = runCatching { EventCentroid.fromJson(event.id, event.featuresJson) }.getOrNull()
        ?.takeIf { it.articleIds.isNotEmpty() }

    private fun window(slot: String, now: Long, prefs: NewsSettings): LongRange {
        val zone = zone(prefs)
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val morning = runCatching { LocalTime.parse(prefs.morning) }.getOrDefault(LocalTime.of(7, 0))
        val evening = runCatching { LocalTime.parse(prefs.evening) }.getOrDefault(LocalTime.of(19, 0))
        val start = when (slot) {
            "MORNING" -> local.toLocalDate().minusDays(if (local.toLocalTime() >= evening) 0 else 1).atTime(evening).atZone(zone).toInstant().toEpochMilli()
            "EVENING" -> local.toLocalDate().atTime(morning).atZone(zone).toInstant().toEpochMilli().let { if (it > now) it - 86_400_000L else it }
            else -> now - 12 * 3_600_000L
        }
        return start..now
    }

    private fun title(slot: String, now: Long, zone: ZoneId): String {
        val day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        return when (slot) { "MORNING" -> "Morning"; "EVENING" -> "Evening"; "BREAKING" -> "Breaking"; else -> "Briefing" } + " · $day"
    }

    private fun zone(prefs: NewsSettings) = runCatching { ZoneId.of(prefs.zone) }.getOrDefault(ZoneId.of("Africa/Dar_es_Salaam"))
    private fun xToken(): String? = app.secrets.read(X_TOKEN)?.optString("token")?.takeIf { it.isNotBlank() }

    companion object {
        const val SETTINGS = "news-settings"
        const val X_TOKEN = "news-x-token"
        fun articleId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }
}
