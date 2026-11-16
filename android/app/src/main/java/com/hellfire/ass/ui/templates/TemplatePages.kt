package com.hellfire.ass.ui.templates

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.finance.FinanceAccount
import com.hellfire.ass.finance.Money
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.templates.ActionTemplate
import com.hellfire.ass.templates.TemplateItem
import com.hellfire.ass.templates.TemplateItems
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.insights.io
import com.hellfire.ass.ui.money.Categories
import com.hellfire.ass.ui.money.ChipPicker
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.launch
import java.util.UUID

private fun typeLabel(type: String) = when (type) { "TASK" -> "Task"; "INCOME" -> "Income"; else -> "Expense" }

private fun typeStyle(type: String) = when (type) {
    "TASK" -> HfIcons.Task to Tone.Primary; "INCOME" -> HfIcons.ArrowIn to Tone.Success; else -> HfIcons.ArrowOut to Tone.Neutral
}

/** "2 tasks · 3 expenses · 1 income". */
private fun counts(items: List<TemplateItem>): String = listOf("TASK" to "task", "EXPENSE" to "expense", "INCOME" to "income").mapNotNull { (type, word) ->
    items.count { it.type == type }.takeIf { it > 0 }?.let { n -> "$n $word" + if (n == 1 || type == "INCOME") "" else "s" }
}.joinToString(" · ")

private fun lineDetail(item: TemplateItem, accounts: List<FinanceAccount>): String = when (item.type) {
    "TASK" -> listOfNotNull(if (item.dueInDays == 0) "same day" else "after ${item.dueInDays} day" + (if (item.dueInDays == 1) "" else "s"), item.dueTime,
        if (item.bill) "bill" else null, item.amount?.let { "TZS ${Fmt.amount(it)}" }).joinToString(" · ")
    else -> listOfNotNull(item.amount?.let { "TZS ${Fmt.amount(it)}" } ?: "amount when used", item.category,
        accounts.firstOrNull { it.id == item.accountId }?.label ?: "no account · goes to To confirm").joinToString(" · ")
}

/** Saved sets of tasks, expenses and income that are added together with one confirmation. */
@Composable
fun TemplatesPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val templates by remember { app.database.templates().templates() }.collectAsStateWithLifecycle(emptyList())
    val accounts by remember { app.database.finances().accounts() }.collectAsStateWithLifecycle(emptyList())
    var using by remember { mutableStateOf<ActionTemplate?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    PushedPage(com.hellfire.ass.ui.tr("Templates"), breadcrumb = "Home") {
        message?.let { Banner(it, modifier = Modifier.clickableRow { message = null }) }
        PrimaryButton("New template", { nav.push(Screen.TemplateEdit, "new") }, Modifier.fillMaxWidth(), icon = HfIcons.Plus, height = 52.dp)
        if (templates.isEmpty()) HfCard(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Add several things in one go", style = Hf.type.cardTitle, color = Hf.colors.onSurface)
                Text("Save a set once, for example \"Month start\": rent, LUKU and school fees as expenses, salary as income, and a task to pay the water bill in 5 days. " +
                    "Later, tap Use, adjust any amounts and add them all together.", style = Hf.type.rowSubtitle.copy(fontSize = 13.sp), color = Hf.colors.muted)
            }
        }
        templates.forEach { t ->
            val items = remember(t.items) { runCatching { TemplateItems.decode(t.items) }.getOrDefault(emptyList()) }
            val out = items.filter { it.type == "EXPENSE" }.sumOf { it.amount ?: 0 }
            val income = items.filter { it.type == "INCOME" }.sumOf { it.amount ?: 0 }
            ActionCard(context = counts(items) + (t.lastUsedAt?.let { " · used ${Fmt.relativeDay(it)}" } ?: ""), contextIcon = HfIcons.Bolt, contextTone = Tone.Primary, actions = {
                PrimaryButton("Use", { using = t }, enabled = items.isNotEmpty())
                SecondaryButton(com.hellfire.ass.ui.tr("Edit"), { nav.push(Screen.TemplateEdit, t.id) })
            }) {
                Text(t.name, style = Hf.type.cardTitle.copy(fontSize = 16.sp), color = Hf.colors.onSurface)
                val totals = listOfNotNull(out.takeIf { it > 0 }?.let { "Out TZS ${Fmt.amount(it)}" }, income.takeIf { it > 0 }?.let { "In TZS ${Fmt.amount(it)}" }).joinToString(" · ")
                if (totals.isNotEmpty()) Text(totals, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                Text(items.take(4).joinToString(" · ") { it.title } + if (items.size > 4) " · +${items.size - 4}" else "", style = Hf.type.rowSubtitle, color = Hf.colors.muted, maxLines = 2)
            }
        }
        if (templates.isNotEmpty()) Note("You can also say or type \"use template <name>\" in Ask. Nothing is added until you confirm.")
    }
    using?.let { t -> TemplateRunSheet(t, accounts) { msg -> using = null; if (msg != null) message = msg } }
}

/** Create or change a template. [id] is "new" for a new one. */
@Composable
fun TemplateEditPage(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val accounts by remember { app.database.finances().accounts() }.collectAsStateWithLifecycle(emptyList())
    var existing by remember { mutableStateOf<ActionTemplate?>(null) }
    var name by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<TemplateItem>() }
    var type by remember { mutableStateOf("EXPENSE") }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Other") }
    var accountId by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf("0") }
    var time by remember { mutableStateOf("09:00") }
    var bill by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id != "new") io { app.database.templates().find(id) }?.let { saved ->
            existing = saved; name = saved.name
            items.clear(); items.addAll(runCatching { TemplateItems.decode(saved.items) }.getOrDefault(emptyList()))
        }
    }
    fun load(item: TemplateItem) {
        type = item.type; title = item.title; amount = item.amount?.toString().orEmpty(); category = item.category ?: "Other"
        accountId = item.accountId; days = item.dueInDays.toString(); time = item.dueTime; bill = item.bill
    }
    PushedPage(if (id == "new") "New template" else "Edit template", breadcrumb = "Templates") {
        HfTextField(name, { name = it.take(80) }, "Template name, e.g. Month start")
        if (items.isNotEmpty()) ListCard(title = "Lines", count = items.size) {
            items.forEachIndexed { i, item ->
                if (i > 0) ListDivider()
                val (icon, tone) = typeStyle(item.type)
                ListRow(item.title, subtitle = "${typeLabel(item.type)} · ${lineDetail(item, accounts)}", icon = icon, tone = tone,
                    trailing = { QuietButton("Remove", { items.removeAt(i) }) }, onClick = { load(item); items.removeAt(i) })
            }
            Spacer(Modifier.height(6.dp))
        }
        SectionCard(if (items.isEmpty()) "First line" else "Add a line") {
            ChipPicker(TemplateItems.types, type, ::typeLabel) { type = it; if (it == "INCOME") category = "Income" else if (category == "Income") category = "Other" }
            HfTextField(title, { title = it.take(200) }, if (type == "TASK") "Task, e.g. Pay the water bill" else "Description, e.g. Rent")
            HfTextField(amount, { amount = it.filter(Char::isDigit) }, if (type == "TASK") "Amount TZS (optional, shown on the task)" else "Amount TZS (leave empty to enter each time)", number = true)
            if (type == "TASK") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HfTextField(days, { days = it.filter(Char::isDigit).take(4) }, "Due after days", Modifier.weight(1f), number = true)
                    HfTextField(time, { time = it.take(5) }, "At HH:mm", Modifier.weight(1f))
                }
                CheckRow(com.hellfire.ass.ui.tr("Bill to pay"), bill, onChange = { bill = it })
                Note("0 days means the day you use the template.")
            } else {
                ChoiceField("Category", category, Categories, { it }, { category = it })
                ChoiceField("Account", accounts.firstOrNull { it.id == accountId }, accounts, { it.label }, { accountId = it.id }, placeholder = "None · confirm later")
                if (accountId == null) Note("Without an account the line waits in Money › To confirm each time.")
                else QuietButton("Use no account", { accountId = null })
            }
            error?.let { Banner(it, Tone.Danger, null) }
            SecondaryButton("Add line", {
                val item = TemplateItem(type, title.trim(), amount.toLongOrNull()?.takeIf { it > 0 }, if (type == "TASK") null else category,
                    if (type == "TASK") null else accountId, if (type == "TASK") days.toIntOrNull() ?: 0 else 0, if (type == "TASK") time else "09:00", type == "TASK" && bill)
                error = runCatching { TemplateItems.validate(item); require(items.size < TemplateItems.MAX) { "A template holds up to ${TemplateItems.MAX} lines" } }.exceptionOrNull()?.message
                if (error == null) { items.add(item); title = ""; amount = "" }
            }, Modifier.fillMaxWidth(), enabled = title.isNotBlank(), icon = HfIcons.Plus)
        }
        PrimaryButton("Save template", {
            scope.launch {
                val saved = io {
                    val now = System.currentTimeMillis()
                    app.database.templates().save(ActionTemplate(existing?.id ?: UUID.randomUUID().toString(), name.trim(), TemplateItems.encode(items.toList()),
                        existing?.createdAt ?: now, now, existing?.lastUsedAt, existing?.uses ?: 0))
                }
                if (saved == null) error = "The template could not be saved." else nav.pop()
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && items.isNotEmpty(), height = 52.dp)
        if (title.isNotBlank()) Note("The line you are typing is not in the template yet. Tap Add line first.")
        if (existing != null) QuietButton("Delete template", { confirmDelete = true }, Modifier.fillMaxWidth())
        Note("Tap a line to change it. Saving a template adds nothing to Money or Tasks; that happens when you use it.")
    }
    if (confirmDelete) ConfirmDialog("Delete ${existing?.name}?", "Tasks and payments already added from it stay as they are.", "Delete", onConfirm = {
        confirmDelete = false
        scope.launch { io { app.database.templates().delete(id) }; nav.pop() }
    }, onDismiss = { confirmDelete = false }, destructive = true)
}

/** Review step before a template is applied: pick the date, untick lines, adjust amounts, then add them all. */
@Composable
fun TemplateRunSheet(template: ActionTemplate, accounts: List<FinanceAccount>, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val items = remember(template.id) { runCatching { TemplateItems.decode(template.items) }.getOrDefault(emptyList()) }
    val selected = remember(template.id) { mutableStateListOf<Boolean>().apply { addAll(items.map { true }) } }
    val amounts = remember(template.id) { mutableStateListOf<String>().apply { addAll(items.map { it.amount?.toString().orEmpty() }) } }
    var date by remember { mutableStateOf(TaskTime.display(System.currentTimeMillis())) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val at = runCatching { TaskTime.parse(date) }.getOrNull()
    val chosen = items.indices.filter { selected[it] }
    fun total(type: String) = chosen.filter { items[it].type == type }.sumOf { amounts[it].toLongOrNull() ?: 0 }
    HfBottomSheet({ onDone(null) }) {
        Text("Use ${template.name}", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        DateTimeField(date, { date = it }, "When · East Africa Time")
        items.forEachIndexed { i, item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CheckRow("${typeLabel(item.type)} · ${item.title}", selected[i], onChange = { selected[i] = it })
                if (selected[i]) {
                    if (item.type == "TASK") Note("Due ${at?.let { Fmt.weekdayDayTime(TemplateItems.dueAt(item, it)) } ?: "—"}" + (item.amount?.let { " · TZS ${Fmt.amount(it)}" } ?: ""))
                    else {
                        HfTextField(amounts[i], { amounts[i] = it.filter(Char::isDigit) }, "Amount TZS", number = true)
                        Note(listOfNotNull(item.category, accounts.firstOrNull { it.id == item.accountId }?.label ?: "no account · will wait in To confirm").joinToString(" · "))
                    }
                }
            }
        }
        val summary = listOfNotNull(total("EXPENSE").takeIf { it > 0 }?.let { "Out TZS ${Fmt.amount(it)}" }, total("INCOME").takeIf { it > 0 }?.let { "In TZS ${Fmt.amount(it)}" },
            chosen.count { items[it].type == "TASK" }.takeIf { it > 0 }?.let { if (it == 1) "1 task" else "$it tasks" }).joinToString(" · ")
        if (summary.isNotEmpty()) Text(summary, style = Hf.type.rowTitle, color = Hf.colors.onSurface)
        error?.let { Banner(it, Tone.Danger, null) }
        val ready = at != null && chosen.isNotEmpty() && chosen.all { items[it].type == "TASK" || (amounts[it].toLongOrNull() ?: 0) > 0 } && !busy
        PrimaryButton(if (chosen.size == 1) "Add 1 item" else "Add ${chosen.size} items", {
            busy = true
            scope.launch {
                val result = io {
                    val lines = chosen.map { i -> items[i].let { if (it.type == "TASK") it else it.copy(amount = Money.tzs(amounts[i])) } }
                    app.templates.run(template, lines, at!!).also { if (it.tasks > 0) TaskScheduler.restore(app) }
                }
                busy = false
                if (result == null) error = "Not added. Check the amounts and date, and that the accounts still exist." else onDone("${template.name}: ${result.message}.")
            }
        }, Modifier.fillMaxWidth(), enabled = ready, height = 52.dp)
        Note("Payments with an account are added to Money straight away with this as their record. Tasks get reminders. You can reverse a payment or mark a task done afterwards.")
    }
}
