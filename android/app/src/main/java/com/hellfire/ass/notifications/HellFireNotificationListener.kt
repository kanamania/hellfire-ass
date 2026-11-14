package com.hellfire.ass.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.processing.CaptureWork
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

object NotificationCaptureStatus {
    val connected = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val dropped = MutableStateFlow(0)
}

class HellFireNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<NotificationSnapshot>(256)
    private val pendingDrops = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        val app = application as HellFireApp
        scope.launch {
            for (first in inbox) {
                val batch = mutableListOf(first)
                while (batch.size < 32) batch += inbox.tryReceive().getOrNull() ?: break
                var captured = false
                for (snapshot in batch) {
                    try { captured = app.events.capture(snapshot, packageName) || captured }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        NotificationCaptureStatus.error.value = "Notification storage failed. Check local storage."
                        runCatching { app.database.events().failure("android_notification", "NOTIFICATION_STORAGE_FAILED") }
                    }
                }
                val dropped = pendingDrops.getAndSet(0)
                if (dropped > 0) runCatching { app.database.events().failure("android_notification", "CAPTURE_QUEUE_OVERFLOW", dropped) }
                if (!captured) continue
                try { CaptureWork.process(app) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    NotificationCaptureStatus.error.value = "Processing delayed; saved events will resume during recovery."
                    runCatching { app.database.events().failure("processing", "WORK_SCHEDULING_FAILED") }
                }
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    override fun onListenerConnected() {
        NotificationCaptureStatus.connected.value = true
        NotificationCaptureStatus.error.value = null
        try {
            activeNotifications?.forEach { enqueue(it, "POSTED", null, currentRanking) }
        } catch (_: Exception) {
            NotificationCaptureStatus.error.value = "Could not read active notifications. Check notification access."
        }
    }

    override fun onListenerDisconnected() {
        NotificationCaptureStatus.connected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        enqueue(sbn, "POSTED", null, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        enqueue(sbn, "REMOVED", reason, rankingMap)
    }

    private fun enqueue(sbn: StatusBarNotification, kind: String, reason: Int?, ranks: RankingMap?) {
        if (sbn.packageName == packageName) return
        try {
            val snapshot = AndroidNotificationAdapter.snapshot(sbn, kind, reason, ranks)
            if (!inbox.trySend(snapshot).isSuccess) {
                pendingDrops.incrementAndGet()
                NotificationCaptureStatus.dropped.value += 1
                NotificationCaptureStatus.error.value = "Capture queue full; some notifications were not saved."
            }
        } catch (_: Exception) {
            NotificationCaptureStatus.error.value = "A notification could not be read. Android may restrict its fields."
        }
    }

    override fun onDestroy() {
        NotificationCaptureStatus.connected.value = false
        inbox.close()
        // Drain accepted snapshots while this process remains alive; persisted jobs survive a restart.
        super.onDestroy()
    }
}
