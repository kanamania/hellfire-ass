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

/** Captures the owner's own statement as source evidence (like a cash entry) and returns its record ID. */
suspend fun ownerStatement(app: com.hellfire.ass.HellFireApp, text: String, at: Long): String {
    val raw = com.hellfire.ass.events.NotificationSnapshot(java.util.UUID.randomUUID().toString(), "owner.balance", at, System.currentTimeMillis(),
        source = "owner_entry", fields = mapOf("text" to listOf(text)))
    val safe = com.hellfire.ass.events.NotificationNormalizer.normalize(raw, app.packageName)!!
    app.events.capture(raw, app.packageName)
    return safe.id
}

/**
 * Set an account's balance. The owner's figure (or the provider's SMS balance, as a suggestion) becomes one confirmed
 * BALANCE ledger entry for the difference, after an explicit confirmation.
 */
@Composable
fun BalanceSheet(account: FinanceAccount, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var at by remember { mutableStateOf(com.hellfire.ass.tasks.TaskTime.display(System.currentTimeMillis())) }
    var current by remember { mutableStateOf<Long?>(null) }
    var sms by remember { mutableStateOf<com.hellfire.ass.finance.ProviderBalance?>(null) }
    var useSms by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account.id) { withContext(Dispatchers.IO) {
        current = app.database.finances().balance(account.id)
        sms = if (account.provider == "CASH") null else app.database.widgetData().balance(account.provider)
    } }
    HfBottomSheet({ onDone(null) }) {
        Text("Set balance", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Text("${account.label} · now ${current?.let { "TZS " + Fmt.amount(it) } ?: "…"} in your ledger", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        sms?.let { b ->
            OptionCard("Use the balance from SMS", "TZS ${Fmt.amount(b.amount)} · message from ${Fmt.dayTime(b.observedAt)}", useSms, {
                useSms = true; amount = b.amount.toString(); at = com.hellfire.ass.tasks.TaskTime.display(b.observedAt)
            })
        }
        HfTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '-' }; useSms = false }, "Balance TZS (whole shillings)", number = true)
        DateTimeField(at, { at = it; useSms = false }, "As of · East Africa Time")
        Note("HellFire adds one confirmed balance entry for the difference. It changes this account's balance but never counts as income or spending, and you can reverse it later.")
        error?.let { Banner(it, Tone.Danger, null) }
        PrimaryButton("Review balance", { confirm = true }, Modifier.fillMaxWidth(), enabled = amount.toLongOrNull() != null, height = 52.dp)
    }
    if (confirm) ConfirmDialog("Set ${account.label} to TZS ${amount.toLongOrNull()?.let(Fmt::amount) ?: amount}?",
        "As of $at East Africa Time. " + if (useSms) "Evidence: the saved SMS." else "Evidence: your statement, saved on this phone.", "Confirm balance",
        onConfirm = {
            confirm = false
            scope.launch {
                try {
                    val target = amount.toLong(); val time = com.hellfire.ass.tasks.TaskTime.parse(at)
                    val delta = withContext(Dispatchers.IO) {
                        val source = sms?.takeIf { useSms && it.amount == target }?.sourceId
                            ?: ownerStatement(app, "Owner stated the ${account.label} balance as TZS ${Fmt.amount(target)} at $at EAT", time)
                        app.finances.setBalance(account.id, target, time, source)
                    }
                    onDone(if (delta == 0L) "${account.label} already shows that balance. Nothing added." else "${account.label} balance set. Adjustment ${Fmt.signed(delta)} recorded.")
                } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); error = "Balance not saved. Use whole shillings and yyyy-MM-dd HH:mm." }
            }
        }, onDismiss = { confirm = false })
}

@Composable
fun AccountsPage(focusAdd: Boolean = false) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val dao = app.database.finances()
    val scope = rememberCoroutineScope()
    val accounts by remember { dao.accounts() }.collectAsStateWithLifecycle(emptyList())
    val entries by remember { dao.entries() }.collectAsStateWithLifecycle(emptyList())
    var balances by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(accounts, entries) { balances = withContext(Dispatchers.IO) { accounts.associate { it.id to dao.balance(it.id) } } }
    var label by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var setting by remember { mutableStateOf<FinanceAccount?>(null) }
    var confirmAdd by remember { mutableStateOf(false) }
    @Composable fun AddAccountCard() {
        SectionCard("Add an account") {
            HfTextField(label, { label = it }, "Account name, e.g. M-Pesa or CRDB Current")
            HfTextField(opening, { opening = it.filter { c -> c.isDigit() } }, "Current balance TZS (optional)", number = true,
                supporting = "Leave empty to start at zero. You can set it later.")
            PrimaryButton("Add account", { if (opening.isBlank()) scope.launch {
                message = try { withContext(Dispatchers.IO) { dao.addAccount(FinanceAccount(label = label.trim())) }; label = ""; "Account added." }
                    catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Account could not be saved." }
            } else confirmAdd = true }, Modifier.fillMaxWidth(), enabled = label.isNotBlank())
        }
    }
    PushedPage("Accounts", breadcrumb = "Money") {
        message?.let { Banner(it) }
        val formFirst = focusAdd || accounts.isEmpty()
        if (formFirst) AddAccountCard()
        if (accounts.isNotEmpty()) ListCard {
            accounts.forEachIndexed { i, a ->
                if (i > 0) ListDivider()
                ListRow(a.label, subtitle = a.currency, icon = if (a.provider == "CASH") HfIcons.Money else HfIcons.Bank,
                    tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.ReportStatement, a.id) },
                    trailing = {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(Fmt.amount(balances[a.id] ?: 0), style = Hf.type.rowTitle.copy(fontFeatureSettings = "tnum"), color = Hf.colors.onSurface)
                            Text("Set balance", Modifier.clickableRow { setting = a }.padding(vertical = 6.dp), style = Hf.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Hf.colors.primary)
                        }
                    })
            }
        }
        Note("Balances come from confirmed ledger entries only. An opening balance needs its own evidence and review.")
        ListCard {
            ListRow("Monthly budgets", subtitle = "Limits per category for the Budget widget", icon = HfIcons.Reports, tone = Tone.Primary, chevron = true,
                onClick = { nav.push(Screen.Budgets) })
        }
        if (!formFirst) AddAccountCard()
    }
    setting?.let { a -> BalanceSheet(a) { msg -> setting = null; if (msg != null) message = msg } }
    if (confirmAdd) ConfirmDialog("Add ${label.trim()} with TZS ${opening.toLongOrNull()?.let(Fmt::amount) ?: opening}?",
        "The starting balance becomes one confirmed opening-balance entry, evidenced by your statement. It never counts as income.", "Add account",
        onConfirm = {
            confirmAdd = false
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) {
                        val account = FinanceAccount(label = label.trim())
                        dao.addAccount(account)
                        val now = System.currentTimeMillis()
                        app.finances.setBalance(account.id, opening.toLong(), now,
                            ownerStatement(app, "Owner stated the ${account.label} opening balance as TZS ${Fmt.amount(opening.toLong())}", now))
                    }
                    label = ""; opening = ""; "Account added with its starting balance."
                } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Account could not be saved." }
            }
        }, onDismiss = { confirmAdd = false })
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
            scope.launch { message = try { withContext(Dispatchers.IO) { app.finances.reverse(id) }; "Reversal entries posted." } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Reversal rejected: already corrected or a correction entry." } }
        }, onDismiss = { confirmReverse = false }, destructive = true)
    if (pickCategory && e != null) HfBottomSheet({ pickCategory = false }) {
        Text("Change category", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Note("Only the label changes. The amount, account and balances stay the same.")
        ChipPicker(Categories, e.category) { chosen ->
            pickCategory = false
            scope.launch { message = try { withContext(Dispatchers.IO) { app.finances.recategorize(id, chosen) }; "Category changed to $chosen." } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Category could not be changed." } }
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
    var sender by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf<List<Pair<String, () -> Unit>>>(emptyList()) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            event = app.database.events().find(id)
            event?.let { text = EventText.raw(app, it); sender = EventText.fields(app, it)["sender_hint"]?.firstOrNull()?.takeIf(String::isNotBlank) }
            val ledger = app.database.finances().entriesFor(id)
            val tasks = app.database.tasks().all().filter { it.sourceId == id }
            linked = ledger.filter { it.id == it.groupId }.map { e -> "Money · ${Fmt.signed(e.amount)} ${e.category}" to { nav.push(Screen.Transaction, e.id) } } +
                tasks.map { t -> "Task · ${t.title.take(40)}" to { nav.push(Screen.Task, t.id) } }
        }
    }
    val e = event
    PushedPage("Source", breadcrumb = e?.let { EventText.sourceLabel(it) + " · " + Fmt.day(it.occurredAt) }) {
        if (e == null) { Note("This source record is no longer available."); return@PushedPage }
        EvidenceCard("${EventText.sourceLabel(e)} · ${sender ?: e.packageName} · ${Fmt.dayTime(e.occurredAt)}", text.ifBlank { "No readable text." }.take(4000))
        KvList(listOf("Source" to EventText.sourceLabel(e), "From" to (sender ?: e.packageName), "Received" to Fmt.full(e.receivedAt),
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
