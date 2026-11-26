package com.hellfire.ass.assistant

import com.hellfire.ass.finance.*
import java.time.Instant
import java.time.YearMonth
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.contacts.PersonContact
import com.hellfire.ass.ui.UiLanguage

object FinanceComparisons {
    private fun label(en: String, sw: String) = if (UiLanguage.code == "sw") sw else en
    fun requested(text: String) = Regex("(?i)\\b(compare|versus|vs|higher|lower|increased|decreased|linganisha|kuliko)\\b").containsMatchIn(text)
    fun questions(text: String, now: Instant, accounts: List<FinanceAccount>, categories: List<String>, people: List<PersonContact>): Pair<FinanceQuestion, FinanceQuestion> {
        val cleaned = text.lowercase().replace(Regex("\\b(compare|why|was|is|higher|lower|increased|decreased|linganisha|kwa nini)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val parts = cleaned.split(Regex("\\s+(?:vs\\.?|versus|with|against|compared to|dhidi ya|kuliko)\\s+"), limit = 2)
        val current = FinanceQuestion.parse(parts[0], now, accounts, categories, people = people)
        val previous = if (parts.size == 2) FinanceQuestion.parse("and ${parts[1]}", now, accounts, categories, current, people) else {
            val first = Instant.ofEpochMilli(current.start).atZone(TaskTime.zone)
            val last = Instant.ofEpochMilli(current.end).atZone(TaskTime.zone)
            val monthly = first.dayOfMonth == 1 && last.dayOfMonth == 1 && java.time.temporal.ChronoUnit.MONTHS.between(first, last) == 1L
            if (monthly) current.copy(start = first.minusMonths(1).toInstant().toEpochMilli(), end = current.start)
            else current.copy(start = Math.subtractExact(current.start, current.end - current.start), end = current.start)
        }
        require(previous.start >= 0 && current.income == previous.income && current.category == previous.category && current.accountId == previous.accountId && current.personKey == previous.personKey && current.merchant == previous.merchant) { "Compare the same metric and filters across two periods." }
        return current to previous
    }
    suspend fun report(repository: FinanceRepository, current: FinanceQuestion, previous: FinanceQuestion): Pair<String, List<String>> {
        suspend fun total(q: FinanceQuestion, category: String? = q.category) = repository.summary(q.start, q.end, category, q.accountId, q.merchant, q.personKey)
        val a = total(current); val b = total(previous)
        val av = if (current.income) a.income else a.expenses; val bv = if (current.income) b.income else b.expenses
        val delta = Math.subtractExact(av, bv)
        val lines = mutableListOf("${current.scope}", "${label("Selected", "Kipindi kilichochaguliwa")}: ${TaskTime.display(current.start)} — ${TaskTime.display(current.end)} (end exclusive): ${Money.display(av)}", "${label("Compared", "Kipindi cha kulinganisha")}: ${TaskTime.display(previous.start)} — ${TaskTime.display(previous.end)} (end exclusive): ${Money.display(bv)}", "${label("Difference", "Tofauti")}: ${Money.display(delta)}")
        if (!current.income && current.category == null) {
            val categories = (repository.selected(current.start, current.end, accountId = current.accountId, merchant = current.merchant, personKey = current.personKey) + repository.selected(previous.start, previous.end, accountId = previous.accountId, merchant = previous.merchant, personKey = previous.personKey)).map { it.category }.distinct()
            val changes = categories.map { it to Math.subtractExact(total(current, it).expenses, total(previous, it).expenses) }.sortedByDescending { kotlin.math.abs(it.second) }.take(6)
            lines += label("Largest category changes:", "Mabadiliko makubwa kwa kundi:"); lines += changes.map { "${it.first}: ${Money.display(it.second)}" }
        }
        lines += label("These are arithmetic contributors, not proof of why spending changed. Only confirmed entries count; transfers are excluded. Current/future periods may be incomplete.", "Hizi ni tofauti za hesabu, si ushahidi wa sababu ya mabadiliko. Malipo yaliyothibitishwa pekee yanahesabiwa; uhamisho haujumuishwi. Vipindi vya sasa au vijavyo vinaweza kutokamilika.")
        return lines.joinToString("\n") to (a.sourceIds + b.sourceIds).distinct()
    }
}
