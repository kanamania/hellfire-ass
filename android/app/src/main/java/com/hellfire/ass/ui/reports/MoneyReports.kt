package com.hellfire.ass.ui.reports

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.finance.FinanceAccount
import com.hellfire.ass.finance.LedgerEntry
import com.hellfire.ass.reports.*
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.money.Categories
import com.hellfire.ass.ui.money.MerchantSheet
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ledger and accounts loaded once per screen; reports then compute from these in memory. */
@Composable
fun rememberLedger(): Pair<List<LedgerEntry>, List<FinanceAccount>>? {
    val app = LocalEnv.current.app
    val tick by remember { app.database.finances().entries() }.collectAsStateWithLifecycle(emptyList())
    var data by remember { mutableStateOf<Pair<List<LedgerEntry>, List<FinanceAccount>>?>(null) }
    LaunchedEffect(tick) { data = withContext(Dispatchers.IO) { app.database.finances().allEntries() to app.database.finances().allAccounts() } }
    return data
}

@Composable
fun ReportsHub() {
    val nav = LocalNav.current
    val app = LocalEnv.current.app
    val range = ReportRange.range
    val ledger = rememberLedger()
    var lines by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(range, ledger) {
        val (entries, accounts) = ledger ?: return@LaunchedEffect
        lines = withContext(Dispatchers.IO) {
            val all = posted(entries, accounts)
            val t = moneyTotals(all.filter { it.entry.occurredAt in range })
            val events = app.database.events().labelledPeriod(range.start, range.end)
            val tasks = app.database.tasks().all().filter { it.dueAt in range }
            val now = System.currentTimeMillis()
            val outcomes = tasks.groupingBy { outcome(it, it.completedAtOrNull(), now) }.eachCount()
            val messages = app.database.communications().messagesIn(range.start, range.end)
            val drafts = app.database.communications().allDrafts().filter { it.createdAt in range && it.state in SENT_STATES }
            val media = app.database.media().all().filter { (it.recordedAt ?: it.importedAt) in range }
            val ai = runCatching { app.ai.store.read() }.getOrNull()
            val usage = aiUsage(app, range)
            mapOf(
                "money" to "Net ${Fmt.signed(t.net)} · in ${Fmt.compact(t.income)} · out ${Fmt.compact(t.expenses)}",
                "accounts" to "Total balance ${Fmt.amount(entries.filter { it.occurredAt < range.end }.sumOf { it.amount })}",
                "capture" to "${Fmt.amount(events.size.toLong())} events · ${events.count { it.state == "FAILED" }} failed",
                "tasks" to "${outcomes[TaskOutcome.OnTime] ?: 0} on time · ${outcomes[TaskOutcome.Late] ?: 0} late · ${outcomes[TaskOutcome.Overdue] ?: 0} overdue",
                "messages" to "${messages.count { it.direction == "INCOMING" }} in · ${drafts.size} sent",
                "media" to "${media.size} items · ${hours(media.sumOf { it.lengthMs() ?: 0 })} · ${media.count { it.summaryState == "INFERENCE_REVIEW" }} to review",
                "ai" to "USD ${usdMicro(usage.sumOf { it.microUsd })} of ${usdMicro(ai?.monthlyCap ?: 0)} cap",
            )
        }
    }
    var pickDates by remember { mutableStateOf(false) }
    if (pickDates) RangeSheet(startCustom = true) { pickDates = false }
    PushedPage(stringResource(R.string.ui_reports)) {
        SegmentedControl(listOf("Today", "Week", "Month", "Custom"), ReportRange.preset, { p -> if (p == "Custom") pickDates = true else ReportRange.set(p) }, label = { if (it == "Today") stringResource(R.string.ui_today) else it })
        Note("${range.label} · East Africa Time")
        ListCard {
            listOf(Triple("Money", "money", Screen.ReportMoney) to (HfIcons.Money to Tone.Info), Triple("Accounts and merchants", "accounts", Screen.ReportAccounts) to (HfIcons.Bank to Tone.Neutral),
                Triple("Capture", "capture", Screen.ReportCapture) to (HfIcons.Inbox to Tone.Success), Triple("Tasks", "tasks", Screen.ReportTasks) to (HfIcons.Task to Tone.Primary),
                Triple("Messages", "messages", Screen.ReportMessages) to (HfIcons.Chat to Tone.Success), Triple("Media", "media", Screen.ReportMedia) to (HfIcons.Waveform to Tone.Neutral),
                Triple("AI usage", "ai", Screen.ReportAi) to (HfIcons.Spark to Tone.Primary)).forEachIndexed { i, (r, style) ->
                if (i > 0) ListDivider(66.dp)
                ListRow(r.first, subtitle = lines[r.second] ?: "…", icon = style.first, tone = style.second, chevron = true, minHeight = 66.dp, onClick = { nav.push(r.third) })
            }
        }
        Note("Every report opens with this date range. Each one has a chart and a table, and you can tap a row to see the records behind it.")
    }
}

@Composable
fun MoneyReport(arg: String) {
    val nav = LocalNav.current
    val range = ReportRange.range
    val ledger = rememberLedger()
    var filter by remember { mutableStateOf(MoneyFilter()) }
    var group by remember { mutableStateOf(MoneyGroup.Category) }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(2 to false) }
    val app = LocalEnv.current.app
    remember { when (arg) {
        "table" -> app.getSharedPreferences("reports", 0).edit().putString("view_money", "Table").apply()
        "calendar" -> app.getSharedPreferences("reports", 0).edit().putString("view_money", "Calendar").apply()
    }; true }
    val (entries, accounts) = ledger ?: (emptyList<LedgerEntry>() to emptyList())
    val all = remember(ledger) { posted(entries, accounts) }
    val current = all.filter { it.entry.occurredAt in range && filter.accepts(it) }
    val previous = all.filter { it.entry.occurredAt in range.previous() && filter.accepts(it) }
    val t = moneyTotals(current); val p = moneyTotals(previous)
    val rows = expenseGroups(current, previous, group, range)
    val b = buckets(range)
    val spec = ExportSpec("Money", "${range.label} · " + (if (filter.active) "filtered · " else "") + "confirmed payments only",
        listOf("Income" to Fmt.amount(t.income), "Expenses" to Fmt.amount(t.expenses), "Fees" to Fmt.amount(t.fees), "Net" to Fmt.signed(t.net)),
        listOf(group.label, "Txns", "Amount", "Share", "vs previous"), rows.map { listOf(it.key, it.count.toString(), Fmt.amount(it.amount), "${it.share}%", pct(it.change)) },
        listOf("Total", rows.sumOf { it.count }.toString(), Fmt.amount(rows.sumOf { it.amount }), "100%", pct(percentChange(t.expenses, p.expenses))),
        listOf("Date", "Account", "Type", "Category", "Description", "Amount", "Source IDs"),
        current.sortedBy { it.entry.occurredAt }.map { listOf(TaskTimeFmt.iso(it.entry.occurredAt), it.accountLabel, it.kind, it.entry.category, it.entry.description, it.entry.amount.toString(), it.entry.id) },
        sourceColumn = 6, chart = rows.map { it.amount })
    val active = buildList {
        filter.types.forEach { v -> add(ActiveFilter(v) { filter = filter.copy(types = filter.types - v) }) }
        filter.categories.forEach { v -> add(ActiveFilter(v) { filter = filter.copy(categories = filter.categories - v) }) }
        filter.accounts.forEach { v -> add(ActiveFilter(accounts.firstOrNull { it.id == v }?.label ?: v) { filter = filter.copy(accounts = filter.accounts - v) }) }
        if (filter.min != null || filter.max != null) add(ActiveFilter("${filter.min ?: 0}–${filter.max ?: "any"}") { filter = filter.copy(min = null, max = null) })
    }
    ReportScaffold("money", "Money", "Overview · from confirmed payments", active,
        filterSheet = { close -> MoneyFilterSheet(filter, accounts, current.size) { filter = it; close() } }, export = spec,
        group = group.label to MoneyGroup.entries.map { it.label }, onGroup = { g -> group = MoneyGroup.entries.first { it.label == g } },
        chart = {
            KpiGrid(listOf(Triple("Income", Fmt.amount(t.income), "TZS · ${pct(percentChange(t.income, p.income))} vs previous"),
                Triple("Expenses", Fmt.amount(t.expenses), "TZS · ${pct(percentChange(t.expenses, p.expenses))} vs previous"),
                Triple("Fees", Fmt.amount(t.fees), if (t.expenses > 0) "${"%.1f".format(t.fees * 100.0 / t.expenses)}% of spending" else "TZS"),
                Triple("Net", Fmt.signed(t.net), "TZS after fees")))
            ChartCard("Income vs expenses", legend = listOf("Income" to Hf.colors.seriesBlue, "Expenses" to Hf.colors.seriesOrange)) {
                BarChart(b.map { it.label }, listOf(
                    Series("Income", Hf.colors.seriesBlue, b.map { k -> current.filter { it.kind == "INCOME" && it.entry.occurredAt in k.start until k.end }.sumOf { it.entry.amount } }),
                    Series("Expenses", Hf.colors.seriesOrange, b.map { k -> current.filter { it.entry.occurredAt in k.start until k.end }.sumOf(::spend) })),
                    description = "Income and expenses for each period of ${range.label}")
            }
            ChartCard("Spending over time") {
                val days = buckets(DateRange(range.start, range.end, range.label)).let { if (range.days in 2..62) (0 until range.days.toInt()).map { i ->
                    Bucket("", range.start + i * 86_400_000L, range.start + (i + 1) * 86_400_000L) } else it }
                LineChart(days.map { k -> current.filter { it.entry.occurredAt in k.start until k.end }.sumOf(::spend) }, description = "Spending per day over ${range.label}")
                Row { Text(Fmt.day(range.start), Modifier.weight(1f), style = Hf.type.caption, color = Hf.colors.muted); Text(Fmt.day(range.end - 1), style = Hf.type.caption, color = Hf.colors.muted) }
            }
            var catStyle by remember { mutableStateOf("Pie") }
            ChartCard("Expenses by category") {
                val cats = expenseGroups(current, previous, MoneyGroup.Category, range)
                SegmentedControl(listOf("Pie", "Bars"), catStyle, { catStyle = it }, compact = true)
                if (cats.isEmpty()) Note("No confirmed expenses in this range.")
                else if (catStyle == "Pie") PieChart(cats.map { it.key to it.amount }, Fmt::compact, centerLabel = Fmt.compact(cats.sumOf { it.amount }),
                    onClick = { nav.push(Screen.ReportCategory, it) }, description = "Expenses by category")
                else HorizontalBars(cats.take(8).map { it.key to it.amount }, Fmt::compact, onClick = { nav.push(Screen.ReportCategory, it) })
            }
            ChartCard("Spending by account") {
                val byAccount = expenseGroups(current, previous, MoneyGroup.Account, range)
                if (byAccount.isEmpty()) Note("No confirmed expenses in this range.")
                else PieChart(byAccount.map { it.key to it.amount }, Fmt::compact, centerLabel = "${byAccount.size} acct",
                    onClick = { label -> accounts.firstOrNull { it.label == label }?.let { nav.push(Screen.ReportCategory, "account:${it.id}") } }, description = "Spending by account")
            }
            Note("Only confirmed payments count. Own transfers are left out; tap a category to see its payments.")
        },
        table = {
            DataTable(listOf(TableColumn(group.label), TableColumn("Txns", true, 64.dp), TableColumn("Amount", true, 104.dp), TableColumn("Share", true, 70.dp), TableColumn("vs prev", true, 80.dp)),
                rows.map { r -> TableRow(r.key, listOf(r.key, r.count.toString(), Fmt.amount(r.amount), "${r.share}%", pct(r.change)),
                    listOf(r.key, r.count, r.amount, r.share, r.change),
                    onClick = when (group) {
                        MoneyGroup.Category -> ({ nav.push(Screen.ReportCategory, r.key) })
                        MoneyGroup.Account -> accounts.firstOrNull { it.label == r.key }?.let { a -> { nav.push(Screen.ReportStatement, a.id) } }
                        else -> null
                    }) },
                totals = spec.totals, sort = sort, onSort = { sort = it })
            TableFooter("Amounts in TZS · fees not included", "Swipe →")
            Note("Tap a column to sort. Group by category, account, week or day.")
        }, calendar = { MoneyCalendar(all.filter { filter.accepts(it) }) })
}

object TaskTimeFmt { fun iso(ms: Long) = com.hellfire.ass.tasks.TaskTime.display(ms) }

@Composable
private fun MoneyFilterSheet(initial: MoneyFilter, accounts: List<FinanceAccount>, count: Int, onApply: (MoneyFilter) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    var min by remember { mutableStateOf(initial.min?.toString().orEmpty()) }
    var max by remember { mutableStateOf(initial.max?.toString().orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Filter Money", Modifier.weight(1f), style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        QuietButton("Reset", { f = MoneyFilter(); min = ""; max = "" })
    }
    FilterGroup(stringResource(R.string.ui_type), listOf("Expense", "Income", "Transfer", "Fee", "Refund"), f.types) { f = f.copy(types = it) }
    GroupLabel(stringResource(R.string.ui_accounts))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        accounts.forEach { a -> CheckRow(a.label, a.id in f.accounts, onChange = { on -> f = f.copy(accounts = if (on) f.accounts + a.id else f.accounts - a.id) }) }
    }
    FilterGroup(stringResource(R.string.ui_category), Categories + "Fees", f.categories) { f = f.copy(categories = it) }
    GroupLabel("Amount · TZS")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HfTextField(min, { min = it.filter(Char::isDigit) }, "Min", Modifier.weight(1f), number = true)
        HfTextField(max, { max = it.filter(Char::isDigit) }, "Max", Modifier.weight(1f), number = true)
    }
    PrimaryButton("Apply filters", { onApply(f.copy(min = min.toLongOrNull(), max = max.toLongOrNull())) }, Modifier.fillMaxWidth(), height = 52.dp)
    Note("$count transactions in the current view.")
}

/** Payments behind a drill-down: a category ("Food"), a day ("day:2026-11-14"), a month ("month:2026-11") or an account ("account:<id>"). */
@Composable
fun CategoryReport(arg: String) {
    val nav = LocalNav.current
    val range = ReportRange.range
    val ledger = rememberLedger()
    var shown by remember { mutableStateOf(20) }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(0 to false) }
    val (entries, accounts) = ledger ?: (emptyList<LedgerEntry>() to emptyList())
    val all = posted(entries, accounts)
    val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", java.util.Locale.ENGLISH)
    val (title, crumb, items, spendingOnly) = when {
        arg.startsWith("day:") -> java.time.LocalDate.parse(arg.removePrefix("day:")).let { d ->
            val r = DateRange.of(d, d)
            Quad(dayFmt.format(d), "Money › Calendar", all.filter { it.entry.occurredAt in r && it.kind in setOf("EXPENSE", "INCOME", "REFUND") }, false) }
        arg.startsWith("month:") -> java.time.YearMonth.parse(arg.removePrefix("month:")).let { m ->
            val r = DateRange.of(m.atDay(1), m.atEndOfMonth())
            Quad(r.label, "Money › Calendar", all.filter { it.entry.occurredAt in r && it.kind in setOf("EXPENSE", "INCOME", "REFUND") }, false) }
        arg.startsWith("account:") -> (accounts.firstOrNull { it.id == arg.removePrefix("account:") }?.label ?: "Account").let { label ->
            Quad(label, "Money › Spending by account", all.filter { it.entry.occurredAt in range && it.accountLabel == label && spend(it) != 0L }, true) }
        else -> Quad(arg, "Money › Expenses › $arg", all.filter { it.entry.occurredAt in range && it.entry.category == arg && spend(it) != 0L }, true)
    }
    fun value(p: Posted) = if (spendingOnly) spend(p) else p.entry.amount
    fun shownValue(p: Posted) = if (spendingOnly) Fmt.amount(spend(p)) else Fmt.signed(p.entry.amount)
    val spent = items.sumOf(::spend)
    val received = items.filter { it.kind == "INCOME" }.sumOf { it.entry.amount }
    val payments = items.count { it.entry.reversesId == null }
    val paymentsLabel = if (payments == 1) "1 payment" else "$payments payments"
    val totalText = if (spendingOnly) Fmt.amount(spent) else Fmt.signed(received - spent)
    val spec = ExportSpec(title, "${if (arg.contains(':') && !arg.startsWith("account:")) title else range.label} · $crumb",
        listOf("Spent" to Fmt.amount(spent), "Received" to Fmt.amount(received), "Payments" to payments.toString()),
        listOf("Date", "Description", "Account", "Amount"), items.map { listOf(Fmt.day(it.entry.occurredAt), it.entry.description, it.accountLabel, shownValue(it)) },
        listOf("Total", paymentsLabel, "", totalText), listOf("Date", "Account", "Type", "Category", "Description", "Amount", "Source IDs"),
        items.map { listOf(TaskTimeFmt.iso(it.entry.occurredAt), it.accountLabel, it.kind, it.entry.category, it.entry.description, it.entry.amount.toString(), it.entry.id) }, 6,
        items.map { kotlin.math.abs(value(it)) })
    val filters = if (arg.contains(':')) listOf(ActiveFilter(title) { nav.pop() }) else listOf(ActiveFilter("Expenses") { nav.pop() }, ActiveFilter(arg) { nav.pop() })
    ReportScaffold("category", title, crumb, filters, export = spec, chart = null, table = {
        KpiGrid(if (spendingOnly) listOf(Triple("Spent", Fmt.amount(spent), "TZS · $paymentsLabel"), Triple("Average", if (payments > 0) Fmt.amount(spent / payments) else "0", "TZS per payment"))
            else listOf(Triple("Spent", Fmt.amount(spent), "TZS · $paymentsLabel"), Triple("Received", Fmt.amount(received), "TZS · net ${Fmt.signed(received - spent)}")))
        if (!spendingOnly && items.isNotEmpty()) ChartCard("By category") {
            PieChart(items.filter { spend(it) > 0 }.groupBy { it.entry.category }.map { (k, v) -> k to v.sumOf(::spend) }, Fmt::compact, description = "Spending by category")
        }
        DataTable(listOf(TableColumn("Date"), TableColumn("Description", width = 150.dp), TableColumn("Account", width = 96.dp), TableColumn("Amount", true, 96.dp)),
            items.take(shown).map { p -> TableRow(p.entry.id, listOf(Fmt.day(p.entry.occurredAt), p.entry.description, p.accountLabel, shownValue(p)),
                listOf(p.entry.occurredAt, p.entry.description, p.accountLabel, value(p)), onClick = { nav.push(Screen.Transaction, p.entry.id) }) },
            totals = listOf("Total", paymentsLabel, "", totalText), sort = sort, onSort = { sort = it }, firstWidth = 84.dp)
        TableFooter("Showing ${minOf(shown, items.size)} of ${items.size}", if (shown < items.size) "Load more" else null, if (shown < items.size) ({ shown += 20 }) else null)
    })
}

private data class Quad(val title: String, val crumb: String, val items: List<Posted>, val spendingOnly: Boolean)

@Composable
fun AccountsReport() {
    val nav = LocalNav.current
    val app = LocalEnv.current.app
    val range = ReportRange.range
    val ledger = rememberLedger()
    val aliases by remember { app.database.finances().aliases() }.collectAsStateWithLifecycle(emptyList())
    var naming by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(5 to false) }
    var message by remember { mutableStateOf<String?>(null) }
    val (entries, accounts) = ledger ?: (emptyList<LedgerEntry>() to emptyList())
    var receiptSources by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(entries) {
        receiptSources = withContext(Dispatchers.IO) {
            val dao = app.database.finances()
            val candidates = dao.allCandidates().associateBy { it.sourceId }
            dao.allEvidence().groupBy { it.ledgerId }.mapNotNull { (id, evidence) ->
                evidence.mapNotNull { candidates[it.sourceId]?.provider }.distinct().singleOrNull()?.let { id to it }
            }.toMap()
        }
    }
    val all = posted(entries, accounts)
    val figures = accountFigures(all, accounts, range)
    val inRange = all.filter { it.entry.occurredAt in range }
    val transfers = inRange.filter { it.kind == "OWN_TRANSFER" && it.entry.amount > 0 }.sumOf { it.entry.amount }
    val payees = inRange.filter { it.kind == "EXPENSE" && it.entry.reversesId == null }.groupBy { it.entry.description }
        .map { (name, list) -> Triple(name, list, -list.sumOf { it.entry.amount }) }.sortedByDescending { it.third }.take(8)
    val spec = ExportSpec("Accounts", range.label, listOf("Total balance" to Fmt.amount(figures.sumOf { it.closing }), "Fees" to Fmt.amount(figures.sumOf { it.fees })),
        listOf("Account", "Opening", "In", "Out", "Fees", "Closing"), figures.map { f -> listOf(f.account.label, Fmt.amount(f.opening), Fmt.amount(f.inflow), Fmt.amount(f.outflow), Fmt.amount(f.fees), Fmt.amount(f.closing)) },
        listOf("Total", Fmt.amount(figures.sumOf { it.opening }), Fmt.amount(figures.sumOf { it.inflow }), Fmt.amount(figures.sumOf { it.outflow }), Fmt.amount(figures.sumOf { it.fees }), Fmt.amount(figures.sumOf { it.closing })),
        listOf("Date", "Account", "Type", "Category", "Description", "Amount", "Source IDs"),
        inRange.map { listOf(TaskTimeFmt.iso(it.entry.occurredAt), it.accountLabel, it.kind, it.entry.category, it.entry.description, it.entry.amount.toString(), it.entry.id) }, 6,
        figures.map { it.closing.coerceAtLeast(0) })
    val b = buckets(range)
    ReportScaffold("accounts", "Accounts and merchants", "Balances · fees · merchants", export = spec,
        group = "Account" to listOf("Account"), chart = {
            message?.let { Banner(it) }
            KpiGrid(listOf(Triple("Total balance, end of range", Fmt.amount(figures.sumOf { it.closing }), "${Fmt.signed(figures.sumOf { it.closing - it.opening })} in this range"),
                Triple("Fees paid", Fmt.amount(figures.sumOf { it.fees }), figures.filter { it.fees > 0 }.joinToString(" · ") { "${it.account.label} ${Fmt.compact(it.fees)}" }.ifEmpty { "None" })))
            ChartCard("Balance per account") {
                Note("Each line has its own scale, so small accounts stay readable.")
                figures.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.clickableRow { nav.push(Screen.ReportStatement, f.account.id) }) {
                        Column(Modifier.width(110.dp)) {
                            Text(f.account.label, style = Hf.type.rowSubtitle, color = Hf.colors.muted, maxLines = 1)
                            Text(Fmt.amount(f.closing), style = Hf.type.rowTitle.copy(fontFeatureSettings = "tnum"), color = Hf.colors.onSurface)
                            Text(Fmt.signed(f.closing - f.opening), style = Hf.type.caption.copy(fontWeight = FontWeight.Normal),
                                color = if (f.closing >= f.opening) Hf.colors.success else Hf.colors.warning)
                        }
                        LineChart(balanceSeries(entries.filter { it.accountId == f.account.id }, b), Modifier.weight(1f), height = 52.dp, fill = false,
                            description = "${f.account.label} balance over ${range.label}")
                    }
                }
            }
            ChartCard("Who you pay most") {
                if (payees.isEmpty()) Note("No confirmed payments in this range.")
                payees.forEach { (name, list, amount) ->
                    val till = Regex("Lipa namba ([0-9]{3,12})").find(name)?.groupValues?.get(1)
                    val provider = list.mapNotNull { receiptSources[it.entry.id] }.distinct().singleOrNull()
                    val known = till != null && aliases.any { it.number == till && it.provider == provider }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (till != null && known) aliases.first { it.number == till && it.provider == provider }.name else name.take(40), style = Hf.type.rowTitle.copy(fontSize = 14.sp), color = Hf.colors.onSurface, maxLines = 1)
                            Text("${list.first().entry.category} · ${list.size}", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                        }
                        if (till != null && !known && provider != null) HfChip("Name it", { naming = provider to till })
                        Text(Fmt.amount(amount), style = Hf.type.rowTitle.copy(fontFeatureSettings = "tnum"), color = Hf.colors.onSurface)
                    }
                }
            }
        }, table = {
            DataTable(listOf(TableColumn("Account"), TableColumn("Opening", true), TableColumn("In", true), TableColumn("Out", true), TableColumn("Fees", true, 80.dp), TableColumn("Closing", true, 104.dp)),
                figures.map { f -> TableRow(f.account.id, listOf(f.account.label, Fmt.amount(f.opening), Fmt.amount(f.inflow), Fmt.amount(f.outflow), Fmt.amount(f.fees), Fmt.amount(f.closing)),
                    listOf(f.account.label, f.opening, f.inflow, f.outflow, f.fees, f.closing), onClick = { nav.push(Screen.ReportStatement, f.account.id) }) },
                totals = spec.totals, sort = sort, onSort = { sort = it })
            TableFooter("In and out include ${Fmt.amount(transfers)} of transfers", "Swipe →")
            Note("Check for every row: opening + in − out − fees = closing. Tap an account for its statement with a running balance.")
        })
    naming?.let { (provider, till) -> MerchantSheet(provider, till, null) { msg -> naming = null; message = msg } }
}

@Composable
fun StatementReport(accountId: String) {
    val nav = LocalNav.current
    val range = ReportRange.range
    val ledger = rememberLedger()
    val (entries, accounts) = ledger ?: (emptyList<LedgerEntry>() to emptyList())
    val account = accounts.firstOrNull { it.id == accountId }
    val mine = entries.filter { it.accountId == accountId }
    val opening = mine.filter { it.occurredAt < range.start }.sumOf { it.amount }
    val rows = runningBalance(mine.filter { it.occurredAt in range }, opening)
    val closing = rows.lastOrNull()?.second ?: opening
    val spec = ExportSpec("${account?.label ?: "Account"} statement", range.label, listOf("Opening" to Fmt.amount(opening), "Closing" to Fmt.amount(closing)),
        listOf("Date", "Description", "Amount", "Balance"), rows.map { (e, bal) -> listOf(Fmt.dayTime(e.occurredAt), e.description, Fmt.signed(e.amount), Fmt.amount(bal)) },
        listOf("Closing", "${rows.size} entries", Fmt.signed(closing - opening), Fmt.amount(closing)),
        listOf("Date", "Type", "Category", "Description", "Amount", "Balance", "Source IDs"),
        rows.map { (e, bal) -> listOf(TaskTimeFmt.iso(e.occurredAt), e.kind, e.category, e.description, e.amount.toString(), bal.toString(), e.id) }, 6)
    ReportScaffold("statement", "${account?.label ?: "Account"} statement", "Accounts › ${account?.label ?: ""}", export = spec, chart = null, table = {
        KpiGrid(listOf(Triple("Opening", Fmt.amount(opening), "TZS at start of range"), Triple("Closing", Fmt.amount(closing), "${rows.size} entries · ${Fmt.signed(closing - opening)}")))
        DataTable(listOf(TableColumn("Date"), TableColumn("Description", width = 150.dp), TableColumn("Amount", true, 96.dp), TableColumn("Balance", true, 104.dp)),
            rows.map { (e, bal) -> TableRow(e.id, listOf(Fmt.day(e.occurredAt), e.description, Fmt.signed(e.amount), Fmt.amount(bal)),
                listOf(e.occurredAt, e.description, e.amount, bal), onClick = { nav.push(Screen.Transaction, e.id) }) },
            totals = spec.totals, firstWidth = 84.dp)
        Note("Running balance from confirmed entries only, oldest first.")
    })
}
