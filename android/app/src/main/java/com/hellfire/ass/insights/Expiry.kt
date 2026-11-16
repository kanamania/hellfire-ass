package com.hellfire.ass.insights

import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ExpiryHit(val label: String?, val date: LocalDate)

/** Finds expiry dates printed on scanned documents (passport, licence, insurance, permits). Rule-based; every hit is only a task proposal. */
object ExpiryFinder {
    private val keyword = Regex("(?i)\\b(?:date\\s+of\\s+expiry|expiry(?:\\s+date)?|expir(?:es|ation|ing)(?:\\s+(?:date|on))?|exp\\.?\\s*date|valid\\s+(?:until|till|thru|through|to|up\\s+to)|good\\s+thru|" +
        "inaisha|itaisha|kuisha|mwisho\\s+wa\\s+(?:matumizi|kutumika)|tarehe\\s+ya\\s+(?:mwisho|kuisha))\\b")
    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private val iso = Regex("\\b(20[0-9]{2})-([0-9]{2})-([0-9]{2})\\b")
    private val numeric = Regex("(?<![0-9])([0-9]{1,2})[/.-]([0-9]{1,2})[/.-]([0-9]{4}|[0-9]{2})(?![0-9])")
    // "12 MAR 2027", and the passport form "12 MAR /MARS 27".
    private val dayMonth = Regex("(?i)(?<![0-9])([0-9]{1,2})\\s*[ .-]\\s*($MONTHS)[a-z]*\\.?(?:\\s*/\\s*[a-zéû]+)?\\s*[ ,.-]\\s*([0-9]{4}|[0-9]{2})(?![0-9])")
    private val monthDay = Regex("(?i)\\b($MONTHS)[a-z]*\\.?\\s+([0-9]{1,2}),?\\s+([0-9]{4})\\b")
    private val monthYear = Regex("(?<![0-9/])(0[1-9]|1[0-2])/(20[0-9]{2})(?![0-9/])")
    private val labels = listOf(
        Regex("(?i)\\b(passport|pasipoti)\\b") to "Passport",
        Regex("(?i)\\b(driving\\s+licen[cs]e|driver'?s?\\s+licen[cs]e|leseni\\s+ya\\s+udereva)\\b") to "Driving licence",
        Regex("(?i)\\b(insurance|bima|cover\\s+note)\\b") to "Insurance",
        Regex("(?i)\\b(road\\s+licen[cs]e|motor\\s+vehicle|vehicle\\s+licen[cs]e|inspection)\\b") to "Vehicle licence",
        Regex("(?i)\\b(visa)\\b") to "Visa",
        Regex("(?i)\\b(residence\\s+permit|work\\s+permit|permit|kibali)\\b") to "Permit",
        Regex("(?i)\\b(national\\s+id|kitambulisho|nida)\\b") to "National ID",
        Regex("(?i)\\b(business\\s+licen[cs]e|leseni\\s+ya\\s+biashara)\\b") to "Business licence",
        Regex("(?i)\\b(warranty|guarantee|dhamana)\\b") to "Warranty",
        Regex("(?i)\\b(contract|agreement|lease|mkataba)\\b") to "Contract",
    )

    private fun year(text: String) = text.toInt().let { if (it < 100) 2000 + it else it }
    private fun month(name: String) = MONTHS.split('|').indexOf(name.lowercase(Locale.ROOT).take(3)) + 1

    /** The first date written within a short distance after an expiry word. Dates are read day-first, as printed in East Africa. */
    private fun dateIn(window: String): LocalDate? {
        val found = listOfNotNull(
            iso.find(window)?.let { it.range.first to runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull() },
            numeric.find(window)?.let { it.range.first to runCatching { LocalDate.of(year(it.groupValues[3]), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull() },
            dayMonth.find(window)?.let { it.range.first to runCatching { LocalDate.of(year(it.groupValues[3]), month(it.groupValues[2]), it.groupValues[1].toInt()) }.getOrNull() },
            monthDay.find(window)?.let { it.range.first to runCatching { LocalDate.of(it.groupValues[3].toInt(), month(it.groupValues[1]), it.groupValues[2].toInt()) }.getOrNull() },
            monthYear.find(window)?.let { it.range.first to runCatching { YearMonth.of(it.groupValues[2].toInt(), it.groupValues[1].toInt()).atEndOfMonth() }.getOrNull() },
        )
        return found.filter { it.second != null }.minByOrNull { it.first }?.second
    }

    fun find(text: String, today: LocalDate): List<ExpiryHit> {
        val label = labels.firstOrNull { it.first.containsMatchIn(text) }?.second
        return keyword.findAll(text).mapNotNull { hit ->
            val window = text.substring(hit.range.last + 1, minOf(text.length, hit.range.last + 61))
            val date = dateIn(window) ?: return@mapNotNull null
            if (date < today || date > today.plusYears(20)) return@mapNotNull null
            ExpiryHit(label, date)
        }.distinctBy { it.date }.take(3).toList()
    }

    /** First reminder: 30 days before expiry, or 7 days before, or the day itself when it is closer than that. */
    fun remindOn(expiry: LocalDate, today: LocalDate): LocalDate = listOf(expiry.minusDays(30), expiry.minusDays(7), expiry).first { it > today || it == expiry }

    private val shown = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /**
     * Proposes one "renew" task per expiry date found in a scanned page. [rawText] is the recognised text before
     * redaction, because redaction removes four-digit years; only the date and document type are kept from it.
     * The proposals wait in Tasks until the owner adds them.
     */
    suspend fun collect(db: EventDatabase, sourceId: String, mediaId: String, name: String, rawText: String, now: Long): Int {
        val today = java.time.Instant.ofEpochMilli(now).atZone(TaskTime.zone).toLocalDate()
        var added = 0
        for (hit in find(rawText, today)) {
            val due = remindOn(hit.date, today).atTime(9, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
            val title = "Renew ${hit.label ?: name.substringBeforeLast('.').take(60)} · expires ${shown.format(hit.date)}"
            if (db.tasks().insert(AssistantTask(NotificationNormalizer.sha256("expiry:$mediaId:${hit.date}"), sourceId, title, due, party = "OWNER",
                    evidence = "Expiry date ${shown.format(hit.date)} read from the scanned document \"${name.take(80)}\"", createdAt = now, updatedAt = now)) != -1L) added++
        }
        return added
    }
}
