package com.hellfire.ass.communications

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.MainActivity
import com.hellfire.ass.R
import com.sun.mail.imap.*
import kotlinx.coroutines.*
import java.util.Properties
import javax.mail.*

/** Explicit, bounded foreground IDLE session; ordinary durable sync remains available. */
class MailIdleService : android.app.Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var store: IMAPStore? = null
    private var job: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != "START" || job?.isActive == true) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) { stopSelf(); return START_NOT_STICKY }
        manager.createNotificationChannel(NotificationChannel("mail-idle", "Mail connection", NotificationManager.IMPORTANCE_LOW))
        if (manager.getNotificationChannel("mail-idle").importance == NotificationManager.IMPORTANCE_NONE) { stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 83, Intent(this, MailIdleService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 84, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            val notification = Notification.Builder(this, "mail-idle").setSmallIcon(R.drawable.ic_hellfire)
                .setContentTitle("HellFire Ass · IMAP IDLE active").setContentText("Owner-started connection · ten-minute limit")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(83, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(83, notification)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            var failed = false
            val app = application as HellFireApp
            val timer = launch { delay(10 * 60_000L); stopSelf() }
            try {
                val config = MailConfig.from(requireNotNull(app.secrets.read("mail")))
                val properties = Properties().apply {
                    setProperty("mail.imaps.ssl.checkserveridentity", "true")
                    setProperty("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3")
                    setProperty("mail.imaps.connectiontimeout", "15000")
                    setProperty("mail.imaps.timeout", "650000")
                    setProperty("mail.imaps.writetimeout", "15000")
                }
                val connected = Session.getInstance(properties).getStore("imaps") as IMAPStore
                store = connected
                connected.connect(config.imapHost, 993, config.address, config.password)
                check(connected.hasCapability("IDLE")) { "IDLE_UNSUPPORTED" }
                val folder = connected.getFolder("INBOX") as IMAPFolder
                folder.open(Folder.READ_ONLY)
                app.database.communications().integration(IntegrationState("IMAP_IDLE", "ACTIVE_BOUNDED_SESSION", System.currentTimeMillis()))
                CommunicationWork.schedule(app)
                while (isActive && connected.isConnected && folder.isOpen) {
                    folder.idle(true)
                    ensureActive(); CommunicationWork.schedule(app)
                }
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) {
                failed = true
                runCatching { app.database.communications().integration(IntegrationState("IMAP_IDLE", "UNAVAILABLE", System.currentTimeMillis(),
                    if (error.message == "IDLE_UNSUPPORTED") "SERVER_DOES_NOT_SUPPORT_IDLE" else "CONNECTION_OR_CREDENTIALS_UNAVAILABLE")) }
            } finally {
                timer.cancel()
                if (!failed) withContext(NonCancellable) { runCatching { app.database.communications().integration(IntegrationState("IMAP_IDLE", "STOPPED", System.currentTimeMillis())) } }
                runCatching { store?.close() }; store = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() {
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { store?.close() }
        }
        super.onDestroy()
    }
}
