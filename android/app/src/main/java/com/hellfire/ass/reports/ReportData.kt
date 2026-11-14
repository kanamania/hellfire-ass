package com.hellfire.ass.reports

import com.hellfire.ass.finance.FinanceAccount
import com.hellfire.ass.finance.LedgerEntry
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Half-open time range [start, end) in epoch millis, East Africa Time. */
data class DateRange(val start: Long, val end: Long, val label: String) {
    val days: Long get() = ChronoUnit.DAYS.between(day(start), day(end - 1)) + 1
    /** The range of equal length just before this one, for "vs previous" figures. */
    fun previous(): DateRange = DateRange(start - (end - start), start, "previous")
    operator fun contains(t: Long) = t >= start && t < end

    companion object {
        private fun day(t: Long) = Instant.ofEpochMilli(t).atZone(TaskTime.zone).toLocalDate()
        private fun at(d: LocalDate) = d.atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()
        private val dm = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        private val dmy = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        fun of(first: LocalDate, last: LocalDate): DateRange {
            val label = when {
                first == last -> DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH).format(first)
                first.withDayOfMonth(1) == first && last == first.plusMonths(1).minusDays(1) -> DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH).format(first)
                first.year == last.year && first.month == last.month -> "${first.dayOfMonth}–${dmy.format(last)}"
                else -> "${dm.format(first)} – ${dmy.format(last)}"
            }
            return DateRange(at(first), at(last.plusDays(1)), label)
        }
        fun today(now: LocalDate) = of(now, now)
        fun week(now: LocalDate) = of(now.minusDays(6), now)
        fun month(now: LocalDate) = of(now.withDayOfMonth(1), now.withDayOfMonth(now.lengthOfMonth()))
    }
}

data class Bucket(val label: String, val start: Long, val end: Long)

/** Chart buckets: 4 × 6 h for one day, days up to two weeks, the design's 4 week-blocks for a calendar month, else 7-day blocks (max 12). */
fun buckets(r: DateRange): List<Bucket> {
    val first = Instant.ofEpochMilli(r.start).atZone(TaskTime.zone).toLocalDate()
    val last = Instant.ofEpochMilli(r.end - 1).atZone(TaskTime.zone).toLocalDate()
    fun at(d: LocalDate) = d.atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()
    val dm = DateTimeFormatter.ofPattern("d", Locale.ENGLISH)
    val mon = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
    return when {
        r.days <= 1 -> (0 until 4).map { i -> Bucket("${"%02d".format(i * 6)}–${"%02d".format(i * 6 + 6)}", r.start + i * 6 * 3_600_000L, r.start + (i + 1) * 6 * 3_600_000L) }
        r.days <= 14 -> (0 until r.days.toInt()).map { i -> val d = first.plusDays(i.toLong())
            Bucket(if (r.days <= 7) DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).format(d) else dm.format(d), at(d), at(d.plusDays(1))) }
        first.dayOfMonth == 1 && last == first.plusMonths(1).minusDays(1) -> listOf(0, 7, 14, 21).mapIndexed { i, offset ->
            val a = first.plusDays(offset.toLong()); val b = if (i == 3) last else first.plusDays(offset + 6L)
            Bucket("${dm.format(a)}–${dm.format(b)} ${mon.format(a)}", at(a), at(b.plusDays(1)))
        }
        else -> {
            val size = maxOf(7L, (r.days + 11) / 12)
            generateSequence(first) { it.plusDays(size) }.takeWhile { !it.isAfter(last) }.map { a ->
                val b = minOf(a.plusDays(size - 1), last)
                Bucket("${dm.format(a)} ${mon.format(a)}", at(a), at(b.plusDays(1)))
            }.toList()
        }
    }
}

// ---------------- Money ----------------

/** Ledger entry with the effective kind: a reversal counts under the kind of the entry it reverses (same rule as FinanceRepository.summary). */
data class Posted(val entry: LedgerEntry, val kind: String, val provider: String, val accountLabel: String)

fun posted(entries: List<LedgerEntry>, accounts: List<FinanceAccount>): List<Posted> {
    val byId = entries.associateBy { it.id }
    val acc = accounts.associateBy { it.id }
    return entries.map { e ->
        val a = acc[e.accountId]
        Posted(e, e.reversesId?.let { byId[it]?.kind } ?: e.kind, a?.provider ?: "", a?.label ?: "Account")
    }
}

data class MoneyFilter(val types: Set<String> = emptySet(), val accounts: Set<String> = emptySet(), val categories: Set<String> = emptySet(),
    val min: Long? = null, val max: Long? = null) {
    val active get() = types.isNotEmpty() || accounts.isNotEmpty() || categories.isNotEmpty() || min != null || max != null
    fun accepts(p: Posted): Boolean {
        val type = when (p.kind) { "EXPENSE" -> "Expense"; "INCOME" -> "Income"; "OWN_TRANSFER" -> "Transfer"; "FEE" -> "Fee"; "REFUND" -> "Refund"; else -> "Other" }
        val size = kotlin.math.abs(p.entry.amount)
        return (types.isEmpty() || type in types) && (accounts.isEmpty() || p.entry.accountId in accounts) &&
            (categories.isEmpty() || p.entry.category in categories) && (min == null || size >= min) && (max == null || size <= max)
    }
}

data class MoneyTotals(val income: Long, val expenses: Long, val fees: Long, val transfers: Long, val count: Int) {
    val net get() = income - expenses - fees
}

/** Income, expenses (net of refunds, without fees), fees and own transfers. Only confirmed ledger entries are ever passed in. */
fun moneyTotals(items: List<Posted>): MoneyTotals {
    var income = 0L; var expenses = 0L; var fees = 0L; var transfers = 0L
    for (p in items) when (p.kind) {
        "INCOME" -> income = Math.addExact(income, p.entry.amount)
        "EXPENSE", "REFUND" -> expenses = Math.subtractExact(expenses, p.entry.amount)
        "FEE" -> fees = Math.subtractExact(fees, p.entry.amount)
        "OWN_TRANSFER" -> if (p.entry.amount > 0) transfers = Math.addExact(transfers, p.entry.amount)
    }
    return MoneyTotals(income, expenses, fees, transfers, items.count { it.entry.reversesId == null && it.kind in setOf("EXPENSE", "INCOME", "REFUND") })
}

/** Expense flow of one entry: positive for spending, negative for refunds and reversals of spending. */
fun spend(p: Posted): Long = if (p.kind == "EXPENSE" || p.kind == "REFUND") -p.entry.amount else 0

enum class MoneyGroup(val label: String) { Category("Category"), Account("Account"), Provider("Provider"), Week("Week"), Day("Day") }

data class GroupRow(val key: String, val count: Int, val amount: Long, val share: Int, val change: Int?)

/** Expenses grouped for the Money table, with share of the total and % change against the previous period. */
fun expenseGroups(current: List<Posted>, previous: List<Posted>, group: MoneyGroup, range: DateRange): List<GroupRow> {
    val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    val weeks = buckets(range)
    fun keyOf(p: Posted, r: DateRange): String = when (group) {
        MoneyGroup.Category -> p.entry.category
        MoneyGroup.Account -> p.accountLabel
        MoneyGroup.Provider -> p.provider
        MoneyGroup.Day -> dayFmt.format(Instant.ofEpochMilli(p.entry.occurredAt).atZone(TaskTime.zone))
        MoneyGroup.Week -> (if (r === range) weeks else buckets(r)).firstOrNull { p.entry.occurredAt >= it.start && p.entry.occurredAt < it.end }?.label ?: "Other"
    }
    val spendCurrent = current.filter { spend(it) != 0L }
    val total = spendCurrent.sumOf { spend(it) }
    val prev = if (group == MoneyGroup.Category || group == MoneyGroup.Account || group == MoneyGroup.Provider)
        previous.filter { spend(it) != 0L }.groupBy { keyOf(it, range.previous()) }.mapValues { (_, v) -> v.sumOf(::spend) } else emptyMap()
    return spendCurrent.groupBy { keyOf(it, range) }.map { (k, v) ->
        val amount = v.sumOf(::spend)
        GroupRow(k, v.count { it.entry.reversesId == null }, amount, if (total > 0) Math.round(amount * 100.0 / total).toInt() else 0,
            prev[k]?.takeIf { it > 0 }?.let { Math.round((amount - it) * 100.0 / it).toInt() })
    }.sortedByDescending { it.amount }
}

fun percentChange(now: Long, before: Long): Int? = if (before > 0) Math.round((now - before) * 100.0 / before).toInt() else null

/** Per-account statement figures for a range: opening + in − out − fees = closing. */
data class AccountFigures(val account: FinanceAccount, val opening: Long, val inflow: Long, val outflow: Long, val fees: Long, val closing: Long)

fun accountFigures(all: List<Posted>, accounts: List<FinanceAccount>, range: DateRange): List<AccountFigures> = accounts.map { a ->
    val mine = all.filter { it.entry.accountId == a.id }
    val opening = mine.filter { it.entry.occurredAt < range.start }.sumOf { it.entry.amount }
    val inRange = mine.filter { it.entry.occurredAt in range }
    val fees = -inRange.filter { it.kind == "FEE" }.sumOf { it.entry.amount }
    val inflow = inRange.filter { it.kind != "FEE" && it.entry.amount > 0 }.sumOf { it.entry.amount }
    val outflow = -inRange.filter { it.kind != "FEE" && it.entry.amount < 0 }.sumOf { it.entry.amount }
    AccountFigures(a, opening, inflow, outflow, fees, opening + inflow - outflow - fees)
}

/** Running balance after each entry, oldest first. */
fun runningBalance(entries: List<LedgerEntry>, opening: Long): List<Pair<LedgerEntry, Long>> {
    var balance = opening
    return entries.sortedWith(compareBy({ it.occurredAt }, { it.createdAt })).map { e -> balance = Math.addExact(balance, e.amount); e to balance }
}

/** End-of-bucket balance for a sparkline. */
fun balanceSeries(entries: List<LedgerEntry>, buckets: List<Bucket>): List<Long> =
    buckets.map { b -> entries.filter { it.occurredAt < b.end }.sumOf { it.amount } }

// ---------------- Tasks ----------------

enum class TaskOutcome(val label: String) { OnTime("On time"), Late("Done late"), DoneUnknown("Done, time unknown"), Overdue("Still overdue"), Open("Not due yet"), Waiting("Waiting for you"), Rejected("Not a task") }

/** Outcome against the due time. [completedAt] is null for tasks finished before completion times were stored. */
fun outcome(t: AssistantTask, completedAt: Long?, now: Long): TaskOutcome = when (t.state) {
    "DONE" -> when { completedAt == null -> TaskOutcome.DoneUnknown; completedAt <= t.dueAt -> TaskOutcome.OnTime; else -> TaskOutcome.Late }
    "ACTIVE" -> if (t.dueAt < now) TaskOutcome.Overdue else TaskOutcome.Open
    "REVIEW" -> TaskOutcome.Waiting
    else -> TaskOutcome.Rejected
}

/** "2 days late", "4 h late", "35 min late". */
fun lateness(due: Long, completed: Long): String {
    val minutes = (completed - due) / 60_000
    return when { minutes >= 48 * 60 -> "${minutes / (24 * 60)} days late"; minutes >= 24 * 60 -> "1 day late"; minutes >= 60 -> "${minutes / 60} h late"; else -> "$minutes min late" }
}

// ---------------- Capture ----------------

/** A financial-looking message that produced no payment candidate and no ledger entry: the reader did not understand it. */
fun unreadFinance(label: String?, state: String, hasCandidate: Boolean, hasLedger: Boolean) =
    state == "DONE" && label == "FINANCE_CANDIDATE" && !hasCandidate && !hasLedger

/** Lenient guess for an unread money message: direction words plus the first TSh amount. Shown as a guess; the owner decides. */
fun guessMoney(text: String): Pair<String, Long>? {
    val amount = Regex("(?i)\\b(?:TZS|TShs?|Tsh)\\s*([0-9][0-9,]*)(?:\\.[0-9]{1,2})?").find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val kind = when {
        Regex("(?i)\\b(received|umepokea|imeingia|credited|kutoka)\\b").containsMatchIn(text) -> "INCOME"
        Regex("(?i)\\b(paid|sent|umelipa|umetuma|debited|kwenda)\\b").containsMatchIn(text) -> "EXPENSE"
        else -> return null
    }
    return kind to amount
}

// ---------------- Media and AI ----------------

/** "12 min", "1 h 24 min", or "unknown". */
fun duration(ms: Long?): String {
    if (ms == null || ms <= 0) return "unknown"
    val minutes = (ms + 30_000) / 60_000
    return when { minutes < 1 -> "<1 min"; minutes < 60 -> "$minutes min"; minutes % 60 == 0L -> "${minutes / 60} h"; else -> "${minutes / 60} h ${minutes % 60} min" }
}

fun hours(ms: Long): String = String.format(Locale.ENGLISH, "%.1f h", ms / 3_600_000.0)

fun usdMicro(micro: Long): String = java.math.BigDecimal.valueOf(micro, 6).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
