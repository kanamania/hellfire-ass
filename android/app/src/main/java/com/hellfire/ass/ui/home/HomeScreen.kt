package com.hellfire.ass.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val skillCount by remember { app.database.skills().pendingCount() }.collectAsStateWithLifecycle(0)
    val db = app.database
    val candidates by remember { db.finances().candidates() }.collectAsStateWithLifecycle(emptyList())
    val drafts by remember { db.communications().drafts() }.collectAsStateWithLifecycle(emptyList())
    val tasks by remember { db.tasks().tasks() }.collectAsStateWithLifecycle(emptyList())
    val reviews by remember { db.tasks().reviews() }.collectAsStateWithLifecycle(emptyList())
    val today = remember { Fmt.today() }
    val start = Fmt.startOf(today); val end = Fmt.startOf(today.plusDays(1))
    val todayEntries by remember(start) { db.finances().periodFlow(start, end) }.collectAsStateWithLifecycle(emptyList())
    var spent by remember { mutableStateOf<Long?>(null) }
    var received by remember { mutableStateOf<Long?>(null) }
    var cloudOn by remember { mutableStateOf(false) }
    LaunchedEffect(todayEntries) {
        runCatching { withContext(Dispatchers.IO) { app.finances.summary(start, end) } }.getOrNull()?.let { spent = it.expenses; received = it.income }
    }
    LaunchedEffect(Unit) { cloudOn = runCatching { withContext(Dispatchers.IO) { app.ai.store.read().cloudEnabled } }.getOrDefault(false) }
    var brief by remember { mutableStateOf<com.hellfire.ass.insights.Brief?>(null) }
    LaunchedEffect(Unit) { brief = com.hellfire.ass.ui.insights.io { com.hellfire.ass.insights.Briefs.load(app) } }
    val now = remember { java.time.ZonedDateTime.now(com.hellfire.ass.tasks.TaskTime.zone) }
    val greeting = when (now.hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

    ScrollPage(gap = 18.dp) {
        ScreenHeader(tr(greeting), overline = DateTimeFormatter.ofPattern("EEEE, d MMMM", UiLanguage.locale).format(now)) {
            IconCircleButton(HfIcons.Reports, "Reports", { nav.push(Screen.Reports) })
            IconCircleButton(HfIcons.Settings, "Settings", { nav.push(Screen.Settings) })
        }
        AskBar(onAsk = { nav.tab(MainTab.Ask) }, onVoice = { nav.tab(MainTab.Ask, "voice") })
        Row(Modifier.padding(start = 4.dp).offset(y = (-8).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (cloudOn) HfIcons.Cloud else HfIcons.Lock, null, Modifier.size(14.dp), tint = Hf.colors.muted)
            Text(if (cloudOn) "Cloud AI on · used only when you choose a cloud model" else "Encrypted on this phone · Cloud AI off",
                style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        }

        ListCard {
            ListRow(tr("Needs attention"), subtitle = tr("Connections, models, backups and recovery"), chevron = true, onClick = { nav.push(Screen.Recovery) })
        }
        val gaps = brief?.sections?.firstOrNull { it.title == "Capture" }?.lines.orEmpty()
        if (gaps.isNotEmpty()) Banner(gaps.first().text + (if (gaps.size > 1) " · and ${gaps.size - 1} more" else "") + ". Tap to fix.", Tone.Warning, HfIcons.Info,
            Modifier.clickableRow { nav.push(Screen.Recovery) })
        ListCard {
            ListRow(if (brief?.evening ?: com.hellfire.ass.insights.Briefs.isEvening(System.currentTimeMillis())) "Evening wrap-up" else "Morning brief",
                subtitle = brief?.headline ?: "Tasks, bills, replies and money in one place", icon = HfIcons.Bell, tone = Tone.Primary, chevron = true,
                onClick = { nav.push(Screen.Brief) })
        }
        val latestNews by remember {
            com.hellfire.ass.ui.news.safeFlow(emptyList(), {}) { app.news.briefings(com.hellfire.ass.ui.news.NewsUi.allSlots, 1) }
        }.collectAsStateWithLifecycle(emptyList())
        ListCard {
            val b = latestNews.firstOrNull()
            ListRow(tr("News"), subtitle = b?.let { "${it.title} · ${com.hellfire.ass.ui.news.NewsUi.itemCount(it.itemsJson)} " + tr("stories") } ?: tr("Briefings from your news sources"),
                icon = HfIcons.Bolt, tone = Tone.Info, chevron = true, onClick = { nav.push(Screen.News) })
        }
        FlowRow(Modifier.offset(y = (-6).dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HfChip(com.hellfire.ass.ui.tr("Record"), { nav.push(Screen.Recordings) })
            HfChip(com.hellfire.ass.ui.tr("Review"), { nav.push(Screen.Review) })
            HfChip(com.hellfire.ass.ui.tr("Templates"), { nav.push(Screen.Templates) })
            HfChip(com.hellfire.ass.ui.tr("Waiting on"), { nav.push(Screen.Waiting) })
            HfChip(com.hellfire.ass.ui.tr("People"), { nav.push(Screen.People) })
            HfChip(com.hellfire.ass.ui.tr("Debts"), { nav.push(Screen.Debts) })
            HfChip(com.hellfire.ass.ui.tr("Bills"), { nav.push(Screen.Bills) })
        }

        val pendingDrafts = drafts.filter { it.state == "DRAFT" }
        val proposals = tasks.filter { it.state == "REVIEW" }
        val needs = candidates.size + pendingDrafts.size + proposals.size + reviews.size + skillCount
        if (needs > 0) ListCard(title = "Needs you", count = needs) {
            ListRow(com.hellfire.ass.ui.tr("Review next"), subtitle = "One queue for payments, tasks and replies", chevron = true, onClick = { nav.push(Screen.Review) })
            ListDivider()
            if (skillCount > 0) ListRow(tr("Review AI skills") + " ($skillCount)", subtitle = tr("Learned from your corrections"), chevron = true, onClick = { nav.push(Screen.Skills) })
            var first = true
            @Composable fun divider() { if (!first) ListDivider(); first = false }
            if (candidates.isNotEmpty()) {
                divider()
                ListRow(if (candidates.size == 1) "Confirm 1 payment" else "Confirm ${candidates.size} payments",
                    subtitle = "Review the account, category and amount",
                    icon = HfIcons.Money, tone = Tone.Info, chevron = true, onClick = { nav.tab(MainTab.Money, "review") })
            }
            if (pendingDrafts.isNotEmpty()) {
                divider()
                val d = pendingDrafts.first()
                ListRow(if (pendingDrafts.size == 1) "Approve a reply to ${d.recipient}" else "Approve ${pendingDrafts.size} replies",
                    subtitle = "${Fmt.channel(d.channel)} draft is ready", icon = HfIcons.Chat, tone = Tone.Success, chevron = true,
                    onClick = { nav.tab(MainTab.Inbox, "draft:${d.id}") })
            }
            if (proposals.isNotEmpty()) {
                divider()
                ListRow(if (proposals.size == 1) "Check 1 new task" else "Check ${proposals.size} new tasks",
                    subtitle = "Found in your messages · not scheduled yet", icon = HfIcons.Task, tone = Tone.Primary, chevron = true,
                    onClick = { nav.tab(MainTab.Tasks) })
            }
            if (reviews.isNotEmpty()) {
                divider()
                ListRow(com.hellfire.ass.ui.tr("Finish the nightly review"), subtitle = reviews.joinToString { it.day } + " · not reviewed yet",
                    icon = HfIcons.Alarm, tone = Tone.Warning, chevron = true, onClick = { nav.tab(MainTab.Money, "review") })
            }
            Spacer(Modifier.height(6.dp))
        }

        TwoColumnGrid(listOf(
            { m -> SummaryTile("Spent today", spent?.let(Fmt::amount) ?: "—",
                "TZS · " + todayEntries.count { it.kind == "EXPENSE" && it.reversesId == null }.let { if (it == 1) "1 payment" else "$it payments" },
                null, m) { nav.tab(MainTab.Money) } },
            { m -> SummaryTile("Received today", received?.let { if (it > 0) Fmt.signed(it) else "0" } ?: "—",
                "TZS · " + todayEntries.count { it.kind == "INCOME" && it.reversesId == null }.let { if (it == 1) "1 transfer" else "$it transfers" },
                Hf.colors.success.takeIf { (received ?: 0) > 0 }, m) { nav.tab(MainTab.Money) } },
        ))
        Note(com.hellfire.ass.ui.tr("Only payments you confirmed count here."), Modifier.offset(y = (-8).dp))

        val nowMs = System.currentTimeMillis()
        val horizon = Fmt.startOf(today.plusDays(2))
        val upNext = tasks.filter { it.state == "ACTIVE" && it.dueAt < horizon }.sortedBy { it.dueAt }.take(4)
        val nightly = app.getSharedPreferences("capture", 0).getBoolean("nightly_review", true)
        if (upNext.isNotEmpty() || nightly) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(com.hellfire.ass.ui.tr("Up next"), action = "All tasks", onAction = { nav.tab(MainTab.Tasks) })
            HfCard {
                upNext.forEachIndexed { i, t ->
                    if (i > 0) ListDivider(72.dp)
                    UpNextRow(if (t.dueAt < start + 86_400_000L && t.dueAt >= start) Fmt.time(t.dueAt) else Fmt.day(t.dueAt), t.title,
                        when { t.dueAt < nowMs -> "Overdue"; else -> "Reminder" }, t.dueAt < nowMs, highlight = i == 0) { nav.push(Screen.Task, t.id) }
                }
                if (nightly) {
                    if (upNext.isNotEmpty()) ListDivider(72.dp)
                    UpNextRow("21:00", "Nightly cash review", "Daily", false, highlight = upNext.isEmpty()) { nav.push(Screen.Reminders) }
                }
            }
        }
    }
}

@Composable
private fun AskBar(onAsk: () -> Unit, onVoice: () -> Unit) {
    val c = Hf.colors
    Row(
        Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(c.surface).border(1.dp, c.outline, CircleShape)
            .clickable(role = Role.Button, onClick = onAsk).padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(HfIcons.Spark, null, Modifier.size(22.dp), tint = c.primary)
        Text("Ask anything, or say \"log cash\"", Modifier.weight(1f), style = Hf.type.body.copy(fontSize = 15.sp), color = c.muted, maxLines = 1)
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.primaryContainer).clickable(role = Role.Button, onClick = onVoice)
                .semantics { contentDescription = "Ask by voice" },
            contentAlignment = Alignment.Center,
        ) { Icon(HfIcons.Mic, null, Modifier.size(18.dp), tint = c.primary) }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, detail: String, valueColor: androidx.compose.ui.graphics.Color?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(Hf.colors.surface).border(1.dp, Hf.colors.outline, shape).clickableRow(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = Hf.type.rowSubtitle.copy(fontWeight = FontWeight.Medium), color = Hf.colors.muted)
        Text(value, style = Hf.type.figure, color = valueColor ?: Hf.colors.onSurface, maxLines = 1)
        Text(detail, style = Hf.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal), color = Hf.colors.muted)
    }
}

@Composable
private fun UpNextRow(time: String, title: String, trailing: String, warn: Boolean, highlight: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickableRow(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(time, Modifier.width(44.dp), style = Hf.type.figure.copy(fontSize = 13.sp),
            color = if (highlight) Hf.colors.primary else Hf.colors.muted, maxLines = 1)
        Text(title, Modifier.weight(1f), style = Hf.type.body.copy(fontWeight = FontWeight.Medium), color = Hf.colors.onSurface, maxLines = 2)
        Text(trailing, style = Hf.type.caption.copy(fontSize = 12.sp, fontWeight = if (warn) FontWeight.SemiBold else FontWeight.Normal),
            color = if (warn) Hf.colors.warning else Hf.colors.muted)
    }
}
