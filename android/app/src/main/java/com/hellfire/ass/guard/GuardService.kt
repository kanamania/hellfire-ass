package com.hellfire.ass.guard

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import android.telephony.SubscriptionManager
import androidx.lifecycle.LifecycleService
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.MainActivity
import com.hellfire.ass.R
import kotlinx.coroutines.*
import java.security.MessageDigest

class GuardService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as HellFireApp
    private var job: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var cameraAllowed = false
    private var connectivity: ConnectivityManager.NetworkCallback? = null
    private var subscriptions: SubscriptionManager.OnSubscriptionsChangedListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Null intents are sticky restarts: request only the location type until the owner resumes camera from the UI.
        val camera = intent?.getBooleanExtra("camera", false) == true
        try {
            check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            check(GuardNotifications.available(this))
            if (Build.VERSION.SDK_INT >= 30) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                startForeground(GuardNotifications.ID, GuardNotifications.notification(this, camera), types)
            } else startForeground(GuardNotifications.ID, GuardNotifications.notification(this, camera))
            cameraAllowed = camera
        } catch (_: Exception) {
            scope.launch {
                runCatching { app.database.guard().status("ANDROID_START_BLOCKED_TAP_TO_RESUME", true, System.currentTimeMillis()) }
                GuardNotifications.resume(this@GuardService); stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY
        observeTriggers()
        heartbeatJob = scope.launch {
            val cycle = GuardCycle(app)
            while (isActive && app.database.guard().state()?.armed == true) {
                val started = SystemClock.elapsedRealtime()
                val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:guard-heartbeat")
                wake.acquire(30_000)
                try { cycle.heartbeat() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { app.database.guard().status("HEARTBEAT_UNCONFIRMED", !cameraAllowed, System.currentTimeMillis()) }
                finally { if (wake.isHeld) wake.release() }
                delay((GuardLogic.PERIOD_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
            }
        }
        job = scope.launch {
            val dao = app.database.guard()
            try {
                if (dao.state()?.armed != true) { stopSelf(); return@launch }
                dao.status(if (cameraAllowed) "ARMED" else "LOCATION_ONLY_TAP_TO_RESUME_CAMERA", !cameraAllowed, System.currentTimeMillis())
                val sensors = GuardSensors(this@GuardService, this@GuardService)
                val cycle = GuardCycle(app)
                while (isActive) {
                    val current = dao.state() ?: break
                    if (!current.armed) break
                    val started = SystemClock.elapsedRealtime()
                    val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:guard-cycle")
                    @Suppress("WakelockTimeout")
                    wake.acquire(120_000)
                    try {
                        // Failure of the uplink must not prevent local evidence capture.
                        var issue: String? = null
                        try { cycle.capture(sensors, cameraAllowed) } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { issue = "CAPTURE_UNAVAILABLE_OR_STORAGE_FULL" }
                        try { cycle.upload() } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { issue = "UPLOAD_UNCONFIRMED" }
                        val lastBeat = dao.state()?.lastHeartbeatAt
                        val health = issue ?: if (lastBeat == null || System.currentTimeMillis() - lastBeat > 90_000) "HEARTBEAT_UNCONFIRMED"
                            else if (cameraAllowed) "ARMED_RUNNING" else "LOCATION_ONLY_TAP_TO_RESUME_CAMERA"
                        dao.status(health, !cameraAllowed, System.currentTimeMillis())
                    } finally { if (wake.isHeld) wake.release() }
                    delay((GuardLogic.PERIOD_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { runCatching { dao.status("GUARD_SERVICE_FAILED", true, System.currentTimeMillis()) } }
            finally { heartbeatJob?.cancel(); stopSelf() }
        }
        return START_STICKY
    }

    private fun observeTriggers() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) scope.launch {
                    if (!GuardCycle.online(this@GuardService)) app.database.guard().activate("connectivity_lost", System.currentTimeMillis())
                }
            }
            override fun onLost(network: Network) {
                scope.launch { if (!GuardCycle.online(this@GuardService)) app.database.guard().activate("connectivity_lost", System.currentTimeMillis()) }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback); connectivity = callback }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() { scope.launch { checkSim() } }
            }
            runCatching {
                val subscriptionsManager = getSystemService(SubscriptionManager::class.java)
                if (Build.VERSION.SDK_INT >= 30) subscriptionsManager.addOnSubscriptionsChangedListener(mainExecutor, listener)
                else @Suppress("DEPRECATION") subscriptionsManager.addOnSubscriptionsChangedListener(listener)
                subscriptions = listener
            }
        }
        scope.launch {
            if (!GuardCycle.online(this@GuardService)) app.database.guard().activate("connectivity_lost", System.currentTimeMillis())
            checkSim()
        }
    }
    private suspend fun checkSim() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            val ids = getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.map { it.subscriptionId }?.sorted() ?: return
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(ids.joinToString(",").toByteArray()).joinToString("") { "%02x".format(it) }
            val dao = app.database.guard()
            val current = dao.state() ?: return
            if (GuardLogic.simChanged(current.armed, current.simFingerprint, fingerprint)) dao.activate("sim_changed", System.currentTimeMillis())
            if (current.simFingerprint == null) dao.sim(fingerprint)
        } catch (_: Exception) { /* Permission/OEM absence is reported by the setup screen; no SIM identity is fabricated. */ }
    }
    override fun onDestroy() {
        connectivity?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        subscriptions?.let { runCatching { getSystemService(SubscriptionManager::class.java).removeOnSubscriptionsChangedListener(it) } }
        scope.cancel(); super.onDestroy()
    }
}

object GuardNotifications {
    const val ID = 71
    private const val CHANNEL = "theftguard"
    fun available(context: android.content.Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "TheftGuard", NotificationManager.IMPORTANCE_LOW))
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    }
    fun notification(context: android.content.Context, camera: Boolean): Notification {
        available(context)
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java).setData(Uri.parse("hellfire://settings/guard")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_hellfire).setContentTitle("HellFire Ass · TheftGuard armed")
            .setContentText(if (camera) "Location and camera guard · tap to review or authenticate to disarm" else "Camera paused · tap to resume TheftGuard")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC).build()
    }
    fun resume(context: android.content.Context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (available(context)) context.getSystemService(NotificationManager::class.java).notify(ID, notification(context, false))
    }
}
