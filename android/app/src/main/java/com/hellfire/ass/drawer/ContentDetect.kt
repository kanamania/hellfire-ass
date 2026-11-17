package com.hellfire.ass.drawer

import com.hellfire.ass.library.ContentKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import kotlin.math.abs

/** One normalized drop. Text is untrusted data, never instructions. */
data class Normalized(
    val kind: ContentKind,
    val title: String,
    val text: String,
    val mime: String,
    val entities: List<Entity> = emptyList(),
    val places: List<Place> = emptyList(),
    val signals: Set<String> = emptySet(),
    val mixed: Boolean = false,
)

data class Entity(val type: String, val value: String, val uncertain: Boolean = false)

data class Place(
    val latitude: Double?,
    val longitude: Double?,
    val label: String,
    val source: String,
    val uncertain: Boolean = false,
)

/** Deterministic parsers. No network, no model, no invented place facts. */
object ContentDetect {
    private val url = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"']+)""")
    private val mapsHost = Regex("""(?i)(?:maps\.google\.|google\.[a-z.]+/maps|maps\.app\.goo\.gl|goo\.gl/maps)""")
    private val geo = Regex("""(?i)\bgeo:(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")
    private val navigation = Regex("""(?i)\bgoogle\.navigation:q=([^\s&]+)""")
    private val labeled = Regex("""(?i)\b(?:lat(?:itude)?|coordinates?)\s*[:=]?\s*(-?\d{1,3}\.\d+)\s*[,;\s]\s*(?:lon(?:gitude)?\s*[:=]?\s*)?(-?\d{1,3}\.\d+)""")
    private val pair = Regex("""(?<!\d)(-?\d{1,2}\.\d{3,})\s*[,;]\s*(-?\d{1,3}\.\d{3,})(?!\d)""")
    private val atPair = Regex("""@(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
    private val queryPair = Regex("""(?i)[?&](?:q|query|ll)=(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
    private val dmsPart = Regex("""(\d{1,3})\s*[°º]\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*(?:"|″)?\s*([NnSsEeWw])""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phone = Regex("""(?<!\d)(\+?\d[\d\s().-]{7,}\d)(?!\d)""")
    private val street = Regex("""(?i)\b\d{1,5}\s+[A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4}\s+(?:Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Drive|Dr|Way|Close|Boulevard|Blvd|Mtaa)\b""")
    private val stack = Regex("""(?m)^\s*at\s+[\w.$]+\(""")
    private val errorWord = Regex("""(?i)\b(exception|traceback|stack trace|fatal error|caused by:|error:)""")
    private val receiptWord = Regex("""(?i)\b(receipt|invoice|subtotal|amount due|\bvat\b|\btotal\b)""")
    private val isoDate = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
    private val slashDate = Regex("""\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b""")
    private val monthDate = Regex("""(?i)\b\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4}\b""")
    private val deadlineNear = Regex("""(?i)\b(due|deadline|expires|expiry|by)\b""")

    fun parse(text: String, mime: String? = null, name: String? = null): Normalized {
        val body = text.take(60_000)
        val signals = linkedSetOf<String>()
        val entities = mutableListOf<Entity>()
        val places = mutableListOf<Place>()
        urls(body).forEach { raw ->
            val value = raw.trimEnd('.', ',', ')')
            entities += Entity("url", value)
            signals += "url"
            if (mapsHost.containsMatchIn(value) || value.startsWith("geo:", true)) signals += "maps"
            placeFromUrl(value)?.let { places += it; signals += "coordinates" }
        }
        geo.findAll(body).forEach { match ->
            place(match.groupValues[1], match.groupValues[2], "geo", match.value)?.let {
                places += it
                signals += "maps"
                signals += "coordinates"
                entities += Entity("geo", match.value)
            }
        }
        navigation.findAll(body).forEach { match ->
            val q = decode(match.groupValues[1])
            signals += "maps"
            entities += Entity("navigation", q)
            val coords = pair.find(q) ?: queryPair.find("?q=$q")
            if (coords != null) place(coords.groupValues[1], coords.groupValues[2], "navigation", q)?.let { places += it; signals += "coordinates" }
            else places += Place(null, null, q, "navigation", uncertain = true)
        }
        labeled.findAll(body).forEach { match ->
            place(match.groupValues[1], match.groupValues[2], "labeled", match.value)?.let { places += it; signals += "coordinates" }
        }
        pair.findAll(body).forEach { match ->
            place(match.groupValues[1], match.groupValues[2], "pair", match.value)?.let { places += it; signals += "coordinates" }
        }
        dms(body).forEach { places += it; signals += "coordinates" }
        email.findAll(body).forEach { entities += Entity("email", it.value); signals += "email" }
        phones(body, places).forEach { entities += Entity("phone", it); signals += "phone" }
        street.findAll(body).forEach {
            entities += Entity("address", it.value, uncertain = true)
            signals += "address"
            if (places.none { p -> p.source == "address" && p.label == it.value })
                places += Place(null, null, it.value, "address", uncertain = true)
        }
        vcard(body, entities, signals)
        ical(body, entities, signals)
        if (json(body)) signals += "json"
        if (stack.containsMatchIn(body) || body.contains("```") || codeLines(body)) signals += "code"
        if (errorWord.containsMatchIn(body) || stack.containsMatchIn(body)) signals += "error"
        if (receiptWord.containsMatchIn(body)) signals += "receipt"
        dates(body).forEach { entities += it }
        val distinctPlaces = places.distinctBy { listOf(it.latitude, it.longitude, it.label, it.source) }
        val mimeKind = mime?.takeIf { it.isNotBlank() }?.let { ContentKind.fromMime(it) }
        val textKind = textKind(body, signals, mime)
        val kind = when {
            mimeKind == null || mimeKind == ContentKind.TEXT || mimeKind == ContentKind.OTHER -> textKind
            else -> mimeKind
        }
        if (mimeKind != null && mimeKind != textKind && textKind != ContentKind.TEXT && body.isNotBlank()) signals += "mixed"
        val title = title(name, body, entities, distinctPlaces)
        return Normalized(kind, title, body, mime ?: mimeFor(kind), entities.distinctBy { it.type to it.value }, distinctPlaces, signals)
    }

    fun combine(parts: List<Normalized>): Normalized {
        if (parts.isEmpty()) return Normalized(ContentKind.OTHER, "Empty drop", "", "application/octet-stream", signals = setOf("empty"))
        if (parts.size == 1) return parts[0]
        val kinds = parts.map { it.kind }.distinct()
        val mixed = kinds.size > 1 || parts.any { it.mixed }
        val signals = parts.flatMap { it.signals }.toMutableSet()
        if (mixed) signals += "mixed"
        return Normalized(
            kind = if (kinds.size == 1) kinds[0] else ContentKind.OTHER,
            title = parts.firstNotNullOfOrNull { it.title.takeIf(String::isNotBlank) } ?: "Mixed drop",
            text = parts.joinToString("\n\n") { it.text }.take(60_000),
            mime = if (kinds.size == 1) parts.first().mime else "*/*",
            entities = parts.flatMap { it.entities }.distinctBy { it.type to it.value },
            places = parts.flatMap { it.places }.distinctBy { listOf(it.latitude, it.longitude, it.label) },
            signals = signals,
            mixed = mixed,
        )
    }

    fun dates(text: String): List<Entity> {
        val found = (isoDate.findAll(text) + slashDate.findAll(text) + monthDate.findAll(text)).map { it.value }.distinct()
        return found.map { value ->
            val window = text.windowAround(value, 40)
            Entity(if (deadlineNear.containsMatchIn(window)) "deadline" else "date", value, uncertain = true)
        }.toList()
    }

    fun summary(content: Normalized): String {
        val bits = mutableListOf<String>()
        content.places.filter { it.latitude != null }.forEach { bits += "Coordinates ${it.latitude}, ${it.longitude} (${it.source})." }
        content.places.filter { it.latitude == null && it.label.isNotBlank() }.forEach {
            bits += if (it.uncertain) "Possible address, not verified: ${it.label}." else it.label
        }
        content.entities.filter { it.type == "url" }.take(3).forEach { bits += "URL ${it.value}." }
        content.entities.filter { it.type == "email" }.forEach { bits += "Email ${it.value}." }
        content.entities.filter { it.type == "phone" }.forEach { bits += "Phone ${it.value}." }
        content.entities.filter { it.type == "contact" }.forEach { bits += "Contact ${it.value}." }
        content.entities.filter { it.type == "event" }.forEach { bits += "Event ${it.value}." }
        content.entities.filter { it.type == "date" || it.type == "deadline" }.forEach { bits += "${it.type.replaceFirstChar { c -> c.uppercase() }} ${it.value} (from text, not verified)." }
        if ("code" in content.signals) bits += "Code or error text was saved and was not executed."
        if ("json" in content.signals) bits += "JSON was parsed as data, not executed."
        if ("url" in content.signals) bits += "The link was saved. No page snapshot was fetched."
        if (content.kind == ContentKind.IMAGE && content.text.isBlank()) bits += "Image saved. On-device OCR did not return text. QR content was not decoded: no barcode decoder is installed."
        if (content.kind == ContentKind.DOCUMENT && content.text.isBlank()) bits += "Document saved. Embedded text was not extracted."
        if (content.places.isNotEmpty()) bits += "Opening hours, weather and travel advisories need a live source and are not shown."
        if (content.mixed) bits += "Mixed content was saved as one inbox record."
        if (bits.isEmpty()) bits += content.title.ifBlank { "Saved content." }
        return bits.joinToString(" ").take(2000)
    }

    private fun textKind(text: String, signals: Set<String>, mime: String?): ContentKind = when {
        "vcard" in signals || mime == "text/vcard" || mime == "text/x-vcard" -> ContentKind.CONTACT
        "ical" in signals || mime == "text/calendar" -> ContentKind.EVENT
        "maps" in signals || "coordinates" in signals -> ContentKind.LOCATION
        "json" in signals || "code" in signals -> ContentKind.CODE
        "url" in signals && text.trim().substringBefore('\n').length < 300 && text.lines().count { it.isNotBlank() } <= 2 -> ContentKind.URL
        text.isNotBlank() -> ContentKind.TEXT
        else -> ContentKind.fromMime(mime)
    }

    private fun urls(text: String): List<String> {
        val found = url.findAll(text).map { it.groupValues[1] }.toMutableList()
        geo.findAll(text).forEach { found += it.value }
        navigation.findAll(text).forEach { found += it.value }
        return found.distinct()
    }

    private fun placeFromUrl(value: String): Place? {
        val decoded = decode(value)
        atPair.find(decoded)?.let { return place(it.groupValues[1], it.groupValues[2], "maps", value) }
        queryPair.find(decoded)?.let { return place(it.groupValues[1], it.groupValues[2], "maps", value) }
        if (mapsHost.containsMatchIn(decoded)) return Place(null, null, value, "maps", uncertain = true)
        return null
    }

    private fun place(lat: String, lon: String, source: String, label: String): Place? {
        val latitude = lat.toDoubleOrNull() ?: return null
        val longitude = lon.toDoubleOrNull() ?: return null
        if (abs(latitude) > 90 || abs(longitude) > 180) return null
        if (latitude == 0.0 && longitude == 0.0 && source == "pair") return null
        return Place(latitude, longitude, label, source)
    }

    private fun dms(text: String): List<Place> {
        val parts = dmsPart.findAll(text).toList()
        if (parts.size < 2) return emptyList()
        val out = mutableListOf<Place>()
        var i = 0
        while (i + 1 < parts.size) {
            val a = parts[i]
            val b = parts[i + 1]
            val hemiA = a.groupValues[4]
            val hemiB = b.groupValues[4]
            val latH = listOf(hemiA, hemiB).firstOrNull { it.equals("N", true) || it.equals("S", true) }
            val lonH = listOf(hemiA, hemiB).firstOrNull { it.equals("E", true) || it.equals("W", true) }
            if (latH != null && lonH != null) {
                val latPart = if (hemiA.equals("N", true) || hemiA.equals("S", true)) a else b
                val lonPart = if (latPart === a) b else a
                val lat = dmsToDecimal(latPart.groupValues[1], latPart.groupValues[2], latPart.groupValues[3], latH)
                val lon = dmsToDecimal(lonPart.groupValues[1], lonPart.groupValues[2], lonPart.groupValues[3], lonH)
                if (lat != null && lon != null && abs(lat) <= 90 && abs(lon) <= 180)
                    out += Place(lat, lon, "${latPart.value} ${lonPart.value}", "dms")
                i += 2
            } else i += 1
        }
        return out
    }

    private fun dmsToDecimal(d: String, m: String, s: String, hemi: String): Double? {
        val value = (d.toDoubleOrNull() ?: return null) + (m.toDoubleOrNull() ?: return null) / 60 + (s.toDoubleOrNull() ?: return null) / 3600
        return if (hemi.equals("S", true) || hemi.equals("W", true)) -value else value
    }

    private fun phones(text: String, places: List<Place>): List<String> {
        val blocked = places.mapNotNull { it.latitude?.let { lat -> "%.4f".format(lat) } }
        return phone.findAll(text).map { it.value.trim() }.filter { raw ->
            val digits = raw.count { it.isDigit() }
            digits in 8..15 && (raw.startsWith("+") || digits >= 10) && blocked.none { raw.contains(it.substringBefore('.')) }
        }.distinct().toList()
    }

    private fun vcard(text: String, entities: MutableList<Entity>, signals: MutableSet<String>) {
        if (!text.contains("BEGIN:VCARD")) return
        signals += "vcard"
        val fn = Regex("""(?m)^FN:(.+)$""").find(text)?.groupValues?.get(1)?.trim()
        val tel = Regex("""(?m)^TEL[^:]*:(.+)$""").find(text)?.groupValues?.get(1)?.trim()
        val mail = Regex("""(?m)^EMAIL[^:]*:(.+)$""").find(text)?.groupValues?.get(1)?.trim()
        entities += Entity("contact", listOfNotNull(fn, tel, mail).joinToString(" · ").ifBlank { "vCard" })
        tel?.let { entities += Entity("phone", it) }
        mail?.let { entities += Entity("email", it); signals += "email" }
    }

    private fun ical(text: String, entities: MutableList<Entity>, signals: MutableSet<String>) {
        if (!text.contains("BEGIN:VEVENT") && !text.contains("BEGIN:VCALENDAR")) return
        signals += "ical"
        val summary = Regex("""(?m)^SUMMARY:(.+)$""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val start = Regex("""(?m)^DTSTART[^:]*:(.+)$""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val where = Regex("""(?m)^LOCATION:(.+)$""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        entities += Entity("event", listOf(summary, start, where).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Calendar event" })
        if (start.isNotBlank()) entities += Entity("date", start, uncertain = true)
        if (where.isNotBlank()) {
            entities += Entity("address", where, uncertain = true)
            signals += "address"
        }
    }

    private fun json(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2 || (trimmed.first() != '{' && trimmed.first() != '[')) return false
        return runCatching {
            if (trimmed.first() == '{') JSONObject(trimmed) else JSONArray(trimmed)
            true
        }.getOrDefault(false)
    }

    private fun codeLines(text: String): Boolean {
        val lines = text.lines()
        if (lines.size < 3) return false
        val hits = lines.count { line ->
            val t = line.trim()
            t.startsWith("fun ") || t.startsWith("def ") || t.startsWith("class ") || t.startsWith("import ") ||
                (t.contains("{") && t.contains("}")) || t.endsWith(";") && t.contains("(")
        }
        return hits >= 2
    }

    private fun title(name: String?, text: String, entities: List<Entity>, places: List<Place>): String {
        val named = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() && !it.equals("note", true) && !it.equals("Shared text", true) }
        val fromPlace = places.firstOrNull { it.latitude != null }?.let { "${it.latitude}, ${it.longitude}" }
        val fromEntity = entities.firstOrNull { it.type == "contact" || it.type == "event" }?.value
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
        return (named ?: fromEntity ?: fromPlace ?: line ?: "Drop").take(80)
    }

    private fun mimeFor(kind: ContentKind) = when (kind) {
        ContentKind.URL -> "text/uri-list"
        ContentKind.CONTACT -> "text/vcard"
        ContentKind.EVENT -> "text/calendar"
        ContentKind.CODE -> "text/plain"
        ContentKind.LOCATION -> "text/plain"
        else -> "text/plain"
    }

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun String.windowAround(token: String, radius: Int): String {
        val at = indexOf(token)
        if (at < 0) return this
        return substring((at - radius).coerceAtLeast(0), (at + token.length + radius).coerceAtMost(length))
    }
}
