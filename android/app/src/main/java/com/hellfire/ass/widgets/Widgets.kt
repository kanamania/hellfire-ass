package com.hellfire.ass.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.audio.MicrophoneStatus
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.hellfire.ass.ui.money.candidateTitle

/** Base for HellFire widgets: loads data off the main thread, then renders in the token theme. Never sends or posts anything. */
abstract class HfWidget<T : Any>(private val title: String, private val fallback: String) : GlanceAppWidget() {
    /** Exact sizing: each placed size renders its own layout, so a large widget shows everything that fits. */
    override val sizeMode: SizeMode = SizeMode.Exact
    abstract suspend fun load(app: HellFireApp): T
    @Composable abstract fun Content(data: T, context: Context)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as HellFireApp
        val data = runCatching { withContext(Dispatchers.IO) { load(app) } }.getOrNull()
        provideContent { GlanceTheme(colors = WidgetColors.providers) { if (data == null) Unavailable(title, fallback) else Content(data, LocalContext.current) } }
    }
}

/** How many rows of [row] dp fit after [reserved] dp of fixed content. */
private fun DpSize.rows(reserved: Int, row: Int, min: Int = 1) = ((height.value - reserved) / row).toInt().coerceAtLeast(min)
private val DpSize.tall get() = height.value >= 300
private val DpSize.wide get() = width.value >= 250
private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
private fun asOf(now: Long = System.currentTimeMillis()) = "as of ${Fmt.time(now)}"
private fun shortDay(ms: Long) = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).format(Fmt.zoned(ms))
private fun dueChip(ms: Long, now: Long): Pair<String, ChipTone> {
    val hours = (ms - now) / 3_600_000
    return when { ms < now -> "overdue" to ChipTone.Crit; hours < 24 -> "in ${hours.coerceAtLeast(1)}h" to ChipTone.Crit
        hours < 72 -> DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).format(Fmt.zoned(ms)) to ChipTone.Warn; else -> Fmt.day(ms) to ChipTone.Neutral }
}

@Composable
private fun TaskLine(t: AssistantTask, now: Long, showAmounts: Boolean, time: Boolean = true) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openApp(context, "hellfire://task/${t.id}")), verticalAlignment = Alignment.CenterVertically) {
        if (time) Text(if (t.dueAt < now) "—" else Fmt.time(t.dueAt), GlanceModifier.width(40.dp),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        Body(t.title + (t.amount?.let { " " + masked(Fmt.amount(it), showAmounts) } ?: ""), GlanceModifier.defaultWeight())
        when (t.direction) { "I_OWE" -> Chip("I owe", ChipTone.Crit); "OWED_TO_ME" -> Chip("owed to me") }
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

// ---------------- Daily briefing 4×5 ----------------

class BriefingWidget : HfWidget<BriefingData>("Briefing", "hellfire://home") {
    override suspend fun load(app: HellFireApp) = loadBriefing(app)
    @Composable override fun Content(data: BriefingData, context: Context) {
        val show = WidgetPrefs.visible(context, "briefing")
        val now = System.currentTimeMillis()
        val size = LocalSize.current
        val roomy = size.height.value >= 460
        val n = if (roomy) 6 else 3
        val parts = topCategories(data.spend.byCategory)
        WidgetCard("hellfire://home", padding = 14) {
            Head(if (data.evening) "Good evening" else "Good morning", shortDay(now))
            Gap(8)
            if (!data.evening || roomy) {
                Label("Today")
                if (data.schedule.isEmpty()) Meta("Nothing scheduled") else data.schedule.take(n).forEach { TaskLine(it, now, show) }
                if (data.schedule.size > n) Meta("+${data.schedule.size - n} more")
                if (data.bills.isNotEmpty()) {
                    Gap(8); Label("Bills coming up")
                    data.bills.take(n).forEach { b -> Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openApp(context, "hellfire://task/${b.id}")), verticalAlignment = Alignment.CenterVertically) {
                        Body(b.title, GlanceModifier.defaultWeight())
                        b.amount?.let { Body(masked(Fmt.amount(it), show), GlanceModifier.padding(end = 6.dp)) }
                        dueChip(b.dueAt, now).let { (t, tone) -> Chip(t, tone) }
                    } }
                }
            }
            Gap(8)
            Row(GlanceModifier.fillMaxWidth()) {
                Label(if (data.evening) "Spent today" else "Spent yesterday"); Spacer(GlanceModifier.defaultWeight())
                Body("TZS " + masked(Fmt.amount(data.spend.total), show), bold = true)
            }
            if (parts.isNotEmpty()) {
                Gap(4)
                BitmapImage(WidgetBitmaps.stackBar(context, parts.map { it.second }), "Spending by category", GlanceModifier.fillMaxWidth().height(10.dp))
                if (roomy) parts.forEach { (k, v) -> Row(GlanceModifier.fillMaxWidth()) { Meta(k); Spacer(GlanceModifier.defaultWeight()); Meta(masked(Fmt.amount(v), show)) } }
                else Meta(parts.joinToString("  ") { it.first })
            }
            if (!data.evening) {
                Gap(8)
                Row {
                    if (data.replies > 0) { Chip(plural(data.replies, "reply", "replies") + " waiting", ChipTone.Accent, openApp(context, "hellfire://inbox")); Spacer(GlanceModifier.width(6.dp)) }
                    if (data.toReview > 0) { Chip("${data.toReview} to review tonight", ChipTone.Accent, openApp(context, "hellfire://money/review")); Spacer(GlanceModifier.width(6.dp)) }
                    if (data.promisesDue > 0) Chip(plural(data.promisesDue, "promise", "promises") + " due", action = openApp(context, "hellfire://tasks"))
                }
            }
            if (data.evening || roomy) {
                Gap(8)
                Row(verticalAlignment = Alignment.CenterVertically) { Label("Needs review"); Spacer(GlanceModifier.width(6.dp)); Chip("${data.toReview}", ChipTone.Warn) }
                data.unknownMerchant?.let { c -> Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Body("Lipa Namba ${c.merchant} · ${Fmt.time(c.occurredAt)}", GlanceModifier.defaultWeight())
                    Body(masked(Fmt.amount(c.amount), show), GlanceModifier.padding(horizontal = 6.dp))
                    TonalButton("Name", openApp(context, "hellfire://money/review"))
                } }
                if (data.moreUnknown > 0) Meta(plural(data.moreUnknown, "more unknown merchant", "more unknown merchants"))
                if (data.uncertain + data.cashGaps > 0) Meta("${data.uncertain} without a reference · ${data.cashGaps} cash days to check")
                if (data.stillOpen.isNotEmpty()) { Gap(6); Label("Still open"); data.stillOpen.take(n).forEach { TaskLine(it, now, show, time = false) } }
                if (data.tomorrow.isNotEmpty()) { Gap(6); Label("Tomorrow · ${shortDay(now + 86_400_000)}"); data.tomorrow.take(n).forEach { TaskLine(it, now, show) } }
            }
            Spacer(GlanceModifier.defaultWeight())
            if (data.evening) FilledButton("Start nightly review", openApp(context, "hellfire://money/review"), GlanceModifier.fillMaxWidth())
            Meta(asOf(now))
        }
    }
}

// ---------------- Capture health 1×1 ----------------

class CaptureHealthWidget : HfWidget<List<SourceStatus>>("Capture", "hellfire://settings/permissions") {
    override suspend fun load(app: HellFireApp) = loadCaptureSources(app)
    @Composable override fun Content(data: List<SourceStatus>, context: Context) {
        val working = data.count { it.working }
        val dot = when (captureHealth(data.size, working)) { Health.Good -> WidgetColors.success; Health.Partial -> WidgetColors.warning; Health.Bad -> WidgetColors.danger }
        val size = LocalSize.current
        WidgetCard("hellfire://settings/permissions", padding = if (size.wide) 12 else 8) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(10.dp).background(dot).cornerRadius(5.dp)) {}
                if (size.wide) { Spacer(GlanceModifier.width(8.dp)); Body("Capture", bold = true) }
            }
            if (size.wide || size.height.value >= 150) {
                Gap(6)
                data.forEach { src -> Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.size(8.dp).background(if (src.working) WidgetColors.success else WidgetColors.danger).cornerRadius(4.dp)) {}
                    Spacer(GlanceModifier.width(8.dp)); Body(src.name, GlanceModifier.defaultWeight(), size = 12)
                    Meta(if (src.working) "working" else "needs attention")
                } }
            }
            Spacer(GlanceModifier.defaultWeight())
            Big("$working/${data.size}", size = 18)
            Meta("sources")
        }
    }
}

// ---------------- Shortcuts 1×1 ----------------

open class ShortcutWidget(private val label: String, private val glyph: String, private val link: String) : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        GlanceTheme(colors = WidgetColors.providers) {
            WidgetCard(link, padding = 8) {
                Box(GlanceModifier.size(32.dp).background(GlanceTheme.colors.primaryContainer).cornerRadius(16.dp), contentAlignment = Alignment.Center) {
                    Text(glyph, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
                Spacer(GlanceModifier.defaultWeight())
                Meta(label)
            }
        }
    }
}
class AskShortcutWidget : ShortcutWidget("Ask", "✦", "hellfire://ask?voice=1")
class CashShortcutWidget : ShortcutWidget("Cash", "+", "hellfire://money/cash")
class NoteShortcutWidget : ShortcutWidget("Note", "✎", "hellfire://note")

// ---------------- Money today 4×2 ----------------

data class MoneyToday(val spend: Spend, val week: List<Long>, val weekStart: Long, val payments: List<Pair<String, Long>> = emptyList())

class MoneyTodayWidget : HfWidget<MoneyToday>("Money today", "hellfire://money") {
    override suspend fun load(app: HellFireApp): MoneyToday {
        val today = Fmt.today()
        val week = (6 downTo 0).map { d -> val day = today.minusDays(d.toLong()); spendFor(app, Fmt.startOf(day), Fmt.startOf(day.plusDays(1))).total }
        val payments = app.database.finances().period(Fmt.startOf(today), Fmt.startOf(today.plusDays(1)))
            .filter { it.reversesId == null && it.kind in setOf("EXPENSE", "INCOME", "REFUND") }.sortedByDescending { it.occurredAt }
            .map { it.description.lineSequence().first().take(40) to it.amount }
        return MoneyToday(spendFor(app, Fmt.startOf(today), Fmt.startOf(today.plusDays(1))), week, Fmt.startOf(today.minusDays(6)), payments)
    }
    @Composable override fun Content(data: MoneyToday, context: Context) {
        val show = WidgetPrefs.visible(context, "money")
        val now = System.currentTimeMillis()
        WidgetCard("hellfire://money") {
            Head("Money today", shortDay(now))
            Gap(4)
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Label("Spent")
                    Big("TZS " + masked(Fmt.amount(data.spend.total), show), size = 20)
                    Meta("In " + masked("+" + Fmt.amount(data.spend.income), show))
                }
                Column(horizontalAlignment = Alignment.End) {
                    BitmapImage(WidgetBitmaps.sparkline(context, data.week), "Spending over the last 7 days", GlanceModifier.width(120.dp).height(40.dp))
                    Meta(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).format(Fmt.zoned(data.weekStart)) + "  …  " + DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).format(Fmt.zoned(now)))
                }
            }
            val size = LocalSize.current
            if (size.tall) {
                val cats = topCategories(data.spend.byCategory)
                if (cats.isNotEmpty()) {
                    Gap(8); Label("By category")
                    BitmapImage(WidgetBitmaps.stackBar(context, cats.map { it.second }), "Today's spending by category", GlanceModifier.fillMaxWidth().height(10.dp))
                    cats.forEach { (k, v) -> Row(GlanceModifier.fillMaxWidth()) { Body(k, GlanceModifier.defaultWeight(), size = 12); Body(masked(Fmt.amount(v), show), size = 12) } }
                }
                if (data.payments.isNotEmpty()) {
                    Gap(8); Label("Confirmed today")
                    val n = size.rows(reserved = 230 + cats.size * 18, row = 20)
                    data.payments.take(n).forEach { (d, v) -> Row(GlanceModifier.fillMaxWidth()) {
                        Body(d, GlanceModifier.defaultWeight(), size = 12); Body(masked(if (v > 0) "+" + Fmt.amount(v) else Fmt.amount(v), show), size = 12)
                    } }
                    if (data.payments.size > n) Meta("+${data.payments.size - n} more")
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!size.tall) topCategories(data.spend.byCategory, 2).filter { it.first != "Other" }.forEach { (k, v) -> Chip("$k ${masked(Fmt.amount(v), show)}"); Spacer(GlanceModifier.width(6.dp)) }
                Spacer(GlanceModifier.defaultWeight())
                FilledButton("+ Cash", openApp(context, "hellfire://money/cash"))
            }
            Meta(asOf(now))
        }
    }
}

// ---------------- Promises 4×2 ----------------

val TaskIdKey = ActionParameters.Key<String>("taskId")

/** The owner's checkbox tap marks a promise done, exactly like the app's checklist (audited, reminders re-armed). */
class MarkPromiseDone : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        val app = context.applicationContext as HellFireApp
        runCatching { withContext(Dispatchers.IO) { TaskScheduler.action(app, id, "DONE") } }
        Widgets.refresh(context)
    }
}

class PromisesWidget : HfWidget<List<AssistantTask>>("Promises", "hellfire://tasks") {
    override suspend fun load(app: HellFireApp) = app.database.tasks().all().filter { it.state == "ACTIVE" && it.direction != "NONE" }.sortedBy { it.dueAt }
    @Composable override fun Content(data: List<AssistantTask>, context: Context) {
        val show = WidgetPrefs.visible(context, "promises")
        val now = System.currentTimeMillis()
        WidgetCard("hellfire://tasks") {
            Head("Promises", "${data.size} open")
            Gap(6)
            Row(GlanceModifier.fillMaxWidth()) {
                listOf("I owe" to "I_OWE", "Owed to me" to "OWED_TO_ME").forEachIndexed { i, (label, dir) ->
                    if (i > 0) Spacer(GlanceModifier.width(10.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Label(label)
                        val all = data.filter { it.direction == dir }
                        val items = all.take(LocalSize.current.rows(reserved = 80, row = 46, min = 2))
                        if (items.isEmpty()) Meta("Nothing open")
                        items.forEach { t ->
                            Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                CheckBox(false, actionRunCallback<MarkPromiseDone>(actionParametersOf(TaskIdKey to t.id)))
                                Column(GlanceModifier.defaultWeight().clickable(openApp(context, "hellfire://task/${t.id}"))) {
                                    Body(t.title + (t.amount?.let { " " + masked(Fmt.amount(it), show) } ?: ""), bold = true, size = 12)
                                    dueChip(t.dueAt, now).let { (text, tone) -> if (tone == ChipTone.Crit) Chip(text, tone) else Meta(shortDay(t.dueAt)) }
                                }
                            }
                        }
                        if (all.size > items.size) Meta("+${all.size - items.size} more")
                    }
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Meta(asOf(now))
        }
    }
}

// ---------------- Ask HellFire 2×2 ----------------

class AskWidget : HfWidget<Pair<String, String>>("HellFire", "hellfire://ask") {
    override suspend fun load(app: HellFireApp): Pair<String, String> =
        app.secrets.read("ask-last")?.let { it.optString("q") to it.optString("a") } ?: ("" to "")
    @Composable override fun Content(data: Pair<String, String>, context: Context) {
        val show = WidgetPrefs.visible(context, "ask")
        WidgetCard("hellfire://ask") {
            Head("HellFire")
            Gap(4)
            if (data.first.isBlank()) Meta("Ask about your money, tasks and messages.", maxLines = 3)
            else {
                Meta("You asked: " + masked(data.first, show), maxLines = 2)
                Body(if (show) data.second else "Answer hidden · tap to open", maxLines = LocalSize.current.rows(reserved = 100, row = 17, min = 2))
            }
            Spacer(GlanceModifier.defaultWeight())
            Chip("Ask anything…", ChipTone.Accent, openApp(context, "hellfire://ask"))
        }
    }
}

// ---------------- Record 2×2 ----------------

data class RecordState(val recording: Boolean, val startedAt: Long, val savedAt: Long)

class RecordWidget : HfWidget<RecordState>("Record", "hellfire://settings/permissions") {
    override suspend fun load(app: HellFireApp): RecordState {
        val p = app.getSharedPreferences("widgets", 0)
        return RecordState(MicrophoneStatus.busy.value && p.getLong("record_started_at", 0) > 0, p.getLong("record_started_at", 0), p.getLong("record_saved_at", 0))
    }
    @Composable override fun Content(data: RecordState, context: Context) {
        val open = actionStartActivity(Intent(context, RecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val now = System.currentTimeMillis()
        Column(GlanceModifier.fillMaxSize().background(if (data.recording) WidgetColors.recording else GlanceTheme.colors.surface).cornerRadius(22.dp).padding(12.dp).clickable(open)) {
            val ink = if (data.recording) androidx.glance.color.ColorProvider(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.White) else GlanceTheme.colors.onSurface
            Row(GlanceModifier.fillMaxWidth()) {
                Text("Record", GlanceModifier.defaultWeight(), style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                Text(when { data.recording -> "Recording"; now - data.savedAt < 10 * 60_000 -> "Saved"; else -> "Ready" }, style = TextStyle(color = ink, fontSize = 11.sp))
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(if (data.recording) "since ${Fmt.time(data.startedAt)}" else "●", style = TextStyle(color = if (data.recording) ink else WidgetColors.recording, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.defaultWeight())
            if (LocalSize.current.tall) Text("Consent is asked once per session. Excluded people and places are checked first. Recording never restarts on its own.",
                GlanceModifier.padding(bottom = 6.dp), style = TextStyle(color = ink, fontSize = 11.sp), maxLines = 3)
            Text(when {
                data.recording -> "Tap to stop. Also shown in the notification bar."
                now - data.savedAt < 10 * 60_000 -> "Saved, transcript queued."
                else -> "Tap to start. Audio stays encrypted on the phone."
            }, style = TextStyle(color = ink, fontSize = 11.sp), maxLines = 2)
        }
    }
}

// ---------------- Wallets 4×2 ----------------

class RevealWallets : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val revealed = WidgetPrefs.walletsRevealed(context)
        WidgetPrefs.revealWallets(context, if (revealed) 0 else System.currentTimeMillis() + 60_000)
        if (!revealed) WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInitialDelay(61, TimeUnit.SECONDS).build())
        WalletsWidget().update(context, glanceId)
    }
}

/** One-shot refresh that hides wallet balances again after the reveal minute. Not a periodic timer. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { WalletsWidget().updateAll(applicationContext); return Result.success() }
}

class WalletsWidget : HfWidget<Pair<List<WalletRow>, Long?>>("Wallets", "hellfire://money/accounts") {
    override suspend fun load(app: HellFireApp) = loadWallets(app)
    @Composable override fun Content(data: Pair<List<WalletRow>, Long?>, context: Context) {
        val show = WidgetPrefs.walletsRevealed(context)
        WidgetCard("hellfire://money/accounts") {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Wallets", GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                TonalButton(if (show) "Hide" else "Tap to reveal", actionRunCallback<RevealWallets>())
            }
            Gap(4)
            if (data.first.isEmpty()) Meta("Add accounts in Money to see balances.")
            val size = LocalSize.current
            val fit = size.rows(reserved = 70, row = 20, min = 3)
            data.first.take(fit).forEach { w -> Row(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Body(w.label, GlanceModifier.defaultWeight())
                if (w.amount == null) Meta(w.note.orEmpty()) else Body(masked(Fmt.amount(w.amount), show), bold = true)
            } }
            if (data.first.size > fit) Meta("+${data.first.size - fit} more accounts")
            if (size.tall && data.first.any { it.amount != null }) {
                Gap(6)
                Row(GlanceModifier.fillMaxWidth()) { Label("Total shown"); Spacer(GlanceModifier.defaultWeight()); Body(masked(Fmt.amount(data.first.sumOf { it.amount ?: 0 }), show), bold = true) }
                Meta("SMS figures are the last reported balance. Other accounts use confirmed ledger entries.", maxLines = 2)
            }
            Spacer(GlanceModifier.defaultWeight())
            Meta("Last balance from SMS" + (data.second?.let { " · as of ${Fmt.dayTime(it)}" } ?: " · none yet"))
        }
    }
}

// ---------------- Replies waiting 4×2 ----------------

class RepliesWidget : HfWidget<List<Waiting>>("Replies waiting", "hellfire://inbox") {
    override suspend fun load(app: HellFireApp) = repliesWaiting(app)
    @Composable override fun Content(data: List<Waiting>, context: Context) {
        val show = WidgetPrefs.visible(context, "replies")
        WidgetCard("hellfire://inbox") {
            Head("Replies waiting", "${data.size}")
            Gap(4)
            if (data.isEmpty()) Meta("Nothing waiting.")
            val fit = LocalSize.current.rows(reserved = 90, row = 26, min = 3)
            data.take(fit).forEach { w -> Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openApp(context, w.link)), verticalAlignment = Alignment.CenterVertically) {
                Chip(w.channel); Spacer(GlanceModifier.width(6.dp))
                Body(w.sender + " · " + masked(w.preview, show), GlanceModifier.defaultWeight())
                Meta(Fmt.age(w.at))
            } }
            if (data.size > fit) Meta("+${data.size - fit} more in Inbox")
            Spacer(GlanceModifier.defaultWeight())
            Meta("Replies open in the app for your approval. Nothing sends from the widget.", maxLines = 2)
        }
    }
}

// ---------------- Nightly review 2×2 ----------------

data class ReviewItem(val title: String, val detail: String, val amount: Long, val income: Boolean)
data class ReviewCounts(val total: Int, val unknown: Int, val uncertain: Int, val cashGaps: Int, val items: List<ReviewItem> = emptyList())

class ReviewWidget : HfWidget<ReviewCounts>("Nightly review", "hellfire://money/review") {
    override suspend fun load(app: HellFireApp): ReviewCounts {
        val c = app.database.finances().allCandidates().filter { it.state == "REVIEW" }
        val items = c.take(12).map { x ->
            val alias = x.merchant?.let { app.database.finances().merchant(x.provider, it) }
            ReviewItem(candidateTitle(x, alias), Fmt.relativeDay(x.occurredAt), x.amount, x.kind == "INCOME" || x.kind == "REFUND")
        }
        return ReviewCounts(c.size, c.count { x -> x.merchant != null && app.database.finances().merchant(x.provider, x.merchant) == null },
            c.count { it.reference == null && it.provider != "MANUAL" }, app.database.tasks().reviewsNow().size, items)
    }
    @Composable override fun Content(data: ReviewCounts, context: Context) {
        WidgetCard("hellfire://money/review") {
            Head("Nightly review")
            Big("${data.total}", size = 28)
            Meta(plural(data.unknown, "unknown merchant", "unknown merchants"))
            Meta("${data.uncertain} no reference · ${data.cashGaps} cash days")
            val size = LocalSize.current
            if (size.tall && data.items.isNotEmpty()) {
                val show = WidgetPrefs.visible(context, "review")
                Gap(8)
                val fit = size.rows(reserved = 190, row = 34, min = 1)
                data.items.take(fit).forEach { item -> Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) { Body(item.title, size = 12, bold = true); Meta(item.detail) }
                    Body(masked((if (item.income) "+" else Fmt.MINUS) + Fmt.amount(item.amount), show), size = 12)
                } }
                if (data.items.size > fit) Meta("+${data.items.size - fit} more")
            }
            Spacer(GlanceModifier.defaultWeight())
            FilledButton("Review now", openApp(context, "hellfire://money/review"), GlanceModifier.fillMaxWidth())
        }
    }
}

// ---------------- Budget 2×2 ----------------

class BudgetWidget : HfWidget<BudgetData>("Budget", "hellfire://money/budgets") {
    override suspend fun load(app: HellFireApp) = loadBudget(app)
    @Composable override fun Content(data: BudgetData, context: Context) {
        val show = WidgetPrefs.visible(context, "budget")
        val link = if (data.budget == null) "hellfire://money/budgets" else "hellfire://report/money?category=${android.net.Uri.encode(data.category)}"
        WidgetCard(link) {
            Head("${data.category} budget")
            if (data.budget == null) { Gap(6); Meta("Set a monthly limit in Money › Budgets.", maxLines = 3); return@WidgetCard }
            val fraction = data.used.toFloat() / data.budget
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BitmapImage(WidgetBitmaps.ring(context, fraction, "${(fraction * 100).toInt()}%", data.pace == Pace.Ahead || data.pace == Pace.Over),
                    "${(fraction * 100).toInt()} percent of the ${data.category} budget used", GlanceModifier.size(64.dp))
                Spacer(GlanceModifier.width(8.dp))
                Column {
                    Body(masked(Fmt.amount(data.used), show), bold = true)
                    Meta("of " + masked(Fmt.amount(data.budget), show))
                }
            }
            val size = LocalSize.current
            if (size.tall && data.all.size > 1) {
                Gap(8); Label("All budgets this month")
                data.all.take(size.rows(reserved = 200, row = 30, min = 1)).forEach { line ->
                    Row(GlanceModifier.fillMaxWidth()) { Body(line.category, GlanceModifier.defaultWeight(), size = 12); Meta(masked(Fmt.amount(line.used), show) + " / " + masked(Fmt.amount(line.budget), show)) }
                    BitmapImage(WidgetBitmaps.stackBar(context, listOf(line.used.coerceAtMost(line.budget), (line.budget - line.used).coerceAtLeast(0))),
                        "${line.category} budget used", GlanceModifier.fillMaxWidth().height(6.dp))
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            when (data.pace) {
                Pace.Over -> Chip("Over budget · ${data.daysLeft} days left", ChipTone.Crit)
                Pace.Ahead -> Chip("Ahead of pace · ${data.daysLeft} days left", ChipTone.Warn)
                else -> Chip("On pace · ${data.daysLeft} days left")
            }
        }
    }
}

// ---------------- Receivers and refresh ----------------

class BriefingReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = BriefingWidget() }
class CaptureHealthReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = CaptureHealthWidget() }
class AskShortcutReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = AskShortcutWidget() }
class CashShortcutReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = CashShortcutWidget() }
class NoteShortcutReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NoteShortcutWidget() }
class MoneyTodayReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MoneyTodayWidget() }
class PromisesReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = PromisesWidget() }
class AskReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = AskWidget() }
class RecordReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = RecordWidget() }
class WalletsReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WalletsWidget() }
class RepliesReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = RepliesWidget() }
class ReviewReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ReviewWidget() }
class BudgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = BudgetWidget() }

object Widgets {
    private val data: List<() -> GlanceAppWidget> = listOf(::BriefingWidget, ::CaptureHealthWidget, ::MoneyTodayWidget, ::PromisesWidget, ::AskWidget,
        ::RecordWidget, ::WalletsWidget, ::RepliesWidget, ::ReviewWidget, ::BudgetWidget)
    /** Re-renders every placed data widget. Called when the ledger, tasks, inbox or settings change; there is no periodic timer. */
    suspend fun refresh(context: Context) { data.forEach { runCatching { it().updateAll(context) } } }
    suspend fun refreshRecord(context: Context) { runCatching { RecordWidget().updateAll(context) } }
}
