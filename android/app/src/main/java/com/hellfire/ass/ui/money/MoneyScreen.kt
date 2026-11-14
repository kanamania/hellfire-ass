package com.hellfire.ass.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.finance.FinanceAccount
import com.hellfire.ass.finance.FinanceCandidate
import com.hellfire.ass.finance.LedgerEntry
import com.hellfire.ass.finance.MerchantAlias
import com.hellfire.ass.reports.spend
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val Categories = listOf("Food", "Groceries", "Transport", "Fuel", "Utilities", "Rent", "Business", "Family", "Airtime", "Income", "Other")

/** Icon and tone for a ledger category or candidate kind. */
fun categoryStyle(category: String, kind: String = ""): Pair<androidx.compose.ui.graphics.vector.ImageVector, Tone> = when {
    kind == "INCOME" || category == "Income" -> HfIcons.ArrowIn to Tone.Success
    category == "Food" || category == "Groceries" -> HfIcons.Food to Tone.Primary
    category == "Utilities" || category == "Rent" -> HfIcons.Bolt to Tone.Info
    category == "Fees" -> HfIcons.Bank to Tone.Neutral
    category == "Own transfer" || kind == "OWN_TRANSFER" -> HfIcons.ArrowOut to Tone.Neutral
    else -> HfIcons.Money to Tone.Neutral
}

@Composable
fun MoneyScreen() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val dao = app.database.finances()
    val scope = rememberCoroutineScope()
    val accounts by remember { dao.accounts() }.collectAsStateWithLifecycle(emptyList())
    val candidates by remember { dao.candidates() }.collectAsStateWithLifecycle(emptyList())
    val aliases by remember { dao.aliases() }.collectAsStateWithLifecycle(emptyList())
    val reviews by remember { app.database.tasks().reviews() }.collectAsStateWithLifecycle(emptyList())
    var month by remember { mutableStateOf(YearMonth.now(com.hellfire.ass.tasks.TaskTime.zone)) }
    val start = Fmt.startOf(month.atDay(1)); val end = Fmt.startOf(month.plusMonths(1).atDay(1))
    val entries by remember(start) { dao.periodFlow(start, end) }.collectAsStateWithLifecycle(emptyList())
    var balances by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var totals by remember { mutableStateOf<com.hellfire.ass.finance.FinanceSummary?>(null) }
    LaunchedEffect(entries, accounts) {
        withContext(Dispatchers.IO) {
            balances = accounts.associate { it.id to dao.balance(it.id) }
            totals = runCatching { app.finances.summary(start, end) }.getOrNull()
        }
    }
    var reviewing by remember { mutableStateOf<Pair<FinanceCandidate, String>?>(null) }
    var naming by remember { mutableStateOf<FinanceCandidate?>(null) }
    var cashOpen by remember { mutableStateOf(nav.consume("cash")) }
    var banner by remember { mutableStateOf<String?>(null) }
    var confirmReview by remember { mutableStateOf<String?>(null) }
    val reviewAnchor = remember { BringIntoViewRequester() }
    val focusReview = remember { nav.consume("review") }
    LaunchedEffect(focusReview, candidates.size) { if (focusReview) runCatching { reviewAnchor.bringIntoView() } }

    Box(Modifier.fillMaxSize()) {
        ScrollPage(bottomInset = 96.dp) {
            ScreenHeader("Money") {
                MonthChip(month) { month = it }
                Row(
                    Modifier.height(36.dp).clip(CircleShape).background(Hf.colors.primaryContainer).clickableRow { nav.push(Screen.ReportMoney) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(HfIcons.Reports, null, Modifier.size(15.dp), tint = Hf.colors.primaryPressed)
                    Text("Report", style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.primaryPressed)
                }
            }
            banner?.let { Banner(it, modifier = Modifier.clickableRow { banner = null }) }
            BalanceCard(accounts, balances, totals, onOpen = { nav.push(Screen.Accounts) }, onAdd = { nav.push(Screen.Accounts, "add") })
            val byCategory = remember(entries) { com.hellfire.ass.reports.posted(entries, accounts).groupBy { it.entry.category }
                .mapValues { (_, v) -> v.sumOf(::spend) }.filterValues { it > 0 }.toList() }
            if (byCategory.isNotEmpty()) ChartCard("Where it went", trailing = month.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)) {
                PieChart(byCategory, Fmt::compact, maxSlices = 5, centerLabel = Fmt.compact(byCategory.sumOf { it.second }),
                    onClick = { nav.push(Screen.ReportCategory, it) }, description = "This month's spending by category")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HfChip("Calendar", { nav.push(Screen.ReportMoney, "calendar") })
                    HfChip("Full report", { nav.push(Screen.ReportMoney) })
                }
            }

            if (reviews.isNotEmpty()) ActionCard(context = "Nightly review · 21:00 reminder", contextIcon = HfIcons.Alarm, contextTone = Tone.Warning,
                actions = { PrimaryButton("I reviewed ${reviews.first().day}", { confirmReview = reviews.first().day }) }) {
                Text("Check ${reviews.joinToString { it.day }}", style = Hf.type.cardTitle.copy(fontSize = 15.sp), color = Hf.colors.onSurface)
                Text("Look over the payments below, name unknown merchants and add any cash you spent. Skipped days stay here.",
                    style = Hf.type.rowSubtitle.copy(fontSize = 13.sp), color = Hf.colors.muted)
            }

            Column(Modifier.bringIntoViewRequester(reviewAnchor), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (candidates.isNotEmpty()) SectionHeader("To confirm", trailing = if (candidates.size == 1) "1 waiting" else "${candidates.size} waiting")
                candidates.take(30).forEach { candidate ->
                    CandidateCard(candidate, aliases.firstOrNull { it.provider == candidate.provider && it.number == candidate.merchant },
                        onConfirm = { reviewing = candidate to "" }, onName = { naming = candidate },
                        onCategory = { reviewing = candidate to "category" })
                }
            }

            SectionHeader("Recent", action = "See all", onAction = { nav.push(Screen.ReportMoney, "table") })
            if (entries.isEmpty()) HfCard(padding = PaddingValues(16.dp)) {
                Text(if (accounts.isEmpty()) "Add an account, then confirm payments to see them here." else "No confirmed payments in ${month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)} yet.",
                    style = Hf.type.body, color = Hf.colors.muted)
            } else HfCard(Modifier.offset(y = (-6).dp)) {
                entries.filter { !it.id.endsWith(":credit") }.take(12).forEachIndexed { i, e ->
                    if (i > 0) ListDivider()
                    LedgerRow(e, accounts) { nav.push(Screen.Transaction, e.id) }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 16.dp).height(52.dp)
                .shadow(10.dp, CircleShape, ambientColor = Hf.colors.inverse, spotColor = Hf.colors.inverse)
                .clip(CircleShape).background(Hf.colors.inverse).clickableRow { cashOpen = true }.padding(start = 16.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(HfIcons.Plus, null, Modifier.size(22.dp), tint = Hf.colors.onInverse)
            Text("Add cash", style = Hf.type.rowTitle, color = Hf.colors.onInverse)
        }
    }
    reviewing?.let { (c, focus) -> CandidateReviewSheet(c, accounts, aliases, focus, onDone = { msg -> reviewing = null; if (msg != null) banner = msg }) }
    naming?.let { c -> MerchantSheet(c.provider, c.merchant.orEmpty(), aliases.firstOrNull { it.provider == c.provider && it.number == c.merchant }) { msg -> naming = null; if (msg != null) banner = msg } }
    if (cashOpen) CashSheet { msg -> cashOpen = false; if (msg != null) banner = msg }
    confirmReview?.let { day -> ConfirmDialog("Mark $day reviewed?", "This clears the nightly review reminder for that day. Payments still waiting stay in To confirm.",
        "Mark reviewed", onConfirm = {
            confirmReview = null
            scope.launch { withContext(Dispatchers.IO) { app.database.tasks().completeReview(day, System.currentTimeMillis()) }; banner = "Nightly review for $day marked done." }
        }, onDismiss = { confirmReview = null }) }
}

@Composable
private fun MonthChip(month: YearMonth, onPick: (YearMonth) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val now = YearMonth.now(com.hellfire.ass.tasks.TaskTime.zone)
    Box {
        Row(
            Modifier.height(36.dp).clip(CircleShape).background(Hf.colors.surface).border(1.dp, Hf.colors.outline, CircleShape)
                .clickable(role = Role.DropdownList) { open = true }.padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(month.format(DateTimeFormatter.ofPattern(if (month.year == now.year) "MMMM" else "MMM yyyy", Locale.ENGLISH)),
                style = Hf.type.label, color = Hf.colors.onSurface)
            Icon(HfIcons.ChevronDown, null, Modifier.size(14.dp), tint = Hf.colors.muted)
        }
        DropdownMenu(open, { open = false }, containerColor = Hf.colors.surface) {
            (0 until 12).map { now.minusMonths(it.toLong()) }.forEach { m ->
                DropdownMenuItem(text = { Text(m.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), style = Hf.type.body) },
                    onClick = { onPick(m); open = false })
            }
        }
    }
}

@Composable
private fun BalanceCard(accounts: List<FinanceAccount>, balances: Map<String, Long>, totals: com.hellfire.ass.finance.FinanceSummary?, onOpen: () -> Unit, onAdd: () -> Unit) {
    val c = Hf.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceDark).clickableRow(onOpen)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Total balance · TZS", style = Hf.type.label, color = c.mutedOnDark)
            Text(if (accounts.isEmpty()) "—" else Fmt.amount(balances.values.sum()), style = Hf.type.bigFigure, color = c.onSurfaceDark)
        }
        if (accounts.isEmpty()) Text("Add your M-Pesa, bank and cash accounts to track balances.", style = Hf.type.rowSubtitle, color = c.mutedOnDark)
        (accounts.map<FinanceAccount, FinanceAccount?> { it } + null).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    if (a == null) Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, c.surfaceDarkInner, RoundedCornerShape(14.dp))
                        .clickableRow(onAdd).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text("+ Add", style = Hf.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.onSurfaceDark)
                        Text("account", style = Hf.type.rowTitle.copy(fontSize = 14.sp), color = c.mutedOnDark)
                    }
                    else Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surfaceDarkInner).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(a.label, style = Hf.type.caption.copy(fontWeight = FontWeight.Normal), color = c.mutedOnDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.amount(balances[a.id] ?: 0), style = Hf.type.rowTitle.copy(fontSize = 14.sp, fontFeatureSettings = "tnum"), color = c.onSurfaceDark, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            InOut(HfIcons.ArrowIn, Color(0xFF6EE7A0), "In", totals?.income)
            InOut(HfIcons.ArrowOut, Color(0xFFFDBA8C), "Out", totals?.expenses)
        }
    }
}

@Composable
private fun InOut(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, label: String, value: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Text(label, style = Hf.type.label.copy(fontWeight = FontWeight.Normal), color = Hf.colors.mutedOnDark)
        Text(value?.let(Fmt::amount) ?: "—", style = Hf.type.label.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"), color = Hf.colors.onSurfaceDark)
    }
}

/** Display title for a candidate: owner's merchant name, Lipa namba, or the parsed description. */
fun candidateTitle(c: FinanceCandidate, alias: MerchantAlias?): String = when {
    alias != null -> alias.name
    c.merchant != null -> "Lipa namba ${c.merchant}"
    c.provider == "MANUAL" -> c.evidence.take(60)
    else -> "${Fmt.kind(c.kind)} · ${Fmt.provider(c.provider)}"
}

@Composable
private fun CandidateCard(c: FinanceCandidate, alias: MerchantAlias?, onConfirm: () -> Unit, onName: () -> Unit, onCategory: () -> Unit) {
    val unknown = c.merchant != null && alias == null
    val (icon, tone) = categoryStyle(alias?.category ?: "", c.kind)
    HfCard(padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (unknown) IconTile(text = "?", tone = Tone.Warning) else IconTile(icon = icon, tone = tone)
                Column(Modifier.weight(1f)) {
                    Text(candidateTitle(c, alias), style = Hf.type.rowTitle, color = Hf.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(if (c.provider == "MANUAL") "Cash, your entry" else "${Fmt.provider(c.provider)} SMS", Fmt.relativeDay(c.occurredAt),
                        if (unknown) "New merchant" else alias?.category, if (c.reference == null && c.provider != "MANUAL") "No reference" else null).joinToString(" · "),
                        style = Hf.type.rowSubtitle, color = Hf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (c.kind == "INCOME" || c.kind == "REFUND") "+" + Fmt.amount(c.amount) else Fmt.outflow(c.amount),
                    style = Hf.type.figure.copy(fontSize = 16.sp), color = if (c.kind == "INCOME" || c.kind == "REFUND") Hf.colors.success else Hf.colors.onSurface)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Confirm", onConfirm)
                if (unknown) SecondaryButton("Name this merchant", onName) else SecondaryButton("Change category", onCategory)
            }
        }
    }
}

@Composable
fun LedgerRow(e: LedgerEntry, accounts: List<FinanceAccount>, onClick: () -> Unit) {
    val (icon, tone) = categoryStyle(e.category, e.kind)
    val account = accounts.firstOrNull { it.id == e.accountId }?.label ?: "Account"
    ListRow(e.description.lineSequence().first().take(80).ifBlank { Fmt.kind(e.kind) },
        subtitle = listOf(if (e.reversesId != null) "Correction" else e.category, account, Fmt.relativeDay(e.occurredAt)).joinToString(" · "),
        icon = icon, tone = tone, onClick = onClick,
        trailing = {
            Text(if (e.amount > 0) Fmt.signed(e.amount) else Fmt.amount(e.amount),
                style = Hf.type.rowTitle.copy(fontFeatureSettings = "tnum"), color = if (e.amount > 0) Hf.colors.success else Hf.colors.onSurface)
        })
}
