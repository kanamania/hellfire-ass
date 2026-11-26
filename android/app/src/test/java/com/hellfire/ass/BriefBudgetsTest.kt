package com.hellfire.ass

import com.hellfire.ass.insights.*
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.widgets.Pace
import org.junit.Assert.*
import org.junit.Test

class BriefBudgetsTest {
    private fun inputs(budgets: List<BudgetStatus>, evening: Boolean = false) = BriefInputs(evening, TaskTime.parse("2026-11-20 07:00"), emptyList(), 0, 0, 0, 0,
        WaitingOn(emptyList(), emptyList()), emptyList(), BillForecast(emptyList(), 0, 0, 0, 0), emptyList(), budgets = budgets)

    @Test fun `morning brief warns only about budgets over or ahead of the month`() {
        val brief = Briefs.build(inputs(listOf(
            BudgetStatus("Fuel", 200_000, 230_000, Pace.Over),
            BudgetStatus("Food", 300_000, 280_000, Pace.Ahead),
            BudgetStatus("Rent", 500_000, 500_000, Pace.OnPace))))
        val lines = brief.sections.single { it.title == "Budgets" }.lines
        assertEquals(listOf("Fuel: TZS 230,000 of TZS 200,000", "Food: TZS 280,000 of TZS 300,000"), lines.map { it.text })
        assertEquals(listOf("over budget", "spending faster than the month"), lines.map { it.detail })
        assertTrue(Briefs.build(inputs(listOf(BudgetStatus("Rent", 500_000, 100_000, Pace.Under)))).sections.none { it.title == "Budgets" })
        assertTrue(Briefs.build(inputs(listOf(BudgetStatus("Fuel", 1, 2, Pace.Over)), evening = true)).sections.none { it.title == "Budgets" })
    }
}
