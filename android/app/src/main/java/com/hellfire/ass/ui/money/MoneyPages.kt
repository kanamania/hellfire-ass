package com.hellfire.ass.ui.money

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.finance.FinanceAccount
import com.hellfire.ass.finance.FinanceCandidate
import com.hellfire.ass.finance.LedgerEntry
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Providers = listOf("CASH", "MPESA", "AIRTEL", "MIXX", "HALOPESA", "CRDB", "NMB", "NBC")

@Composable
fun AccountsPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val dao = app.database.finances()
    val scope = rememberCoroutineScope()
    val accounts by remember { dao.accounts() }.collectAsStateWithLifecycle(emptyList())
    val entries by remember { dao.entries() }.collectAsStateWithLifecycle(emptyList())
    var balances by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(accounts, entries) { balances = withContext(Dispatchers.IO) { accounts.associate { it.id to dao.balance(it.id) } } }
    var label by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("MPESA") }
    var message by remember { mutableStateOf<String?>(null) }
    PushedPage("Accounts", breadcrumb = "Money") {
        message?.let { Banner(it) }
        if (accounts.isNotEmpty()) ListCard {
            accounts.forEachIndexed { i, a ->
                if (i > 0) ListDivider()
                ListRow(a.label, subtitle = Fmt.provider(a.provider) + " · " + a.currency, icon = if (a.provider == "CASH") HfIcons.Money else HfIcons.Bank,
                    tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.ReportStatement, a.id) },
                    trailing = { Text(Fmt.amount(balances[a.id] ?: 0), style = Hf.type.rowTitle.copy(fontFeatureSettings = "tnum"), color = Hf.colors.onSurface) })
            }
        }
        Note("Balances come from confirmed ledger entries only. An opening balance needs its own evidence and review.")
        ListCard {
            ListRow("Monthly budgets", subtitle = "Limits per category for the Budget widget", icon = HfIcons.Reports, tone = Tone.Primary, chevron = true,
                onClick = { nav.push(Screen.Budgets) })
        }
        SectionCard("Add an account") {
            HfTextField(label, { label = it }, "Account name, e.g. M-Pesa or CRDB Current")
            GroupLabel("Provider")
            ChipPicker(Providers, provider, Fmt::provider) { provider = it }
            PrimaryButton("Add account", {
                scope.launch {
                    message = try { withContext(Dispatchers.IO) { dao.addAccount(FinanceAccount(label = label.trim(), provider = provider)) }; label = ""; "Account added." }
                        catch (_: Exception) { "Account could not be saved." }
                }
            }, Modifier.fillMaxWidth(), enabled = label.isNotBlank())
        }
    }
}

/** One posted transaction (RTxn): confirmed values, the original source as read-only evidence, correction by reversal. */
@Composable
fun TransactionPage(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val dao = app.database.finances()
    val scope = rememberCoroutineScope()
    val entry by remember(id) { dao.observeEntry(id) }.collectAsStateWithLifecycle(null)
    var account by remember { mutableStateOf<FinanceAccount?>(null) }
    var fee by remember { mutableStateOf(0L) }
    var reversed by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<FinanceCandidate?>(null) }
    var source by remember { mutableStateOf<SourceEvent?>(null) }
    var sourceText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf<String?>(null) }
    var confirmReverse by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry) {
        val e = entry ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            account = dao.account(e.accountId)
            fee = dao.group(e.groupId).filter { it.kind == "FEE" }.sumOf { -it.amount }
            reversed = dao.reversed(e.id)
            val sources = dao.sources(e.id)
            candidate = sources.firstNotNullOfOrNull { s -> dao.candidatesFor(s).firstOrNull { it.state == "APPROVED" } ?: dao.candidatesFor(s).firstOrNull() }
            source = sources.firstNotNullOfOrNull { app.database.events().find(it) }
            sourceText = source?.let { EventText.raw(app, it) }.orEmpty()
            merchant = candidate?.merchant?.let { m -> dao.merchant(candidate!!.provider, m)?.name?.let { "$it · till $m" } ?: "Lipa namba $m" }
        }
    }
    val e = entry
    PushedPage("Transaction", breadcrumb = e?.let { "Money › ${it.category} › ${Fmt.day(it.occurredAt)}" }) {
        if (e == null) { Note("This transaction is no longer available."); return@PushedPage }
        message?.let { Banner(it) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${Fmt.kind(e.kind)} · ${e.category}", style = Hf.type.label, color = Hf.colors.muted)
            Text((if (e.amount < 0) "${Fmt.MINUS} " else "+ ") + "TZS " + Fmt.amount(kotlin.math.abs(e.amount)), style = Hf.type.bigFigure.copy(fontSize = 30.sp),
                color = if (e.amount > 0) Hf.colors.success else Hf.colors.onSurface)
            Text(Fmt.full(e.occurredAt), style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        }
        KvList(listOfNotNull(
            "Account" to (account?.label ?: "—"),
            merchant?.let { "Merchant" to it },
            "Description" to e.description.take(120),
            "Fee" to "TZS " + Fmt.amount(fee),
            candidate?.reference?.let { "Reference" to it },
            "Confirmed by you" to Fmt.dayTime(e.createdAt),
            "Ledger" to when { e.reversesId != null -> "Correction entry"; reversed -> "Reversed"; else -> "1 entry · not reversed" },
        ))
        source?.let { s ->
            EvidenceCard("Source · ${EventText.sourceLabel(s)} · ${Fmt.dayTime(s.occurredAt)}", sourceText.ifBlank { "No readable text in the source." }.take(1200))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.reversesId == null && e.kind != "FEE") SecondaryButton("Change category", { pickCategory = true })
            source?.let { s -> SecondaryButton("Full source", { nav.push(Screen.Source, s.id) }) }
        }
        if (e.reversesId == null && e.id == e.groupId && !reversed) QuietButton("Correct with a reversal", { confirmReverse = true })
        Note("Amount, date and account come from the confirmed entry. The source message is kept as proof and is never edited.")
    }
    if (confirmReverse) ConfirmDialog("Correct with a reversal?", "Adds reversal entries for this payment, its fee and any transfer credit. The original entry and its evidence stay.",
        "Add reversal", onConfirm = {
            confirmReverse = false
            scope.launch { message = try { withContext(Dispatchers.IO) { app.finances.reverse(id) }; "Reversal entries posted." } catch (_: Exception) { "Reversal rejected: already corrected or a correction entry." } }
        }, onDismiss = { confirmReverse = false }, destructive = true)
    if (pickCategory && e != null) HfBottomSheet({ pickCategory = false }) {
        Text("Change category", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Note("Only the label changes. The amount, account and balances stay the same.")
        ChipPicker(Categories, e.category) { chosen ->
            pickCategory = false
            scope.launch { message = try { withContext(Dispatchers.IO) { app.finances.recategorize(id, chosen) }; "Category changed to $chosen." } catch (_: Exception) { "Category could not be changed." } }
        }
    }
}

/** Read-only view of one captured source record and what was made from it. */
@Composable
fun SourcePage(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var event by remember { mutableStateOf<SourceEvent?>(null) }
    var text by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf<List<Pair<String, () -> Unit>>>(emptyList()) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            event = app.database.events().find(id)
            event?.let { text = EventText.raw(app, it) }
            val ledger = app.database.finances().entriesFor(id)
            val tasks = app.database.tasks().all().filter { it.sourceId == id }
            linked = ledger.filter { it.id == it.groupId }.map { e -> "Money · ${Fmt.signed(e.amount)} ${e.category}" to { nav.push(Screen.Transaction, e.id) } } +
                tasks.map { t -> "Task · ${t.title.take(40)}" to { nav.push(Screen.Task, t.id) } }
        }
    }
    val e = event
    PushedPage("Source", breadcrumb = e?.let { EventText.sourceLabel(it) + " · " + Fmt.day(it.occurredAt) }) {
        if (e == null) { Note("This source record is no longer available."); return@PushedPage }
        EvidenceCard("${EventText.sourceLabel(e)} · ${e.packageName} · ${Fmt.dayTime(e.occurredAt)}", text.ifBlank { "No readable text." }.take(4000))
        KvList(listOf("Source" to EventText.sourceLabel(e), "From" to e.packageName, "Received" to Fmt.full(e.receivedAt),
            "Content" to when { e.securitySensitive -> "Redacted (security code)"; !e.contentAvailable -> "Not shared by Android"; e.contentRedacted -> "Partly redacted"; else -> "As delivered" },
            "Linked records" to if (linked.isEmpty()) "None" else linked.size.toString()))
        if (linked.isNotEmpty()) ListCard { linked.forEachIndexed { i, (label, open) -> if (i > 0) ListDivider(16.dp); ListRow(label, chevron = true, onClick = open) } }
        if (raw == null) QuietButton("Show raw record", { raw = "loading" })
        else {
            LaunchedEffect(Unit) { raw = withContext(Dispatchers.IO) { com.hellfire.ass.assistant.AssistantEngine(app).source(id) } }
            EvidenceCard("Raw record", raw!!.take(16000))
        }
        Note("Captured records are read-only evidence. Nothing here can be edited.")
    }
}
