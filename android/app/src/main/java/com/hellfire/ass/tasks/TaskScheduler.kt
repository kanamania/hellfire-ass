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
import java.util.UUID

object TaskScheduler {
    private const val CHANNEL = "commitment-reminders"
    private val lock = Mutex()
    private fun pending(app: HellFireApp): PendingIntent = PendingIntent.getBroadcast(app, 70,
        Intent(app, ReminderReceiver::class.java).setAction("com.hellfire.ass.FIRE_REMINDERS"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notifications(app: HellFireApp): NotificationManager = app.getSystemService(NotificationManager::class.java).also { manager ->
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Commitments and reviews", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        })
    }
    private suspend fun reviewTime(app: HellFireApp, now: Long): Long? {
        if (!app.getSharedPreferences("capture", 0).getBoolean("nightly_review", true)) return null
        val local = Instant.ofEpochMilli(now).atZone(TaskTime.zone)
        val today = local.toLocalDate().toString()
        val at = local.toLocalDate().atTime(21, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
        return if (!app.database.tasks().reviewedOrPrompted(today)) at.coerceAtLeast(now)
            else local.toLocalDate().plusDays(1).atTime(21, 0).atZone(TaskTime.zone).toInstant().toEpochMilli()
    }
    @SuppressLint("ScheduleExactAlarm")
    private suspend fun arm(app: HellFireApp) {
        val alarm = app.getSystemService(AlarmManager::class.java)
        val manager = notifications(app)
        alarm.cancel(pending(app))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            app.database.communications().integration(IntegrationState("REMINDERS", "PERMISSION_MISSING", System.currentTimeMillis(), "NOTIFICATIONS_BLOCKED"))
            return
        }
        val now = System.currentTimeMillis()
        val next = listOfNotNull(app.database.tasks().nextAlert(), reviewTime(app, now)).minOrNull() ?: return
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        try {
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.coerceAtLeast(now + 1000), pending(app))
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.coerceAtLeast(now + 1000), pending(app))
            app.database.communications().integration(IntegrationState("REMINDERS", if (exact) "EXACT_ALLOWED" else "BEST_EFFORT", now))
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.coerceAtLeast(now + 1000), pending(app))
            app.database.communications().integration(IntegrationState("REMINDERS", "BEST_EFFORT", now, "EXACT_ACCESS_REVOKED"))
        }
    }
    suspend fun restore(app: HellFireApp) = lock.withLock { arm(app) }
    suspend fun collect(app: HellFireApp, sourceId: String, sourceTime: Long, text: String, outgoing: Boolean) {
        val now = System.currentTimeMillis()
        TaskExtractor.extract(text, sourceTime, outgoing)?.takeIf { TaskTime.reviewable(it.dueAt, now) }?.let { candidate ->
            app.database.tasks().insert(AssistantTask(com.hellfire.ass.events.NotificationNormalizer.sha256("task:$sourceId"), sourceId,
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
        return task.copy(id = com.hellfire.ass.events.NotificationNormalizer.sha256("bill:${task.id}:$next"), dueAt = next, state = "ACTIVE",
            createdAt = now, updatedAt = now, nextAlertAt = TaskTime.firstAlert(next, now), lastAlertAt = null, alertCount = 0, completedAt = null)
    }

    /** Who a message commitment is owed by, from the extracted responsible party. */
    fun directionFor(party: String) = when (party) { "OWNER" -> "I_OWE"; "OTHER" -> "OWED_TO_ME"; else -> "NONE" }

    /** Owner-edited promise and bill details. Amounts are display only and never reach the ledger. */
    suspend fun updateDetails(app: HellFireApp, id: String, direction: String, amount: Long?, kind: String, repeatMonthly: Boolean) = lock.withLock {
        require(direction in setOf("I_OWE", "OWED_TO_ME", "NONE") && kind in setOf("TASK", "BILL") && (amount == null || amount > 0))
        require(app.database.tasks().details(id, direction, amount, kind, repeatMonthly && kind == "BILL", System.currentTimeMillis()) == 1)
    }

    suspend fun action(app: HellFireApp, id: String, action: String) = lock.withLock {
        val now = System.currentTimeMillis()
        app.database.withTransaction {
            when (action) {
                "DONE", "REJECTED" -> {
                    val task = app.database.tasks().task(id)
                    app.database.tasks().close(id, action, now)
                    if (action == "DONE" && task != null) nextBill(task, now)?.let { next ->
                        app.database.tasks().insert(next)
                        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), next.id, "OWNER_BILL_REPEAT", "ACTIVE", now))
                    }
                }
                "SNOOZE" -> app.database.tasks().snooze(id, Math.addExact(now, 10 * 60_000), now)
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
            require(app.database.tasks().reopen(id, TaskTime.nextAlert(task.dueAt, now), now) == 1)
            app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_TASK_REOPEN", "ACTIVE", now))
        }
        arm(app)
    }
    @SuppressLint("MissingPermission")
    suspend fun fire(app: HellFireApp) = lock.withLock {
        val manager = notifications(app)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            arm(app); return@withLock
        }
        val now = System.currentTimeMillis()
        val open = PendingIntent.getActivity(app, 71, Intent(app, MainActivity::class.java)
            .setData(Uri.parse("hellfire://tasks")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for (task in app.database.tasks().due(now)) {
            manager.notify(task.id, 71, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_hellfire)
                .setContentTitle(task.title).setContentText(if (task.dueAt <= now) "Overdue · open HellFire Ass to choose Done, Snooze or Reschedule" else "Upcoming commitment · review in HellFire Ass")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(false)
                .setCategory(Notification.CATEGORY_ALARM).build())
            app.database.tasks().alerted(task.id, now, TaskTime.nextAlert(task.dueAt, now, app.getSharedPreferences("capture", 0).getInt("overdue_minutes", 10).coerceIn(5, 1440)))
        }
        val local = Instant.ofEpochMilli(now).atZone(TaskTime.zone)
        val day = local.toLocalDate().toString()
        if (app.getSharedPreferences("capture", 0).getBoolean("nightly_review", true) && local.hour >= 21 && !app.database.tasks().reviewedOrPrompted(day)) {
            manager.notify("cash-review", 72, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_hellfire)
                .setContentTitle("HellFire Ass · evening review").setContentText("Review today's transactions, unknown merchants and unlogged cash expenses.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).build())
            app.database.tasks().review(DailyReview(day, createdAt = now))
        }
        if (local.hour >= 21) app.requestWidgetRefresh()
        arm(app)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch {
            try { TaskScheduler.fire(app) }
            catch (_: Exception) { runCatching { app.database.communications().integration(IntegrationState("REMINDERS", "FAILED", System.currentTimeMillis(), "ALARM_PROCESSING_FAILED")) } }
            finally { result.finish() }
        }
    }
}

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch { try { TaskScheduler.restore(app) }
            catch (_: Exception) { runCatching { app.database.events().failure("reminders", "RESTORATION_FAILED") } }
            finally { result.finish() } }
    }
}
