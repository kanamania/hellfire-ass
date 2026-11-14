package com.hellfire.ass

import com.hellfire.ass.finance.FinanceAccount
import com.hellfire.ass.finance.LedgerEntry
import com.hellfire.ass.reports.*
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReportDataTest {
    private val oct = DateRange.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    private fun at(day: Int, hour: Int = 12) = LocalDate.of(2026, 10, day).atTime(hour, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
    private fun e(id: String, account: String, kind: String, amount: Long, day: Int, category: String = "Food", reverses: String? = null) =
        LedgerEntry(id, account, "k-$id", kind, amount, category, "desc $id", at(day), at(day), id, reverses)
    private val accounts = listOf(FinanceAccount("m", "M-Pesa", "MPESA"), FinanceAccount("c", "Cash", "CASH"))

    @Test fun `totals separate income, expenses, fees and transfers, and reversals count under the original kind`() {
        val entries = listOf(
            e("i", "m", "INCOME", 150_000, 2),
            e("x", "m", "EXPENSE", -20_000, 3),
            e("f", "m", "FEE", -300, 3, "Fees"),
            e("t1", "m", "OWN_TRANSFER", -50_000, 4), e("t2", "c", "OWN_TRANSFER", 50_000, 4),
            e("r", "m", "REFUND", 5_000, 5),
            e("x2", "c", "EXPENSE", -7_000, 6), e("rev", "c", "ADJUSTMENT", 7_000, 7, reverses = "x2"),
        )
        val t = moneyTotals(posted(entries, accounts))
        assertEquals(150_000, t.income)
        assertEquals(15_000, t.expenses) // 20,000 + 7,000 − 5,000 refund − 7,000 reversal
        assertEquals(300, t.fees)
        assertEquals(50_000, t.transfers)
        assertEquals(150_000 - 15_000 - 300, t.net)
    }

    @Test fun `expense groups give shares and change against the previous period`() {
        val current = posted(listOf(e("a", "m", "EXPENSE", -30_000, 2), e("b", "m", "EXPENSE", -10_000, 3, "Transport")), accounts)
        val previous = posted(listOf(e("p", "m", "EXPENSE", -20_000, 2, "Food").copy(occurredAt = at(2) - 31L * 86_400_000)), accounts)
        val rows = expenseGroups(current, previous, MoneyGroup.Category, oct)
        assertEquals(listOf("Food", "Transport"), rows.map { it.key })
        assertEquals(75, rows[0].share); assertEquals(25, rows[1].share)
        assertEquals(50, rows[0].change)
        assertNull(rows[1].change)
    }

    @Test fun `account figures reconcile and the running balance ends at the closing figure`() {
        val entries = listOf(
            e("open", "m", "INCOME", 100_000, 1).copy(occurredAt = oct.start - 1000),
            e("i", "m", "INCOME", 40_000, 5), e("x", "m", "EXPENSE", -12_000, 6), e("f", "m", "FEE", -300, 6, "Fees"),
        )
        val f = accountFigures(posted(entries, accounts), accounts, oct).first { it.account.id == "m" }
        assertEquals(100_000, f.opening); assertEquals(40_000, f.inflow); assertEquals(12_000, f.outflow); assertEquals(300, f.fees)
        assertEquals(f.opening + f.inflow - f.outflow - f.fees, f.closing)
        val running = runningBalance(entries.filter { it.occurredAt in oct }, f.opening)
        assertEquals(f.closing, running.last().second)
    }

    @Test fun `a calendar month splits into the design's four week blocks covering every day`() {
        val b = buckets(oct)
        assertEquals(listOf("1–7 Oct", "8–14 Oct", "15–21 Oct", "22–31 Oct"), b.map { it.label })
        assertEquals(oct.start, b.first().start); assertEquals(oct.end, b.last().end)
        assertEquals(7, buckets(DateRange.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))).size)
        assertEquals(4, buckets(DateRange.today(LocalDate.of(2026, 10, 3))).size)
    }

    @Test fun `previous range has the same length and ends where this one starts`() {
        val p = oct.previous()
        assertEquals(oct.start, p.end); assertEquals(oct.end - oct.start, p.end - p.start)
    }

    @Test fun `task outcomes compare completion with the due time and keep unknown completions apart`() {
        fun task(state: String, due: Long) = AssistantTask("t", "s", "Pay", due, party = "OWNER", evidence = "Pay", state = state, createdAt = 0, updatedAt = 0)
        val now = at(20)
        assertEquals(TaskOutcome.OnTime, outcome(task("DONE", at(10)), at(9), now))
        assertEquals(TaskOutcome.Late, outcome(task("DONE", at(10)), at(12), now))
        assertEquals(TaskOutcome.DoneUnknown, outcome(task("DONE", at(10)), null, now))
        assertEquals(TaskOutcome.Overdue, outcome(task("ACTIVE", at(10)), null, now))
        assertEquals(TaskOutcome.Open, outcome(task("ACTIVE", at(25)), null, now))
        assertEquals(TaskOutcome.Waiting, outcome(task("REVIEW", at(25)), null, now))
        assertEquals("2 days late", lateness(at(10), at(12, 13)))
        assertEquals("4 h late", lateness(at(10, 9), at(10, 13)))
    }

    @Test fun `unread finance needs a finance label, finished processing and nothing created from it`() {
        assertTrue(unreadFinance("FINANCE_CANDIDATE", "DONE", hasCandidate = false, hasLedger = false))
        assertFalse(unreadFinance("FINANCE_CANDIDATE", "DONE", hasCandidate = true, hasLedger = false))
        assertFalse(unreadFinance("FINANCE_CANDIDATE", "PENDING", hasCandidate = false, hasLedger = false))
        assertFalse(unreadFinance("MESSAGE", "DONE", hasCandidate = false, hasLedger = false))
    }

    @Test fun `money guess reads Kiswahili direction words and the first amount only`() {
        assertEquals("INCOME" to 45_000L, guessMoney("Umepokea TSh 45,000 kutoka 0754 *** ***. Salio jipya ni TSh 120,000"))
        assertEquals("EXPENSE" to 8_000L, guessMoney("Umelipa Tsh 8,000 kwa LUKU"))
        assertNull(guessMoney("Habari, karibu dukani"))
        assertNull(guessMoney("Salio lako ni TSh 10,000"))
    }

    @Test fun `csv quotes separators and neutralises spreadsheet formulas`() {
        assertEquals("plain", Csv.cell("plain"))
        assertEquals("\"a,b\"", Csv.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""))
        assertEquals("'=SUM(A1)", Csv.cell("=SUM(A1)"))
        assertEquals("-7000", Csv.cell("-7000"))
        assertEquals("h1,h2\r\n1,2\r\n", Csv.write(listOf("h1", "h2"), listOf(listOf("1", "2"))))
    }

    @Test fun `record export can drop the source ID column`() {
        val spec = ExportSpec("T", "S", emptyList(), listOf("A"), emptyList(), null, listOf("Date", "Amount", "Source IDs"),
            listOf(listOf("d", "1", "src-1")), sourceColumn = 2)
        assertTrue(Csv.records(spec, includeIds = true).contains("src-1"))
        assertEquals("Date,Amount\r\nd,1\r\n", Csv.records(spec, includeIds = false))
    }

    @Test fun `durations and money format for tables`() {
        assertEquals("12 min", duration(12 * 60_000L))
        assertEquals("1 h 24 min", duration(84 * 60_000L))
        assertEquals("unknown", duration(null))
        assertEquals("6.84", usdMicro(6_840_000))
    }
}
