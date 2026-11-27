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
import com.hellfire.ass.Diagnostics

/** Explicit, bounded foreground IDLE session; ordinary durable sync remains available. */
class MailIdleService : android.app.Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stores = java.util.concurrent.ConcurrentHashMap<String, IMAPStore>()
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
                .setContentTitle("HellFire Ass · IMAP IDLE active").setContentText("Owner-started connection to every inbox · ten-minute limit")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(83, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(83, notification)
        } catch (caught: Exception) { Diagnostics.warn(caught); stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            val app = application as HellFireApp
            val timer = launch { delay(10 * 60_000L); stopSelf() }
            try {
                val accounts = Accounts.mail(app.secrets)
                // One bounded IDLE connection per inbox; a failing inbox does not end the others.
                accounts.map { config -> launch { idle(app, config) } }.joinAll()
            } catch (e: CancellationException) { throw e }
            catch (caught: Exception) { Diagnostics.warn(caught); runCatching { app.database.events().failure("email", "ACCOUNTS_UNREADABLE") } }
            finally {
                timer.cancel()
                stores.values.forEach { runCatching { it.close() } }; stores.clear()
                withContext(NonCancellable + Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun idle(app: HellFireApp, config: MailConfig) = coroutineScope {
        val state = Accounts.idleState(config.id)
        var failed = false
        try {
            val properties = Properties().apply {
                setProperty("mail.imaps.ssl.checkserveridentity", "true")
                setProperty("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3")
                setProperty("mail.imaps.connectiontimeout", "15000")
                setProperty("mail.imaps.timeout", "650000")
                setProperty("mail.imaps.writetimeout", "15000")
            }
            val connected = Session.getInstance(if (config.oauth) MailAdapter.oauth(properties) else properties).getStore("imaps") as IMAPStore
            stores[config.id] = connected
            connected.connect(config.imapHost, 993, config.address, MailAdapter.credential(app, config))
            check(connected.hasCapability("IDLE")) { "IDLE_UNSUPPORTED" }
            val folder = connected.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            app.database.communications().integration(IntegrationState(state, "ACTIVE_BOUNDED_SESSION", System.currentTimeMillis()))
            CommunicationWork.schedule(app)
            while (isActive && connected.isConnected && folder.isOpen) {
                folder.idle(true)
                ensureActive(); CommunicationWork.schedule(app)
            }
        } catch (e: CancellationException) { throw e }
        catch (error: Exception) {
            Diagnostics.warn(error)
            failed = true
            runCatching { app.database.communications().integration(IntegrationState(state, "UNAVAILABLE", System.currentTimeMillis(),
                if (error.message == "IDLE_UNSUPPORTED") "SERVER_DOES_NOT_SUPPORT_IDLE" else MailErrors.code(error))) }
        } finally {
            stores.remove(config.id)?.let { runCatching { it.close() } }
            if (!failed) withContext(NonCancellable) { runCatching { app.database.communications().integration(IntegrationState(state, "STOPPED", System.currentTimeMillis())) } }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() {
        scope.cancel()
        val open = stores.values.toList(); stores.clear()
        CoroutineScope(Dispatchers.IO).launch { open.forEach { runCatching { it.close() } } }
        super.onDestroy()
    }
}
