package com.hellfire.ass

import com.hellfire.ass.finance.LedgerEntry
import com.hellfire.ass.insights.*
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import org.junit.Assert.*
import org.junit.Test

class CashFlowTest {
    private val now = TaskTime.parse("2026-11-20 07:00")
    private fun salary(day: String) = LedgerEntry("s$day", "dtb", "s$day", "INCOME", 2_450_000, "Salary", "SALARY", TaskTime.parse("$day 08:00"), 1, "s$day")
    private fun bill(id: String, amount: Long, due: String) = AssistantTask(id, "src", id, TaskTime.parse(due), party = "OWNER", evidence = "Synthetic",
        state = "ACTIVE", createdAt = 1, updatedAt = 1, kind = "BILL", amount = amount)
    private fun brief(balance: Long, bills: List<AssistantTask>, income: List<RecurringPayment>) = Briefs.build(BriefInputs(false, now, bills, 0, 0, 0, 0,
        WaitingOn(emptyList(), emptyList()), emptyList(), Recurring.forecast(bills, balance, now), emptyList(), income = income))

    @Test fun `salary seen three months running counts as expected income`() {
        val income = Recurring.detect(listOf(salary("2026-08-25"), salary("2026-09-25"), salary("2026-10-25")), now, "INCOME")
        assertEquals(1, income.size); assertEquals(2_450_000, income.single().amount)
        assertEquals("2026-11-25 08:00", TaskTime.display(income.single().nextAt))
        assertTrue(Recurring.detect(listOf(salary("2026-08-25"), salary("2026-09-25"), salary("2026-10-25")), now).isEmpty()) // not an expense
    }
    @Test fun `brief counts expected income before warning about bills`() {
        val income = Recurring.detect(listOf(salary("2026-08-25"), salary("2026-09-25"), salary("2026-10-25")), now, "INCOME")
        val bills = listOf(bill("Rent", 900_000, "2026-12-01 09:00"))
        // Balance alone would be short, but the salary arrives first: an informational line, not a warning.
        val covered = brief(300_000, bills, income).sections.first { it.title == "Bills" }.lines.last()
        assertFalse(covered.warn)
        assertEquals("Next 30 days: TZS 300,000 + expected income TZS 2,450,000 − bills TZS 900,000 = TZS 1,850,000", covered.text)
        val short = brief(300_000, bills, emptyList()).sections.first { it.title == "Bills" }.lines.last()
        assertTrue(short.warn); assertEquals("balances leave you TZS 600,000 short", short.detail)
    }
}
