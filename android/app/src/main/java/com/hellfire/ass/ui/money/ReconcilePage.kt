package com.hellfire.ass.ui.money

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.*

@Composable
fun ReconcilePage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val accounts by remember { app.database.finances().accounts() }.collectAsStateWithLifecycle(emptyList())
    val candidates by remember { app.database.finances().candidates() }.collectAsStateWithLifecycle(emptyList())
    var savedAccount by rememberPrivateDraft("reconcile-account")
    val accountId = savedAccount.ifEmpty { null }
    var observed by rememberPrivateDraft("reconcile-observed")
    var at by rememberPrivateDraft("reconcile-time", TaskTime.display(System.currentTimeMillis()))
    var ledger by remember { mutableStateOf<Long?>(null) }
    var receipts by remember { mutableStateOf<List<com.hellfire.ass.finance.ProviderBalance>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var correcting by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
    LaunchedEffect(account?.id, at, candidates.size, refresh) {
        withContext(Dispatchers.IO) {
            ledger = account?.let { app.database.finances().balanceAt(it.id, TaskTime.parse(at)) }
            receipts = app.database.widgetData().balances()
        }
    }
    PushedPage(com.hellfire.ass.ui.tr("Check account balance"), breadcrumb = "Money") {
        message?.let { Banner(it) }
        if (accounts.isEmpty()) { Note("Add an account in Money › Accounts first."); SecondaryButton("Add account", { nav.push(Screen.Accounts, "add") }) }
        ChoiceField("Account", account, accounts, { it.label }, { savedAccount = it.id })
        HfTextField(observed, { observed = it.filter { c -> c.isDigit() || c == '-' } }, "Balance shown by your provider · TZS", number = true)
        DateTimeField(at, { at = it }, "Balance as of · East Africa Time")
        ledger?.let { balance ->
            val target = observed.toLongOrNull()?.takeIf { it in -1_000_000_000_000L..1_000_000_000_000L }
            KvList(listOf("Ledger at this time" to "TZS ${Fmt.amount(balance)}") + listOfNotNull(target?.let {
                "Observed minus ledger" to "TZS ${Fmt.signed(Math.subtractExact(it, balance))}"
            }))
            if (target == balance) Note(com.hellfire.ass.ui.tr("The two balances agree at this time."))
        }
        val pending = candidates.filter { it.accountId == null || it.accountId == account?.id }
        SectionCard(com.hellfire.ass.ui.tr("Before making an adjustment")) {
            Note("${pending.size} pending or unassigned payments may explain the difference. Confirm genuine payments first. Check missing income, cash withdrawals, fees and transfers; a balance difference does not prove which transaction is missing.")
            PrimaryButton(com.hellfire.ass.ui.tr("Review pending payments"), { nav.push(Screen.Review) })
            pending.take(8).forEach { p -> Note("${Fmt.dayTime(p.occurredAt)} · ${Fmt.amount(p.amount)} + fee ${Fmt.amount(p.fee)} · ${p.category ?: "Uncategorized"}") }
        }
        if (receipts.isNotEmpty()) SectionCard(com.hellfire.ass.ui.tr("Latest observed SMS balances")) {
            Note(com.hellfire.ass.ui.tr("Pick a receipt only if it belongs to this account. A provider name alone does not identify your account."))
            receipts.forEach { receipt -> ListRow("${receipt.provider} · TZS ${Fmt.amount(receipt.amount)}", subtitle = Fmt.full(receipt.observedAt),
                onClick = { observed = receipt.amount.toString(); at = TaskTime.display(receipt.observedAt) }, trailing = {
                    QuietButton(com.hellfire.ass.ui.tr("Source"), { nav.push(Screen.Source, receipt.sourceId) })
                }) }
        }
        if (account != null) SecondaryButton(com.hellfire.ass.ui.tr("Record a reviewed balance adjustment…"), { correcting = true })
        Note(com.hellfire.ass.ui.tr("An adjustment records the difference with your approval and evidence. It never fabricates missing payments or counts as income/spending."))
    }
    if (correcting && account != null) BalanceSheet(account, initialAmount = observed, initialAt = at) { message = it; if (it != null) refresh++; correcting = false }
}
