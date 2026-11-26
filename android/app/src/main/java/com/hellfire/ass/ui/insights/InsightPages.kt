package com.hellfire.ass.ui.insights

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.insights.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.contacts.ContactChoice
import com.hellfire.ass.contacts.ContactLinks
import com.hellfire.ass.contacts.PersonContact
import com.hellfire.ass.finance.LedgerEntry
import com.hellfire.ass.ui.money.ownerStatement

/** Runs [block] off the main thread; a failure is logged and returns null so the screen can say so. */
internal suspend fun <T> io(block: suspend () -> T): T? = try { withContext(Dispatchers.IO) { block() } }
    catch (e: CancellationException) { throw e } catch (caught: Exception) { Diagnostics.warn(caught); null }

internal fun channelIcon(channel: String) = when (channel) { "Email" -> HfIcons.Mail; "SMS" -> HfIcons.ChatLines; else -> HfIcons.Chat }

/** Morning brief and evening wrap-up: one page built from what is saved on this phone. */
@Composable
fun BriefPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var mode by rememberSaveable { mutableStateOf(if (Briefs.isEvening(System.currentTimeMillis())) "Evening" else "Morning") }
    var brief by remember { mutableStateOf<Brief?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(mode) {
        brief = null; failed = false
        brief = io { Briefs.load(app, evening = mode == "Evening") }
        failed = brief == null
    }
    PushedPage(if (mode == "Evening") "Evening wrap-up" else "Morning brief", breadcrumb = "Home") {
        SegmentedControl(listOf("Morning", "Evening"), mode, { mode = it })
        val b = brief
        when {
            failed -> Banner("The brief could not be read. Check storage and try again.", Tone.Danger, null)
            b == null -> Note("Reading what is saved on this phone…")
            else -> {
                Text(b.headline, style = Hf.type.reportTitle, color = Hf.colors.onSurface)
                b.sections.forEach { section ->
                    ListCard(title = section.title) {
                        section.lines.forEachIndexed { i, line ->
                            if (i > 0) ListDivider(16.dp)
                            ListRow(line.text, subtitle = line.detail, chevron = line.link != null,
                                trailing = if (line.warn) ({ StatusPill("Check", Tone.Warning) }) else null,
                                onClick = line.link?.let { link -> { nav.open(Uri.parse(link)) } })
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Note("Built on this phone from payments you confirmed, your tasks and saved messages. The morning notification time is in Settings › Reminders.")
            }
        }
    }
}

/** Who the owner owes a reply, and who owes the owner one, across email, WhatsApp and SMS. */
@Composable
fun WaitingPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var version by remember { mutableIntStateOf(0) }
    var waiting by remember { mutableStateOf<WaitingOn?>(null) }
    LaunchedEffect(version) {
        val now = System.currentTimeMillis()
        waiting = io { Waiting.compute(loadInteractions(app, now - Waiting.WINDOW), loadAliases(app), now, WaitingPrefs.dismissed(app), loadIdentities(app)) } ?: WaitingOn(emptyList(), emptyList())
    }
    @Composable fun rows(items: List<WaitingItem>, empty: String) {
        if (items.isEmpty()) { HfCard(padding = PaddingValues(16.dp)) { Text(empty, style = Hf.type.body, color = Hf.colors.muted) }; return }
        ListCard {
            items.forEachIndexed { i, item ->
                if (i > 0) ListDivider()
                ListRow(item.name, subtitle = "${item.channel} · ${Fmt.age(item.at)} ago · ${item.preview.take(90)}", icon = channelIcon(item.channel), tone = Tone.Neutral,
                    trailing = { Column {
                        QuietButton(stringResource(R.string.ui_no_reply_needed), { WaitingPrefs.dismiss(app, item.reviewKey, item.at); version++ })
                        QuietButton(stringResource(R.string.ui_tomorrow), { WaitingPrefs.snooze(app, item.reviewKey, System.currentTimeMillis() + 86_400_000); version++ })
                    } },
                    onClick = { item.messageId?.let { nav.push(Screen.Message, it) } ?: nav.push(Screen.Source, item.sourceId) })
            }
        }
    }
    PushedPage(stringResource(R.string.ui_waiting_on), breadcrumb = "Inbox") {
        val w = waiting
        if (w == null) { Note("Reading your recent messages…"); return@PushedPage }
        SectionHeader("You owe a reply", trailing = w.iOwe.size.toString())
        rows(w.iOwe.sortedByDescending { it.at }, "Nobody is waiting on you.")
        SectionHeader("Waiting to hear back", trailing = w.theyOwe.size.toString())
        rows(w.theyOwe.sortedByDescending { it.at }, "Nothing you asked is unanswered.")
        ListCard { ListRow(stringResource(R.string.ui_people), subtitle = "Everything with one person in one place", icon = HfIcons.Chat, chevron = true, onClick = { nav.push(Screen.People) }) }
        Note("From the last 14 days. An unanswered request appears after 4 hours; \"waiting\" means you asked something a day ago and got no answer. " +
            "Groups, short codes and automated senders are left out. No reply needed clears that request. Tomorrow snoozes the thread; separate email threads stay independent.")
    }
}

@Composable
fun PeoplePage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        people = io {
            val conversations = People.build(loadInteractions(app, System.currentTimeMillis() - 180L * 86_400_000), loadAliases(app), loadIdentities(app))
            val saved = app.database.contacts().all().filter { c -> conversations.none { it.key == c.personKey } }
                .map { Person(it.personKey, it.displayName, true, emptyMap(), 0, 0, 0, emptySet()) }
            conversations + saved
        } ?: emptyList()
    }
    PushedPage(stringResource(R.string.ui_people), breadcrumb = "Home") {
        val all = people
        if (all == null) { Note("Reading your messages from the last 6 months…"); return@PushedPage }
        HfTextField(query, { query = it }, "Search a name, number or address")
        val shown = all.filter { p -> query.isBlank() || p.name.contains(query.trim(), true) || p.handles.values.any { it.contains(query.trim(), true) } }.take(150)
        if (shown.isEmpty()) HfCard(padding = PaddingValues(16.dp)) {
            Text(if (all.isEmpty()) "No conversations saved yet. People appear here once SMS, email or WhatsApp capture is on." else "No one matches.", style = Hf.type.body, color = Hf.colors.muted)
        } else ListCard {
            shown.forEachIndexed { i, p ->
                if (i > 0) ListDivider()
                ListRow(p.name, subtitle = if (p.lastAt == 0L) "Linked person · no saved conversations" else "${p.channels.sorted().joinToString()} · last ${Fmt.relativeDay(p.lastAt)} · ${p.incoming} in, ${p.outgoing} out",
                    tileText = p.name.firstOrNull { it.isLetter() }?.uppercase() ?: "#", tone = if (p.named) Tone.Primary else Tone.Neutral, chevron = true,
                    onClick = { nav.push(Screen.Person, p.key) })
            }
        }
        Note("Built from saved SMS, email and WhatsApp messages. Open someone and give them a name to join their number and email into one person.")
    }
}

private class PersonView(val person: Person, val timeline: List<Interaction>, val tasks: List<AssistantTask>, val debt: DebtBalance?, val media: List<MediaItem>,
    val payments: List<LedgerEntry>, val debts: List<DebtEntry>)

/** One person: every message across channels, what was promised, money between you, and matching recordings. */
@Composable
fun PersonPage(key: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<PersonView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<ContactChoice?>(null) }
    var managing by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<PersonContact?>(null) }
    var separateHandle by remember { mutableStateOf<String?>(null) }
    var separateName by remember { mutableStateOf("") }
    var moveTasks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var movePayments by remember { mutableStateOf<Set<String>>(emptySet()) }
    var moveDebts by remember { mutableStateOf<Set<String>>(emptySet()) }
    var otherDetails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var others by remember { mutableStateOf<List<PersonContact>>(emptyList()) }
    LaunchedEffect(managing) { if (managing) {
        others = io { app.database.contacts().all().filter { it.personKey != key } }.orEmpty()
        otherDetails = io { app.database.people().all().groupBy { it.personKey ?: People.namedKey(it.name) }.mapValues { (_, list) -> list.take(2).joinToString { Handles.label(it.handle) } } }.orEmpty()
    } }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        view = io {
            val aliases = loadAliases(app)
            val identities = loadIdentities(app)
            val all = loadInteractions(app, System.currentTimeMillis() - 365L * 86_400_000)
            val mine = all.filter { People.personKey(it.handle, aliases, identities) == key }
            val saved = app.database.contacts().find(key)
            val person = People.build(mine, aliases, identities).firstOrNull()
                ?: saved?.let { Person(key, it.displayName, true, emptyMap(), 0, 0, 0, emptySet()) } ?: return@io null
            val sources = mine.map { it.sourceId }.toSet()
            val digits = person.handles.keys.filter { it.startsWith("tel:") }.map { it.removePrefix("tel:") }
            PersonView(person, mine.sortedByDescending { it.at }.take(60),
                app.database.tasks().all().filter { it.personKey == key || (it.personKey == null && it.sourceId in sources && it.state in setOf("ACTIVE", "REVIEW")) }.sortedBy { it.dueAt },
                Debts.balances(app.database.debts().all()).firstOrNull { (it.personKey ?: People.namedKey(it.person)) == key },
                app.database.media().all().filter { m -> (person.named && m.name.contains(person.name, true)) || digits.any { m.name.contains(it) } }.take(10),
                app.database.finances().allEntries().filter { it.personKey == key }.sortedByDescending { it.occurredAt },
                app.database.debts().all().filter { (it.personKey ?: People.namedKey(it.person)) == key })
        }
        name = view?.person?.takeIf { it.named }?.name.orEmpty()
        loaded = true
    }
    val v = view
    PushedPage(v?.person?.name ?: "Person", breadcrumb = "People") {
        if (v == null) { Note(if (loaded) "No saved messages with this person in the last year." else "Reading messages…"); return@PushedPage }
        val p = v.person
        message?.let { Banner(it) }
        KvList(listOfNotNull(
            "Reachable on" to p.handles.values.distinct().joinToString(),
            "Last contact" to if (p.lastAt == 0L) "No saved messages" else Fmt.dayTime(p.lastAt),
            "Messages" to "${p.incoming} from them · ${p.outgoing} from you",
            v.debt?.let { "Money" to if (it.balance > 0) "Owes you TZS ${Fmt.amount(it.balance)}" else "You owe TZS ${Fmt.amount(-it.balance)}" },
        ))
        SavedContactStatus(key)
        SectionCard(if (p.named) "Name" else "Give this person a name") {
            ContactPersonField(name, { name = it; picked = null }, stringResource(R.string.ui_name), onPick = { picked = it; name = it.displayName })
            Note("A contact joins all its numbers and email addresses into this person. Choose the correct saved person when names repeat.")
            PrimaryButton(stringResource(R.string.ui_save_name), {
                val chosen = name.trim()
                scope.launch {
                    val saved = io { ContactLinks(app.database).namePerson(key,
                        picked ?: ContactChoice(chosen), p.handles.keys, p.name) }
                    if (saved == null) message = "The name could not be saved."
                    else { nav.pop(); nav.push(Screen.Person, saved) }
                }
            }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && name.trim().length <= 200)
        }
        SecondaryButton("Manage linked identities…", { managing = true })
        if (v.tasks.isNotEmpty()) {
            SectionHeader(stringResource(R.string.ui_tasks), trailing = v.tasks.size.toString())
            ListCard { v.tasks.forEachIndexed { i, t ->
                if (i > 0) ListDivider()
                ListRow(t.title.take(100), subtitle = (when (t.state) { "REVIEW" -> "Found in a message · not added yet"; "DONE" -> "Done"; "DISMISSED" -> "Dismissed"; else -> "Due ${Fmt.dayTime(t.dueAt)}" }) +
                    when (t.direction) { "I_OWE" -> " · you promised"; "OWED_TO_ME" -> " · they promised"; else -> "" },
                    icon = HfIcons.Task, tone = if (t.dueAt < System.currentTimeMillis()) Tone.Warning else Tone.Primary, chevron = true, onClick = { nav.push(Screen.Task, t.id) })
            } }
        }
        if (v.payments.isNotEmpty()) {
            SectionHeader("Payments", trailing = v.payments.size.toString())
            ListCard { v.payments.forEachIndexed { i, payment ->
                if (i > 0) ListDivider()
                ListRow("${Fmt.signed(payment.amount)} · ${payment.description}", subtitle = Fmt.dayTime(payment.occurredAt), chevron = true,
                    onClick = { nav.push(Screen.Transaction, payment.id) })
            } }
        }
        if (v.debts.isNotEmpty()) {
            SectionHeader("Loans and repayments", trailing = v.debts.size.toString())
            ListCard { v.debts.forEachIndexed { i, debt ->
                if (i > 0) ListDivider()
                ListRow("${Debts.label(debt.kind)} · TZS ${Fmt.amount(kotlin.math.abs(debt.amount))}", subtitle = Fmt.dayTime(debt.occurredAt) + " · " + debt.note,
                    onClick = { debt.ledgerId?.let { nav.push(Screen.Transaction, it) } ?: nav.push(Screen.Debts) })
            } }
        }
        if (v.debt != null) ListCard { ListRow("Loans and repayments", subtitle = "Open the debt record", icon = HfIcons.Money, chevron = true, onClick = { nav.push(Screen.Debts) }) }
        if (v.media.isNotEmpty()) {
            SectionHeader(stringResource(R.string.ui_recordings), trailing = v.media.size.toString())
            ListCard { v.media.forEachIndexed { i, m ->
                if (i > 0) ListDivider()
                ListRow(m.name.take(80), subtitle = Fmt.dayTime(m.recordedAt ?: m.importedAt) + (m.summary.takeIf { it.isNotBlank() }?.let { " · ${it.take(80)}" } ?: ""),
                    icon = HfIcons.Waveform, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.MediaItem, m.id) })
            } }
        }
        SectionHeader("Conversation", trailing = "latest ${v.timeline.size}")
        ListCard { v.timeline.forEachIndexed { i, m ->
            if (i > 0) ListDivider()
            ListRow(m.preview.ifBlank { "(no text)" }.take(140), subtitle = "${if (m.incoming) "From them" else "From you"} · ${m.channel} · ${Fmt.dayTime(m.at)}",
                icon = if (m.incoming) HfIcons.ArrowIn else HfIcons.ArrowOut, tone = if (m.incoming) Tone.Info else Tone.Neutral,
                onClick = { m.messageId?.let { nav.push(Screen.Message, it) } ?: nav.push(Screen.Source, m.sourceId) })
        } }
        Note("Recordings are matched by file name: this person's name or number. Calls and conversations are not linked to people in any other way yet.")
    }
    if (managing && v != null) HfBottomSheet({ managing = false }) {
        Text("Linked identities", style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        Note("Names can repeat. Merge only when both records are the same person. Separate a handle and choose which records move with it.")
        v.person.handles.forEach { (handle, label) -> SecondaryButton("Separate $label…", { separateHandle = handle; separateName = v.person.name; managing = false }) }
        others.forEach { person -> SecondaryButton("Merge into ${person.displayName} · ${otherDetails[person.personKey].orEmpty()}…", { mergeTarget = person; managing = false }) }
    }
    mergeTarget?.let { target -> ConfirmDialog("Merge these people?", "Move aliases, tasks, payment associations and debt notes into ${target.displayName}. Ledger amounts stay unchanged. Review the target carefully.", "Merge people", {
        mergeTarget = null
        scope.launch { val ok = io { ContactLinks(app.database).merge(key, target.personKey) }; if (ok != null) { nav.pop(); nav.push(Screen.Person, target.personKey) } else message = "Merge failed; no changes committed." }
    }, { mergeTarget = null }) }
    separateHandle?.let { handle -> HfBottomSheet({ separateHandle = null }) {
        Text("Separate identity", style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        HfTextField(separateName, { separateName = it }, "Name for the separate person")
        Note("Only checked records move. All other associations remain with the original person.")
        v?.tasks?.filter { it.personKey == key }?.forEach { t -> CheckRow(t.title, t.id in moveTasks, { checked -> moveTasks = if (checked) moveTasks + t.id else moveTasks - t.id }) }
        v?.payments?.forEach { p -> CheckRow("${Fmt.amount(p.amount)} · ${p.description}", p.id in movePayments, { checked -> movePayments = if (checked) movePayments + p.id else movePayments - p.id }) }
        v?.debts?.forEach { d -> CheckRow("Debt · ${Fmt.amount(d.amount)} · ${d.note}", d.id in moveDebts, { checked -> moveDebts = if (checked) moveDebts + d.id else moveDebts - d.id }) }
        PrimaryButton("Separate selected identity and records", {
            scope.launch {
                val newKey = io { ContactLinks(app.database).separate(key, handle, separateName, moveTasks, movePayments, moveDebts) }
                separateHandle = null
                if (newKey != null) { nav.pop(); nav.push(Screen.Person, newKey) } else message = "Separation failed; no changes committed."
            }
        }, enabled = separateName.isNotBlank())
    } }
}

private fun billsPrefs(app: HellFireApp) = app.getSharedPreferences("bills", 0)

/** The detected payment becomes an active monthly bill reminder; the owner's tap is the confirmation. */
private suspend fun trackBill(app: HellFireApp, r: RecurringPayment) {
    val now = System.currentTimeMillis()
    val day = Instant.ofEpochMilli(r.nextAt).atZone(TaskTime.zone).toLocalDate()
    val due = day.atTime(9, 0).atZone(TaskTime.zone).toInstant().toEpochMilli().takeIf { it > now }
        ?: Fmt.today().plusDays(1).atTime(9, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
    val text = "Owner chose to track ${r.label} as a monthly bill of about TZS ${Fmt.amount(r.amount)}, paid ${r.months} months in a row"
    val sourceId = ownerStatement(app, text, now)
    val id = java.util.UUID.randomUUID().toString()
    app.database.tasks().insert(AssistantTask(id, sourceId, r.label, due, party = "OWNER", evidence = text, createdAt = now, updatedAt = now))
    TaskScheduler.activate(app, id, r.label, due, "OWNER")
    TaskScheduler.updateDetails(app, id, "NONE", r.amount, "BILL", true)
}

/** Bills coming up against the money on hand, and payments that look monthly but are not tracked yet. */
@Composable
fun BillsPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val tasks by remember { app.database.tasks().tasks() }.collectAsStateWithLifecycle(emptyList())
    var found by remember { mutableStateOf<List<RecurringPayment>>(emptyList()) }
    var balance by remember { mutableStateOf<Long?>(null) }
    var hidden by remember { mutableStateOf(billsPrefs(app).getStringSet("hidden", emptySet())!!.toSet()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        io {
            val dao = app.database.finances()
            balance = dao.allAccounts().fold(0L) { sum, a -> Math.addExact(sum, dao.balance(a.id)) }
            found = Recurring.detect(dao.allEntries(), System.currentTimeMillis())
        }
    }
    val now = System.currentTimeMillis()
    val forecast = Recurring.forecast(tasks, balance ?: 0, now)
    val suggestions = Recurring.untracked(found, tasks).filter { it.label.lowercase() !in hidden }
    PushedPage("Bills and subscriptions", breadcrumb = "Money") {
        message?.let { Banner(it) }
        KvList(listOf(
            "Bills due in 30 days" to "TZS ${Fmt.amount(forecast.total)}" + if (forecast.unpriced > 0) " + ${forecast.unpriced} without an amount" else "",
            "In your accounts" to (balance?.let { "TZS ${Fmt.amount(it)}" } ?: "…"),
        ))
        if (balance != null && forecast.shortfall > 0) Banner("Short by TZS ${Fmt.amount(forecast.shortfall)} for the bills due in the next 30 days.", Tone.Warning, HfIcons.Info)
        SectionHeader("Coming up", trailing = forecast.due.size.toString())
        if (forecast.due.isEmpty()) HfCard(padding = PaddingValues(16.dp)) {
            Text("No bills in the next 30 days. Add one from Tasks: choose \"Bill to pay\" and \"Repeats every month\".", style = Hf.type.body, color = Hf.colors.muted)
        } else ListCard { forecast.due.forEachIndexed { i, t ->
            if (i > 0) ListDivider()
            ListRow(t.title.take(90), subtitle = (if (t.dueAt < now) "Overdue since " else "Due ") + Fmt.day(t.dueAt) + if (t.repeatMonthly) " · monthly" else "",
                icon = HfIcons.Bolt, tone = if (t.dueAt < now) Tone.Warning else Tone.Info, chevron = true, onClick = { nav.push(Screen.Task, t.id) },
                trailing = { Text(t.amount?.let(Fmt::amount) ?: "—", style = Hf.type.rowTitle.copy(fontFeatureSettings = "tnum"), color = Hf.colors.onSurface) })
        } }
        if (suggestions.isNotEmpty()) SectionHeader("Looks like a monthly payment", trailing = suggestions.size.toString())
        suggestions.forEach { r ->
            ActionCard(context = "Paid ${r.months} months in a row · ${r.category}", contextIcon = HfIcons.Reports, contextTone = Tone.Info, actions = {
                PrimaryButton("Track as monthly bill", { scope.launch {
                    message = if (io { trackBill(app, r) } != null) "${r.label} is now a monthly bill. You will be reminded before it is due." else "The bill could not be added. Check reminder access."
                } })
                QuietButton("Not a bill", {
                    hidden = hidden + r.label.lowercase()
                    billsPrefs(app).edit().putStringSet("hidden", hidden).apply()
                })
            }) {
                Text(r.label, style = Hf.type.cardTitle.copy(fontSize = 15.sp), color = Hf.colors.onSurface)
                Text("${if (r.varies) "About TZS" else "TZS"} ${Fmt.amount(r.amount)} · last paid ${Fmt.day(r.lastAt)} · next around ${Fmt.day(r.nextAt)}",
                    style = Hf.type.rowSubtitle, color = Hf.colors.muted)
            }
        }
        Note("Forecast assumptions: confirmed ledger balances only; no future income assumed. Bills without amounts are excluded from the total. Only scheduled occurrences are included; future repeats have not yet been created.")
        Note("Found by rule from payments you confirmed: the same description, once or twice a month, three months running. Bill amounts are reminders only and never change your ledger.")
    }
}
