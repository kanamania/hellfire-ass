package com.hellfire.ass.news

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A parsed news question. [from]/[to]: time window (epoch ms, end exclusive). [changed]: "what changed in the story I heard";
 * [social]: only X posts, labelled unverified. [terms]: free words after "about"/"kuhusu".
 */
data class NewsQuestion(
    val from: Long,
    val to: Long,
    val window: String,
    val places: Set<String> = emptySet(),
    val topics: Set<String> = emptySet(),
    val important: Boolean = false,
    val saved: Boolean = false,
    val changed: Boolean = false,
    val social: Boolean = false,
    val terms: List<String> = emptyList(),
)

/** English and Kiswahili news questions. Returns null for anything that is not clearly about news. */
object NewsAsk {
    private const val DAY = 86_400_000L
    val places = listOf("dar es salaam", "dodoma", "arusha", "mwanza", "zanzibar", "mbeya", "morogoro", "tanga", "moshi", "kilimanjaro", "iringa",
        "tabora", "kigoma", "mtwara", "tanzania", "kenya", "nairobi", "mombasa", "uganda", "kampala", "rwanda", "kigali", "burundi", "congo", "drc",
        "mozambique", "malawi", "zambia", "ethiopia", "somalia", "south sudan", "sudan", "east africa", "africa", "south africa", "nigeria", "egypt",
        "usa", "united states", "china", "india", "europe", "uk", "britain", "ukraine", "russia", "israel", "gaza", "iran", "japan")
    private val swPlaces = mapOf("afrika mashariki" to "east africa", "afrika kusini" to "south africa", "afrika" to "africa", "marekani" to "usa",
        "uingereza" to "uk", "uchina" to "china", "urusi" to "russia", "kongo" to "congo", "msumbiji" to "mozambique", "uhindi" to "india")
    private val topicPatterns = listOf(
        "ai" to "\\b(ai|a\\.i\\.|artificial intelligence|akili mnemba|akili bandia)\\b",
        "smartphones" to "\\b(smartphones?|phones?|simu janja|simu za mkononi|simu)\\b",
        "hardware" to "\\b(hardware|chips?|processors?|gpus?|vifaa)\\b",
        "software" to "\\b(software|apps?|updates? to android|programu)\\b",
        "technology" to "\\b(tech|technology|teknolojia)\\b",
        "business" to "\\b(business|companies|markets?|biashara|soko)\\b",
        "economy" to "\\b(economy|economic|inflation|uchumi|mfumuko)\\b",
        "science" to "\\b(science|space|sayansi)\\b",
        "sports" to "\\b(sports?|football|soccer|michezo|soka|mpira)\\b",
        "entertainment" to "\\b(entertainment|music|movies?|burudani|muziki|filamu)\\b",
        "politics" to "\\b(politics|political|elections?|siasa|uchaguzi)\\b",
        "health" to "\\b(health|disease|outbreak|afya|ugonjwa)\\b",
    )
    private val numbers = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "ten" to 10, "fourteen" to 14,
        "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5, "sita" to 6, "saba" to 7, "kumi" to 10)
    private val greetings = Regex("^(habari|habari yako|habari gani|habari zenu|habari za asubuhi|habari za mchana|habari za jioni|habari za kazi|habari ya asubuhi)[?!. ]*$")
    private val stop = setOf("the", "a", "an", "of", "on", "in", "about", "is", "are", "and", "news", "story", "stories", "today", "yesterday", "week",
        "this", "that", "what", "x", "twitter", "saying", "people", "kuhusu", "ya", "za", "wa", "la", "na", "habari", "leo", "jana")

    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[’'`]"), "'").replace(Regex("\\s+"), " ").trim()

    fun parse(question: String, now: Long, zone: ZoneId, knownPlaces: Collection<String> = emptyList()): NewsQuestion? {
        val q = normalize(question).trimEnd('?', '.', '!', ' ')
        if (q.isBlank() || greetings.matches(q)) return null
        val newsWord = Regex("\\b(news|headlines?|stories|story|breaking|habari|taarifa|matukio|stori)\\b").containsMatchIn(q)
        val happened = Regex("\\b(happened|happening|going on|kilichotokea|kimetokea|kilitokea|yaliyotokea|yametokea|yanayoendelea|kinachoendelea|nini kimetokea)\\b").containsMatchIn(q)
        val changed = Regex("what('s| has| have)? changed|what is new|what's new|any updates?|updates? on|nini kimebadilika|kimebadilika|kipi kipya|nini kipya|imebadilika").containsMatchIn(q)
        val heard = Regex("\\b(i heard|i read|i saw|nilisikia|niliyosikia|nilisoma|niliyosoma|niliyoiona)\\b").containsMatchIn(q)
        val social = Regex("(people on x|on x\\b|\\bx posts?|twitter|tweets?|watu (wa|kwenye) x|kwenye x|mtandao wa x)").containsMatchIn(q)
        val important = Regex("\\b(important|top|major|critical|biggest|muhimu|kubwa|kuu)\\b").containsMatchIn(q)
        val saved = Regex("\\b(saved|bookmarked|bookmarks|read later|nilizohifadhi|zilizohifadhiwa|nilizozihifadhi|nilizoweka)\\b").containsMatchIn(q)
        val placeHits = linkedSetOf<String>()
        swPlaces.forEach { (sw, en) -> if (Regex("\\b${Regex.escape(sw)}\\b").containsMatchIn(q)) placeHits += en }
        (places + knownPlaces.map(::normalize)).distinct().filter { it.length >= 3 || it == "uk" }
            .forEach { place -> if (Regex("\\b${Regex.escape(place)}\\b").containsMatchIn(q)) placeHits += place }
        if ("east africa" in placeHits || "south africa" in placeHits) placeHits.remove("africa")
        if ("south sudan" in placeHits) placeHits.remove("sudan")
        val topics = topicPatterns.filter { Regex(it.second).containsMatchIn(q) }.map { it.first }.toSet()
        val about = Regex("\\b(about|kuhusu|regarding)\\s+(.+)$").find(q)?.groupValues?.get(2).orEmpty()
        val terms = about.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop }.distinct()

        val isNews = (newsWord && !(q.startsWith("habari") && q.split(' ').size <= 2 && placeHits.isEmpty() && topics.isEmpty())) ||
            (happened && (placeHits.isNotEmpty() || topics.isNotEmpty())) || (changed && (heard || newsWord)) ||
            (social && Regex("\\b(saying|say|think|posting|wanasema|wanasemaje|kuhusu|about)\\b").containsMatchIn(q)) || (saved && newsWord)
        if (!isNews) return null

        val local = Instant.ofEpochMilli(now).atZone(zone)
        val today = local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val lastDays = Regex("\\b(?:last|past|previous) (\\d{1,2}|[a-z]+) days?\\b").find(q)?.groupValues?.get(1)
            ?: Regex("\\bsiku (\\d{1,2}|[a-z]+) (?:zilizopita|za mwisho|zilizopo)\\b").find(q)?.groupValues?.get(1)
        val days = lastDays?.let { it.toIntOrNull() ?: numbers[it] }?.coerceIn(1, 31)
        val (from, to, window) = when {
            days != null -> Triple(now - days * DAY, now + 1, "the last $days days")
            Regex("\\b(today|tonight|leo)\\b").containsMatchIn(q) -> Triple(today, now + 1, "today")
            Regex("\\b(yesterday|jana)\\b").containsMatchIn(q) -> Triple(local.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), today, "yesterday")
            Regex("\\b(this week|wiki hii)\\b").containsMatchIn(q) -> Triple(local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone).toInstant().toEpochMilli(), now + 1, "this week")
            Regex("\\b(last week|wiki iliyopita)\\b").containsMatchIn(q) -> Triple(now - 7 * DAY, now + 1, "the last 7 days")
            Regex("\\b(this month|mwezi huu)\\b").containsMatchIn(q) -> Triple(local.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli(), now + 1, "this month")
            saved -> Triple(0L, now + 1, "all saved")
            changed -> Triple(now - 2 * DAY, now + 1, "the last 2 days")
            else -> Triple(now - DAY, now + 1, "the last 24 hours")
        }
        return NewsQuestion(from, to, window, placeHits, topics, important, saved, changed, social, terms)
    }
}
