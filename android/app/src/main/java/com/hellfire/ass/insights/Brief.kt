package com.hellfire.ass.insights

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.MainActivity
import com.hellfire.ass.R
import com.hellfire.ass.finance.Money
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.Fmt
import java.time.Instant
import java.time.LocalDate

/** [link] is a hellfire:// deep link the line opens. [warn] marks overdue, short or broken things. */
data class BriefLine(val text: String, val detail: String? = null, val link: String? = null, val warn: Boolean = false)
data class BriefSection(val title: String, val lines: List<BriefLine>)
data class Brief(val evening: Boolean, val day: LocalDate, val headline: String, val sections: List<BriefSection>) {
    /** Plain text for Ask and the voice assistant. */
    fun text(): String = "${if (evening) "Evening wrap-up" else "Morning brief"} · $day\n$headline\n" + sections.joinToString("\n") { s ->
        "${s.title}:\n" + s.lines.joinToString("\n") { "- " + it.text + (it.detail?.let { d -> " ($d)" } ?: "") }
    }
}

/** Everything the brief is built from. [spent] and [received] cover today in the evening and yesterday in the morning. */
data class BriefInputs(val evening: Boolean, val now: Long, val tasks: List<AssistantTask>, val spent: Long, val received: Long,
    val toConfirm: Int, val reviewsPending: Int, val waiting: WaitingOn, val debts: List<DebtBalance>, val forecast: BillForecast, val gaps: List<CaptureGap>, val calendar: com.hellfire.ass.calendar.CalendarAgenda = com.hellfire.ass.calendar.CalendarAgenda("NOT_CHECKED"))

object Briefs {
    private const val CHANNEL = "daily-brief"
    private const val DAY = 86_400_000L

    /** Evening layout from 17:00 East Africa Time. */
    fun isEvening(now: Long) = Instant.ofEpochMilli(now).atZone(TaskTime.zone).hour >= 17

    private fun taskLine(t: AssistantTask, now: Long, withDay: Boolean) = BriefLine(
        (if (withDay) Fmt.dayTime(t.dueAt) else Fmt.time(t.dueAt)) + " · " + t.title.take(90),
        listOfNotNull(if (t.dueAt < now) "overdue" else null, t.amount?.let(Money::display)).joinToString(" · ").ifEmpty { null },
        "hellfire://task/${t.id}", warn = t.dueAt < now)

    fun build(i: BriefInputs): Brief {
        val day = Instant.ofEpochMilli(i.now).atZone(TaskTime.zone).toLocalDate()
        val start = Fmt.startOf(day); val end = Fmt.startOf(day.plusDays(1)); val end2 = Fmt.startOf(day.plusDays(2))
        val active = i.tasks.filter { it.state == "ACTIVE" }.sortedBy { it.dueAt }
        val proposals = i.tasks.count { it.state == "REVIEW" }
        val sections = mutableListOf<BriefSection>()
        fun section(title: String, lines: List<BriefLine>) { if (lines.isNotEmpty()) sections += BriefSection(title, lines) }

        val overdue = active.filter { it.dueAt < start }
        val today = active.filter { it.dueAt in start until end }
        val tomorrow = active.filter { it.dueAt in end until end2 }
        val billsSoon = i.forecast.due.filter { it.dueAt < i.now + 7 * DAY }
        val money = buildList {
            add(BriefLine("${if (i.evening) "Today" else "Yesterday"}: spent ${Money.display(i.spent)} · received ${Money.display(i.received)}", link = "hellfire://money"))
            if (i.toConfirm > 0) add(BriefLine(if (i.toConfirm == 1) "1 payment to confirm" else "${i.toConfirm} payments to confirm", link = "hellfire://money/review"))
            if (i.evening && i.reviewsPending > 0) add(BriefLine("Any cash you spent and did not log?", "nightly review is waiting", "hellfire://money/cash"))
        }
        val replies = i.waiting.iOwe.sortedByDescending { it.at }.take(5).map {
            BriefLine("Reply to ${it.name}", "${it.channel} · ${Fmt.age(it.at, i.now)} ago", "hellfire://waiting", warn = i.now - it.at > DAY)
        } + listOfNotNull(i.waiting.theyOwe.takeIf { it.isNotEmpty() }?.let { list ->
            BriefLine("Waiting to hear from ${list.take(3).joinToString { it.name }}" + if (list.size > 3) " and ${list.size - 3} more" else "", link = "hellfire://waiting")
        })
        val capture = i.gaps.map { BriefLine(it.message, link = "hellfire://settings/permissions", warn = true) }

        if (i.evening) {
            section("Today's money", money)
            section("Still open", (overdue + today.filter { it.dueAt < i.now }).take(8).map { taskLine(it, i.now, withDay = it.dueAt < start) })
            section("Tomorrow", tomorrow.take(8).map { taskLine(it, i.now, false) })
            section("Replies", replies)
        } else {
            section("Today", (overdue + today).take(8).map { taskLine(it, i.now, withDay = it.dueAt < start) }
                .ifEmpty { listOf(BriefLine("Nothing due today.", link = "hellfire://tasks")) })
            section("Bills", billsSoon.take(6).map {
                BriefLine(it.title.take(90), listOfNotNull(it.amount?.let(Money::display), "due ${Fmt.day(it.dueAt)}").joinToString(" · "), "hellfire://task/${it.id}", warn = it.dueAt < i.now)
            } + listOfNotNull(if (i.forecast.shortfall > 0) BriefLine("Bills in the next 30 days come to ${Money.display(i.forecast.total)}",
                "your balances cover ${Money.display(i.forecast.balance.coerceAtLeast(0))}", "hellfire://bills", warn = true) else null))
            section("Money", money)
            section("Replies", replies)
            val owed = i.debts.filter { it.balance > 0 }; val owing = i.debts.filter { it.balance < 0 }
            section("Debts", buildList {
                if (owed.isNotEmpty()) add(BriefLine("Owed to you: ${Money.display(owed.sumOf { it.balance })}",
                    owed.filter { it.overdue(i.now) }.takeIf { it.isNotEmpty() }?.let { late -> "overdue: " + late.joinToString { it.person } }
                        ?: owed.take(3).joinToString { it.person }, "hellfire://debts", warn = owed.any { it.overdue(i.now) }))
                if (owing.isNotEmpty()) add(BriefLine("You owe: ${Money.display(-owing.sumOf { it.balance })}", owing.take(3).joinToString { it.person }, "hellfire://debts",
                    warn = owing.any { it.overdue(i.now) }))
            })
            if (proposals > 0) section("To review", listOf(BriefLine(if (proposals == 1) "1 new task found in your messages and documents" else "$proposals new tasks found in your messages and documents", link = "hellfire://tasks")))
        }
        val calendarStart = if (i.evening) end else start
        val calendarEnd = if (i.evening) end2 else end
        val appointments = i.calendar.events.filter { com.hellfire.ass.calendar.CalendarAgendaLogic.inWindow(it, calendarStart, calendarEnd) }
        val conflicts = com.hellfire.ass.calendar.CalendarAgendaLogic.conflicts(i.calendar.events)
        section(if (i.evening) "Tomorrow's appointments" else "Today's appointments", appointments.map {
            BriefLine(com.hellfire.ass.calendar.CalendarAgendaLogic.whenText(it) + " · " + it.title,
                if (it in conflicts) "OVERLAP with another appointment" else null, it.sourceId?.let { id -> "hellfire://source/$id" }, it in conflicts)
        }.ifEmpty { listOf(BriefLine(if (i.calendar.status == "AVAILABLE") "No appointments in this window." else "Calendar: ${i.calendar.statusLabel}", link = "hellfire://settings/permissions")) } +
            if (i.calendar.truncated) listOf(BriefLine("Calendar results limited to 200 occurrences; check the calendar for more.", warn = true)) else emptyList())
        if (i.calendar.status == "AVAILABLE") section("Upcoming appointments", i.calendar.events.filter { it.start >= calendarEnd }.take(8).map {
            BriefLine(com.hellfire.ass.calendar.CalendarAgendaLogic.whenText(it) + " · " + it.title,
                if (it in conflicts) "OVERLAP" else null, it.sourceId?.let { id -> "hellfire://source/$id" }, it in conflicts)
        })
        section("Capture", capture)

        val parts = if (i.evening) listOfNotNull("Spent ${Money.display(i.spent)} today",
            (overdue.size + today.count { it.dueAt < i.now }).takeIf { it > 0 }?.let { "$it still open" }, tomorrow.size.takeIf { it > 0 }?.let { "$it tomorrow" })
        else listOfNotNull((overdue.size + today.size).takeIf { it > 0 }?.let { "$it due today" }, i.waiting.iOwe.size.takeIf { it > 0 }?.let { if (it == 1) "1 reply owed" else "$it replies owed" },
            billsSoon.size.takeIf { it > 0 }?.let { if (it == 1) "1 bill this week" else "$it bills this week" })
        val headline = (parts + listOfNotNull(i.gaps.size.takeIf { it > 0 }?.let { "capture needs attention" })).joinToString(" · ").ifEmpty { "Nothing urgent today" }
        return Brief(i.evening, day, headline, sections)
    }

    /** Reads everything from this phone. Decrypts recent SMS for the reply tracker; call off the main thread. */
    suspend fun load(app: HellFireApp, now: Long = System.currentTimeMillis(), evening: Boolean = isEvening(now)): Brief {
        val day = Instant.ofEpochMilli(now).atZone(TaskTime.zone).toLocalDate()
        val start = Fmt.startOf(day)
        val totals = if (evening) app.finances.summary(start, Fmt.startOf(day.plusDays(1))) else app.finances.summary(Fmt.startOf(day.minusDays(1)), start)
        val dao = app.database.finances()
        val balance = dao.allAccounts().fold(0L) { sum, a -> Math.addExact(sum, dao.balance(a.id)) }
        val tasks = app.database.tasks().all()
        val waiting = Waiting.compute(loadInteractions(app, now - Waiting.WINDOW), loadAliases(app), now, WaitingPrefs.dismissed(app), loadIdentities(app))
        return build(BriefInputs(evening, now, tasks, totals.expenses, totals.income, dao.reviewCount(), app.database.tasks().reviewsNow().size,
            waiting, Debts.balances(app.database.debts().all()), Recurring.forecast(tasks, balance, now), CaptureGaps.check(app, now), app.calendar.agenda(start, Fmt.startOf(day.plusDays(8)))))
    }

    /** Quiet morning notification with counts only; details stay inside the app. */
    fun notify(app: HellFireApp, brief: Brief) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Morning brief", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 74, Intent(app, MainActivity::class.java).setData(Uri.parse("hellfire://brief")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify("daily-brief", 74, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_hellfire)
            .setContentTitle("HellFire Ass · morning brief").setContentText(brief.headline)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
    }
}
