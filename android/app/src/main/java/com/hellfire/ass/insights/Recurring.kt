package com.hellfire.ass.insights

import com.hellfire.ass.finance.LedgerEntry
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import java.time.Instant
import java.time.YearMonth
import java.util.Locale

/** A payment seen about once a month for at least three months in a row. A suggestion; nothing is scheduled from it automatically. */
data class RecurringPayment(val label: String, val category: String, val amount: Long, val months: Int, val lastAt: Long, val nextAt: Long, val varies: Boolean)

/** Bills due in the coming days against the money on hand. [shortfall] is 0 when the balance covers them. */
data class BillForecast(val due: List<AssistantTask>, val total: Long, val unpriced: Int, val balance: Long, val shortfall: Long)

object Recurring {
    private val generic = setOf("expense", "income", "transfer", "refund", "fee", "payment", "")

    /** Rule-based: same description, one or two payments a month, in each of the last three calendar months it appeared, and still current. */
    /** [kind] EXPENSE finds bills; INCOME finds money that arrives monthly, such as a salary. Amounts are positive either way. */
    fun detect(entries: List<LedgerEntry>, now: Long, kind: String = "EXPENSE"): List<RecurringPayment> {
        val reversed = entries.mapNotNull { it.reversesId }.toSet()
        val current = YearMonth.from(Instant.ofEpochMilli(now).atZone(TaskTime.zone))
        return entries.filter { it.kind == kind && it.reversesId == null && it.id !in reversed && it.occurredAt <= now }
            .groupBy { it.description.lineSequence().first().trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .filterKeys { it !in generic }
            .mapNotNull { (_, list) ->
                val sorted = list.sortedBy { it.occurredAt }
                val byMonth = sorted.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(TaskTime.zone)) }
                val months = byMonth.keys.sorted()
                if (months.size < 3) return@mapNotNull null
                // Count the unbroken run of months ending at the latest one.
                var run = 1
                while (run < months.size && months[months.size - run - 1] == months[months.size - run].minusMonths(1)) run++
                val recent = months.takeLast(run)
                if (run < 3 || recent.last() < current.minusMonths(1)) return@mapNotNull null
                val inRun = recent.flatMap { byMonth.getValue(it) }
                if (inRun.size > run * 3 / 2) return@mapNotNull null
                val amounts = inRun.map { Math.absExact(it.amount) }.sorted()
                val last = sorted.last()
                RecurringPayment(last.description.lineSequence().first().trim().take(80), last.category, amounts[amounts.size / 2], run, last.occurredAt,
                    Instant.ofEpochMilli(last.occurredAt).atZone(TaskTime.zone).plusMonths(1).toInstant().toEpochMilli(),
                    amounts.last() - amounts.first() > amounts.last() / 10)
            }.sortedBy { it.nextAt }
    }

    /** Recurring payments the owner has not already turned into a bill. */
    fun untracked(found: List<RecurringPayment>, tasks: List<AssistantTask>): List<RecurringPayment> {
        val bills = tasks.filter { it.kind == "BILL" && it.state in setOf("ACTIVE", "REVIEW") }.map { it.title.trim().lowercase(Locale.ROOT) }
        return found.filter { r -> val label = r.label.lowercase(Locale.ROOT); bills.none { it == label || it.contains(label) || label.contains(it) } }
    }

    /** Money on hand plus recurring income expected in the window, minus the bills due in it. */
    fun cashFlowEnd(forecast: BillForecast, income: List<RecurringPayment>, now: Long, days: Int = 30): Long =
        income.filter { it.nextAt in now until now + days * 86_400_000L }
            .fold(forecast.balance) { sum, i -> Math.addExact(sum, i.amount) }.let { Math.subtractExact(it, forecast.total) }

    fun forecast(tasks: List<AssistantTask>, balance: Long, now: Long, days: Int = 30): BillForecast {
        val due = tasks.filter { it.kind == "BILL" && it.state == "ACTIVE" && it.dueAt < now + days * 86_400_000L }.sortedBy { it.dueAt }
        val total = due.fold(0L) { sum, t -> Math.addExact(sum, t.amount ?: 0) }
        return BillForecast(due, total, due.count { it.amount == null }, balance, (total - balance).coerceAtLeast(0))
    }
}
