package com.hellfire.ass.intelligence

import com.hellfire.ass.library.ContentKind
import java.time.Instant
import java.time.ZoneId


data class SearchConstraints(
    val kinds: Set<ContentKind> = emptySet(),
    val categories: Set<String> = emptySet(),
    val text: String = "",
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val adultOnly: Boolean = false,
)

object QueryParser {
    val zone: ZoneId = ZoneId.of("Africa/Dar_es_Salaam")
    private val categoryWords = setOf(
        "travel", "beach", "nature", "food", "animals", "animal", "vehicles", "vehicle", "work", "technology",
        "documents", "document", "screenshots", "screenshot", "artwork", "memes", "meme", "entertainment",
        "adult", "music", "family", "personal",
    )
    private val stop = setOf(
        "find", "my", "the", "show", "me", "a", "an", "of", "from", "only", "ones", "containing", "with", "where",
        "saved", "all", "i", "please", "that", "those", "them", "one", "ones", "last", "year", "week", "today",
        "yesterday", "and", "or", "to", "in", "on", "for", "about", "belonging", "selected", "categories",
    )

    fun parse(utterance: String, now: Long, zone: ZoneId = this.zone): SearchConstraints {
        val lower = utterance.lowercase()
        val kinds = linkedSetOf<ContentKind>()
        if (Regex("\\b(pictures?|photos?|images?)\\b").containsMatchIn(lower)) kinds += ContentKind.IMAGE
        if (Regex("\\b(videos?|footage)\\b").containsMatchIn(lower)) kinds += ContentKind.VIDEO
        if (Regex("\\b(music|songs?)\\b").containsMatchIn(lower)) kinds += ContentKind.AUDIO
        if (Regex("\\b(audio|recordings?|podcasts?)\\b").containsMatchIn(lower)) kinds += ContentKind.AUDIO
        if (Regex("\\b(locations?|places)\\b").containsMatchIn(lower)) kinds += ContentKind.LOCATION
        if (Regex("\\bdocuments?\\b").containsMatchIn(lower)) kinds += ContentKind.DOCUMENT
        if (Regex("\\bscreenshots?\\b").containsMatchIn(lower)) kinds += ContentKind.IMAGE
        val categories = Regex("[\\p{L}\\p{N}]+").findAll(lower).map { it.value }
            .filter { it in categoryWords || it == "screenshot" || it == "screenshots" }
            .map { if (it.endsWith("s") && it.dropLast(1) in categoryWords) it.dropLast(1) else it }
            .map { if (it == "screenshots") "screenshot" else it }
            .toSet()
        val (from, to) = dates(lower, now, zone)
        val consumed = stop + setOf("pictures", "picture", "photos", "photo", "images", "image", "videos", "video", "footage", "audio", "recordings", "recording", "podcasts", "podcast", "songs", "song", "locations", "location", "places", "place", "documents", "document", "screenshots", "screenshot")
        val text = Regex("[\\p{L}\\p{N}]+").findAll(lower).map { it.value }
            .filter { it.length >= 3 && it !in consumed && it !in categories && it !in categoryWords }
            .distinct().joinToString(" ")
        return SearchConstraints(kinds, categories, text, from, to, "adult" in categories)
    }

    fun refine(prior: SearchConstraints, utterance: String, now: Long, zone: ZoneId = this.zone): SearchConstraints {
        if (utterance.trim().lowercase().startsWith("find ")) return parse(utterance, now, zone)
        val next = parse(utterance, now, zone)
        val explicitKind = Regex("\\b(pictures?|photos?|images?|videos?|footage|audio|recordings?|podcasts?|music|songs?|locations?|places|documents?|screenshots?)\\b")
            .containsMatchIn(utterance.lowercase())
        return SearchConstraints(
            kinds = if (explicitKind) next.kinds else prior.kinds,
            categories = prior.categories + next.categories,
            text = listOf(prior.text, next.text).filter { it.isNotBlank() }.joinToString(" "),
            fromMs = next.fromMs ?: prior.fromMs,
            toMs = next.toMs ?: prior.toMs,
            adultOnly = prior.adultOnly || next.adultOnly,
        )
    }

    private fun dates(lower: String, now: Long, zone: ZoneId): Pair<Long?, Long?> {
        val zoned = Instant.ofEpochMilli(now).atZone(zone)
        return when {
            "last year" in lower -> {
                val startThis = zoned.withDayOfYear(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                val startLast = zoned.minusYears(1).withDayOfYear(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                startLast to startThis
            }
            "last week" in lower -> now - 7L * 24 * 60 * 60 * 1000 to now
            "yesterday" in lower -> {
                val startToday = zoned.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                startToday - 86_400_000L to startToday
            }
            "today" in lower -> zoned.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() to now
            else -> null to null
        }
    }
}
