package com.hellfire.ass.guard

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.os.UserManager
import androidx.work.*
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class GuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch {
            try {
                val state = app.database.guard().state() ?: return@launch
                if (state.pendingDisarm) GuardHeartbeatWork.enqueue(context)
                when (GuardLogic.bootMode(state.armed, context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
                    "LOCATION_ONLY" -> {
                        app.database.guard().status("BOOT_LOCATION_ONLY_TAP_TO_RESUME_CAMERA", true, System.currentTimeMillis())
                        try { context.startForegroundService(Intent(context, GuardService::class.java)) }
                        catch (_: Exception) { GuardNotifications.resume(context) }
                    }
                    "TAP_TO_RESUME" -> {
                        app.database.guard().status("BOOT_TAP_TO_RESUME", true, System.currentTimeMillis())
                        GuardNotifications.resume(context)
                    }
                }
            } catch (_: Exception) { GuardNotifications.resume(context) }
            finally { pending.finish() }
        }
    }
}

class GuardAdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        val pending = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch {
            try {
                val manager = context.getSystemService(DevicePolicyManager::class.java)
                val state = app.database.guard().state()
                if (GuardLogic.unlockTrigger(state?.armed == true, manager.currentFailedPasswordAttempts))
                    app.database.guard().activate("unlock_failures", System.currentTimeMillis())
            } catch (_: Exception) { /* Admin callback availability is platform/owner dependent. */ }
            finally { pending.finish() }
        }
    }
}

class GuardHeartbeatWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HellFireApp
        if (app.database.guard().state()?.pendingDisarm != true) return Result.success()
        return try {
            GuardCycle(app).heartbeat()
            app.database.guard().settled()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("guard-disarm", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GuardHeartbeatWork>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}

/** Tier 2 only. Changing global actions has an effect only within Android lock-task mode. */
object GuardPolicies {
    fun admin(context: Context) = ComponentName(context, GuardAdminReceiver::class.java)
    fun isOwner(context: Context) = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
    fun apply(app: HellFireApp): Boolean {
        if (!isOwner(app)) return false
        val manager = app.getSystemService(DevicePolicyManager::class.java)
        val component = admin(app)
        if (app.secrets.read("guard-policy") == null) app.secrets.save("guard-policy", JSONObject()
            .put("uninstall", manager.isUninstallBlocked(component, app.packageName))
            .put("safeBoot", manager.getUserRestrictions(component).getBoolean(UserManager.DISALLOW_SAFE_BOOT))
            .put("features", manager.getLockTaskFeatures(component)).put("packages", JSONArray(manager.getLockTaskPackages(component).toList())))
        manager.setUninstallBlocked(component, app.packageName, true)
        manager.addUserRestriction(component, UserManager.DISALLOW_SAFE_BOOT)
        manager.setLockTaskPackages(component, (manager.getLockTaskPackages(component).toList() + app.packageName).distinct().toTypedArray())
        manager.setLockTaskFeatures(component, manager.getLockTaskFeatures(component) and DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS.inv())
        return true
    }
    /** Policy values to put back on disarm. Without a saved snapshot, fall back to Android's defaults rather than leave guard locks on. */
    data class Restore(val uninstallBlocked: Boolean, val clearSafeBoot: Boolean, val features: Int, val packages: List<String>)
    fun restoreValues(previous: JSONObject?, ownPackage: String, currentFeatures: Int, currentPackages: List<String>): Restore =
        if (previous != null) Restore(previous.getBoolean("uninstall"), !previous.getBoolean("safeBoot"), previous.getInt("features"),
            previous.getJSONArray("packages").let { array -> List(array.length()) { array.getString(it) } })
        else Restore(false, true, currentFeatures or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS, currentPackages - ownPackage)

    fun restore(app: HellFireApp) {
        if (!isOwner(app)) return
        val manager = app.getSystemService(DevicePolicyManager::class.java)
        val component = admin(app)
        val values = restoreValues(app.secrets.read("guard-policy"), app.packageName, manager.getLockTaskFeatures(component),
            manager.getLockTaskPackages(component).toList())
        manager.setUninstallBlocked(component, app.packageName, values.uninstallBlocked)
        if (values.clearSafeBoot) manager.clearUserRestriction(component, UserManager.DISALLOW_SAFE_BOOT)
        manager.setLockTaskFeatures(component, values.features)
        manager.setLockTaskPackages(component, values.packages.toTypedArray())
        app.secrets.remove("guard-policy")
    }

    /**
     * Gives up Device Owner so TheftGuard can be removed without a factory reset. Only while disarmed; guard policies are
     * restored first. Android offers no way back except provisioning again from a computer.
     */
    @Suppress("DEPRECATION")
    fun relinquish(app: HellFireApp) {
        if (!isOwner(app)) return
        restore(app)
        app.getSystemService(DevicePolicyManager::class.java).clearDeviceOwnerApp(app.packageName)
    }
}
