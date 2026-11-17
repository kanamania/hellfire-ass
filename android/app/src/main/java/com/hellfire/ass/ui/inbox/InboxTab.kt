package com.hellfire.ass.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.communications.OutboundDraft
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Channel(val label: String) { WHATSAPP("WhatsApp"), SMS("SMS"), EMAIL("Email"), CALL("Calls"), OTHER("Other") }

/** One row in the unified inbox, with what the app did with it. */
data class InboxItem(val key: String, val channel: Channel, val from: String, val text: String, val at: Long, val sourceId: String,
    val tag: String? = null, val tagTone: Tone = Tone.Neutral, val open: Route? = null, val thread: String = key)

@Composable
fun InboxTab() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val db = app.database
    val scope = rememberCoroutineScope()
    var limit by rememberSaveable { mutableIntStateOf(100) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var search by rememberSaveable { mutableStateOf<String?>(null) }
    var conversations by rememberSaveable { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    val query = search.orEmpty().trim()
    val messages by remember(query, limit) { db.communications().inbox(query, limit) }.collectAsStateWithLifecycle(emptyList())
    val drafts by remember { db.communications().drafts() }.collectAsStateWithLifecycle(emptyList())
    val events by remember(query, limit) { db.events().inbox(query, limit) }.collectAsStateWithLifecycle(emptyList())
    val media by remember { db.media().items() }.collectAsStateWithLifecycle(emptyList())
    val candidates by remember { db.finances().candidates() }.collectAsStateWithLifecycle(emptyList())
    val tasks by remember { db.tasks().tasks() }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(query) { limit = 100 }
    var items by remember { mutableStateOf<List<InboxItem>>(emptyList()) }
    var sending by remember { mutableStateOf<OutboundDraft?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ReplyDraft?>(null) }
    var accountLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(drafts) { accountLabels = withContext(Dispatchers.IO) { runCatching { com.hellfire.ass.communications.Accounts.labels(app.secrets) }.getOrDefault(emptyMap()) } }
    val focusDraft = remember { nav.consumePrefix("draft:") }

    LaunchedEffect(messages, events, media, candidates, tasks, accountLabels) {
        items = withContext(Dispatchers.IO) {
            // With more than one account, each message names the inbox or WhatsApp account it belongs to.
            fun via(accountId: String) = accountLabels[accountId]?.takeIf { accountLabels.size > 1 }?.let { " · $it" }.orEmpty()
            val review = candidates.map { it.sourceId }.toSet()
            val taskReview = tasks.filter { it.state == "REVIEW" }.map { it.sourceId }.toSet()
            val sourceIds = messages.map { it.sourceId } + events.map { it.event.id } + media.mapNotNull { it.sourceId }
            val evidenceGroups = sourceIds.chunked(500).flatMap { db.finances().evidenceGroups(it) }.associate { it.sourceId to it.groupId }
            val posted = sourceIds.chunked(500).flatMap { db.finances().evidenced(it) }.toSet()
            fun tag(sourceId: String): Pair<String?, Tone> = when (sourceId) {
                in review -> "Payment to confirm" to Tone.Warning
                in taskReview -> "Task found" to Tone.Warning
                in posted -> "Added to Money" to Tone.Success
                else -> null to Tone.Neutral
            }
            val fromMessages = messages.map { m ->
                val (t, tone) = tag(m.sourceId)
                InboxItem("m:${m.id}", if (m.channel == "EMAIL_IMAP") Channel.EMAIL else Channel.WHATSAPP,
                    (if (m.direction == "OUTGOING") "You → ${m.recipient}" else m.sender) + via(m.accountId), listOf(m.subject, m.body).filter(String::isNotBlank).joinToString(" · ").take(200),
                    m.occurredAt, m.sourceId, t, tone, Route(Screen.Message, m.id), evidenceGroups[m.sourceId]?.let { "payment:$it" } ?: "${m.channel}:${m.accountId}:${m.threadKey}")
            }
            val fromEvents = events.filter { it.event.source == "android_sms" || it.label == "MESSAGE" || it.label == "FINANCE_CANDIDATE" }.map { le ->
                val e = le.event
                val f = EventText.fields(app, e)
                val channel = when {
                    e.source == "android_sms" -> Channel.SMS
                    e.packageName.startsWith("com.whatsapp") -> Channel.WHATSAPP
                    e.packageName == "com.google.android.gm" -> Channel.EMAIL
                    else -> Channel.OTHER
                }
                val (t, tone) = tag(e.id)
                InboxItem("e:${e.id}", channel,
                    (if (e.source == "android_sms") f["sender_hint"]?.firstOrNull() else f["title"]?.firstOrNull())?.takeIf(String::isNotBlank)
                        ?: e.packageName.substringAfterLast('.'),
                    when { e.securitySensitive -> "Security code hidden"; !e.contentAvailable -> "Content not shared by Android"; else -> EventText.body(f).take(200) },
                    e.occurredAt, e.id, t, tone, Route(Screen.Source, e.id), evidenceGroups[e.id]?.let { "payment:$it" } ?: ("${channel.name}:" + (f["sender_hint"]?.firstOrNull() ?: f["title"]?.firstOrNull() ?: e.packageName)))
            }
            val fromMedia = media.filter { query.isBlank() || it.name.contains(query, true) || it.summary.contains(query, true) }.map { m ->
                InboxItem("media:${m.id}", if (m.kind == "CALL_RECORDING") Channel.CALL else Channel.OTHER, m.name,
                    when (m.kind) { "CALL_RECORDING" -> "Call recording"; "IN_PERSON_AUDIO" -> "Conversation"; "VOICE_NOTE" -> "Voice note"; else -> m.kind.lowercase() } +
                        " · " + when (m.state) { "RETAINED" -> "kept encrypted"; "QUEUED", "PROCESSING" -> "analysing"; "DONE", "INDEXED" -> "analysed"; else -> m.state.lowercase() },
                    m.recordedAt ?: m.importedAt, m.sourceId.orEmpty(),
                    when (m.summaryState) { "INFERENCE_REVIEW" -> "Summary ready"; else -> null }, Tone.Warning, Route(Screen.MediaItem, m.id))
            }
            (fromMessages + fromEvents + fromMedia).sortedByDescending { it.at }
        }
    }

    val pending = drafts.filter { it.state == "DRAFT" }.let { list -> focusDraft?.let { id -> list.sortedByDescending { it.id == id } } ?: list }
    ScrollPage(gap = 14.dp) {
        ScreenHeader(com.hellfire.ass.ui.tr("Inbox")) {
            IconCircleButton(HfIcons.Alarm, "Waiting on", { nav.push(Screen.Waiting) })
            IconCircleButton(HfIcons.Search, "Search", { search = if (search == null) "" else null })
            IconCircleButton(HfIcons.Plus, "New message", { editing = ReplyDraft() })
        }
        if (search != null) HfTextField(search!!, { search = it }, "Search all saved messages")
        FilterChips(filter) { filter = it }
        SegmentedControl(listOf("Conversations", "All items"), if (conversations) "Conversations" else "All items", { conversations = it == "Conversations" })
        sent?.let { Banner(it, modifier = Modifier.clickableRow { sent = null }) }
        pending.forEach { d ->
            ActionCard(highlighted = true, actions = {
                PrimaryButton(com.hellfire.ass.ui.tr("Approve and send"), { sending = d }, icon = HfIcons.Check)
                SecondaryButton(com.hellfire.ass.ui.tr("Edit"), { editing = ReplyDraft(d.channel, d.recipient, d.subject, d.body, d.replyToMessageId, d.accountId, d.id) })
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(com.hellfire.ass.ui.tr("DRAFT READY"), Modifier.weight(1f), style = Hf.type.rowSubtitle.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.25.sp), color = Hf.colors.primary)
                    Text("To ${d.recipient} · ${Fmt.channel(d.channel)}" + (accountLabels[d.accountId]?.let { " · from $it" } ?: ""), style = Hf.type.rowSubtitle, color = Hf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (d.subject.isNotBlank()) Text(d.subject, style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                Text(d.body.take(1200), style = Hf.type.body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Hf.colors.onSurface)
                d.error?.let { Text("Last attempt: $it", style = Hf.type.rowSubtitle, color = Hf.colors.warning) }
            }
        }
        val shown = items.filter { filter == "All" || it.channel.label == filter }

        if (shown.isEmpty()) HfCard(padding = PaddingValues(16.dp)) {
            Text(if (items.isEmpty()) "Nothing captured yet. Turn on notifications, SMS or connect email in Settings." else "Nothing in $filter.",
                style = Hf.type.body, color = Hf.colors.muted)
        } else HfCard(padding = PaddingValues(vertical = 4.dp)) {
            if (conversations) shown.groupBy { it.thread }.values.sortedByDescending { group -> group.maxOf { it.at } }.forEach { group ->
                val newest = group.maxBy { it.at }
                MessageRow(newest.copy(from = newest.from + if (group.size > 1) " (${group.size})" else "")) {
                    expanded = if (newest.thread in expanded) expanded - newest.thread else expanded + newest.thread
                }
                if (newest.thread in expanded) {
                    Note("${group.size} captured item(s) in loaded history. More history is available below.")
                    group.distinctBy { it.sourceId }.sortedByDescending { it.at }.forEach { item -> MessageRow(item) { item.open?.let(nav::push) } }
                }
            } else shown.forEach { item -> MessageRow(item) { item.open?.let(nav::push) } }
        }
        if (messages.size >= limit || events.size >= limit) SecondaryButton(com.hellfire.ass.ui.tr("Load older history"), { limit += 100 }, Modifier.fillMaxWidth())
        Note(com.hellfire.ass.ui.tr("Search checks saved message bodies and the full source-text index, including older captures."))
        val history = drafts.filter { it.state != "DRAFT" }.take(10)
        if (history.isNotEmpty()) {
            SectionHeader(com.hellfire.ass.ui.tr("Sent and failed drafts"))
            ListCard {
                history.forEachIndexed { i, d ->
                    if (i > 0) ListDivider(16.dp)
                    ListRow("To ${d.recipient}", subtitle = d.body.take(80), trailing = {
                        StatusPill(draftState(d.state), when (d.state) { "SENT_CONFIRMED", "DELIVERED", "READ" -> Tone.Success; "SUBMITTED", "SENDING", "APPROVED" -> Tone.Neutral; else -> Tone.Danger })
                    })
                }
            }
        }
        Note(com.hellfire.ass.ui.tr("Email and WhatsApp accounts are set up in Settings. Nothing is sent without your approval."))
    }
    sending?.let { d ->
        ConfirmDialog("Approve and send?", "${Fmt.channel(d.channel)} to ${d.recipient}" + (accountLabels[d.accountId]?.let { "\nFrom $it" } ?: "") + "\n${d.subject}\n\n${d.body.take(1500)}", "Approve and send once", onConfirm = {
            sending = null
            scope.launch {
                sent = try {
                    val approval = app.communications.approve(d.id)
                    app.communications.sendApproved(d.id, approval)
                    "Reply sent to ${d.recipient} on ${Fmt.channel(d.channel)}. Delivery confirmation is tracked separately."
                } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Send rejected or delivery unconfirmed. Check the channel; this draft will not retry automatically." }
            }
        }, onDismiss = { sending = null })
    }
    editing?.let { d -> ReplySheet(d) { msg -> editing = null; if (msg != null) sent = msg } }
}

fun draftState(state: String) = when (state) {
    "SUBMITTED" -> "Sent"; "SENT_CONFIRMED" -> "Confirmed"; "DELIVERED" -> "Delivered"; "READ" -> "Read"; "UNKNOWN" -> "Unconfirmed"
    "FAILED" -> "Failed"; "SENDING" -> "Sending"; "APPROVED" -> "Approved"; "DISCARDED" -> "Discarded"; else -> state.lowercase()
}

@Composable
private fun FilterChips(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("All", "WhatsApp", "SMS", "Email", "Calls").forEach { f ->
            HfChip(f, { onSelect(f) }, style = if (f == selected) ChipStyle.Selected else ChipStyle.Outline)
        }
    }
}

@Composable
private fun MessageRow(m: InboxItem, onClick: () -> Unit) {
    val (bg, fg) = when (m.channel) {
        Channel.WHATSAPP -> Hf.colors.successContainer to Hf.colors.success
        Channel.EMAIL -> Hf.colors.infoContainer to Hf.colors.info
        Channel.SMS -> Hf.colors.primaryContainer to Hf.colors.primary
        Channel.CALL -> Hf.colors.fill to Hf.colors.bodySecondary
        Channel.OTHER -> Hf.colors.fill to Hf.colors.muted
    }
    Row(Modifier.fillMaxWidth().clickableRow(onClick).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text(m.from.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", style = Hf.type.figure.copy(fontSize = 15.sp), color = fg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.from, Modifier.weight(1f), style = Hf.type.rowTitle, color = Hf.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Fmt.relativeDay(m.at), style = Hf.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal), color = Hf.colors.muted)
            }
            Text(m.text.replace('\n', ' '), style = Hf.type.body.copy(fontSize = 13.5.sp), color = Hf.colors.bodySecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.channel.label.removeSuffix("s"), style = Hf.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal), color = Hf.colors.muted)
                m.tag?.let { StatusPill(it, m.tagTone) }
            }
        }
    }
}
