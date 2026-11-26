package com.hellfire.ass.widgets

import com.hellfire.ass.communications.Accounts

import android.content.ComponentName
import android.content.Context
import android.app.NotificationManager
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.notifications.HellFireNotificationListener
import com.hellfire.ass.reports.DateRange
import com.hellfire.ass.reports.posted
import com.hellfire.ass.reports.spend
import com.hellfire.ass.sms.SmsCaptureSettings
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import com.hellfire.ass.finance.FinanceCandidate
import com.hellfire.ass.ui.EventText

const val MASK = "•••••"

/** Owner privacy choices for widgets (§8). Amounts and previews start hidden; "Hide all amounts" overrides every widget. */
object WidgetPrefs {
    val widgets = listOf("briefing" to "Daily briefing", "money" to "Money today", "promises" to "Promises", "ask" to "Ask HellFire",
        "wallets" to "Wallets", "replies" to "Replies waiting", "review" to "Nightly review", "budget" to "Budget")
    private fun prefs(c: Context) = c.getSharedPreferences("widgets", 0)
    fun hideAll(c: Context) = prefs(c).getBoolean("hide_all", false)
    fun setHideAll(c: Context, v: Boolean) = prefs(c).edit().putBoolean("hide_all", v).apply()
    fun shows(c: Context, id: String) = prefs(c).getBoolean("show_$id", false)
    fun setShows(c: Context, id: String, v: Boolean) = prefs(c).edit().putBoolean("show_$id", v).apply()
    /** Effective visibility of amounts or previews on widget [id]. */
    fun visible(c: Context, id: String) = !hideAll(c) && shows(c, id)
    fun budgetCategory(c: Context) = prefs(c).getString("budget_category", "Food")!!
    fun setBudgetCategory(c: Context, v: String) = prefs(c).edit().putString("budget_category", v).apply()
    /** Wallet balances show only for a minute after the owner taps Reveal. */
    fun walletsRevealed(c: Context, now: Long = System.currentTimeMillis()) = !hideAll(c) && prefs(c).getLong("wallets_until", 0) > now
    fun revealWallets(c: Context, until: Long) = prefs(c).edit().putLong("wallets_until", until).apply()
}

fun masked(value: String, visible: Boolean) = if (visible) value else MASK

/** Evening layout from 21:00 East Africa Time, morning otherwise (§8). */
fun isEvening(now: Long) = Instant.ofEpochMilli(now).atZone(TaskTime.zone).hour >= 21

enum class Pace { NoBudget, Under, OnPace, Ahead, Over }

/** Compares the share of the budget used with the share of the month gone. "Ahead" means spending faster than the month. */
fun budgetPace(used: Long, budget: Long?, today: LocalDate): Pace {
    if (budget == null || budget <= 0) return Pace.NoBudget
    if (used > budget) return Pace.Over
    val elapsed = today.dayOfMonth.toDouble() / today.lengthOfMonth()
    val share = used.toDouble() / budget
    return when { share > elapsed + 0.05 -> Pace.Ahead; share < elapsed - 0.15 -> Pace.Under; else -> Pace.OnPace }
}

enum class Health { Good, Partial, Bad }

/** Capture health from the sources the owner turned on: all working is green, some is amber, none is red. */
fun captureHealth(enabled: Int, working: Int): Health = when { enabled == 0 || working == 0 -> Health.Bad; working == enabled -> Health.Good; else -> Health.Partial }

/** Category totals for a stack bar: top four plus "Other". */
fun topCategories(byCategory: Map<String, Long>, count: Int = 4): List<Pair<String, Long>> {
    val sorted = byCategory.filterValues { it > 0 }.entries.sortedByDescending { it.value }
    val top = sorted.take(count).map { it.key to it.value }
    val rest = sorted.drop(count).sumOf { it.value }
    return if (rest > 0) top + ("Other" to rest) else top
}

// ---------------- Loaders (call off the main thread) ----------------

private fun day(d: LocalDate) = d.atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()

data class Spend(val total: Long, val income: Long, val byCategory: Map<String, Long>)

suspend fun spendFor(app: HellFireApp, from: Long, to: Long): Spend {
    val all = posted(app.database.finances().entriesBetween(from, to), app.database.finances().allAccounts())
    val totals = app.finances.summary(from, to)
    return Spend(totals.expenses, totals.income, all.groupBy { it.entry.category }.mapValues { (_, v) -> v.sumOf(::spend) })
}

data class BriefingData(
    val evening: Boolean, val today: LocalDate, val schedule: List<AssistantTask>, val bills: List<AssistantTask>, val spend: Spend,
    val replies: Int, val toReview: Int, val promisesDue: Int,
    val unknownMerchant: FinanceCandidate?, val moreUnknown: Int, val uncertain: Int, val cashGaps: Int,
    val stillOpen: List<AssistantTask>, val tomorrow: List<AssistantTask>,
)

suspend fun loadBriefing(app: HellFireApp, now: Long = System.currentTimeMillis()): BriefingData {
    val today = Instant.ofEpochMilli(now).atZone(TaskTime.zone).toLocalDate()
    val evening = isEvening(now)
    val active = app.database.tasks().active()
    val start = day(today); val end = day(today.plusDays(1)); val end2 = day(today.plusDays(2))
    val candidates = app.database.finances().reviewCandidates()
    val unknown = candidates.filter { c -> c.merchant != null && app.database.finances().merchant(c.provider, c.merchant) == null }
    return BriefingData(
        evening, today,
        schedule = active.filter { it.kind == "TASK" && it.dueAt in start until end }.sortedBy { it.dueAt }.take(12),
        bills = active.filter { it.kind == "BILL" && it.dueAt < day(today.plusDays(15)) }.sortedBy { it.dueAt }.take(8),
        spend = if (evening) spendFor(app, start, end) else spendFor(app, day(today.minusDays(1)), start),
        replies = repliesWaiting(app, now).size + app.database.communications().pendingCount(),
        toReview = candidates.size,
        promisesDue = active.count { it.direction != "NONE" && it.dueAt < end2 },
        unknownMerchant = unknown.firstOrNull(), moreUnknown = (unknown.size - 1).coerceAtLeast(0),
        uncertain = candidates.count { it.reference == null && it.provider != "MANUAL" },
        cashGaps = app.database.tasks().reviewsNow().size,
        stillOpen = active.filter { it.dueAt < now }.sortedBy { it.dueAt }.take(8),
        tomorrow = active.filter { it.dueAt in end until end2 }.sortedBy { it.dueAt }.take(8),
    )
}

/** [syncedAt]: last sync attempt of a polled source (email, WhatsApp); null for sources Android pushes. */
data class SourceStatus(val name: String, val working: Boolean, val syncedAt: Long? = null)

suspend fun loadCaptureSources(app: HellFireApp): List<SourceStatus> {
    val notifications = app.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(app, HellFireNotificationListener::class.java))
    val states = app.database.communications().integrationsNow().associateBy { it.adapter }
    val list = mutableListOf(SourceStatus("Notifications", notifications))
    if (SmsCaptureSettings.enabled(app)) list += SourceStatus("SMS", SmsCaptureSettings.canRead(app))
    val mail = runCatching { Accounts.list(app.secrets, Accounts.MAIL) }.getOrDefault(emptyList())
    for (account in mail) list += SourceStatus(if (mail.size > 1) "Email · ${account.optString("address")}" else "Email",
        states[Accounts.mailState(account.getString("id"))]?.let { it.error == null && it.state !in setOf("FAILED", "AUTH_FAILED", "ERROR") } ?: true,
        states[Accounts.mailState(account.getString("id"))]?.updatedAt)
    val gateways = runCatching { Accounts.gateways(app.secrets) }.getOrDefault(emptyList())
    for (gateway in gateways) list += SourceStatus(if (gateways.size > 1) "WhatsApp · ${Accounts.label(gateway)}" else "WhatsApp",
        states[Accounts.gatewayState(gateway.getString("id"))]?.let { it.error == null && it.state != "FAILED" } ?: true,
        states[Accounts.gatewayState(gateway.getString("id"))]?.updatedAt)
    return list
}

/** Threads whose latest message is incoming and recent: email/WhatsApp by thread, SMS by sender. */
data class Waiting(val channel: String, val sender: String, val preview: String, val at: Long, val link: String)

suspend fun repliesWaiting(app: HellFireApp, now: Long = System.currentTimeMillis()): List<Waiting> {
    val since = now - 7L * 86_400_000
    val drafts = app.database.communications().allDrafts().filter { it.state == "DRAFT" }
    val threads = app.database.communications().messagesIn(since, now + 1).groupBy { it.threadKey }.values
        .map { it.maxBy { m -> m.occurredAt } }.filter { it.direction == "INCOMING" && it.channel != "WHATSAPP_WEB" }
        .map { m ->
            val draft = drafts.firstOrNull { d -> d.replyToMessageId == m.externalId || d.recipient.equals(m.sender, true) }
            Waiting(if (m.channel == "EMAIL_IMAP") "@" else "WA", m.sender.substringBefore('<').trim().ifBlank { m.sender }, m.subject.ifBlank { m.body }.take(60),
                m.occurredAt, draft?.let { "hellfire://draft/${it.id}" } ?: "hellfire://message/${m.id}")
        }
    val sms = app.database.events().labelledPeriod(since, now + 1).filter { it.event.source == "android_sms" && it.label == "MESSAGE" }
        .map { it.event }.groupBy { EventText.fields(app, it)["sender_hint"]?.firstOrNull().orEmpty() }
        .filterKeys { it.isNotBlank() }.map { (sender, list) -> list.maxBy { it.occurredAt }.let { e -> sender to e } }
        .filter { (_, e) -> e.kind != "OUTGOING" }
        .map { (sender, e) -> Waiting("SMS", sender, EventText.body(EventText.fields(app, e)).take(60), e.occurredAt, "hellfire://inbox") }
    return (threads + sms).sortedByDescending { it.at }
}

data class WalletRow(val label: String, val amount: Long?, val note: String?)

suspend fun loadWallets(app: HellFireApp): Pair<List<WalletRow>, Long?> {
    val balances = app.database.widgetData().balances().associateBy { it.provider }
    val accounts = app.database.finances().allAccounts()
    val rows = accounts.map { a ->
        when {
            a.provider in setOf("CASH", "MANUAL") -> WalletRow(a.label, app.database.finances().balance(a.id), "from your ledger")
            balances[a.provider] != null -> WalletRow(a.label, balances.getValue(a.provider).amount, null)
            else -> WalletRow(a.label, null, "no SMS yet")
        }
    }
    return rows to balances.values.maxOfOrNull { it.observedAt }
}

/** One budget line: category, spent this month, limit. */
data class BudgetLine(val category: String, val used: Long, val budget: Long)
data class BudgetData(val category: String, val used: Long, val budget: Long?, val pace: Pace, val daysLeft: Int, val all: List<BudgetLine> = emptyList())

suspend fun loadBudget(app: HellFireApp, now: Long = System.currentTimeMillis()): BudgetData {
    val today = Instant.ofEpochMilli(now).atZone(TaskTime.zone).toLocalDate()
    val category = WidgetPrefs.budgetCategory(app)
    val month = YearMonth.from(today)
    val range = DateRange.month(today)
    val spent = spendFor(app, range.start, range.end).byCategory
    val budgets = app.database.widgetData().budgetsFor(month.toString())
    val used = spent[category] ?: 0
    val budget = budgets.firstOrNull { it.category == category }?.amount
    return BudgetData(category, used, budget, budgetPace(used, budget, today), today.lengthOfMonth() - today.dayOfMonth,
        budgets.map { BudgetLine(it.category, spent[it.category] ?: 0, it.amount) }.sortedByDescending { it.used.toDouble() / it.budget })
}
