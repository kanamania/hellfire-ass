package com.hellfire.ass.ui.money

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.finance.CategoryBudget
import com.hellfire.ass.reports.spend
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/** Money › Budgets (not drawn): a monthly limit per category, used by the Budget widget. Limits never change the ledger. */
@Composable
fun BudgetsPage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val month = remember { YearMonth.now(TaskTime.zone) }
    val dao = app.database.widgetData()
    val budgets by remember { dao.budgets(month.toString()) }.collectAsStateWithLifecycle(emptyList())
    var spent by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val entries by remember { app.database.finances().entries() }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(entries) { spent = withContext(Dispatchers.IO) {
        val range = com.hellfire.ass.reports.DateRange.month(Fmt.today())
        com.hellfire.ass.reports.posted(app.database.finances().allEntries(), app.database.finances().allAccounts()).filter { it.entry.occurredAt in range }
            .groupBy { it.entry.category }.mapValues { (_, v) -> v.sumOf(::spend) }
    } }
    var message by remember { mutableStateOf<String?>(null) }
    PushedPage("Budgets", breadcrumb = "Money · ${month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)}") {
        message?.let { Banner(it) }
        Note("Set a monthly limit for any category. Spending counts confirmed payments only. Pick the category the Budget widget shows in Settings › Home screen widgets.")
        HfCard {
            Categories.filter { it != "Income" }.forEachIndexed { i, category ->
                if (i > 0) ListDivider(16.dp)
                val current = budgets.firstOrNull { it.category == category }
                var text by remember(current) { mutableStateOf(current?.amount?.toString().orEmpty()) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(category, style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                        Text("Spent ${Fmt.amount(spent[category] ?: 0)}" + (current?.let { " of ${Fmt.amount(it.amount)}" } ?: ""), style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                    }
                    HfTextField(text, { text = it.filter(Char::isDigit) }, "Limit TZS", Modifier.width(132.dp), number = true)
                    QuietButton(com.hellfire.ass.ui.tr("Save"), { scope.launch {
                        val amount = text.toLongOrNull()
                        withContext(Dispatchers.IO) { if (amount == null || amount == 0L) dao.clearBudget(category, month.toString()) else dao.budget(CategoryBudget(category, month.toString(), amount)) }
                        message = if (amount == null || amount == 0L) "$category limit removed." else "$category limit saved."
                    } })
                }
            }
        }
        SecondaryButton("Copy last month's limits", { scope.launch {
            val copied = withContext(Dispatchers.IO) {
                val last = dao.budgetsFor(month.minusMonths(1).toString())
                last.forEach { dao.budget(it.copy(month = month.toString())) }; last.size
            }
            message = if (copied == 0) "No limits were set last month." else "Copied $copied limits."
        } }, Modifier.fillMaxWidth())
    }
}
