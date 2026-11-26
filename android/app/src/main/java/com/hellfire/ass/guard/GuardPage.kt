package com.hellfire.ass.guard

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.security.AppLock
import com.hellfire.ass.security.confirmGuard
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun GuardPage() {
    val app = LocalEnv.current.app
    val activity = androidx.activity.compose.LocalActivity.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val state by remember { app.database.guard().observe() }.collectAsStateWithLifecycle(null)
    val evidence by remember { app.database.guard().evidenceLog() }.collectAsStateWithLifecycle(emptyList())
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var auth by remember { mutableStateOf<android.os.CancellationSignal?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { auth?.cancel(); auth = null }
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); auth?.cancel() }
    }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            configured = app.secrets.read(GuardRelay.SECRETS) != null
            val saved = app.database.guard().config()
            if (url.isEmpty()) url = saved?.relayUrl.orEmpty()
        }
    }
    fun allowed(permission: String): Boolean { refresh; return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
    val camera = allowed(Manifest.permission.CAMERA)
    val location = allowed(Manifest.permission.ACCESS_FINE_LOCATION)
    val background = allowed(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val notifications = GuardNotifications.available(app)
    fun run(work: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not complete the guard change. Check configuration, permissions and relay status." }
            finally { busy = false; refresh++ }
        }
    }
    fun authenticated(work: suspend () -> Unit) {
        if (auth != null) return
        auth = AppLock.confirmGuard(activity, onSuccess = { auth = null; run(work) }, onError = { message = it; auth = null })
    }
    PushedPage("TheftGuard", breadcrumb = "Settings") {
        Note("For your own device. Once armed, guard triggers can capture location and front/back photos and upload them to your relay without another prompt. Android indicators and this guard's notification stay visible.")
        if (message.isNotEmpty()) Banner(message, Tone.Info, null, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        KvList(listOf("State" to (state?.let { if (it.armed) if (it.active) "Active · ${it.trigger}" else "Armed" else "Disarmed" } ?: "Off"),
            "Service" to (state?.status ?: "OFF"), "Relay heartbeat" to (state?.lastHeartbeatAt?.let { Fmt.dayTime(it) } ?: "Never confirmed"),
            "Camera" to if (camera) "Permission granted · capture not device-verified" else "Permission missing",
            "Precise location" to if (location) "Permission granted · fix not verified" else "Permission missing",
            "Background location" to if (background) "Granted" else "Not granted · boot needs a tap",
            "Notifications" to if (notifications) "Allowed" else "Blocked · cannot arm",
            "SIM trigger" to if (allowed(Manifest.permission.READ_PHONE_STATE)) "Access granted · requires SIM baseline" else "Phone permission missing",
            "Device Admin" to if (app.getSystemService(DevicePolicyManager::class.java).isAdminActive(GuardPolicies.admin(app))) "Enabled · callbacks require device verification" else "Disabled · unlock trigger unavailable",
            "Device Owner" to if (GuardPolicies.isOwner(app)) "Provisioned · policies require device verification" else "Not provisioned · Tier 2 unavailable"))
        SectionCard("Access") {
            SecondaryButton("Grant camera, location and phone access", {
                permissions.launch((listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE) +
                    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray())
            }, Modifier.fillMaxWidth())
            SecondaryButton("Background location and notification settings", {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
            }, Modifier.fillMaxWidth())
            Note("First allow precise location. In Android app permissions, choose Location → Allow all the time for location-only boot recovery. Camera recovery always needs a tap on stock Android.")
            SecondaryButton("Enable Device Admin unlock alerts", {
                activity.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, GuardPolicies.admin(app)))
            }, Modifier.fillMaxWidth())
            SecondaryButton("Request battery optimization exemption", {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}")))
            }, Modifier.fillMaxWidth())
            Note(if (app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)) "Battery exemption granted; OEM limits may still apply." else "Battery exemption not granted. Heartbeats are best effort; missed checks may cause alerts.")
        }
        if (state?.armed != true) SectionCard("Guard relay") {
            Note(if (configured) "Credentials saved in encrypted SecretStore. Replace all fields to change them." else "Use the existing HTTPS FastAPI relay and its device token. Configure the separate guard payload key and SMTP on that server first.")
            if (state?.pendingDisarm == true) Note("Relay origin is fixed until cancellation is acknowledged. You can repair its token/key below.")
            HfTextField(url, { if (state?.pendingDisarm != true) url = it }, "Relay HTTPS origin", supporting = "https://your-relay.example", enabled = state?.pendingDisarm != true)
            HfTextField(token, { token = it }, "Device bearer token", password = true)
            HfTextField(key, { key = it }, "Guard transport key (base64)", password = true)
            PrimaryButton("Save guard relay", { run {
                withContext(Dispatchers.IO) {
                    if (app.database.guard().state()?.pendingDisarm == true) check(url.trim().trimEnd('/') == app.database.guard().config()?.relayUrl)
                    GuardRelay.validate(url.trim(), token, key).fill(0)
                    app.secrets.save(GuardRelay.SECRETS, JSONObject().put("token", token).put("key", key))
                    val now = System.currentTimeMillis()
                    app.database.guard().config(GuardConfig(relayUrl = url.trim().trimEnd('/'), createdAt = now, updatedAt = now))
                }
                if (state?.pendingDisarm == true) GuardHeartbeatWork.enqueue(app)
                token = ""; key = ""; configured = true; message = "Guard relay saved. Arming requires a fresh biometric."
            } }, Modifier.fillMaxWidth(), enabled = !busy)
        }
        SectionCard("Owner controls") {
            if (state?.armed != true) PrimaryButton("Arm with biometric", { authenticated {
                check(configured && camera && location && notifications)
                withContext(Dispatchers.IO) {
                    app.database.openHelper.writableDatabase
                    app.database.guard().arm(System.currentTimeMillis())
                    GuardPolicies.apply(app)
                }
                check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                activity.startForegroundService(Intent(activity, GuardService::class.java).putExtra("camera", true))
                message = "Guard armed locally. Relay protection starts after the first acknowledged heartbeat."
            } }, Modifier.fillMaxWidth(), enabled = !busy && configured && camera && location && notifications && state?.pendingDisarm != true)
            else {
                SecondaryButton("Resume camera with biometric", { authenticated {
                    check(camera && location && notifications)
                    activity.startForegroundService(Intent(activity, GuardService::class.java).putExtra("camera", true))
                } }, Modifier.fillMaxWidth(), enabled = !busy)
                SecondaryButton("Run a guard trigger now", { run { withContext(Dispatchers.IO) {
                    app.database.guard().activate("manual_test", System.currentTimeMillis())
                }; message = "Trigger recorded. Evidence runs on the next guard cycle." } }, Modifier.fillMaxWidth(), enabled = !busy)
                PrimaryButton("Disarm with biometric", { authenticated {
                    withContext(Dispatchers.IO) {
                        app.database.guard().disarm(System.currentTimeMillis())
                        GuardHeartbeatWork.enqueue(app)
                        GuardPolicies.restore(app)
                    }
                    activity.stopService(Intent(activity, GuardService::class.java))
                    app.getSystemService(android.app.NotificationManager::class.java).cancel(GuardNotifications.ID)
                    message = "Disarmed locally. Relay cancellation is queued; a missing-heartbeat alert may still arrive while offline."
                } }, Modifier.fillMaxWidth(), enabled = !busy)
            }
            if (state?.pendingDisarm == true) Note("Relay disarm is pending. Re-arming waits for its acknowledgment. Connect to the network and check relay configuration.")
            if (state?.armed != true && GuardPolicies.isOwner(app)) {
                Note("Device Owner blocks uninstalling HellFire and safe boot while armed. Removing it restores those settings; setting it up again needs a computer.")
                SecondaryButton("Remove Device Owner with biometric", { authenticated {
                    withContext(Dispatchers.IO) {
                        check(app.database.guard().state()?.armed != true)
                        GuardPolicies.relinquish(app)
                    }
                    message = if (GuardPolicies.isOwner(app)) "Android kept Device Owner. Check Settings › Security › Device admin apps." else "Device Owner removed. HellFire can now be uninstalled normally."
                } }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
        SectionCard("Evidence log") {
            if (evidence.isEmpty()) Note("No evidence captured.")
            evidence.forEach { row -> Note("${Fmt.dayTime(row.createdAt)} · ${if (row.uploadedAt != null) "Relay accepted · email queued" else row.error ?: "Encrypted locally · upload pending"}") }
            Note("Local encrypted evidence has a 50 MiB cap. Full storage pauses new capture; heartbeats continue. Hardware power-off cannot be blocked. A server alert means a missed check-in, not confirmed theft.")
        }
    }
}
