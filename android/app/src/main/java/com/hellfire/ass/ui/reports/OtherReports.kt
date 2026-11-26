package com.hellfire.ass.ui.reports

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.data.LabelledEvent
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.finance.FinanceCandidate
import com.hellfire.ass.finance.FinanceParser
import com.hellfire.ass.processing.CaptureWork
import com.hellfire.ass.reports.*
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hellfire.ass.ai.AiState
import com.hellfire.ass.communications.CommunicationMessage
import com.hellfire.ass.communications.OutboundDraft
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.tasks.AssistantTask

// ---------------- Capture ----------------

/** One captured event with its display grouping and whether it needs attention. */
data class CaptureRow(val e: LabelledEvent, val app: String, val source: String, val unread: Boolean) {
    val failed get() = e.state == "FAILED"
    val pending get() = e.state == "PENDING"
}

@Composable
private fun rememberCapture(range: DateRange): List<CaptureRow>? {
    val app = LocalEnv.current.app
    var rows by remember { mutableStateOf<List<CaptureRow>?>(null) }
    LaunchedEffect(range) {
        rows = withContext(Dispatchers.IO) {
            val events = app.database.events().labelledPeriod(range.start, range.end)
            val withCandidate = app.database.finances().allCandidates().map { it.sourceId }.toSet()
            val evidenced = events.map { it.event.id }.chunked(500).flatMap { app.database.finances().evidenced(it) }.toSet()
            events.map { le ->
                val e = le.event
                val sender = if (e.source == "android_sms") EventText.fields(app, e)["sender_hint"]?.firstOrNull()?.takeIf(String::isNotBlank) else null
                CaptureRow(le, sender ?: if (e.source == "android_notification") e.packageName else EventText.sourceLabel(e), EventText.sourceLabel(e),
                    unreadFinance(le.label, le.state, e.id in withCandidate, e.id in evidenced))
            }
        }
    }
    return rows
}

@Composable
fun CaptureReport(arg: String) {
    val nav = LocalNav.current
    val range = ReportRange.range
    val rows = rememberCapture(range) ?: emptyList()
    var group by remember { mutableStateOf("App") }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(2 to false) }
    val appFilter = arg.removePrefix("app:").takeIf { arg.startsWith("app:") }
    if (appFilter != null) {
        val mine = rows.filter { it.app == appFilter }.sortedByDescending { it.e.event.receivedAt }
        ReportScaffold("capture-app", appFilter, "Capture › $appFilter", listOf(ActiveFilter(appFilter) { nav.pop() }), chart = null, table = {
            DataTable(listOf(TableColumn("Received"), TableColumn("Source", width = 104.dp), TableColumn("Label", width = 120.dp), TableColumn("State", width = 112.dp)),
                mine.map { r -> TableRow(r.e.event.id, listOf(Fmt.dayTime(r.e.event.receivedAt), r.source, r.e.label?.lowercase()?.replace('_', ' ') ?: "—",
                    when { r.unread -> "Not understood"; r.failed -> "Failed"; r.pending -> "Pending"; else -> "Done" }),
                    listOf(r.e.event.receivedAt, r.source, r.e.label, r.e.state), highlight = r.unread || r.failed,
                    onClick = { if (r.unread || r.failed) nav.push(Screen.FixSms, r.e.event.id) else nav.push(Screen.Source, r.e.event.id) }) }, firstWidth = 112.dp)
            TableFooter("${mine.size} events", null)
            Note("Tap a failed or unread message to fix it.")
        })
        return
    }
    val bySource = rows.groupBy { it.source }.mapValues { it.value.size }.entries.sortedByDescending { it.value }
    val keyOf: (CaptureRow) -> String = { if (group == "App") it.app else it.source }
    val groups = rows.groupBy(keyOf).map { (k, list) -> listOf(k, list.first().source, list.size, list.count { it.failed }, list.count { it.pending }, list.count { it.unread }) }
    val spec = ExportSpec("Capture", range.label, listOf("Events" to rows.size.toString(), "Failed" to rows.count { it.failed }.toString(),
        "Pending" to rows.count { it.pending }.toString(), "Not understood" to rows.count { it.unread }.toString()),
        listOf(if (group == "App") "App / sender" else "Source", "Source", "Events", "Failed", "Pending", "Unread"), groups.map { it.map(Any::toString) },
        listOf("Total", "", rows.size.toString(), rows.count { it.failed }.toString(), rows.count { it.pending }.toString(), rows.count { it.unread }.toString()),
        listOf("Received", "Source", "App / sender", "Label", "State", "Source ID"),
        rows.map { listOf(TaskTimeFmt.iso(it.e.event.receivedAt), it.source, it.app, it.e.label.orEmpty(), it.e.state, it.e.event.id) }, 5, groups.map { (it[2] as Int).toLong() })
    val b = buckets(range)
    ReportScaffold("capture", "Capture", "Notifications, SMS, email, WhatsApp", export = spec, group = group to listOf("App", "Source"), onGroup = { group = it },
        chart = {
            val prev = remember(range) { mutableStateOf<Int?>(null) }
            val app = LocalEnv.current.app
            LaunchedEffect(range) { prev.value = withContext(Dispatchers.IO) { app.database.events().labelledPeriod(range.previous().start, range.start).size } }
            KpiGrid(listOf(Triple("Events captured", Fmt.amount(rows.size.toLong()), "${pct(prev.value?.let { percentChange(rows.size.toLong(), it.toLong()) })} vs previous"),
                Triple("Need attention", (rows.count { it.failed || it.pending || it.unread }).toString(), "${rows.count { it.failed }} failed · ${rows.count { it.pending }} pending · ${rows.count { it.unread }} not understood")))
            ChartCard("Events per period") { LineChart(b.map { k -> rows.count { it.e.event.receivedAt in k.start until k.end }.toLong() }, description = "Captured events over ${range.label}")
                Row { Text(b.first().label, Modifier.weight(1f), style = Hf.type.caption, color = Hf.colors.muted); Text(b.last().label, style = Hf.type.caption, color = Hf.colors.muted) } }
            ChartCard("By source") { HorizontalBars(bySource.map { it.key to it.value.toLong() }, { Fmt.amount(it) }, color = Hf.colors.seriesBlue) }
            DotSummary(listOf("${rows.count { it.failed }} failed" to Hf.colors.overdue, "${rows.count { it.pending }} pending" to Hf.colors.late,
                "${rows.count { it.unread }} not understood" to Hf.colors.seriesOrange))
            Note("Not understood: messages that look like payments but produced nothing to confirm. Open them from the table to fix.")
        }, table = {
            DataTable(listOf(TableColumn(if (group == "App") "App / sender" else "Source"), TableColumn("Source", width = 104.dp), TableColumn("Events", true, 80.dp),
                TableColumn("Failed", true, 72.dp), TableColumn("Pending", true, 80.dp), TableColumn("Unread", true, 76.dp)),
                groups.map { g -> TableRow(g[0] as String, g.map(Any::toString), g, highlight = (g[3] as Int) > 0 || (g[5] as Int) > 0,
                    onClick = if (group == "App") ({ nav.push(Screen.ReportCapture, "app:${g[0]}") }) else null) },
                totals = spec.totals, sort = sort, onSort = { sort = it })
            TableFooter("${groups.size} ${if (group == "App") "apps and senders" else "sources"}", "Swipe →")
        })
}

/** Fix a captured message the reader could not use (RFixSms). Every fix becomes a proposal to confirm in Money. */
@Composable
fun FixSmsPage(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var event by remember { mutableStateOf<SourceEvent?>(null) }
    var text by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var linked by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { withContext(Dispatchers.IO) {
        event = app.database.events().find(id)
        event?.let { e ->
            val f = EventText.fields(app, e); text = EventText.raw(app, e); sender = f["sender_hint"]?.firstOrNull() ?: f["title"]?.firstOrNull() ?: e.packageName
            state = app.database.events().processing(id)?.state.orEmpty()
            linked = app.database.finances().candidatesFor(id).size + app.database.finances().entriesFor(id).size
        }
    } }
    val e = event
    val guess = guessMoney(text)
    PushedPage("Fix a captured message", breadcrumb = "Capture › $sender") {
        if (e == null) { Note("This record is no longer available."); return@PushedPage }
        message?.let { Banner(it) }
        Banner(if (state == "FAILED") "Processing failed · not in your totals" else "Could not be read · not in your totals", Tone.Danger, null)
        EvidenceCard("${EventText.sourceLabel(e)} from $sender · ${Fmt.dayTime(e.occurredAt)}", text.ifBlank { "No readable text." }.take(2000))
        SectionCard("What we think it is") {
            Text(if (guess == null) "No amount and direction could be found. Open the full record, or add it by hand in Money with Add cash."
                else "Money ${if (guess.first == "INCOME") "received" else "paid"}: TZS ${Fmt.amount(guess.second)}. This is a guess from the wording; you confirm it in Money.",
                style = Hf.type.body, color = Hf.colors.onSurface)
        }
        KvList(listOf("Source" to EventText.sourceLabel(e), "Sender" to sender, "Received" to Fmt.full(e.receivedAt), "Linked records" to if (linked == 0) "None" else linked.toString()))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (guess != null && linked == 0) PrimaryButton(if (guess.first == "INCOME") "Add as income" else "Add as expense", { scope.launch {
                message = runCatching { withContext(Dispatchers.IO) {
                    app.database.finances().candidate(FinanceCandidate(NotificationNormalizer.sha256("finance:$id:0"), id, provider = FinanceParser.provider(sender, text) ?: "MANUAL",
                        reference = null, kind = guess.first, amount = guess.second, fee = 0, merchant = null, evidence = text.take(2000), occurredAt = e.occurredAt))
                } }.fold({ linked = 1; "Added to Money › To confirm. It counts once you confirm it there." }, { "Could not add this record." })
            } })
            SecondaryButton("Try again", { scope.launch {
                message = runCatching { withContext(Dispatchers.IO) { app.database.events().retry(id) }; CaptureWork.process(app) }
                    .fold({ "Queued for processing again." }, { "Could not queue processing." })
            } })
        }
        QuietButton("Show raw message", { nav.push(Screen.Source, id) })
        Note("The original message is kept as proof and never edited.")
    }
}

// ---------------- Tasks ----------------

@Composable
fun TasksReport() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val range = ReportRange.range
    val tick by remember { app.database.tasks().tasks() }.collectAsStateWithLifecycle(emptyList())
    var data by remember { mutableStateOf<Pair<List<AssistantTask>, Int>?>(null) }
    LaunchedEffect(range, tick) { data = withContext(Dispatchers.IO) {
        app.database.tasks().all().filter { it.dueAt in range } to app.database.communications().audits("OWNER_TASK_SNOOZE").count { it.occurredAt in range }
    } }
    var outcomes by remember { mutableStateOf(emptySet<String>()) }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(1 to true) }
    val now = System.currentTimeMillis()
    val tasks = data?.first.orEmpty().filter { it.state != "REJECTED" }
    val rows = tasks.map { it to outcome(it, it.completedAtOrNull(), now) }
    val count = rows.groupingBy { it.second }.eachCount()
    val due = rows.count { it.second in setOf(TaskOutcome.OnTime, TaskOutcome.Late, TaskOutcome.DoneUnknown, TaskOutcome.Overdue) }
    val shown = rows.filter { outcomes.isEmpty() || it.second.label in outcomes }
    fun label(t: AssistantTask, o: TaskOutcome) = if (o == TaskOutcome.Late) lateness(t.dueAt, t.completedAtOrNull()!!) else o.label
    val spec = ExportSpec("Tasks", range.label, listOf("On time" to "${count[TaskOutcome.OnTime] ?: 0}", "Late" to "${count[TaskOutcome.Late] ?: 0}",
        "Overdue" to "${count[TaskOutcome.Overdue] ?: 0}", "Waiting" to "${count[TaskOutcome.Waiting] ?: 0}"),
        listOf("Task", "Due", "Outcome", "Alerts"), shown.map { (t, o) -> listOf(t.title, Fmt.dayTime(t.dueAt), label(t, o), t.alertCount.toString()) }, null,
        listOf("Task", "Due", "State", "Outcome", "Alerts", "Source ID"), rows.map { (t, o) -> listOf(t.title, TaskTimeFmt.iso(t.dueAt), t.state, o.label, t.alertCount.toString(), t.sourceId) }, 5)
    val b = buckets(range)
    ReportScaffold("tasks", "Tasks", "Reminders and commitments", outcomes.map { o -> ActiveFilter(o) { outcomes = outcomes - o } },
        filterSheet = { close -> Text("Filter tasks", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
            FilterGroup("Outcome", TaskOutcome.entries.filter { it != TaskOutcome.Rejected }.map { it.label }, outcomes) { outcomes = it }
            PrimaryButton("Show ${shown.size} tasks", close, Modifier.fillMaxWidth()) },
        export = spec, group = "None" to listOf("None"), chart = {
            KpiGrid(listOf(Triple("Done on time", "${count[TaskOutcome.OnTime] ?: 0}", if (due > 0) "${(count[TaskOutcome.OnTime] ?: 0) * 100 / due}% of tasks due" else "No tasks due"),
                Triple("Waiting for you", "${count[TaskOutcome.Waiting] ?: 0}", "Not scheduled yet")))
            ChartCard("Tasks due, by period and outcome", legend = listOf("On time" to Hf.colors.onTime, "Done late" to Hf.colors.late, "Still overdue" to Hf.colors.overdue, "Done, time unknown" to Hf.colors.faint)) {
                fun s(o: TaskOutcome) = b.map { k -> rows.count { it.second == o && it.first.dueAt in k.start until k.end }.toLong() }
                BarChart(b.map { it.label }, listOf(Series("On time", Hf.colors.onTime, s(TaskOutcome.OnTime)), Series("Late", Hf.colors.late, s(TaskOutcome.Late)),
                    Series("Overdue", Hf.colors.overdue, s(TaskOutcome.Overdue)), Series("Unknown", Hf.colors.faint, s(TaskOutcome.DoneUnknown))), stacked = true,
                    description = "Tasks due in each period by outcome")
                DotSummary(listOf("${count[TaskOutcome.OnTime] ?: 0} on time" to Hf.colors.onTime, "${count[TaskOutcome.Late] ?: 0} late" to Hf.colors.late,
                    "${count[TaskOutcome.Overdue] ?: 0} overdue" to Hf.colors.overdue, "${count[TaskOutcome.Waiting] ?: 0} to review" to Hf.colors.primary))
            }
            Note("${tasks.sumOf { it.alertCount }} reminders sent in this range · snoozed ${data?.second ?: 0} times." +
                if ((count[TaskOutcome.DoneUnknown] ?: 0) > 0) " Tasks finished before completion times were saved show as done, time unknown." else "")
        }, table = {
            DataTable(listOf(TableColumn("Task"), TableColumn("Due", width = 112.dp), TableColumn("Outcome", width = 132.dp), TableColumn("Alerts", true, 70.dp)),
                shown.map { (t, o) -> TableRow(t.id, listOf(t.title, Fmt.dayTime(t.dueAt), label(t, o), t.alertCount.toString()), listOf(t.title, t.dueAt, o.ordinal, t.alertCount),
                    highlight = o == TaskOutcome.Overdue, onClick = { nav.push(Screen.Task, t.id) }) }, sort = sort, onSort = { sort = it }, firstWidth = 150.dp)
            TableFooter("${shown.size} tasks", "Swipe →")
        })
}

// ---------------- Messages ----------------

@Composable
fun MessagesReport() {
    val app = LocalEnv.current.app
    val range = ReportRange.range
    var data by remember { mutableStateOf<Pair<List<CommunicationMessage>, List<OutboundDraft>>?>(null) }
    LaunchedEffect(range) { data = withContext(Dispatchers.IO) {
        app.database.communications().messagesIn(range.start, range.end) to app.database.communications().allDrafts().filter { it.createdAt in range }
    } }
    var group by remember { mutableStateOf("Contact") }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(2 to false) }
    val messages = data?.first.orEmpty(); val drafts = data?.second.orEmpty()
    val incoming = messages.filter { it.direction == "INCOMING" }
    val sent = drafts.filter { it.state in SENT_STATES }
    val email = incoming.count { it.channel == "EMAIL_IMAP" }
    val keyOf: (CommunicationMessage) -> String = { if (group == "Contact") (if (it.direction == "INCOMING") it.sender else it.recipient) else Fmt.channel(it.channel) }
    val groups = messages.groupBy(keyOf).map { (k, list) -> listOf(k, Fmt.channel(list.first().channel), list.count { it.direction == "INCOMING" }, list.count { it.direction == "OUTGOING" }) }
    val spec = ExportSpec("Messages", range.label, listOf("Received" to incoming.size.toString(), "Sent by you" to sent.size.toString()),
        listOf(group, "Channel", "In", "Out"), groups.map { it.map(Any::toString) },
        listOf("All", "", incoming.size.toString(), messages.count { it.direction == "OUTGOING" }.toString()),
        listOf("When", "Channel", "Direction", "From", "To", "Subject", "Source ID"),
        messages.map { listOf(TaskTimeFmt.iso(it.occurredAt), Fmt.channel(it.channel), it.direction, it.sender, it.recipient, it.subject, it.sourceId) }, 6,
        groups.map { (it[2] as Int).toLong() })
    val b = buckets(range)
    ReportScaffold("messages", "Messages", "Email and WhatsApp", export = spec, group = group to listOf("Contact", "Channel"), onGroup = { group = it }, chart = {
        KpiGrid(listOf(Triple("Received", incoming.size.toString(), "$email email · ${incoming.size - email} WhatsApp"),
            Triple("Sent by you", sent.size.toString(), "All approved first")))
        ChartCard("Incoming by period", legend = listOf("Email" to Hf.colors.seriesBlue, "WhatsApp" to Hf.colors.seriesTeal)) {
            BarChart(b.map { it.label }, listOf(
                Series("Email", Hf.colors.seriesBlue, b.map { k -> incoming.count { it.channel == "EMAIL_IMAP" && it.occurredAt in k.start until k.end }.toLong() }),
                Series("WhatsApp", Hf.colors.seriesTeal, b.map { k -> incoming.count { it.channel != "EMAIL_IMAP" && it.occurredAt in k.start until k.end }.toLong() })),
                stacked = true, description = "Incoming messages per period")
        }
        ChartCard("What happened to your drafts") {
            val approved = drafts.count { it.approvedAt != null || it.state in SENT_STATES || it.state in setOf("SENDING", "UNKNOWN", "FAILED") }
            val confirmed = drafts.count { it.state in setOf("SENT_CONFIRMED", "DELIVERED", "READ") }
            HorizontalBars(listOf("Drafted" to drafts.size.toLong(), "Approved" to approved.toLong(), "Sent" to sent.size.toLong(), "Confirmed" to confirmed.toLong()),
                { it.toString() }, color = Hf.colors.seriesTeal)
            Note("${drafts.count { it.state == "DRAFT" }} still waiting · ${sent.size - confirmed} sent but not yet confirmed · ${drafts.count { it.state in setOf("UNKNOWN", "FAILED") }} failed or unconfirmed.")
        }
    }, table = {
        DataTable(listOf(TableColumn(group), TableColumn("Channel", width = 96.dp), TableColumn("In", true, 72.dp), TableColumn("Out", true, 72.dp)),
            groups.map { g -> TableRow(g[0] as String, g.map(Any::toString), g) }, totals = spec.totals, sort = sort, onSort = { sort = it }, firstWidth = 150.dp)
        TableFooter("${groups.size} ${if (group == "Contact") "contacts" else "channels"}", "Swipe →")
    })
}

// ---------------- Media ----------------

@Composable
fun MediaReport() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val range = ReportRange.range
    var items by remember { mutableStateOf<List<Pair<MediaItem, Long?>>?>(null) }
    LaunchedEffect(range) { items = withContext(Dispatchers.IO) {
        app.database.media().all().filter { (it.recordedAt ?: it.importedAt) in range }.map { m ->
            m to (m.lengthMs() ?: app.database.media().transcript(m.id).maxOfOrNull { it.endMs }?.takeIf { it > 0 })
        }
    } }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(2 to false) }
    val list = items.orEmpty()
    val audio = list.filter { it.first.mime.startsWith("audio/") || it.first.mime.startsWith("video/") }
    fun kind(k: String) = when (k) { "CALL_RECORDING" -> "Call"; "IN_PERSON_AUDIO" -> "In person"; "VOICE_NOTE" -> "Voice note"; "IMAGE" -> "Image"; "DOCUMENT" -> "Document"; "VIDEO" -> "Video"; else -> k }
    fun summary(s: String) = when (s) { "INFERENCE_REVIEW" -> "To review"; "OWNER_CONFIRMED_INFERENCE" -> "Reviewed"; else -> "None" }
    val processing = list.count { it.first.state in setOf("QUEUED", "PROCESSING") }
    val transcribed = list.count { (m, _) -> m.processedMs > 0 && m.state !in setOf("QUEUED", "PROCESSING") }
    val spec = ExportSpec("Media", range.label, listOf("Audio captured" to hours(audio.sumOf { it.second ?: 0 }), "Items" to list.size.toString(),
        "Stored" to "${list.sumOf { it.first.size } / 1_048_576} MB"),
        listOf("Name", "Kind", "Recorded", "Length", "Summary"), list.map { (m, len) -> listOf(m.name, kind(m.kind), Fmt.day(m.recordedAt ?: m.importedAt), duration(len), summary(m.summaryState)) }, null,
        listOf("Name", "Kind", "Recorded", "Length ms", "Size bytes", "State", "Summary", "Source ID"),
        list.map { (m, len) -> listOf(m.name, m.kind, TaskTimeFmt.iso(m.recordedAt ?: m.importedAt), len?.toString().orEmpty(), m.size.toString(), m.state, m.summaryState, m.sourceId.orEmpty()) }, 7)
    val b = buckets(range)
    ReportScaffold("media", "Media", "Calls, conversations, voice notes", export = spec, group = "None" to listOf("None"), chart = {
        KpiGrid(listOf(Triple("Audio captured", hours(audio.sumOf { it.second ?: 0 }), "${audio.size} recordings" + if (audio.any { it.second == null }) " · some lengths unknown" else ""),
            Triple("Stored on phone", if (list.sumOf { it.first.size } >= 1L shl 30) "%.1f GB".format(list.sumOf { it.first.size } / 1_073_741_824.0) else "${list.sumOf { it.first.size } / 1_048_576} MB", "Encrypted originals")))
        ChartCard("Hours by period", legend = listOf("Calls" to Hf.colors.seriesBlue, "In person" to Hf.colors.seriesOrange, "Voice notes" to Hf.colors.seriesTeal)) {
            fun s(k: String) = b.map { bk -> audio.filter { it.first.kind == k && (it.first.recordedAt ?: it.first.importedAt) in bk.start until bk.end }.sumOf { (it.second ?: 0) / 60_000 } }
            BarChart(b.map { it.label }, listOf(Series("Calls", Hf.colors.seriesBlue, s("CALL_RECORDING")), Series("In person", Hf.colors.seriesOrange, s("IN_PERSON_AUDIO")),
                Series("Voice notes", Hf.colors.seriesTeal, s("VOICE_NOTE"))), stacked = true, description = "Recorded minutes per period by kind")
        }
        ChartCard("Processing") {
            DotSummary(listOf("$transcribed analysed" to Hf.colors.onTime, "$processing analysing" to Hf.colors.late,
                "${list.size - transcribed - processing} not analysed" to Hf.colors.faint, "${list.count { it.first.summaryState == "INFERENCE_REVIEW" }} summaries to review" to Hf.colors.primary))
        }
    }, table = {
        DataTable(listOf(TableColumn("Name"), TableColumn("Kind", width = 96.dp), TableColumn("Recorded", width = 88.dp), TableColumn("Length", true, 96.dp), TableColumn("Summary", width = 96.dp)),
            list.map { (m, len) -> TableRow(m.id, listOf(m.name, kind(m.kind), Fmt.day(m.recordedAt ?: m.importedAt), duration(len), summary(m.summaryState)),
                listOf(m.name, m.kind, m.recordedAt ?: m.importedAt, len ?: -1, m.summaryState), onClick = { nav.push(Screen.MediaItem, m.id) }) },
            sort = sort, onSort = { sort = it }, firstWidth = 150.dp)
        TableFooter("${list.size} items", "Swipe →")
    })
}

// ---------------- AI ----------------

@Composable
fun AiReport() {
    val app = LocalEnv.current.app
    val range = ReportRange.range
    var data by remember { mutableStateOf<Pair<List<UsageRow>, AiState?>?>(null) }
    LaunchedEffect(range) { data = withContext(Dispatchers.IO) { aiUsage(app, range) to runCatching { app.ai.store.read() }.getOrNull() } }
    var group by remember { mutableStateOf("Provider") }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(5 to false) }
    val rows = data?.first.orEmpty(); val state = data?.second
    val monthSpend = state?.usage?.filterKeys { it.startsWith("${app.ai.month()}/") }?.values?.sumOf { it.charged } ?: 0
    val cap = state?.monthlyCap ?: 0
    val groups = rows.groupBy { if (group == "Provider") it.provider else "${it.provider} / ${it.model}" }.map { (k, l) ->
        listOf(k, l.size.toLong(), l.count { it.result != "SUCCESS" }.toLong(), l.sumOf { it.input }, l.sumOf { it.output }, l.sumOf { it.microUsd }) }
    val spec = ExportSpec("AI usage", range.label, listOf("Requests" to rows.size.toString(), "Estimated USD" to usdMicro(rows.sumOf { it.microUsd })),
        listOf(group, "Requests", "Failed", "Tokens in", "Tokens out", "USD"), groups.map { g -> listOf(g[0] as String) + g.drop(1).dropLast(1).map { Fmt.amount(it as Long) } + usdMicro(g[5] as Long) },
        listOf("Total", rows.size.toString(), rows.count { it.result != "SUCCESS" }.toString(), Fmt.amount(rows.sumOf { it.input }), Fmt.amount(rows.sumOf { it.output }), usdMicro(rows.sumOf { it.microUsd })),
        listOf("Time", "Task", "Provider", "Model", "Result", "Tokens in", "Tokens out", "Micro USD"),
        rows.map { listOf(TaskTimeFmt.iso(it.at), it.task, it.provider, it.model, it.result, it.input.toString(), it.output.toString(), it.microUsd.toString()) },
        chart = groups.map { it[5] as Long })
    ReportScaffold("ai", "AI usage", "Cloud AI spending", export = spec, group = group to listOf("Provider", "Model"), onGroup = { group = it }, chart = {
        ChartCard("Spend against your cap", trailing = if (cap > 0) "${monthSpend * 100 / cap}%" else "No cap") {
            Meter(if (cap > 0) monthSpend.toFloat() / cap else 0f, description = "USD ${usdMicro(monthSpend)} of ${usdMicro(cap)} used this month")
            TableFooter("USD ${usdMicro(monthSpend)} estimated this month", "Cap USD ${usdMicro(cap)}")
        }
        KpiGrid(listOf(Triple("Requests", rows.size.toString(), "${rows.count { it.result != "SUCCESS" }} failed"),
            Triple("Tokens", Fmt.compact(rows.sumOf { it.input + it.output }), "${Fmt.compact(rows.sumOf { it.input })} in · ${Fmt.compact(rows.sumOf { it.output })} out")))
        ChartCard("Estimated USD by provider") {
            HorizontalBars(rows.groupBy { it.provider }.map { (k, l) -> k to l.sumOf { it.microUsd } }.sortedByDescending { it.second }, { "$" + usdMicro(it) }, color = Hf.colors.seriesBlue)
        }
        Note("Uses the prices you entered. Your provider's bill is the real figure. Only task, model, time, tokens and cost are recorded. Prompts and answers are never stored.")
    }, table = {
        DataTable(listOf(TableColumn(group), TableColumn("Requests", true, 84.dp), TableColumn("Failed", true, 72.dp), TableColumn("Tokens in", true, 100.dp), TableColumn("Tokens out", true, 100.dp), TableColumn("USD", true, 80.dp)),
            groups.mapIndexed { i, g -> TableRow(g[0] as String, spec.rows[i], g) }, totals = spec.totals, sort = sort, onSort = { sort = it })
        TableFooter("Group by model for one row per model", "Swipe →")
        Note("Only task, model, time, tokens and cost are recorded. Prompts and answers are never stored.")
    })
}
