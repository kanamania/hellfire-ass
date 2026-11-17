package com.hellfire.ass.news

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory

/** One media link from a feed. Links only; nothing is downloaded or rehosted. [type]: IMAGE, VIDEO, AUDIO. */
data class FeedMedia(val type: String, val url: String, val mime: String? = null, val durationMs: Long? = null)

/** One item as the feed published it. [text] is plain text (HTML stripped, entities decoded), at most 4000 chars. */
data class FeedItem(
    val title: String,
    val url: String,
    val guid: String? = null,
    val publishedAt: Long? = null,
    val text: String = "",
    val author: String? = null,
    val categories: List<String> = emptyList(),
    val media: List<FeedMedia> = emptyList(),
    val language: String? = null,
)

data class ParsedFeed(val kind: String, val title: String?, val language: String?, val items: List<FeedItem>)

class NewsResponse(val code: Int, val body: ByteArray, val headers: Map<String, String>, val url: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    val contentType: String? get() = header("Content-Type")
}

class NewsHttpException(message: String) : IOException(message)

/** Transport for connectors; tests inject a fake. [method]: GET or HEAD. Throws IOException on network failure, refused redirects or oversized bodies. */
fun interface NewsHttp {
    fun request(url: String, headers: Map<String, String>, method: String): NewsResponse
}

object NewsNet {
    const val USER_AGENT = "HellFireAss-News/0.10 (personal reader)"
    const val MAX_BODY = 3 * 1024 * 1024
    const val MAX_REDIRECTS = 3

    fun isHttps(url: String) = runCatching { URL(url).protocol.equals("https", ignoreCase = true) && URL(url).host.isNotBlank() }.getOrDefault(false)

    fun readBounded(input: InputStream, max: Int = MAX_BODY): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (output.size() + n > max) throw NewsHttpException("Response exceeds 3 MB")
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    fun unmetered(context: android.content.Context): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return manager.activeNetwork != null && !manager.isActiveNetworkMetered
    }
}

/** HTTPS only, at most 3 same-scheme redirects, 3 MB decoded body, gzip, bounded timeouts. */
class HttpsNewsHttp(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 20_000) : NewsHttp {
    override fun request(url: String, headers: Map<String, String>, method: String): NewsResponse {
        require(method == "GET" || method == "HEAD")
        if (!NewsNet.isHttps(url)) throw NewsHttpException("Only https:// addresses are fetched")
        var current = URL(url)
        for (hop in 0..NewsNet.MAX_REDIRECTS) {
            val connection = current.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = method
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", NewsNet.USER_AGENT)
                connection.setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val code = connection.responseCode
                if (code in 300..399 && code != 304) {
                    val location = connection.getHeaderField("Location") ?: throw NewsHttpException("Redirect without a location")
                    val next = URL(current, location)
                    if (!next.protocol.equals("https", ignoreCase = true)) throw NewsHttpException("Redirect to a non-HTTPS address refused")
                    current = next
                    continue
                }
                val responseHeaders = connection.headerFields.filterKeys { it != null }.mapValues { it.value.lastOrNull().orEmpty() }
                if (connection.contentLengthLong > NewsNet.MAX_BODY && connection.getHeaderField("Content-Encoding") == null)
                    throw NewsHttpException("Response exceeds 3 MB")
                val body = if (method == "HEAD" || code == 304) ByteArray(0) else {
                    val raw = (if (code >= 400) connection.errorStream else connection.inputStream) ?: ByteArrayInputStream(ByteArray(0))
                    raw.use { stream ->
                        val decoded = if (connection.getHeaderField("Content-Encoding").equals("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream
                        NewsNet.readBounded(decoded)
                    }
                }
                return NewsResponse(code, body, responseHeaders, current.toString())
            } finally { connection.disconnect() }
        }
        throw NewsHttpException("Too many redirects")
    }
}

/** RSS 2.0, RSS 1.0 (RDF), Atom and JSON Feed 1.x. Documents with a DTD or entity declarations are refused (XXE). */
object NewsFeeds {
    const val MAX_TEXT = 4000
    private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val MEDIA = "http://search.yahoo.com/mrss/"
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val RSS1 = "http://purl.org/rss/1.0/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val plainNs = setOf(null, "", RSS1, ATOM, "http://purl.org/atom/ns#")

    fun parse(body: ByteArray, baseUrl: String): ParsedFeed {
        if (body.size > NewsNet.MAX_BODY) throw NewsHttpException("Response exceeds 3 MB")
        return if (head(body).startsWith("{")) parseJson(String(body, Charsets.UTF_8), baseUrl) else parseXml(body, baseUrl)
    }

    /** True for an RSS/RDF/Atom document or a JSON Feed (by content type or by sniffing the start of the body). */
    fun looksLikeFeed(body: ByteArray, contentType: String?): Boolean {
        val type = contentType.orEmpty().lowercase()
        if ("rss" in type || "atom" in type || "feed+json" in type || "rdf+xml" in type) return true
        val start = head(body).take(2048).lowercase()
        if (start.startsWith("{")) return "jsonfeed.org/version/1" in start
        if ("<html" in start) return false
        return start.startsWith("<") && ("<rss" in start || "<feed" in start || "<rdf:rdf" in start)
    }

    fun kindOf(body: ByteArray) = if (head(body).startsWith("{")) "JSON_FEED" else "RSS"

    private fun head(body: ByteArray): String {
        val utf16 = body.size >= 2 && ((body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) || (body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()))
        val text = String(body, 0, minOf(body.size, 8192), if (utf16) Charsets.UTF_16 else Charsets.ISO_8859_1)
        return text.trimStart('\uFEFF', 'ï', '»', '¿', ' ', '\n', '\r', '\t')
    }

    fun excerpt(text: String): String {
        if (text.length <= MAX_TEXT) return text
        val cut = text.take(MAX_TEXT)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    private fun parseXml(body: ByteArray, baseUrl: String): ParsedFeed {
        val utf16 = body.size >= 2 && (body[0] == 0xFE.toByte() || body[0] == 0xFF.toByte())
        val scan = String(body, if (utf16) Charsets.UTF_16 else Charsets.ISO_8859_1)
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(scan)) { "Feeds with a DTD or entity declarations are refused" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.setErrorHandler(null)
        val root = builder.parse(ByteArrayInputStream(body)).documentElement
        return when (root.local.lowercase()) {
            "rss" -> {
                val channel = root.kid("channel") ?: root
                val language = channel.plain("language").ifBlank { null }
                ParsedFeed("RSS", channel.plain("title").let(::clean).ifBlank { null }, language,
                    channel.kids().filter { it.local == "item" }.mapNotNull { rssItem(it, baseUrl, language) })
            }
            "rdf" -> {
                val channel = root.kids().firstOrNull { it.local == "channel" }
                val language = channel?.text("language", DC)?.ifBlank { null }
                ParsedFeed("RSS", channel?.plain("title")?.let(::clean), language,
                    root.kids().filter { it.local == "item" }.mapNotNull { rssItem(it, baseUrl, language) })
            }
            "feed" -> {
                val language = root.getAttribute("xml:lang").ifBlank { root.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang") }.ifBlank { null }
                ParsedFeed("RSS", root.plain("title").let(::clean).ifBlank { null }, language,
                    root.kids().filter { it.local == "entry" }.mapNotNull { atomEntry(it, baseUrl, language) })
            }
            else -> throw IllegalArgumentException("Not an RSS, RDF or Atom feed")
        }
    }

    private fun rssItem(item: Element, baseUrl: String, language: String?): FeedItem? {
        val guidElement = item.kids().firstOrNull { it.local == "guid" && it.namespaceURI in plainNs }
        val guid = guidElement?.textContent?.trim()?.ifBlank { null } ?: item.getAttributeNS(RDF, "about").ifBlank { null }
        var link = item.plain("link").trim()
        if (link.isBlank()) link = atomLink(item).orEmpty()
        if (link.isBlank() && guid != null && guidElement?.getAttribute("isPermaLink") != "false") link = guid
        val url = resolve(baseUrl, link) ?: return null
        val date = item.plain("pubDate").ifBlank { item.text("date", DC) }.ifBlank { item.plain("updated") }.ifBlank { item.plain("published") }
        val html = item.text("encoded", CONTENT).ifBlank { item.plain("description") }.ifBlank { item.plain("summary") }
        val author = item.text("creator", DC).ifBlank { item.plain("author") }.ifBlank { null }
        val categories = item.kids().filter { (it.local == "category" && it.namespaceURI in plainNs) || (it.local == "subject" && it.namespaceURI == DC) }
            .map { clean(it.textContent) }.filter { it.isNotBlank() }.distinct()
        return FeedItem(clean(item.plain("title")), url, guid, NewsDates.parse(date), excerpt(NewsHtml.text(html)), author?.let(::clean),
            categories, media(item, baseUrl), language)
    }

    private fun atomEntry(entry: Element, baseUrl: String, language: String?): FeedItem? {
        val url = resolve(baseUrl, atomLink(entry).orEmpty()) ?: return null
        val date = entry.plain("published").ifBlank { entry.plain("updated") }.ifBlank { entry.plain("issued") }.ifBlank { entry.plain("modified") }
            .ifBlank { entry.text("date", DC) }
        val html = entry.plain("content").ifBlank { entry.plain("summary") }
        val author = entry.kids().firstOrNull { it.local == "author" }?.let { it.plain("name").ifBlank { it.textContent } }?.trim()?.ifBlank { null }
            ?: entry.text("creator", DC).ifBlank { null }
        val categories = entry.kids().filter { it.local == "category" }.map { clean(it.getAttribute("term").ifBlank { it.textContent }) }.filter { it.isNotBlank() }.distinct()
        val lang = entry.getAttribute("xml:lang").ifBlank { null } ?: language
        return FeedItem(clean(entry.plain("title")), url, entry.plain("id").ifBlank { null }, NewsDates.parse(date), excerpt(NewsHtml.text(html)),
            author?.let(::clean), categories, media(entry, baseUrl), lang)
    }

    private fun atomLink(element: Element): String? {
        val links = element.kids().filter { it.local == "link" && it.hasAttribute("href") }
        val alternates = links.filter { it.getAttribute("rel").let { rel -> rel.isBlank() || rel == "alternate" } }
        return (alternates.firstOrNull { it.getAttribute("type").startsWith("text/html") } ?: alternates.firstOrNull())?.getAttribute("href")?.trim()
    }

    private fun media(item: Element, baseUrl: String): List<FeedMedia> {
        val found = mutableListOf<FeedMedia>()
        fun add(type: String?, url: String?, mime: String? = null, seconds: String? = null) {
            val resolved = resolve(baseUrl, url.orEmpty()) ?: return
            if (!NewsNet.isHttps(resolved) || type == null) return
            found += FeedMedia(type, resolved, mime?.ifBlank { null }, seconds?.toDoubleOrNull()?.let { (it * 1000).toLong() })
        }
        val elements = item.kids().flatMap { if (it.local == "group" && it.namespaceURI == MEDIA) it.kids() else listOf(it) }
        for (element in elements) {
            when {
                element.namespaceURI == MEDIA && element.local == "content" -> add(mediaType(element.getAttribute("type"), element.getAttribute("medium"), element.getAttribute("url")),
                    element.getAttribute("url"), element.getAttribute("type"), element.getAttribute("duration"))
                element.namespaceURI == MEDIA && element.local == "thumbnail" -> add("IMAGE", element.getAttribute("url"))
                element.local == "enclosure" && element.namespaceURI in plainNs -> add(mediaType(element.getAttribute("type"), null, element.getAttribute("url")),
                    element.getAttribute("url"), element.getAttribute("type"))
                element.local == "link" && element.getAttribute("rel") == "enclosure" -> add(mediaType(element.getAttribute("type"), null, element.getAttribute("href")),
                    element.getAttribute("href"), element.getAttribute("type"))
                element.namespaceURI == ITUNES && element.local == "image" -> add("IMAGE", element.getAttribute("href"))
            }
        }
        return found.distinctBy { it.url }
    }

    fun mediaType(mime: String?, medium: String?, url: String?): String? {
        when (medium.orEmpty().lowercase()) { "image" -> return "IMAGE"; "video" -> return "VIDEO"; "audio" -> return "AUDIO" }
        val type = mime.orEmpty().lowercase()
        when {
            type.startsWith("image/") -> return "IMAGE"
            type.startsWith("video/") || type == "application/x-mpegurl" -> return "VIDEO"
            type.startsWith("audio/") -> return "AUDIO"
        }
        val path = url.orEmpty().substringBefore('?').lowercase()
        return when {
            Regex("\\.(jpe?g|png|webp|gif|avif)$").containsMatchIn(path) -> "IMAGE"
            Regex("\\.(mp4|m3u8|webm|mov)$").containsMatchIn(path) -> "VIDEO"
            Regex("\\.(mp3|m4a|aac|ogg|opus)$").containsMatchIn(path) -> "AUDIO"
            else -> null
        }
    }

    private fun parseJson(text: String, baseUrl: String): ParsedFeed {
        val feed = JSONObject(text)
        require(feed.optString("version").startsWith("https://jsonfeed.org/version/1")) { "Not a JSON Feed 1.x document" }
        val language = feed.optString("language").ifBlank { null }
        val items = feed.optJSONArray("items") ?: JSONArray()
        return ParsedFeed("JSON_FEED", feed.optString("title").ifBlank { null }, language, (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val url = resolve(baseUrl, item.optString("url").ifBlank { item.optString("external_url") }) ?: return@mapNotNull null
            val body = item.optString("content_text").ifBlank { NewsHtml.text(item.optString("content_html")) }.ifBlank { item.optString("summary") }
            val author = item.optJSONArray("authors")?.optJSONObject(0)?.optString("name")?.ifBlank { null }
                ?: item.optJSONObject("author")?.optString("name")?.ifBlank { null }
            val tags = item.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            val media = mutableListOf<FeedMedia>()
            for (key in listOf("image", "banner_image")) resolve(baseUrl, item.optString(key))?.takeIf(NewsNet::isHttps)?.let { media += FeedMedia("IMAGE", it) }
            item.optJSONArray("attachments")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { att ->
                val link = resolve(baseUrl, att.optString("url"))?.takeIf(NewsNet::isHttps) ?: return@let
                val type = mediaType(att.optString("mime_type"), null, link) ?: return@let
                media += FeedMedia(type, link, att.optString("mime_type").ifBlank { null },
                    if (att.has("duration_in_seconds")) (att.optDouble("duration_in_seconds") * 1000).toLong() else null)
            } }
            FeedItem(clean(item.optString("title")), url, item.optString("id").ifBlank { null },
                NewsDates.parse(item.optString("date_published").ifBlank { item.optString("date_modified") }),
                excerpt(NewsHtml.text(body)), author, tags, media.distinctBy { it.url }, item.optString("language").ifBlank { null } ?: language)
        })
    }

    private fun resolve(base: String, link: String): String? {
        if (link.isBlank()) return null
        return runCatching { URL(URL(base), link.trim()).toString() }.getOrNull()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun clean(text: String) = NewsHtml.text(text).replace('\n', ' ').trim()
    private val Element.local: String get() = localName ?: nodeName.substringAfter(':')
    private fun Element.kids(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.kid(name: String): Element? = kids().firstOrNull { it.local == name }
    private fun Element.plain(name: String): String = kids().firstOrNull { it.local == name && it.namespaceURI in plainNs }?.textContent?.trim().orEmpty()
    private fun Element.text(name: String, ns: String): String = kids().firstOrNull { it.local == name && it.namespaceURI == ns }?.textContent?.trim().orEmpty()
}

/** HTML fragment → plain text. */
object NewsHtml {
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ",
        "ccedil" to "ç", "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß", "euro" to "€", "pound" to "£", "deg" to "°", "middot" to "·",
        "bull" to "•", "laquo" to "«", "raquo" to "»", "times" to "×", "shy" to "", "zwj" to "", "zwnj" to "")

    fun text(html: String): String {
        if (html.isBlank()) return ""
        var s = html
        s = Regex("(?is)<(script|style|iframe|noscript|svg)\\b[^>]*>.*?</\\1\\s*>").replace(s, " ")
        s = Regex("(?s)<!--.*?-->").replace(s, " ")
        s = Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|blockquote|tr)\\s*>").replace(s, "\n")
        s = Regex("(?s)<[^>]*>").replace(s, "")
        s = decode(s)
        s = s.replace(Regex("[ \\t\\u00A0\\u2009\\u200B]+"), " ")
        s = s.lines().joinToString("\n") { it.trim() }.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    fun decode(text: String): String = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,7});").replace(text) { match ->
        val entity = match.groupValues[1]
        when {
            entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }
            else -> named[entity] ?: named[entity.lowercase()]
        } ?: match.value
    }
}

/** RFC 822/1123 (with common variants) and ISO 8601 feed dates → epoch ms, or null. */
object NewsDates {
    private val zones = mapOf("UT" to "+0000", "UTC" to "+0000", "GMT" to "+0000", "Z" to "+0000", "EST" to "-0500", "EDT" to "-0400",
        "CST" to "-0600", "CDT" to "-0500", "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700", "EAT" to "+0300",
        "CET" to "+0100", "CEST" to "+0200", "BST" to "+0100", "WAT" to "+0100", "CAT" to "+0200", "SAST" to "+0200", "IST" to "+0530")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parse(raw: String?): Long? {
        val text = raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        return iso(text) ?: rfc822(text)
    }

    private fun iso(text: String): Long? {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(text)) return null
        var t = text.replace(Regex("^(\\d{4}-\\d{2}-\\d{2}) (\\d)"), "$1T$2").replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        if (t.endsWith(" UTC") || t.endsWith(" GMT")) t = t.dropLast(4) + "Z"
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(t.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun rfc822(text: String): Long? {
        val t = text.replace(Regex("^[A-Za-z]{2,9}\\.?,?\\s*"), "").replace(",", " ").replace(Regex("\\s+"), " ").trim()
        val parts = t.split(" ")
        if (parts.size < 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = months.indexOf(parts[1].take(3).lowercase()) + 1
        if (month == 0) return null
        return runCatching { rfcInstant(parts, day, month) }.getOrNull()
    }

    private fun rfcInstant(parts: List<String>, day: Int, month: Int): Long {
        var year = parts[2].toInt()
        if (year < 100) year += if (year < 50) 2000 else 1900
        val time = parts.getOrNull(3)?.takeIf { ':' in it }?.split(":").orEmpty()
        val zoneText = parts.getOrNull(if (time.isEmpty()) 3 else 4).orEmpty()
        val offset = when {
            zoneText.isEmpty() -> "+0000"
            Regex("[+-]\\d{2}:?\\d{2}").matches(zoneText) -> zoneText.replace(":", "")
            else -> zones[zoneText.uppercase()] ?: "+0000"
        }
        return LocalDateTime.of(year, month, day, time.getOrNull(0)?.toInt() ?: 0, time.getOrNull(1)?.toInt() ?: 0, time.getOrNull(2)?.toDouble()?.toInt() ?: 0)
            .toInstant(ZoneOffset.of(offset)).toEpochMilli()
    }
}
