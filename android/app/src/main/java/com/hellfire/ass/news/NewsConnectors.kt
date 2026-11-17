package com.hellfire.ass.news

import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Result of one source fetch. [source] carries connector changes (etag, cursor, discovered feed, NEEDS_KEY/NEEDS_FEED state);
 * the pipeline applies health and backoff. [retryAt] overrides the backoff (e.g. X rate-limit reset).
 */
data class FetchResult(val source: NewsSource, val items: List<FeedItem>, val error: String? = null, val retryAt: Long? = null, val notModified: Boolean = false)

/** One pluggable connector. Implementations may throw; the pipeline isolates each source's failure. */
interface NewsConnector {
    fun supports(kind: String): Boolean
    fun fetch(source: NewsSource, now: Long): FetchResult
}

/** RSS 2.0 / RSS 1.0 RDF / Atom / JSON Feed with conditional GET. 304 is a success with no items. */
class FeedConnector(private val http: NewsHttp) : NewsConnector {
    override fun supports(kind: String) = kind == "RSS" || kind == "JSON_FEED"
    override fun fetch(source: NewsSource, now: Long): FetchResult {
        val headers = linkedMapOf("Accept" to "application/rss+xml, application/atom+xml, application/feed+json, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
        source.etag?.let { headers["If-None-Match"] = it }
        source.lastModified?.let { headers["If-Modified-Since"] = it }
        val response = http.request(source.url, headers, "GET")
        if (response.code == 304) return FetchResult(source, emptyList(), notModified = true)
        if (response.code !in 200..299) throw NewsHttpException("HTTP ${response.code}")
        val feed = NewsFeeds.parse(response.body, response.url)
        return FetchResult(source.copy(etag = response.header("ETag"), lastModified = response.header("Last-Modified")),
            feed.items.map { if (it.language == null) it.copy(language = feed.language) else it })
    }
}

/** X API v2 recent search. Live access is NOT TESTED; it needs the owner's bearer token on a tier that includes recent search. */
class XConnector(private val http: NewsHttp, private val token: () -> String?) : NewsConnector {
    override fun supports(kind: String) = kind == "X_ACCOUNT" || kind == "X_QUERY"
    override fun fetch(source: NewsSource, now: Long): FetchResult {
        val bearer = token()?.takeIf { it.isNotBlank() } ?: return FetchResult(source.copy(state = "NEEDS_KEY", note = NewsCatalog.X_NOTE), emptyList(), "No X API bearer token")
        val query = if (source.kind == "X_ACCOUNT") "from:${source.url} -is:retweet" else source.url
        require(query.length <= 512) { "X queries are limited to 512 characters" }
        val params = linkedMapOf("query" to query, "max_results" to "25", "tweet.fields" to "created_at,lang,author_id,entities",
            "expansions" to "author_id", "user.fields" to "username,name,verified")
        source.cursor?.let { params["since_id"] = it }
        val url = "https://api.x.com/2/tweets/search/recent?" + params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val response = http.request(url, mapOf("Authorization" to "Bearer $bearer", "Accept" to "application/json"), "GET")
        when (response.code) {
            401, 403 -> return FetchResult(source.copy(state = "NEEDS_KEY", note = "X API rejected the token (HTTP ${response.code}). Recent search needs a valid bearer token on a paid X API tier."),
                emptyList(), "X API HTTP ${response.code}")
            429 -> {
                val reset = response.header("x-rate-limit-reset")?.toLongOrNull()?.times(1000)
                return FetchResult(source, emptyList(), "X API rate limit reached", reset?.coerceIn(now + 60_000, now + NewsBackoff.DAY))
            }
        }
        if (response.code !in 200..299) throw NewsHttpException("X API HTTP ${response.code}")
        val (items, newest) = XPosts.parse(String(response.body, Charsets.UTF_8))
        return FetchResult(source.copy(cursor = newest ?: source.cursor), items)
    }
}

object XPosts {
    /** Maps a recent-search response to items (url https://x.com/<user>/status/<id>) and the newest id for the next since_id. */
    fun parse(json: String): Pair<List<FeedItem>, String?> {
        val root = JSONObject(json)
        val users = root.optJSONObject("includes")?.optJSONArray("users")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("id") }
        }.orEmpty()
        val data = root.optJSONArray("data") ?: return emptyList<FeedItem>() to root.optJSONObject("meta")?.optString("newest_id")?.ifBlank { null }
        val items = (0 until data.length()).mapNotNull { index ->
            val post = data.optJSONObject(index) ?: return@mapNotNull null
            val id = post.optString("id").takeIf { it.matches(Regex("\\d{1,25}")) } ?: return@mapNotNull null
            val user = users[post.optString("author_id")]
            val username = user?.optString("username")?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,15}")) } ?: "i/web"
            var text = post.optString("text")
            post.optJSONObject("entities")?.optJSONArray("urls")?.let { urls ->
                for (i in 0 until urls.length()) urls.optJSONObject(i)?.let { u ->
                    val short = u.optString("url"); val expanded = u.optString("expanded_url")
                    if (short.isNotBlank() && expanded.startsWith("https://")) text = text.replace(short, expanded)
                }
            }
            text = NewsHtml.decode(text).trim()
            val name = user?.optString("name")?.ifBlank { null }
            FeedItem(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140), "https://x.com/$username/status/$id", id,
                runCatching { Instant.parse(post.optString("created_at")).toEpochMilli() }.getOrNull(), NewsFeeds.excerpt(text),
                "@$username" + (name?.let { " ($it)" } ?: "") + (if (user?.optBoolean("verified") == true) " · verified" else ""),
                language = post.optString("lang").takeIf { it == "en" || it == "sw" })
        }
        val newest = root.optJSONObject("meta")?.optString("newest_id")?.ifBlank { null }
            ?: items.mapNotNull { it.guid?.toBigIntegerOrNull() }.maxOrNull()?.toString()
        return items to newest
    }
}

/** A website: tries the address as a feed, then `<link rel="alternate">` autodiscovery, then /feed, /rss, /feed/ (HEAD/GET with a content-type check). */
class SiteConnector(private val http: NewsHttp) : NewsConnector {
    override fun supports(kind: String) = kind == "SITE"
    override fun fetch(source: NewsSource, now: Long): FetchResult {
        val home = http.request(source.url, mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.5"), "GET")
        if (home.code !in 200..299) throw NewsHttpException("HTTP ${home.code}")
        if (NewsFeeds.looksLikeFeed(home.body, home.contentType)) feed(source, home, source.url, "Feed address added directly")?.let { return it }
        for (candidate in NewsDiscovery.links(String(home.body, Charsets.UTF_8), home.url).take(3)) {
            val response = runCatching { http.request(candidate, emptyMap(), "GET") }.getOrNull() ?: continue
            if (response.code in 200..299 && NewsFeeds.looksLikeFeed(response.body, response.contentType))
                feed(source, response, candidate, "Feed discovered from ${source.url}")?.let { return it }
        }
        for (path in listOf("/feed", "/rss", "/feed/")) {
            val candidate = URL(URL(home.url), path).toString()
            if (!NewsNet.isHttps(candidate)) continue
            val head = runCatching { http.request(candidate, emptyMap(), "HEAD") }.getOrNull()
            val headOk = head != null && head.code in 200..299 && NewsDiscovery.feedType(head.contentType)
            if (!headOk && !(head != null && head.code in setOf(405, 501))) continue
            val response = runCatching { http.request(candidate, emptyMap(), "GET") }.getOrNull() ?: continue
            if (response.code in 200..299 && NewsFeeds.looksLikeFeed(response.body, response.contentType))
                feed(source, response, candidate, "Feed found at $path on ${source.url}")?.let { return it }
        }
        return FetchResult(source.copy(state = "NEEDS_FEED", note = "No public feed found"), emptyList())
    }

    private fun feed(source: NewsSource, response: NewsResponse, url: String, note: String): FetchResult? {
        val parsed = runCatching { NewsFeeds.parse(response.body, response.url) }.getOrNull() ?: return null
        return FetchResult(source.copy(kind = parsed.kind, url = url, note = note, etag = response.header("ETag"), lastModified = response.header("Last-Modified")),
            parsed.items.map { if (it.language == null) it.copy(language = parsed.language) else it })
    }
}

object NewsDiscovery {
    private val types = setOf("application/rss+xml", "application/atom+xml", "application/feed+json", "application/rdf+xml")
    fun feedType(contentType: String?): Boolean = contentType.orEmpty().lowercase().let { "rss" in it || "atom" in it || "feed+json" in it || "xml" in it }

    /** `<link rel="alternate" type="application/rss+xml|atom+xml|feed+json" href>` targets, https only, in page order. */
    fun links(html: String, baseUrl: String): List<String> = Regex("(?is)<link\\b[^>]*>").findAll(html.take(512 * 1024)).mapNotNull { tag ->
        val attributes = Regex("(?is)([a-z-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").findAll(tag.value)
            .associate { it.groupValues[1].lowercase() to NewsHtml.decode(it.groupValues[3].ifEmpty { it.groupValues[4] }.ifEmpty { it.groupValues[5] }) }
        val rel = attributes["rel"].orEmpty().lowercase().split(Regex("\\s+"))
        val type = attributes["type"].orEmpty().lowercase().substringBefore(';').trim()
        if ("alternate" !in rel || type !in types) return@mapNotNull null
        runCatching { URL(URL(baseUrl), attributes["href"].orEmpty().trim()).toString() }.getOrNull()?.takeIf(NewsNet::isHttps)
    }.distinct().toList()
}

object NewsBackoff {
    const val MINUTE = 60_000L
    const val DAY = 86_400_000L
    fun base(kind: String) = if (kind.startsWith("X_")) 60 * MINUTE else 30 * MINUTE
    /** Delay before the next attempt: the base interval after success; base·2^n (≤ 24 h) plus deterministic jitter after n failures. */
    fun delay(sourceId: String, kind: String, failures: Int): Long {
        if (failures <= 0) return base(kind)
        val raw = minOf(DAY, base(kind) shl minOf(failures, 10))
        val jitter = java.util.Random(sourceId.hashCode() * 31L + failures).nextInt((raw / 10).toInt().coerceAtLeast(1)).toLong()
        return minOf(DAY, raw + jitter)
    }
}
