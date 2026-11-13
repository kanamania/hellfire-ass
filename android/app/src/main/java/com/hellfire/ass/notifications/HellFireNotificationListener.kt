package com.hellfire.ass.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationSnapshot
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

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (snapshot in inbox) {
                try {
                    (application as HellFireApp).events.capture(snapshot, packageName)
                } catch (_: Exception) {
                    NotificationCaptureStatus.error.value = "Notification storage failed. Reopen the app and check local storage."
                }
            }
        }
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
        scope.cancel()
        super.onDestroy()
    }
}
