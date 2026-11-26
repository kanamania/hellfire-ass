package com.hellfire.ass.ui.insights

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.hellfire.ass.communications.Accounts
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.communications.OutboundDraft
import com.hellfire.ass.finance.Money
import com.hellfire.ass.insights.*
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.money.ChipPicker
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.launch
import java.util.UUID

/** Starting values for the debt sheet, e.g. from a transaction or a repayment suggestion. */
data class DebtPrefill(val person: String = "", val kind: String = "LENT", val amount: Long? = null,
    val occurredAt: Long = System.currentTimeMillis(), val ledgerId: String? = null, val personKey: String? = null)

/** Record a loan or repayment. This is the owner's own note; it never changes the ledger. */
@Composable
fun DebtSheet(prefill: DebtPrefill, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var person by remember { mutableStateOf(prefill.person) }
    var picked by remember { mutableStateOf<com.hellfire.ass.contacts.ContactChoice?>(null) }
    var kind by remember { mutableStateOf(prefill.kind) }
    var amount by remember { mutableStateOf(prefill.amount?.toString().orEmpty()) }
    var occurred by remember { mutableStateOf(TaskTime.display(prefill.occurredAt)) }
    var hasDue by remember { mutableStateOf(false) }
    var due by remember { mutableStateOf(TaskTime.display(prefill.occurredAt + 30L * 86_400_000)) }
    var note by remember { mutableStateOf("") }
    var known by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        known = io { (app.database.debts().all().map { it.person.trim() } + app.database.people().all().map { it.name.trim() }).distinctBy(Debts::key) } ?: emptyList()
    }
    HfBottomSheet({ onDone(null) }) {
        Text("Loan or repayment", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        if (prefill.ledgerId != null) Note("Linked to the payment you opened. Its amount and account stay as they are in Money.")
        ContactPersonField(person, { person = it; picked = null }, stringResource(R.string.ui_person), known, onPick = { picked = it; person = it.displayName })
        SavedContactStatus(com.hellfire.ass.insights.People.namedKey(person))
        GroupLabel("What happened")
        ChipPicker(Debts.kinds, kind, { Debts.label(it) }) { kind = it }
        HfTextField(amount, { amount = it.filter(Char::isDigit) }, stringResource(R.string.ui_amount_tzs), number = true)
        DateTimeField(occurred, { occurred = it }, "When · East Africa Time")
        if (kind == "LENT" || kind == "BORROWED") {
            CheckRow("A date was agreed to pay it back", hasDue, onChange = { hasDue = it })
            if (hasDue) DateTimeField(due, { due = it }, "Pay back by · East Africa Time")
        }
        HfTextField(note, { note = it.take(300) }, "Note (optional)")
        error?.let { Banner(it, Tone.Danger, null) }
        PrimaryButton(stringResource(R.string.ui_save), {
            scope.launch {
                val saved = io {
                    val now = System.currentTimeMillis()
                    app.database.withTransaction {
                        val personKey = com.hellfire.ass.contacts.ContactLinks(app.database).save(picked ?: com.hellfire.ass.contacts.ContactChoice(person, personKey = prefill.personKey))
                        app.database.debts().insert(DebtEntry(UUID.randomUUID().toString(), person.trim(), Debts.signed(kind, Money.tzs(amount)), kind, note.trim(),
                            TaskTime.parse(occurred), if (hasDue && (kind == "LENT" || kind == "BORROWED")) TaskTime.parse(due) else null, prefill.ledgerId, now, personKey))
                    }
                }
                if (saved == null) error = "Not saved. Use a whole-shilling amount and a valid date." else onDone("Saved: ${Debts.label(kind).lowercase()} TZS ${Fmt.amount(amount.toLong())} · ${person.trim()}.")
            }
        }, Modifier.fillMaxWidth(), enabled = person.isNotBlank() && person.trim().length <= 200 && amount.isNotBlank(), height = 52.dp)
    }
}

/** Editable reminder for money owed to the owner. Sharing or saving a draft is the owner's action; nothing is sent from here. */
@Composable
private fun ReminderSheet(balance: DebtBalance, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var swahili by remember { mutableStateOf(true) }
    var text by remember(swahili) { mutableStateOf(Debts.reminderText(balance.person, balance.balance, balance.dueAt, swahili)) }
    var email by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(balance.person) {
        email = io {
            val address = app.database.people().all().firstOrNull { (it.personKey ?: People.namedKey(it.name)) == (balance.personKey ?: People.namedKey(balance.person)) && it.handle.startsWith("mail:") }?.handle?.removePrefix("mail:")
            val account = Accounts.list(app.secrets, Accounts.MAIL).firstOrNull()?.getString("id")
            if (address != null && account != null) account to address else null
        }
    }
    HfBottomSheet({ onDone(null) }) {
        Text("Remind ${balance.person}", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        ChipPicker(listOf("Kiswahili", "English"), if (swahili) "Kiswahili" else "English", { languageLabel(it) }) { swahili = it == "Kiswahili" }
        HfTextField(text, { text = it.take(1000) }, stringResource(R.string.ui_message), singleLine = false, minLines = 3)
        PrimaryButton("Share…", {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send reminder"))
            onDone(null)
        }, Modifier.fillMaxWidth(), enabled = text.isNotBlank(), height = 52.dp)
        email?.let { (account, address) ->
            SecondaryButton("Save as email draft to $address", {
                scope.launch {
                    val saved = io {
                        val now = System.currentTimeMillis(); val id = UUID.randomUUID().toString()
                        app.database.withTransaction {
                            app.database.communications().draft(OutboundDraft(id, "EMAIL_IMAP", account, address, "Reminder", text, now))
                            app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_DEBT_REMINDER_DRAFT", "DRAFT", now))
                        }
                    }
                    onDone(if (saved != null) "Draft saved. Approve it in Inbox to send; nothing was sent." else "The draft could not be saved.")
                }
            }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
        }
        Note("Share opens your own apps (WhatsApp, SMS, email) with this text. You choose the person and press send there.")
    }
}

/** Madeni: who owes the owner, who the owner owes, and payments that look like repayments. */
@Composable
fun DebtsPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val entries by remember { app.database.debts().entries() }.collectAsStateWithLifecycle(emptyList())
    val prefs = remember { app.getSharedPreferences("debts", 0) }
    var dismissed by remember { mutableStateOf(prefs.getStringSet("dismissed", emptySet())!!.toSet()) }
    var suggestions by remember { mutableStateOf<List<RepaymentSuggestion>>(emptyList()) }
    var sheet by remember { mutableStateOf<DebtPrefill?>(null) }
    var reminding by remember { mutableStateOf<DebtBalance?>(null) }
    var removing by remember { mutableStateOf<DebtEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val balances = remember(entries) { Debts.balances(entries) }
    val now = System.currentTimeMillis()
    LaunchedEffect(entries, dismissed) {
        suggestions = io {
            val dao = app.database.finances()
            val linked = entries.mapNotNull { it.ledgerId }.flatMap { dao.sources(it) }.toSet()
            Debts.suggestRepayments(balances, dao.allCandidates(), now - 60L * 86_400_000, linked + dismissed)
        } ?: emptyList()
    }
    PushedPage(stringResource(R.string.ui_debts), breadcrumb = "Money") {
        message?.let { Banner(it, modifier = Modifier.clickableRow { message = null }) }
        TwoColumnGrid(listOf(
            { m -> KpiTile("Owed to you", Fmt.amount(balances.filter { it.balance > 0 }.sumOf { it.balance }), m, "TZS", Hf.colors.success) },
            { m -> KpiTile("You owe", Fmt.amount(-balances.filter { it.balance < 0 }.sumOf { it.balance }), m, "TZS") },
        ))
        PrimaryButton("Record a loan or repayment", { sheet = DebtPrefill() }, Modifier.fillMaxWidth(), icon = HfIcons.Plus, height = 52.dp)
        suggestions.take(5).forEach { s ->
            ActionCard(context = "Looks like a repayment · ${Fmt.relativeDay(s.candidate.occurredAt)}", contextIcon = HfIcons.Money, contextTone = Tone.Info,
                quote = s.candidate.evidence.take(200), actions = {
                    PrimaryButton("Record it", { scope.launch {
                        val ledger = io { app.database.finances().entriesFor(s.candidate.sourceId).firstOrNull { it.id == it.groupId && it.reversesId == null }?.id }
                        sheet = DebtPrefill(s.person, s.kind, s.candidate.amount, s.candidate.occurredAt, ledger)
                    } })
                    QuietButton("Not this", { dismissed = dismissed + s.candidate.sourceId; prefs.edit().putStringSet("dismissed", dismissed).apply() })
                }) {
                Text("${if (s.kind == "REPAID_TO_ME") "${s.person} paid you" else "You paid ${s.person}"} TZS ${Fmt.amount(s.candidate.amount)}",
                    style = Hf.type.cardTitle.copy(fontSize = 15.sp), color = Hf.colors.onSurface)
            }
        }
        if (balances.isEmpty()) HfCard(padding = PaddingValues(16.dp)) {
            Text(if (entries.isEmpty()) "Nothing recorded yet. Record money you lend or borrow here, or open a payment in Money and choose \"Record as debt\"."
                else "Everyone is settled.", style = Hf.type.body, color = Hf.colors.muted)
        }
        balances.forEach { b ->
            val mine = entries.filter { (it.personKey ?: Debts.key(it.person)) == (b.personKey ?: Debts.key(b.person)) }
            HfCard(padding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.person, style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                            Text((if (b.balance > 0) "Owes you" else "You owe") + (b.dueAt?.let { " · ${if (b.overdue(now)) "was due" else "due"} ${Fmt.day(it)}" } ?: ""),
                                style = Hf.type.rowSubtitle, color = if (b.overdue(now)) Hf.colors.warning else Hf.colors.muted)
                        }
                        Text(Fmt.amount(kotlin.math.abs(b.balance)), style = Hf.type.figure.copy(fontSize = 18.sp), color = if (b.balance > 0) Hf.colors.success else Hf.colors.onSurface)
                    }
                    mine.take(6).forEach { e ->
                        Row(Modifier.fillMaxWidth().clickableRow { removing = e }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${Fmt.day(e.occurredAt)} · ${Debts.label(e.kind)}" + (e.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                Modifier.weight(1f).padding(vertical = 6.dp), style = Hf.type.rowSubtitle, color = Hf.colors.bodySecondary, maxLines = 2)
                            if (e.ledgerId != null) Text("Payment", Modifier.clickableRow { nav.push(Screen.Transaction, e.ledgerId) }.padding(vertical = 6.dp),
                                style = Hf.type.rowSubtitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Hf.colors.primary)
                            Text(Fmt.amount(kotlin.math.abs(e.amount)), style = Hf.type.rowSubtitle.copy(fontFeatureSettings = "tnum"), color = Hf.colors.bodySecondary)
                        }
                    }
                    if (mine.size > 6) Note("${mine.size - 6} older entries not shown.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Add entry", { sheet = DebtPrefill(b.person, if (b.balance > 0) "REPAID_TO_ME" else "I_REPAID", kotlin.math.abs(b.balance), personKey = b.personKey) })
                        if (b.balance > 0) SecondaryButton("Remind", { reminding = b })
                    }
                }
            }
        }
        Note("These are your own notes. They never change balances in Money. Tap an entry to remove it; \"Payment\" opens the payment it is linked to. People who are settled are hidden.")
    }
    sheet?.let { p -> DebtSheet(p) { msg -> sheet = null; if (msg != null) message = msg } }
    reminding?.let { b -> ReminderSheet(b) { msg -> reminding = null; if (msg != null) message = msg } }
    removing?.let { e -> ConfirmDialog("Remove this entry?", "${Debts.label(e.kind)} TZS ${Fmt.amount(kotlin.math.abs(e.amount))} · ${e.person} · ${Fmt.day(e.occurredAt)}. Money is not affected.",
        "Remove", onConfirm = { removing = null; scope.launch { io { app.database.debts().delete(e.id) }; message = "Entry removed." } }, onDismiss = { removing = null }, destructive = true) }
}
