package com.hellfire.ass.ui.money

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.finance.*
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.ContactChoiceStateSaver
import com.hellfire.ass.ui.Fmt
import com.hellfire.ass.ui.LocalEnv
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Chip picker for one value. */
@Composable
fun ChipPicker(options: List<String>, selected: String?, display: (String) -> String = { it }, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { HfChip(com.hellfire.ass.ui.tr(display(it)), { onSelect(it) }, style = if (it == selected) ChipStyle.Selected else ChipStyle.Outline) }
    }
}

/**
 * The confirmation step for a payment candidate: original evidence, editable parsed values,
 * then one "Confirm" that posts to the ledger. Nothing is posted before that tap.
 */
@Composable
fun CandidateReviewSheet(c: FinanceCandidate, accounts: List<FinanceAccount>, aliases: List<MerchantAlias>, focus: String, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val alias = aliases.firstOrNull { it.provider == c.provider && it.number == c.merchant }
    val eligible = accounts
    var accountId by rememberSaveable(c.id) { mutableStateOf(c.accountId?.takeIf { id -> eligible.any { it.id == id } } ?: eligible.singleOrNull()?.id) }
    var transferId by rememberSaveable(c.id) { mutableStateOf<String?>(null) }
    var kind by rememberSaveable(c.id) { mutableStateOf(c.kind) }
    var amount by rememberSaveable(c.id) { mutableStateOf(c.amount.toString()) }
    var fee by rememberSaveable(c.id) { mutableStateOf(c.fee.toString()) }
    var occurred by rememberSaveable(c.id) { mutableStateOf(TaskTime.display(c.occurredAt)) }
    var category by rememberSaveable(c.id) { mutableStateOf(c.category ?: alias?.category ?: if (c.kind == "INCOME") "Income" else "Other") }
    var description by rememberSaveable(c.id) { mutableStateOf(alias?.name ?: if (c.provider == "MANUAL") c.evidence.take(200) else Fmt.kind(c.kind) + (c.merchant?.let { " · Lipa namba $it" } ?: "")) }
    var merchantName by rememberSaveable(c.id) { mutableStateOf(alias?.name.orEmpty()) }
    var picked by rememberSaveable(c.id, saver = ContactChoiceStateSaver) { mutableStateOf<com.hellfire.ass.contacts.ContactChoice?>(null) }
    var rememberName by rememberSaveable(c.id) { mutableStateOf(false) }
    var rememberChoice by rememberSaveable(c.id) { mutableStateOf(false) }
    var choiceKey by rememberSaveable(c.id) { mutableStateOf<String?>(null) }
    var suggestion by rememberSaveable(c.id) { mutableStateOf(false) }
    var defaultsLoaded by rememberSaveable(c.id) { mutableStateOf(false) }
    LaunchedEffect(c.id) {
        if (!defaultsLoaded) withContext(Dispatchers.IO) {
            val key = com.hellfire.ass.finance.ReviewChoices.key(app, c)
            choiceKey = key
            (app.skills.payment(key, c.id, c.sourceId, c.accountId == null || c.category == null)?.second ?: runCatching {
                com.hellfire.ass.finance.ReviewChoices.read(app, key)?.let { legacy ->
                    com.hellfire.ass.finance.ReviewChoices.save(app, key, legacy.first, legacy.second)
                    app.skills.payment(key, c.id, c.sourceId, c.accountId == null || c.category == null)?.second
                }
            }.getOrNull())?.takeIf { saved -> accounts.any { it.id == saved.first } }?.let { saved ->
                if (c.accountId == null) accountId = saved.first
                if (c.category == null) category = saved.second
                suggestion = true
            }
            defaultsLoaded = true
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    HfBottomSheet(onDismiss = { onDone(null) }) {
        Text(if (focus == "category") "Change category" else "Confirm payment", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Text(candidateTitle(c, alias), style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        EvidenceCard(if (c.provider == "MANUAL") "Your cash entry" else "Source message", c.evidence.take(1500))
        if (c.reference == null && c.provider != "MANUAL") Note("No reference in this message, so duplicates from other sources can't be detected automatically.")
        Note("Why suggested: a payment parser recognized ${c.provider} ${c.kind.lowercase()} wording and kept this source for review. Parsed fields can be wrong.")
        choiceKey?.let { Note("Sender/merchant key: $it") }
        GroupLabel(com.hellfire.ass.ui.tr("Category"))
        ChipPicker(Categories, category) { category = it }
        GroupLabel(com.hellfire.ass.ui.tr("Type"))
        ChipPicker(listOf("EXPENSE", "INCOME", "OWN_TRANSFER", "REFUND"), kind, Fmt::kind) { kind = it }
        if (eligible.isEmpty()) Banner("Add an account in Money › Accounts first.", Tone.Warning, null)
        ChoiceField("Account", eligible.firstOrNull { it.id == accountId }, eligible, { it.label }, { accountId = it.id })
        if (kind == "OWN_TRANSFER") ChoiceField("Transfer to", accounts.firstOrNull { it.id == transferId }, accounts.filter { it.id != accountId }, { it.label }, { transferId = it.id })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HfTextField(amount, { amount = it }, "Amount TZS", Modifier.weight(1f), number = true)
            HfTextField(fee, { fee = it }, "Fee TZS", Modifier.weight(1f), number = true)
        }
        DateTimeField(occurred, { occurred = it })
        HfTextField(description, { description = it }, "Description")
        if (c.merchant != null) {
            ContactPersonField(merchantName, { merchantName = it; picked = null; rememberName = it.isNotBlank()
                aliases.firstOrNull { a -> a.name == it }?.let { a -> category = a.category; description = a.name } }, "Name for Lipa namba ${c.merchant}", aliases.map { it.name }, onPick = {
                picked = it; merchantName = it.displayName; rememberName = true
            })
            SavedContactStatus(com.hellfire.ass.insights.People.namedKey(merchantName))
            CheckRow(com.hellfire.ass.ui.tr("Remember this name and category"), rememberName, onChange = { rememberName = it })
        }
        if (suggestion) Note(com.hellfire.ass.ui.tr("Account/category suggested from your saved choice for this sender or merchant. Check them before confirming."))
        CheckRow(com.hellfire.ass.ui.tr("Remember my account and category for this sender/merchant"), rememberChoice, { rememberChoice = it })
        error?.let { Banner(it, Tone.Danger, null) }
        val ready = accountId != null && (kind != "OWN_TRANSFER" || transferId != null) && !busy && defaultsLoaded
        PrimaryButton("Confirm · post TZS ${amount.trim()}", {
            busy = true
            scope.launch {
                try {
                    val msg = withContext(Dispatchers.IO) {
                        app.database.withTransaction {
                            val personKey = when {
                                merchantName.isBlank() -> null
                                picked != null || rememberName -> com.hellfire.ass.contacts.ContactLinks(app.database).save(picked ?: com.hellfire.ass.contacts.ContactChoice(merchantName))
                                else -> app.database.contacts().find(com.hellfire.ass.insights.People.namedKey(merchantName))?.personKey
                            }
                            val result = app.finances.approve(c.id, accountId!!, kind, category, description.trim().ifBlank { Fmt.kind(kind) }, transferId,
                                Money.tzs(amount), if (fee.trim() == "0") 0 else Money.tzs(fee), TaskTime.parse(occurred), personKey = personKey)
                            if (rememberName && c.merchant != null && merchantName.isNotBlank())
                                app.database.finances().merchant(MerchantAlias(c.provider, c.merchant, merchantName.trim(), category, System.currentTimeMillis()))
                            result
                        }
                    }
                    val learningSaved = withContext(Dispatchers.IO) { runCatching {
                        if (!rememberChoice && (c.category != category || c.accountId != accountId)) app.skills.suggest("Payment choices for ${c.provider}", "PAYMENT_DEFAULT", requireNotNull(choiceKey),
                            org.json.JSONObject().put("account", accountId).put("category", category).toString(), "You confirmed this account/category while reviewing a payment. Review before applying to later matches.", c.sourceId)
                    }.isSuccess }
                    val savedDefaults = if (rememberChoice) withContext(Dispatchers.IO) { runCatching { choiceKey?.let { com.hellfire.ass.finance.ReviewChoices.save(app, it, accountId!!, category) } }.isSuccess } else true
                    onDone(msg + (if (!learningSaved) " Skill suggestion was not saved." else "") + if (!savedDefaults) " Defaults could not be saved; the payment itself was posted." else "")
                } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); error = "Not posted. Check the account, amounts (whole shillings), date and duplicate status." }
                finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = ready, height = 52.dp)
        QuietButton(com.hellfire.ass.ui.tr("Not a payment"), {
            scope.launch { withContext(Dispatchers.IO) {
                app.finances.reject(c.id)
                app.skills.feedbackFor(app, "IGNORE_FINANCE", c.sourceId, "You marked this captured item as not a payment. An exact-match rule can avoid repeating this false suggestion.")
            }; onDone("Marked as not a payment. Nothing was posted.") }
        }, Modifier.fillMaxWidth())
        Note(com.hellfire.ass.ui.tr("Only confirmed payments count toward your totals."))
    }
}

/** Name a Lipa namba merchant. Saving is the owner's confirmation; it changes no ledger entry. */
@Composable
fun MerchantSheet(provider: String, number: String, alias: MerchantAlias?, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(alias?.name.orEmpty()) }
    var picked by remember { mutableStateOf<com.hellfire.ass.contacts.ContactChoice?>(null) }
    var category by remember { mutableStateOf(alias?.category ?: "Other") }
    val known by remember { app.database.finances().aliases() }.collectAsStateWithLifecycle(emptyList())
    HfBottomSheet(onDismiss = { onDone(null) }) {
        Text(com.hellfire.ass.ui.tr("Name this merchant"), style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Text("Lipa namba $number", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        ContactPersonField(name, { name = it; picked = null; known.firstOrNull { alias -> alias.name == it }?.let { category = it.category } }, "Merchant name", known.map { it.name }, onPick = { picked = it; name = it.displayName })
        SavedContactStatus(com.hellfire.ass.insights.People.namedKey(name))
        GroupLabel(com.hellfire.ass.ui.tr("Usual category"))
        ChipPicker(Categories, category) { category = it }
        Note("Future payments to this number use this name and category. You still confirm each payment.")
        PrimaryButton(com.hellfire.ass.ui.tr("Save name"), {
            scope.launch {
                withContext(Dispatchers.IO) { app.database.withTransaction {
                    com.hellfire.ass.contacts.ContactLinks(app.database).save(picked ?: com.hellfire.ass.contacts.ContactChoice(name))
                    app.database.finances().merchant(MerchantAlias(provider, number, name.trim(), category, System.currentTimeMillis()))
                } }
                onDone("Saved ${name.trim()} for Lipa namba $number.")
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && name.trim().length <= 200, height = 52.dp)
    }
}

/** Existing cash-expense parser: owner text becomes review candidates, never ledger entries directly. */
@Composable
fun CashSheet(onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val accounts by remember { app.database.finances().accounts() }.collectAsStateWithLifecycle(emptyList())
    var text by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(TaskTime.display(System.currentTimeMillis())) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf("Other") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(accounts) {
        if (accounts.none { it.id == accountId }) accountId = accounts.singleOrNull()?.id
    }
    val preview = remember(text) { if (text.isBlank()) null else runCatching { CashParser.parse(text) }.getOrNull() }
    var error by remember { mutableStateOf<String?>(null) }
    HfBottomSheet(onDismiss = { onDone(null) }) {
        Text(com.hellfire.ass.ui.tr("Add cash spending"), style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        HfTextField(text, { text = it }, "e.g. bodaboda elfu tatu na chakula elfu saba", singleLine = false, minLines = 2)
        DateTimeField(date, { date = it }, "When · East Africa Time")
        ChoiceField("Category", category, Categories, { it }, { category = it })
        ChoiceField("Account", accounts.firstOrNull { it.id == accountId }, accounts, { it.label }, { accountId = it.id })
        if (accounts.isEmpty()) Note(com.hellfire.ass.ui.tr("Add an account in Money › Accounts to record spending."))
        if (preview != null) KvList(preview.map { it.first to "TZS " + Fmt.amount(it.second) })
        else if (text.isNotBlank()) Note("Write each item as a description and an amount: 15000, 15,000 or elfu tatu. Separate items with na or ;")
        error?.let { Banner(it, Tone.Danger, null) }
        PrimaryButton(if (preview == null) "Add for review" else "Add ${preview.size} for review", {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        requireNotNull(app.database.finances().account(requireNotNull(accountId)))
                        val parts = CashParser.parse(text)
                        val raw = NotificationSnapshot(UUID.randomUUID().toString(), "owner.cash", TaskTime.parse(date), System.currentTimeMillis(),
                            source = "owner_entry", fields = mapOf("text" to listOf(text)))
                        val normalized = NotificationNormalizer.normalize(raw, app.packageName)!!
                        require(!normalized.securitySensitive) { "Remove authentication content from this cash entry" }
                        app.events.capture(raw, app.packageName)
                        app.database.withTransaction {
                            parts.forEachIndexed { index, (description, value) ->
                                app.database.finances().candidate(FinanceCandidate(UUID.randomUUID().toString(), normalized.id, index, "MANUAL", null, "EXPENSE",
                                    value, 0, null, description, raw.postedAt, accountId = accountId, category = category))
                            }
                        }
                    }
                    onDone("Cash items added to To confirm. They count once you confirm them.")
                } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); error = "Could not save. Check the account and use a description + amount for each item." }
                finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = preview != null && accounts.any { it.id == accountId } && !busy, height = 52.dp)
    }
}
