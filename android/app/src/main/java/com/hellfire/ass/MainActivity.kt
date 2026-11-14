package com.hellfire.ass

import android.Manifest
import android.os.CancellationSignal
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager.Authenticators
import android.app.KeyguardManager
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import com.hellfire.ass.security.AppLock
import com.hellfire.ass.security.AppLockSession
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.hellfire.ass.assistant.HellFireVoiceService
import com.hellfire.ass.notifications.HellFireNotificationListener
import com.hellfire.ass.ui.HellFireRoot
import com.hellfire.ass.ui.MainTab
import com.hellfire.ass.ui.PermissionActions
import com.hellfire.ass.sms.SmsCaptureSettings
import com.hellfire.ass.sms.SmsSyncWorker
import androidx.work.WorkManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val lockSession = AppLockSession()
    private var lockEnabled by mutableStateOf(false)
    private var locked by mutableStateOf(true)
    private var authentication: CancellationSignal? = null
    private var lockMessage by mutableStateOf("")
    private var permissions by mutableStateOf(PermissionStatus())
    private var message by mutableStateOf<String?>(null)
    private var deepLink by mutableStateOf<Uri?>(null)
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshStatus()
        message = "Permission status refreshed. Tap Start recording when ready."
    }
    private val requestSms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val app = application as HellFireApp
        if (!SmsCaptureSettings.setEnabled(app, SmsCaptureSettings.canRead(app))) {
            SmsCaptureSettings.setEnabled(app, false)
            message = "Could not save SMS capture consent. SMS capture remains disabled."
            refreshStatus()
            return@registerForActivityResult
        }
        app.refreshSmsCapture()
        refreshStatus()
        message = if (permissions.smsRead) "SMS capture enabled. Authorized history will be imported locally."
            else "SMS access was not granted. Android or the installer may restrict this permission."
    }
    private val requestRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lockEnabled = AppLock.enabled(this)
        locked = lockSession.requiresUnlock(lockEnabled)
        deepLink = linkFor(intent)
        setContent {
            val privateUi = rememberSaveableStateHolder()
            if (!locked) privateUi.SaveableStateProvider("private-ui") {
                HellFireRoot(
                    app = application as HellFireApp,
                    permissions = permissions,
                    actions = PermissionActions(
                        onAppLockChange = { authenticate(it) },
                        onRole = ::selectAssistant,
                        onSettings = ::openSettings,
                        onMicrophone = {
                            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
                            requestPermissions.launch(needed.toTypedArray())
                        },
                        onNotifications = {
                            if (Build.VERSION.SDK_INT >= 33) requestPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            else openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        },
                        onSmsEnable = {
                            requestSms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                        },
                        onSmsDisable = {
                            val saved = SmsCaptureSettings.setEnabled(this, false)
                            WorkManager.getInstance(this).cancelUniqueWork(SmsSyncWorker.WORK_NAME)
                            (application as HellFireApp).refreshSmsCapture()
                            refreshStatus()
                            message = if (saved) "SMS capture disabled. Previously captured events are retained."
                                else "SMS capture stopped for this session, but the preference could not be saved. Revoke SMS permission in app settings before restarting."
                        },
                        onSession = {
                            if (permissions.voiceServiceActive) {
                                try { startLocalVoiceInteraction(Bundle()) }
                                catch (_: Exception) { message = "Assistant session unavailable. Try the system assistant gesture and record the device result." }
                            } else message = "Select HellFire Ass as the digital assistant, then try again."
                        },
                    ),
                    initialTab = MainTab.Home,
                    deepLink = deepLink,
                    onDeepLinkHandled = { deepLink = null },
                    message = message,
                    onMessageShown = { message = null },
                )
            } else {
                com.hellfire.ass.ui.theme.HellFireTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("HellFire Ass is locked", style = MaterialTheme.typography.headlineMedium)
                            Text("Use your enrolled fingerprint or device PIN, pattern or password to open your private data.")
                            com.hellfire.ass.ui.components.PrimaryButton("Unlock HellFire Ass", { authenticate() })
                            Text(lockMessage)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = linkFor(intent)
    }

    /** Deep links come from widgets, reminder notifications and the assist gesture; all are explicit, unexported intents. */
    private fun linkFor(intent: Intent?): Uri? = when {
        intent?.action == Intent.ACTION_ASSIST -> Uri.parse("hellfire://ask")
        intent?.data?.scheme == "hellfire" -> intent.data
        else -> null
    }

    override fun onResume() {
        super.onResume(); refreshStatus()
        val app = application as HellFireApp
        app.refreshSmsCapture()
        app.captureScope.launch { runCatching { com.hellfire.ass.tasks.TaskScheduler.restore(app) } }
    }
    @Suppress("DEPRECATION")
    private fun authenticate(enable: Boolean? = null) {
        if (authentication != null) return
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            lockMessage = "Set a device PIN and enroll a fingerprint in Android Settings first."
            message = lockMessage
            return
        }
        val signal = CancellationSignal()
        authentication = signal
        val builder = BiometricPrompt.Builder(this)
            .setTitle(if (enable == null) "Unlock HellFire Ass" else "Confirm app lock change")
            .setDescription("Use your enrolled fingerprint or device screen lock.")
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        else builder.setDeviceCredentialAllowed(true)
        try {
            builder.build().authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (authentication !== signal) return
                    authentication = null
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                    try {
                        if (enable != null) AppLock.setEnabled(this@MainActivity, enable)
                        lockEnabled = AppLock.enabled(this@MainActivity)
                        refreshStatus()
                        lockSession.authenticate()
                        locked = lockSession.requiresUnlock(lockEnabled)
                        lockMessage = ""
                        message = if (lockEnabled) "App lock enabled. HellFire locks when it leaves the foreground." else "App lock disabled."
                    } catch (_: Exception) { lockMessage = "Could not save app lock. Try again."; message = lockMessage }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (authentication !== signal) return
                    authentication = null
                    lockMessage = "Authentication was cancelled or unavailable. Try again or use the device screen lock."
                    message = lockMessage
                }
            })
        } catch (_: Exception) {
            authentication = null
            lockMessage = "Authentication could not start. Check your device screen lock and fingerprint enrollment."
            message = lockMessage
        }
    }

    override fun onStop() {
        lockSession.lock()
        locked = lockSession.requiresUnlock(lockEnabled)
        if (isLocalVoiceInteractionSupported) stopLocalVoiceInteraction()
        super.onStop()
    }

    override fun onDestroy() {
        val pending = authentication
        authentication = null
        pending?.cancel()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val roles = getSystemService(RoleManager::class.java)
        val notifications = getSystemService(NotificationManager::class.java)
        permissions = PermissionStatus(
            appLockEnabled = lockEnabled,
            roleAvailable = roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT),
            roleHeld = roles.isRoleHeld(RoleManager.ROLE_ASSISTANT),
            voiceServiceActive = VoiceInteractionService.isActiveService(this, ComponentName(this, HellFireVoiceService::class.java)),
            notificationAccess = notifications.isNotificationListenerAccessGranted(ComponentName(this, HellFireNotificationListener::class.java)),
            notificationPosting = notifications.areNotificationsEnabled(),
            smsRead = SmsCaptureSettings.canRead(this),
            smsReceive = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
            smsEnabled = SmsCaptureSettings.enabled(this),
            microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            exactAlarm = if (Build.VERSION.SDK_INT >= 31) getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else null,
            batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun selectAssistant() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            try { requestRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)); return }
            catch (_: Exception) { message = "Role chooser unavailable. Use digital assistant settings." }
        }
        openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).apply {
                if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS || action == Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) data = Uri.parse("package:$packageName")
                if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        } catch (_: Exception) {
            message = "This settings page is unavailable on this device. Open Android Settings manually."
        }
    }
}

data class PermissionStatus(
    val appLockEnabled: Boolean = false,
    val roleAvailable: Boolean = false,
    val roleHeld: Boolean = false,
    val voiceServiceActive: Boolean = false,
    val notificationAccess: Boolean = false,
    val notificationPosting: Boolean = false,
    val smsRead: Boolean = false,
    val smsReceive: Boolean = false,
    val smsEnabled: Boolean = false,
    val microphone: Boolean = false,
    val exactAlarm: Boolean? = null,
    val batteryExempt: Boolean = false,
)
