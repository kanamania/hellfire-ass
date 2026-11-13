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

object TaskExtractor {
    fun extract(text: String, sourceTime: Long, outgoing: Boolean): TaskCandidate? {
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
        val digital = Regex("\\b([01]?[0-9]|2[0-3]):([0-5][0-9])\\b").find(lower)
        val time = if (digital != null) LocalTime.of(digital.groupValues[1].toInt(), digital.groupValues[2].toInt())
            else if ("saa nne asubuhi" in lower) LocalTime.of(10, 0) else LocalTime.of(9, 0)
        val party = if (outgoing) "OWNER" else if (Regex("\\b(i will|nitak|nita[a-z])").containsMatchIn(lower)) "OTHER" else "UNKNOWN"
        return TaskCandidate(text.take(300), date.atTime(time).atZone(TaskTime.zone).toInstant().toEpochMilli(), party, text)
    }
}
