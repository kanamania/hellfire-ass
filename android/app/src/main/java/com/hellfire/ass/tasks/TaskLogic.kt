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
        // Swahili hours count from 06:00 (saa moja = 07:00) and from 18:00 at night.
        val western = when (match.groupValues[3]) {
            "usiku", "alfajiri" -> if (hour <= 5) hour + 18 else hour - 6
            "asubuhi" -> if (hour >= 11) hour - 6 else hour + 6
            else -> hour + 6
        }
        return LocalTime.of(western % 24, minute)
    }
}

object TaskExtractor {
    private val commitment = Regex("\\b(will|'ll|shall|going to|remind|meeting|meet|appointment|call|pay|send|deliver|submit|bring|visit|" +
        "finish|return|confirm|due|deadline|invoice|please|need to|must|have to|interview|class|flight|" +
        "(?:ni|u|a|tu|m|wa)ta[a-z]+|tukutane|nikutane|tuonane|kikao|mkutano|miadi|ahadi|lipa|kulipa|tuma|kutuma|leta|njoo|nipigie|" +
        "tafadhali|kumbuka|nikumbushe)\\b")
    /** [requireCommitment] is off for owner-typed text (commands, quick add), which is a request by definition. */
    fun extract(text: String, sourceTime: Long, outgoing: Boolean, requireCommitment: Boolean = true): TaskCandidate? {
        val source = Instant.ofEpochMilli(sourceTime).atZone(TaskTime.zone)
        val lower = text.lowercase(Locale.ROOT)
        // Whole words only: "Leonard" is not "leo", and "keshokutwa" is two days ahead, not "kesho".
        val date = when {
            Regex("\\b(day after tomorrow|keshokutwa|kesho kutwa)\\b").containsMatchIn(lower) -> source.toLocalDate().plusDays(2)
            Regex("\\b(tomorrow|kesho)\\b").containsMatchIn(lower) -> source.toLocalDate().plusDays(1)
            Regex("\\b(today|leo)\\b").containsMatchIn(lower) -> source.toLocalDate()
            else -> {
                val explicit = Regex("\\b(20[0-9]{2}-[0-9]{2}-[0-9]{2})\\b").find(text)?.value
                if (explicit != null) runCatching { LocalDate.parse(explicit) }.getOrNull()
                else listOf("monday" to DayOfWeek.MONDAY, "jumatatu" to DayOfWeek.MONDAY,
                    "tuesday" to DayOfWeek.TUESDAY, "jumanne" to DayOfWeek.TUESDAY,
                    "wednesday" to DayOfWeek.WEDNESDAY, "jumatano" to DayOfWeek.WEDNESDAY,
                    "thursday" to DayOfWeek.THURSDAY, "alhamisi" to DayOfWeek.THURSDAY,
                    "friday" to DayOfWeek.FRIDAY, "ijumaa" to DayOfWeek.FRIDAY,
                    "saturday" to DayOfWeek.SATURDAY, "jumamosi" to DayOfWeek.SATURDAY,
                    "sunday" to DayOfWeek.SUNDAY, "jumapili" to DayOfWeek.SUNDAY)
                    .firstOrNull { Regex("\\b${it.first}\\b").containsMatchIn(lower) }?.let {
                        source.toLocalDate().with(TemporalAdjusters.nextOrSame(it.second))
                    }
            }
        } ?: return null
        // A date alone ("habari ya leo") is not a commitment; some promise, request or appointment wording is required.
        if (requireCommitment && !commitment.containsMatchIn(lower)) return null
        val digital = Regex("\\b([01]?[0-9]|2[0-3]):([0-5][0-9])\\b").find(lower)
        val time = if (digital != null) LocalTime.of(digital.groupValues[1].toInt(), digital.groupValues[2].toInt())
            else SwahiliTime.find(lower) ?: LocalTime.of(9, 0)
        val party = if (outgoing) "OWNER" else if (Regex("\\b(i will|nitak|nita[a-z])").containsMatchIn(lower)) "OTHER" else "UNKNOWN"
        return TaskCandidate(text.take(300), date.atTime(time).atZone(TaskTime.zone).toInstant().toEpochMilli(), party, text)
    }
}
