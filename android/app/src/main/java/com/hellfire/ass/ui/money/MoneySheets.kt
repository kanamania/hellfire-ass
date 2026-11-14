package com.hellfire.ass.ui.money

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.finance.*
import com.hellfire.ass.tasks.TaskTime
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
        options.forEach { HfChip(display(it), { onSelect(it) }, style = if (it == selected) ChipStyle.Selected else ChipStyle.Outline) }
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
    val eligible = accounts.filter { c.provider == "MANUAL" || it.provider == c.provider }
    var accountId by remember { mutableStateOf(eligible.singleOrNull()?.id) }
    var transferId by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(c.kind) }
    var amount by remember { mutableStateOf(c.amount.toString()) }
    var fee by remember { mutableStateOf(c.fee.toString()) }
    var occurred by remember { mutableStateOf(TaskTime.display(c.occurredAt)) }
    var category by remember { mutableStateOf(alias?.category ?: if (c.kind == "INCOME") "Income" else "Other") }
    var description by remember { mutableStateOf(alias?.name ?: if (c.provider == "MANUAL") c.evidence.take(200) else "${Fmt.kind(c.kind)} · ${Fmt.provider(c.provider)}" + (c.merchant?.let { " · Lipa namba $it" } ?: "")) }
    var merchantName by remember { mutableStateOf(alias?.name.orEmpty()) }
    var rememberName by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    HfBottomSheet(onDismiss = { onDone(null) }) {
        Text(if (focus == "category") "Change category" else "Confirm payment", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Text(candidateTitle(c, alias) + " · " + Fmt.provider(c.provider), style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        EvidenceCard(if (c.provider == "MANUAL") "Your cash entry" else "Source · ${Fmt.provider(c.provider)} message", c.evidence.take(1500))
        if (c.reference == null && c.provider != "MANUAL") Note("No reference in this message, so duplicates from other sources can't be detected automatically.")
        GroupLabel("Category")
        ChipPicker(Categories, category) { category = it }
        GroupLabel("Type")
        ChipPicker(listOf("EXPENSE", "INCOME", "OWN_TRANSFER", "REFUND"), kind, Fmt::kind) { kind = it }
        if (eligible.isEmpty()) Banner("Add a ${Fmt.provider(c.provider)} account in Money › Accounts first.", Tone.Warning, null)
        ChoiceField("Account", eligible.firstOrNull { it.id == accountId }, eligible, { it.label }, { accountId = it.id })
        if (kind == "OWN_TRANSFER") ChoiceField("Transfer to", accounts.firstOrNull { it.id == transferId }, accounts.filter { it.id != accountId }, { it.label }, { transferId = it.id })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HfTextField(amount, { amount = it }, "Amount TZS", Modifier.weight(1f), number = true)
            HfTextField(fee, { fee = it }, "Fee TZS", Modifier.weight(1f), number = true)
        }
        HfTextField(occurred, { occurred = it }, "Date and time · yyyy-MM-dd HH:mm")
        HfTextField(description, { description = it }, "Description")
        if (c.merchant != null) {
            HfTextField(merchantName, { merchantName = it }, "Name for Lipa namba ${c.merchant}")
            CheckRow("Remember this name and category", rememberName, onChange = { rememberName = it })
        }
        error?.let { Banner(it, Tone.Danger, null) }
        val ready = accountId != null && (kind != "OWN_TRANSFER" || transferId != null) && !busy
        PrimaryButton("Confirm · post TZS ${amount.trim()}", {
            busy = true
            scope.launch {
                try {
                    val msg = withContext(Dispatchers.IO) {
                        val result = app.finances.approve(c.id, accountId!!, kind, category, description.trim().ifBlank { Fmt.kind(kind) }, transferId,
                            Money.tzs(amount), if (fee.trim() == "0") 0 else Money.tzs(fee), TaskTime.parse(occurred))
                        if (rememberName && c.merchant != null && merchantName.isNotBlank())
                            app.database.finances().merchant(MerchantAlias(c.provider, c.merchant, merchantName.trim(), category, System.currentTimeMillis()))
                        result
                    }
                    onDone(msg)
                } catch (_: Exception) { error = "Not posted. Check the account, amounts (whole shillings), date and duplicate status." }
                finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = ready, height = 52.dp)
        QuietButton("Not a payment", {
            scope.launch { withContext(Dispatchers.IO) { app.database.finances().candidateState(c.id, "REJECTED") }; onDone("Marked as not a payment. Nothing was posted.") }
        }, Modifier.fillMaxWidth())
        Note("Only confirmed payments count toward your totals.")
    }
}

/** Name a Lipa namba merchant. Saving is the owner's confirmation; it changes no ledger entry. */
@Composable
fun MerchantSheet(c: FinanceCandidate, alias: MerchantAlias?, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(alias?.name.orEmpty()) }
    var category by remember { mutableStateOf(alias?.category ?: "Other") }
    HfBottomSheet(onDismiss = { onDone(null) }) {
        Text("Name this merchant", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Text("Lipa namba ${c.merchant} · ${Fmt.provider(c.provider)}", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        HfTextField(name, { name = it }, "Merchant name")
        GroupLabel("Usual category")
        ChipPicker(Categories, category) { category = it }
        Note("Future payments to this number use this name and category. You still confirm each payment.")
        PrimaryButton("Save name", {
            scope.launch {
                withContext(Dispatchers.IO) { app.database.finances().merchant(MerchantAlias(c.provider, c.merchant!!, name.trim(), category, System.currentTimeMillis())) }
                onDone("Saved ${name.trim()} for Lipa namba ${c.merchant}.")
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && c.merchant != null, height = 52.dp)
    }
}

/** Existing cash-expense parser: owner text becomes review candidates, never ledger entries directly. */
@Composable
fun CashSheet(onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(TaskTime.display(System.currentTimeMillis())) }
    val preview = remember(text) { if (text.isBlank()) null else runCatching { CashParser.parse(text) }.getOrNull() }
    var error by remember { mutableStateOf<String?>(null) }
    HfBottomSheet(onDismiss = { onDone(null) }) {
        Text("Add cash spending", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        HfTextField(text, { text = it }, "e.g. bodaboda elfu tatu na chakula elfu saba", singleLine = false, minLines = 2)
        HfTextField(date, { date = it }, "When · yyyy-MM-dd HH:mm (East Africa Time)")
        if (preview != null) KvList(preview.map { it.first to "TZS " + Fmt.amount(it.second) })
        else if (text.isNotBlank()) Note("Write each item as a description and an amount: 15000, 15,000 or elfu tatu. Separate items with na or ;")
        error?.let { Banner(it, Tone.Danger, null) }
        PrimaryButton(if (preview == null) "Add for review" else "Add ${preview.size} for review", {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val parts = CashParser.parse(text)
                        val raw = NotificationSnapshot(UUID.randomUUID().toString(), "owner.cash", TaskTime.parse(date), System.currentTimeMillis(),
                            source = "owner_entry", fields = mapOf("text" to listOf(text)))
                        val normalized = NotificationNormalizer.normalize(raw, app.packageName)!!
                        require(!normalized.securitySensitive) { "Remove authentication content from this cash entry" }
                        app.events.capture(raw, app.packageName)
                        parts.forEachIndexed { index, (description, value) ->
                            app.database.finances().candidate(FinanceCandidate(UUID.randomUUID().toString(), normalized.id, index, "MANUAL", null, "EXPENSE",
                                value, 0, null, description, raw.postedAt))
                        }
                    }
                    onDone("Cash items added to To confirm. They count once you confirm them.")
                } catch (_: Exception) { error = "Could not read that. Use description + 15000, or description + elfu tatu, separated by na or semicolons." }
            }
        }, Modifier.fillMaxWidth(), enabled = preview != null, height = 52.dp)
    }
}
