package com.hellfire.ass.tasks

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.MainActivity
import com.hellfire.ass.R
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.communications.IntegrationState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.insights.Briefs

/** Notification decisions for reminders, kept pure so they can be tested without Android. */
object TaskAlerts {
    /** Due and overdue commitments: alarm sound, escalates until the owner acts in the app. */
    const val ALARM = "commitment-reminders"
    /** 24 h and 2 h heads-up: an ordinary notification, not an alarm. */
    const val HEADS_UP = "task-heads-up"
    const val REVIEW = "daily-review"
    const val GROUP = "hellfire-commitments"
    val QUIET_START: LocalTime = LocalTime.of(22, 0)
    val QUIET_END: LocalTime = LocalTime.of(6, 30)

    fun channel(dueAt: Long, now: Long): String = if (dueAt <= now) ALARM else HEADS_UP
    /** When quiet hours (22:00–06:30 EAT) hold back a heads-up, the time they end; null outside quiet hours. Alarms never wait. */
    fun quietUntil(now: Long, enabled: Boolean): Long? {
        if (!enabled) return null
        val local = Instant.ofEpochMilli(now).atZone(TaskTime.zone)
        val time = local.toLocalTime()
        if (!(time >= QUIET_START || time < QUIET_END)) return null
        val endDay = if (time >= QUIET_START) local.toLocalDate().plusDays(1) else local.toLocalDate()
        return endDay.atTime(QUIET_END).atZone(TaskTime.zone).toInstant().toEpochMilli()
    }
}

object TaskScheduler {
    private const val CHANNEL = TaskAlerts.ALARM
    private val lock = Mutex()
    private fun pending(app: HellFireApp): PendingIntent = PendingIntent.getBroadcast(app, 70,
        Intent(app, ReminderReceiver::class.java).setAction("com.hellfire.ass.FIRE_REMINDERS"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notifications(app: HellFireApp): NotificationManager = app.getSystemService(NotificationManager::class.java).also { manager ->
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Commitments due now", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        })
        manager.createNotificationChannel(NotificationChannel(TaskAlerts.HEADS_UP, "Upcoming commitments", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(TaskAlerts.REVIEW, "Evening review", NotificationManager.IMPORTANCE_DEFAULT))
    }
    /** Hour of the evening review, East Africa Time (default 21). */
    fun reviewHour(app: HellFireApp): Int = app.getSharedPreferences("capture", 0).getInt("review_hour", 21).coerceIn(18, 23)
    private fun quietHours(app: HellFireApp): Boolean = app.getSharedPreferences("capture", 0).getBoolean("quiet_hours", true)
    private fun openTask(app: HellFireApp, id: String?): PendingIntent = PendingIntent.getActivity(app, 71, Intent(app, MainActivity::class.java)
        .setData(Uri.parse(if (id == null) "hellfire://tasks" else "hellfire://task/$id")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private suspend fun reviewTime(app: HellFireApp, now: Long): Long? {
        if (!app.getSharedPreferences("capture", 0).getBoolean("nightly_review", true)) return null
        val local = Instant.ofEpochMilli(now).atZone(TaskTime.zone)
        val today = local.toLocalDate().toString()
        val hour = reviewHour(app)
        val at = local.toLocalDate().atTime(hour, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
        return if (!app.database.tasks().reviewedOrPrompted(today)) at.coerceAtLeast(now)
            else local.toLocalDate().plusDays(1).atTime(hour, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
    }
    /** Hour of the morning brief notification, East Africa Time, or null when the owner turned it off. */
    fun briefHour(app: HellFireApp): Int? = app.getSharedPreferences("capture", 0).let { p ->
        if (p.getBoolean("morning_brief", true)) p.getInt("brief_hour", 7).coerceIn(4, 11) else null
    }
    /** Next morning brief: today at the chosen hour if it is still morning and not yet shown, else tomorrow. */
    fun briefTime(hour: Int?, shownDay: String?, now: Long): Long? {
        if (hour == null) return null
        val local = Instant.ofEpochMilli(now).atZone(TaskTime.zone)
        val today = local.toLocalDate()
        val day = if (shownDay != today.toString() && local.hour < 12) today else today.plusDays(1)
        return day.atTime(hour, 0).atZone(TaskTime.zone).toInstant().toEpochMilli().coerceAtLeast(now)
    }
    @SuppressLint("ScheduleExactAlarm")
    private suspend fun arm(app: HellFireApp) {
        val alarm = app.getSystemService(AlarmManager::class.java)
        val manager = notifications(app)
        alarm.cancel(pending(app))
        if (!manager.areNotificationsEnabled()) {
            app.database.communications().integration(IntegrationState("REMINDERS", "PERMISSION_MISSING", System.currentTimeMillis(), "NOTIFICATIONS_BLOCKED"))
            return
        }
        // A blocked alarm channel must not also stop the brief and review, which have their own channels.
        val alarmsBlocked = manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE
        val now = System.currentTimeMillis()
        val taskAlert = app.database.tasks().nextAlert()
        val next = listOfNotNull(taskAlert, reviewTime(app, now),
            briefTime(briefHour(app), app.getSharedPreferences("capture", 0).getString("brief_day", null), now)).minOrNull() ?: return
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        try {
            val at = next.coerceAtLeast(now + 1000)
            // An owner commitment uses the alarm-clock API: exact through Doze and shown as the next alarm.
            if (exact && next == taskAlert) alarm.setAlarmClock(AlarmManager.AlarmClockInfo(at, openTask(app, null)), pending(app))
            else if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(app))
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.coerceAtLeast(now + 1000), pending(app))
            app.database.communications().integration(if (alarmsBlocked) IntegrationState("REMINDERS", "PERMISSION_MISSING", now, "ALARM_CHANNEL_BLOCKED")
                else IntegrationState("REMINDERS", if (exact) "EXACT_ALLOWED" else "BEST_EFFORT", now))
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.coerceAtLeast(now + 1000), pending(app))
            app.database.communications().integration(IntegrationState("REMINDERS", "BEST_EFFORT", now, "EXACT_ACCESS_REVOKED"))
        }
    }
    suspend fun restore(app: HellFireApp) = lock.withLock { arm(app) }
    /**
     * A missed call becomes a "Call back" proposal in review (one per caller per day), never a live alarm: the owner
     * activates it like any other found task.
     */
    suspend fun missedCall(app: HellFireApp, sourceId: String, at: Long, caller: String) {
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(at).atZone(TaskTime.zone).toLocalDate()
        if (!TaskTime.reviewable(at, now) || app.skills.ignoredFor(app, "IGNORE_TASK", sourceId, "Missed call $caller")) return
        val id = NotificationNormalizer.sha256("missed-call:${caller.lowercase()}:$day")
        app.database.tasks().insert(AssistantTask(id, sourceId, "Call back $caller", at + 3_600_000L, party = "OWNER",
            evidence = "Missed call from $caller", createdAt = now, updatedAt = now))
    }
    suspend fun collect(app: HellFireApp, sourceId: String, sourceTime: Long, text: String, outgoing: Boolean) {
        val now = System.currentTimeMillis()
        TaskExtractor.extract(text, sourceTime, outgoing)?.takeIf { TaskTime.reviewable(it.dueAt, now) }?.let { candidate ->
            if (app.skills.ignoredFor(app, "IGNORE_TASK", sourceId, text)) return
            app.database.tasks().insert(AssistantTask(NotificationNormalizer.sha256("task:$sourceId"), sourceId,
                candidate.title, candidate.dueAt, party = candidate.party, evidence = candidate.evidence, createdAt = now, updatedAt = now,
                direction = directionFor(candidate.party)))
        }
    }
    suspend fun activate(app: HellFireApp, id: String, title: String, due: Long, party: String) = lock.withLock {
        require(title.isNotBlank() && title.length <= 500 && party in setOf("OWNER", "OTHER", "UNKNOWN"))
        val now = System.currentTimeMillis()
        app.database.withTransaction {
            require(app.database.tasks().activate(id, title, due, party, TaskTime.firstAlert(due, now), now) == 1)
            app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_TASK_APPROVAL", "ACTIVE", now))
        }
        notifications(app).cancel(id, 71)
        arm(app)
    }
    /** The owner set this bill to repeat: next month's bill, scheduled from the same source evidence. Null for anything else. */
    fun nextBill(task: AssistantTask, now: Long): AssistantTask? {
        if (task.kind != "BILL" || !task.repeatMonthly || task.state != "ACTIVE") return null
        val next = Instant.ofEpochMilli(task.dueAt).atZone(TaskTime.zone).plusMonths(1).toInstant().toEpochMilli()
        return task.copy(id = NotificationNormalizer.sha256("bill:${task.id}:$next"), dueAt = next, state = "ACTIVE",
            createdAt = now, updatedAt = now, nextAlertAt = TaskTime.firstAlert(next, now), lastAlertAt = null, alertCount = 0, completedAt = null)
    }

    /** Who a message commitment is owed by, from the extracted responsible party. */
    fun directionFor(party: String) = when (party) { "OWNER" -> "I_OWE"; "OTHER" -> "OWED_TO_ME"; else -> "NONE" }

    /** Owner-edited promise and bill details. Amounts are display only and never reach the ledger. */
    suspend fun updateDetails(app: HellFireApp, id: String, direction: String, amount: Long?, kind: String, repeatMonthly: Boolean) = lock.withLock {
        require(direction in setOf("I_OWE", "OWED_TO_ME", "NONE") && kind in setOf("TASK", "BILL") && (amount == null || amount > 0))
        require(app.database.tasks().details(id, direction, amount, kind, repeatMonthly && kind == "BILL", System.currentTimeMillis()) == 1)
    }

    suspend fun updateReminderOptions(app: HellFireApp, id: String, repeat: String, profile: String, snooze: Int) = lock.withLock {
        require(repeat in setOf("NONE", "DAILY", "WEEKLY", "MONTHLY") && profile in setOf("GENTLE", "NORMAL", "PERSISTENT") && snooze in 5..1440)
        app.database.tasks().reminderOptions(id, repeat, profile, snooze)
        arm(app)
    }
    fun nextOccurrence(task: AssistantTask, now: Long): AssistantTask? {
        val rule = task.repeatRule.takeIf { it != "NONE" } ?: if (task.kind == "BILL" && task.repeatMonthly) "MONTHLY" else return null
        if (task.state != "ACTIVE") return null
        var date = Instant.ofEpochMilli(task.dueAt).atZone(TaskTime.zone)
        val current = Instant.ofEpochMilli(now).atZone(TaskTime.zone)
        val original = date
        val count = when (rule) {
            "DAILY" -> java.time.temporal.ChronoUnit.DAYS.between(date.toLocalDate(), current.toLocalDate()).coerceAtLeast(1)
            "WEEKLY" -> (java.time.temporal.ChronoUnit.DAYS.between(date.toLocalDate(), current.toLocalDate()) / 7).coerceAtLeast(1)
            else -> java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(date), java.time.YearMonth.from(current)).coerceAtLeast(1)
        }
        fun advance(n: Long) = when (rule) { "DAILY" -> original.plusDays(n); "WEEKLY" -> original.plusWeeks(n); else -> original.plusMonths(n) }
        date = advance(count)
        if (date.toInstant().toEpochMilli() <= now) date = advance(count + 1)
        val next = date.toInstant().toEpochMilli()
        return task.copy(id = NotificationNormalizer.sha256("repeat:${task.id}:$next"), dueAt = next, state = "ACTIVE",
            createdAt = now, updatedAt = now, nextAlertAt = TaskTime.firstAlert(next, now), lastAlertAt = null, alertCount = 0, completedAt = null)
    }
    internal suspend fun retainNext(dao: TaskDao, task: AssistantTask, now: Long): AssistantTask? {
        val next = nextOccurrence(task, now) ?: return null
        if (dao.insert(next) == -1L) dao.reactivateRepeat(next.id, next.nextAlertAt, now)
        return next
    }
    /** One distinct broadcast per task and action, so buttons on several reminders never collide. */
    fun actionIntent(app: HellFireApp, id: String, action: String): PendingIntent = PendingIntent.getBroadcast(app, 76,
        Intent(app, TaskActionReceiver::class.java).setAction(TaskActionReceiver.ACTION).setData(Uri.parse("hellfire://task/$id?action=$action")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    /** Closes a conditional reminder whose condition already holds, so it never rings. Any error means remind anyway. */
    private suspend fun closeIfMet(app: HellFireApp, task: AssistantTask, now: Long, manager: NotificationManager): Boolean {
        val condition = task.condition ?: return false
        val met = try {
            TaskConditions.met(condition, com.hellfire.ass.insights.Debts.balances(app.database.debts().all()),
                if (condition.startsWith("NO_REPLY")) com.hellfire.ass.insights.loadInteractions(app, task.createdAt) else emptyList(),
                com.hellfire.ass.insights.loadAliases(app), task.createdAt)
        } catch (e: CancellationException) { throw e } catch (caught: Exception) { Diagnostics.warn(caught); false }
        if (!met) return false
        app.database.withTransaction {
            app.database.tasks().close(task.id, "DONE", now)
            app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), task.id, "TASK_CONDITION_MET", condition.substringBefore(':'), now))
        }
        manager.cancel(task.id, 71)
        return true
    }
    fun repeatInterval(task: AssistantTask, default: Int) = when (task.alertProfile) { "GENTLE" -> 1440; "NORMAL" -> 60; else -> default.coerceIn(5, 1440) }

    suspend fun action(app: HellFireApp, id: String, action: String) = lock.withLock {
        val now = System.currentTimeMillis()
        app.database.withTransaction {
            when (action) {
                "DONE", "REJECTED" -> {
                    val task = app.database.tasks().task(id)
                    app.database.tasks().close(id, action, now)
                    if (action == "DONE" && task != null) retainNext(app.database.tasks(), task, now)?.let { next ->
                        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), next.id, "OWNER_TASK_REPEAT", task.id, now))
                    }
                }
                "SNOOZE" -> app.database.tasks().snooze(id, Math.addExact(now, (app.database.tasks().task(id)?.snoozeMinutes ?: 10) * 60_000L), now)
                else -> error("Unsupported task action")
            }
            app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_TASK_$action", action, now))
        }
        notifications(app).cancel(id, 71)
        arm(app)
    }
    /** Undo of an owner's "done" tap: the task becomes active again with its original due time. */
    suspend fun reopen(app: HellFireApp, id: String) = lock.withLock {
        val now = System.currentTimeMillis()
        app.database.withTransaction {
            val task = requireNotNull(app.database.tasks().task(id))
            val children = app.database.communications().audits("OWNER_TASK_REPEAT").filter { it.state == id }
            require(children.none { app.database.tasks().task(it.draftId)?.state == "DONE" }) { "A later occurrence is already complete. Edit the current occurrence instead." }
            children.forEach { child ->
                if (app.database.tasks().task(child.draftId)?.state == "ACTIVE") app.database.tasks().close(child.draftId, "REJECTED", now)
            }
            require(app.database.tasks().reopen(id, TaskTime.nextAlert(task.dueAt, now), now) == 1)
            app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_TASK_REOPEN", "ACTIVE", now))
        }
        arm(app)
    }
    @SuppressLint("MissingPermission")
    suspend fun fire(app: HellFireApp) = lock.withLock {
        val manager = notifications(app)
        if (!manager.areNotificationsEnabled()) { arm(app); return@withLock }
        val now = System.currentTimeMillis()
        val open = openTask(app, null)
        val due = app.database.tasks().due(now).filterNot { it.condition != null && closeIfMet(app, it, now, manager) }
        val quietUntil = TaskAlerts.quietUntil(now, quietHours(app))
        val alarms = due.count { TaskAlerts.channel(it.dueAt, now) == CHANNEL }
        val overdueMinutes = app.getSharedPreferences("capture", 0).getInt("overdue_minutes", 10)
        for (task in due) {
            val channel = TaskAlerts.channel(task.dueAt, now)
            if (channel == TaskAlerts.HEADS_UP && quietUntil != null) {
                // Hold an early heads-up until quiet hours end (never past the due time).
                app.database.tasks().alerted(task.id, now, minOf(quietUntil, task.dueAt))
                continue
            }
            val builder = Notification.Builder(app, channel).setSmallIcon(R.drawable.ic_hellfire)
                .setContentTitle(task.title).setContentText(if (channel == CHANNEL) "Due · Done, Snooze, or open HellFire Ass to reschedule" else "Due ${TaskTime.display(task.dueAt)} EAT · review in HellFire Ass")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(openTask(app, task.id)).setAutoCancel(false)
                .setCategory(if (channel == CHANNEL) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            // Several commitments due together ring once, through the summary below.
            if (channel == CHANNEL && alarms >= 2) builder.setGroup(TaskAlerts.GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            // Owner decision 2026-11-27: Done and Snooze can be chosen on the notification, as well as in the app.
            builder.addAction(Notification.Action.Builder(null, "Done", actionIntent(app, task.id, "DONE")).build())
                .addAction(Notification.Action.Builder(null, "Snooze", actionIntent(app, task.id, "SNOOZE")).build())
            manager.notify(task.id, 71, builder.build())
            app.database.tasks().alerted(task.id, now, TaskTime.nextAlert(task.dueAt, now, repeatInterval(task, overdueMinutes)))
        }
        if (alarms >= 2) manager.notify("commitments-summary", 75, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_hellfire)
            .setContentTitle("$alarms commitments need you").setContentText("Open HellFire Ass to choose Done, Snooze or Reschedule")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setGroup(TaskAlerts.GROUP).setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).setCategory(Notification.CATEGORY_ALARM).build())
        else manager.cancel("commitments-summary", 75)
        val local = Instant.ofEpochMilli(now).atZone(TaskTime.zone)
        val day = local.toLocalDate().toString()
        val reviewHour = reviewHour(app)
        if (app.getSharedPreferences("capture", 0).getBoolean("nightly_review", true) && local.hour >= reviewHour && !app.database.tasks().reviewedOrPrompted(day)) {
            manager.notify("cash-review", 72, Notification.Builder(app, TaskAlerts.REVIEW).setSmallIcon(R.drawable.ic_hellfire)
                .setContentTitle("HellFire Ass · evening review").setContentText("Review today's transactions, unknown merchants and unlogged cash expenses.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).build())
            app.database.tasks().review(DailyReview(day, createdAt = now))
        }
        if (local.hour >= reviewHour) app.requestWidgetRefresh()
        val prefs = app.getSharedPreferences("capture", 0)
        val briefHour = briefHour(app)
        if (briefHour != null && local.hour in briefHour until 12 && prefs.getString("brief_day", null) != day) {
            // Mark the day first: a brief that fails to load must not re-fire the alarm in a loop.
            prefs.edit().putString("brief_day", day).apply()
            try { Briefs.notify(app, Briefs.load(app, now, evening = false)) }
            catch (e: CancellationException) { throw e }
            catch (caught: Exception) { Diagnostics.warn(caught); runCatching { app.database.events().failure("morning_brief", "BRIEF_FAILED") } }
        }
        arm(app)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch {
            try { TaskScheduler.fire(app) }
            catch (caught: Exception) { Diagnostics.warn(caught); runCatching { app.database.communications().integration(IntegrationState("REMINDERS", "FAILED", System.currentTimeMillis(), "ALARM_PROCESSING_FAILED")) } }
            finally { result.finish() }
        }
    }
}

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch { try { TaskScheduler.restore(app) }
            catch (caught: Exception) { Diagnostics.warn(caught); runCatching { app.database.events().failure("reminders", "RESTORATION_FAILED") } }
            finally { result.finish() } }
    }
}

/** Done / Snooze tapped on a reminder notification. Not exported: only this app's PendingIntents reach it. */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val data = intent.data ?: return
        val id = data.lastPathSegment?.takeIf { it.matches(Regex("[a-f0-9-]{16,80}")) } ?: return
        val action = data.getQueryParameter("action")?.takeIf { it == "DONE" || it == "SNOOZE" } ?: return
        val result = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch {
            try { TaskScheduler.action(app, id, action) }
            catch (caught: Exception) { Diagnostics.warn(caught); runCatching { app.database.events().failure("reminders", "NOTIFICATION_ACTION_FAILED") } }
            finally { result.finish() }
        }
    }
    companion object { const val ACTION = "com.hellfire.ass.TASK_ACTION" }
}
