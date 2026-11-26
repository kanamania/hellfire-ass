package com.hellfire.ass.tasks

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object TaskTime {
    val zone: ZoneId = ZoneId.of("Africa/Dar_es_Salaam")
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    fun parse(value: String): Long = LocalDateTime.parse(value.trim(), format).atZone(zone).toInstant().toEpochMilli()
    fun display(value: Long): String = Instant.ofEpochMilli(value).atZone(zone).format(format)
    fun firstAlert(due: Long, now: Long): Long = when {
        due - now >= 24 * 60 * 60_000 -> due - 24 * 60 * 60_000
        due - now >= 2 * 60 * 60_000 -> due - 2 * 60 * 60_000
        due > now -> due
        else -> now
    }
    /** Extracted deadlines more than a week past are historical (SMS/mail backfill), not review items. */
    fun reviewable(due: Long, now: Long): Boolean = due >= now - 7 * 24 * 60 * 60_000L
    fun nextAlert(due: Long, now: Long, repeatMinutes: Int = 10): Long {
        require(repeatMinutes in 5..1440)
        return when {
        due - now > 2 * 60 * 60_000 -> due - 2 * 60 * 60_000
        due > now -> due
        else -> Math.addExact(now, repeatMinutes * 60_000L)
        }
    }
}

data class TaskCandidate(val title: String, val dueAt: Long, val party: String, val evidence: String)

/** Swahili clock time: "saa nne asubuhi" is 10:00, "saa saba mchana" 13:00, "saa mbili usiku" 20:00. */
object SwahiliTime {
    private val numbers = listOf("kumi na mbili" to 12, "kumi na moja" to 11, "kumi" to 10, "moja" to 1, "mbili" to 2, "tatu" to 3,
        "nne" to 4, "tano" to 5, "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9)
    private val pattern = Regex("\\bsaa\\s+(kumi na mbili|kumi na moja|kumi|moja|mbili|tatu|nne|tano|sita|saba|nane|tisa|1[0-2]|[1-9])" +
        "(?:\\s+(?:na\\s+)?(nusu|robo))?(?:\\s+(asubuhi|mchana|alasiri|jioni|usiku|alfajiri))?\\b")
    fun find(lower: String): LocalTime? {
        val match = pattern.find(lower) ?: return null
        val word = match.groupValues[1]
        val hour = word.toIntOrNull() ?: numbers.first { it.first == word }.second
        val minute = when (match.groupValues[2]) { "nusu" -> 30; "robo" -> 15; else -> 0 }
        return LocalTime.of(western(hour, match.groupValues[3]), minute)
    }
    /** Swahili hours count from 06:00 (saa moja = 07:00) and from 18:00 at night. */
    fun western(hour: Int, period: String): Int = when (period) {
        "usiku", "alfajiri" -> if (hour <= 5) hour + 18 else hour - 6
        "asubuhi" -> if (hour >= 11) hour - 6 else hour + 6
        else -> hour + 6
    } % 24
}

object TaskExtractor {
    private val commitment = Regex("\\b(will|'ll|shall|going to|remind|meeting|meet|appointment|call|pay|send|deliver|submit|bring|visit|" +
        "finish|return|confirm|due|deadline|invoice|please|need to|must|have to|interview|class|flight|" +
        "(?:ni|u|a|tu|m|wa)ta[a-z]+|tukutane|nikutane|tuonane|kikao|mkutano|miadi|ahadi|lipa|kulipa|tuma|kutuma|leta|njoo|nipigie|" +
        "tafadhali|kumbuka|nikumbushe)\\b")
    /** Swahili nouns shaped like "-ta-" future verbs ("mtandao umerudi", "utalii umeongezeka") that promise nothing. */
    private val futureLookalike = Regex("^(?:mtandao\\w*|mtaa\\w*|mtaalam\\w*|mtaji\\w*|utalii|utafiti\\w*|utaratibu\\w*|utamaduni\\w*|" +
        "utajiri\\w*|utawala\\w*|utata\\w*|utamu|wataalam\\w*|watalii|watani\\w*|watafiti\\w*)$")

    private const val SW_HOURS = "kumi na mbili|kumi na moja|kumi|moja|mbili|tatu|nne|tano|sita|saba|nane|tisa"
    private const val PERIODS = "asubuhi|mchana|alasiri|jioni|usiku|alfajiri"
    private const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|jumatatu|jumanne|jumatano|alhamisi|ijumaa|jumamosi|jumapili"
    private val counts = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5, "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
        "kumi na moja" to 11, "kumi na mbili" to 12, "kumi na tano" to 15, "ishirini" to 20, "thelathini" to 30, "arobaini" to 40)
    private const val COUNT = "\\d{1,3}|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty"
    private const val SW_COUNT = "\\d{1,3}|kumi na mbili|kumi na moja|kumi na tano|kumi|moja|mbili|tatu|nne|tano|sita|saba|nane|tisa|ishirini|thelathini|arobaini"
    private val inEnglish = Regex("\\bin\\s+(?:(half an)\\s+hour|($COUNT)\\s+(minutes?|mins?|hours?|hrs?))\\b")
    private val inSwahili = Regex("\\bbaada\\s+ya\\s+(?:(nusu)\\s+saa|(dakika|saa)\\s+($SW_COUNT))\\b")
    private val twelveHour = Regex("\\b(1[0-2]|0?[1-9])(?::([0-5][0-9]))?\\s*([ap])\\.?\\s?m\\.?(?![a-z])")
    private val swahiliDigital = Regex("\\bsaa\\s+(1[0-2]|0?[1-9]):([0-5][0-9])\\s+($PERIODS)\\b")
    private val digital = Regex("\\b([01]?[0-9]|2[0-3]):([0-5][0-9])\\b")
    private val dayParts = listOf(
        Regex("\\btonight\\b") to LocalTime.of(20, 0),
        Regex("\\b(?:this|in the)\\s+evening\\b") to LocalTime.of(18, 0),
        Regex("\\b(?:this|in the)\\s+afternoon\\b") to LocalTime.of(14, 0),
        Regex("\\b(?:this|in the)\\s+morning\\b") to LocalTime.of(8, 0),
        Regex("\\b(?:at\\s+)?(?:noon|midday)\\b") to LocalTime.of(12, 0),
        Regex("\\busiku\\b") to LocalTime.of(20, 0),
        Regex("\\bjioni\\b") to LocalTime.of(18, 0),
        Regex("\\balasiri\\b") to LocalTime.of(15, 0),
        Regex("\\bmchana\\b") to LocalTime.of(13, 0),
        Regex("\\basubuhi\\b") to LocalTime.of(8, 0))
    private val todayWords = Regex("\\b(today|leo|tonight|this\\s+(?:morning|afternoon|evening))\\b")
    private val dayOfMonth = Regex("\\b(?:on\\s+)?(?:the\\s+)?([1-9]|[12][0-9]|3[01])(?:st|nd|rd|th)\\b|\\btarehe\\s+([1-9]|[12][0-9]|3[01])\\b")
    private val weekday = Regex("\\b(next\\s+)?($WEEKDAYS)\\b")

    /** Phrases that only say when; [com.hellfire.ass.assistant.AssistantCommands.stripWhen] removes them from titles. */
    internal val whenPhrases: List<Regex> = listOf(
        "\\b(?:in\\s+(?:half an\\s+hour|(?:$COUNT)\\s+(?:minutes?|mins?|hours?|hrs?)))\\b",
        "\\bbaada\\s+ya\\s+(?:nusu\\s+saa|(?:dakika|saa)\\s+(?:$SW_COUNT))\\b",
        "\\b(?:at\\s+)?(?:1[0-2]|0?[1-9])(?::[0-5][0-9])?\\s*[ap]\\.?\\s?m\\.?(?![a-z])",
        "\\b(?:at\\s+|saa\\s+)?(?:[01]?[0-9]|2[0-3]):[0-5][0-9](?:\\s+(?:$PERIODS))?\\b",
        "\\bsaa\\s+(?:$SW_HOURS|1[0-2]|[1-9])(?:\\s+(?:na\\s+)?(?:nusu|robo))?(?:\\s+(?:$PERIODS))?\\b",
        "\\b(?:on\\s+)?20[0-9]{2}-[0-9]{2}-[0-9]{2}\\b",
        "\\b(?:the\\s+)?(?:day after tomorrow|keshokutwa|kesho kutwa)\\b",
        "\\b(?:today|tomorrow|leo|kesho|tonight)\\b",
        "\\b(?:this|in the)\\s+(?:morning|afternoon|evening)\\b",
        "\\b(?:at\\s+)?(?:noon|midday)\\b",
        "\\b(?:$PERIODS)\\b",
        "\\b(?:on\\s+|by\\s+)?(?:the\\s+)?(?:[1-9]|[12][0-9]|3[01])(?:st|nd|rd|th)\\b",
        "\\b(?:on\\s+|by\\s+)?tarehe\\s+(?:[1-9]|[12][0-9]|3[01])\\b",
        "\\b(?:on\\s+|by\\s+|next\\s+|this\\s+)?(?:$WEEKDAYS)\\b",
    ).map { Regex("(?i)$it") }

    private fun count(word: String): Long = word.toLongOrNull() ?: counts.getValue(word).toLong()

    /** "in 20 minutes", "baada ya saa moja": an offset from the source time, in milliseconds. */
    private fun offset(lower: String): Long? {
        inEnglish.find(lower)?.let { m ->
            if (m.groupValues[1].isNotEmpty()) return 30 * 60_000L
            val n = count(m.groupValues[2])
            return if (m.groupValues[3].startsWith("h")) n * 3_600_000L else n * 60_000L
        }
        inSwahili.find(lower)?.let { m ->
            if (m.groupValues[1].isNotEmpty()) return 30 * 60_000L
            val n = count(m.groupValues[3])
            return if (m.groupValues[2] == "saa") n * 3_600_000L else n * 60_000L
        }
        return null
    }

    /** The stated clock time, or null when the text gives none. */
    private fun clock(lower: String): LocalTime? {
        twelveHour.find(lower)?.let { m ->
            val hour = m.groupValues[1].toInt() % 12 + if (m.groupValues[3] == "p") 12 else 0
            return LocalTime.of(hour, m.groupValues[2].toIntOrNull() ?: 0)
        }
        swahiliDigital.find(lower)?.let { m ->
            return LocalTime.of(SwahiliTime.western(m.groupValues[1].toInt(), m.groupValues[3]), m.groupValues[2].toInt())
        }
        digital.find(lower)?.let { m -> return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        SwahiliTime.find(lower)?.let { return it }
        return dayParts.firstOrNull { it.first.containsMatchIn(lower) }?.second
    }

    private fun nextDayOfMonth(day: Int, from: LocalDate): LocalDate? =
        (0L..12L).map { from.withDayOfMonth(1).plusMonths(it) }.firstNotNullOfOrNull { month ->
            if (day <= month.lengthOfMonth()) month.withDayOfMonth(day).takeIf { !it.isBefore(from) } else null
        }

    private fun hasCommitment(lower: String): Boolean =
        commitment.findAll(lower).any { !futureLookalike.matches(it.value) }

    /**
     * [requireCommitment] is off for owner-typed text (commands, quick add), which is a request by definition.
     * Owner text may also give only a time ("at 17:00", "5pm"), meaning the next such time, and a weekday that already
     * passed today means next week. Captured messages need an explicit day, so a stray clock time never becomes a task.
     */
    fun extract(text: String, sourceTime: Long, outgoing: Boolean, requireCommitment: Boolean = true): TaskCandidate? {
        val source = Instant.ofEpochMilli(sourceTime).atZone(TaskTime.zone)
        val lower = text.lowercase(Locale.ROOT)
        val owner = !requireCommitment
        if (requireCommitment && !hasCommitment(lower)) return null
        val party = if (outgoing) "OWNER" else if (Regex("\\b(i will|nitak|nita[a-z])").containsMatchIn(lower)) "OTHER" else "UNKNOWN"
        offset(lower)?.let { return TaskCandidate(text.take(300), Math.addExact(sourceTime, it), party, text) }

        val time = clock(lower)
        var rollDays = 0L
        // Whole words only: "Leonard" is not "leo", and "keshokutwa" is two days ahead, not "kesho".
        var date: LocalDate? = when {
            Regex("\\b(day after tomorrow|keshokutwa|kesho kutwa)\\b").containsMatchIn(lower) -> source.toLocalDate().plusDays(2)
            Regex("\\b(tomorrow|kesho)\\b").containsMatchIn(lower) -> source.toLocalDate().plusDays(1)
            todayWords.containsMatchIn(lower) -> source.toLocalDate()
            else -> null
        }
        if (date == null) Regex("\\b(20[0-9]{2}-[0-9]{2}-[0-9]{2})\\b").find(text)?.value?.let { explicit ->
            date = runCatching { LocalDate.parse(explicit) }.getOrNull() ?: return null
        }
        if (date == null) dayOfMonth.find(lower)?.let { m ->
            val day = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
            date = nextDayOfMonth(day, source.toLocalDate())
            if (owner && date == source.toLocalDate() && time != null && time.isBefore(source.toLocalTime())) date = nextDayOfMonth(day, source.toLocalDate().plusDays(1))
        }
        if (date == null) weekday.find(lower)?.let { m ->
            val day = dayOfWeek(m.groupValues[2])
            date = if (m.groupValues[1].isNotEmpty()) source.toLocalDate().with(TemporalAdjusters.next(day))
                else source.toLocalDate().with(TemporalAdjusters.nextOrSame(day))
            if (owner && m.groupValues[1].isEmpty()) rollDays = 7
        }
        if (date == null && owner && time != null) { date = source.toLocalDate(); rollDays = 1 }
        val day = date ?: return null
        var due = day.atTime(time ?: LocalTime.of(9, 0)).atZone(TaskTime.zone)
        if (rollDays > 0 && due.isBefore(source)) due = due.plusDays(rollDays)
        return TaskCandidate(text.take(300), due.toInstant().toEpochMilli(), party, text)
    }

    private fun dayOfWeek(word: String): DayOfWeek = when (word) {
        "monday", "jumatatu" -> DayOfWeek.MONDAY
        "tuesday", "jumanne" -> DayOfWeek.TUESDAY
        "wednesday", "jumatano" -> DayOfWeek.WEDNESDAY
        "thursday", "alhamisi" -> DayOfWeek.THURSDAY
        "friday", "ijumaa" -> DayOfWeek.FRIDAY
        "saturday", "jumamosi" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
}
