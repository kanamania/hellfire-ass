package com.hellfire.ass.assistant

import com.hellfire.ass.contacts.PersonContact
import com.hellfire.ass.insights.PersonAlias
import com.hellfire.ass.tasks.TaskTime
import java.time.Instant
import java.time.YearMonth
import java.util.Locale

/**
 * Narrows source search by who and when: "what did Asha and I agree about the invoice in September?" searches Asha's
 * saved numbers and addresses within September, instead of looking for the words "asha" and "september".
 */
object SearchScope {
    data class Month(val start: Long, val end: Long, val words: Set<String>, val label: String)
    data class Person(val word: String, val clause: String)

    private val months = mapOf("january" to 1, "januari" to 1, "february" to 2, "februari" to 2, "march" to 3, "machi" to 3,
        "april" to 4, "aprili" to 4, "mei" to 5, "june" to 6, "juni" to 6, "july" to 7, "julai" to 7, "august" to 8, "agosti" to 8,
        "september" to 9, "septemba" to 9, "october" to 10, "oktoba" to 10, "november" to 11, "novemba" to 11,
        "december" to 12, "desemba" to 12, "disemba" to 12)

    /** A named month, with an optional year; without one, the most recent such month up to now. "may" counts only as "in may" or "may 2026". */
    fun month(query: String, now: Instant): Month? {
        val lower = query.lowercase(Locale.ROOT)
        val match = Regex("\\b(${months.keys.joinToString("|")}|may)\\b(?:\\s+(20[0-9]{2}))?").findAll(lower).firstOrNull { m ->
            m.groupValues[1] != "may" || m.groupValues[2].isNotEmpty() || Regex("\\bin\\s+may\\b").containsMatchIn(lower)
        } ?: return null
        val number = months[match.groupValues[1]] ?: 5
        val today = YearMonth.from(now.atZone(TaskTime.zone))
        val year = match.groupValues[2].toIntOrNull() ?: if (number <= today.monthValue) today.year else today.year - 1
        val month = YearMonth.of(year, number)
        return Month(month.atDay(1).atStartOfDay(TaskTime.zone).toInstant().toEpochMilli(),
            month.plusMonths(1).atDay(1).atStartOfDay(TaskTime.zone).toInstant().toEpochMilli(),
            setOfNotNull(match.groupValues[1], match.groupValues[2].ifEmpty { null }), month.toString())
    }

    /** Full-text terms that find a handle as captured: a Tanzanian number as 255…, 0… or the bare nine digits; an email address as its words. */
    fun handleTerms(key: String): List<String> = when {
        key.startsWith("tel:") -> key.removePrefix("tel:").filter(Char::isDigit).let { d ->
            if (d.length == 9) listOf("255$d", "0$d", d) else listOf(d) }
        key.startsWith("mail:") -> listOf("\"" + Regex("[\\p{L}\\p{N}]+").findAll(key.removePrefix("mail:")).joinToString(" ") { it.value } + "\"")
        key.startsWith("wa:") -> listOf(key.removePrefix("wa:").filter(Char::isLetterOrDigit))
        else -> emptyList()
    }.filter { it.length >= 3 }

    /** The first query word that names a saved person, as an FTS OR-clause of the name and all their handles. */
    fun person(words: List<String>, aliases: List<PersonAlias>, contacts: List<PersonContact>): Person? {
        fun names(name: String) = Regex("[\\p{L}\\p{N}]+").findAll(name.lowercase(Locale.ROOT)).map { it.value }.toSet()
        for (word in words.filter { it.length >= 3 }) {
            val keys = contacts.filter { word in names(it.displayName) }.map { it.personKey }.toSet()
            val handles = aliases.filter { word in names(it.name) || (it.personKey != null && it.personKey in keys) }.map { it.handle }
            val terms = handles.flatMap(::handleTerms)
            if (terms.isNotEmpty()) return Person(word, (listOf(word) + terms).distinct().joinToString(" OR "))
        }
        return null
    }
}
