package com.hellfire.ass

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.hellfire.ass.assistant.HellFireVoiceService
import com.hellfire.ass.notifications.HellFireNotificationListener
import com.hellfire.ass.ui.HellFireScreen
import com.hellfire.ass.sms.SmsCaptureSettings
import com.hellfire.ass.sms.SmsSyncWorker
import androidx.work.WorkManager

class MainActivity : ComponentActivity() {
    private var permissions by mutableStateOf(PermissionStatus())
    private var message by mutableStateOf<String?>(null)
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
        setContent {
            HellFireScreen(
                app = application as HellFireApp,
                permissions = permissions,
                initialTab = if (intent.action == Intent.ACTION_ASSIST) "Assistant" else "Setup",
                message = message,
                onRole = ::selectAssistant,
                onSettings = ::openSettings,
                onMicrophonePermission = {
                    val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
                    requestPermissions.launch(needed.toTypedArray())
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
            )
        }
    }

    override fun onResume() { super.onResume(); refreshStatus(); (application as HellFireApp).refreshSmsCapture() }
    override fun onStop() {
        if (isLocalVoiceInteractionSupported) stopLocalVoiceInteraction()
        super.onStop()
    }

    private fun refreshStatus() {
        val roles = getSystemService(RoleManager::class.java)
        val notifications = getSystemService(NotificationManager::class.java)
        permissions = PermissionStatus(
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
                if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) data = Uri.parse("package:$packageName")
                if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        } catch (_: Exception) {
            message = "This settings page is unavailable on this device. Open Android Settings manually."
        }
    }
}

data class PermissionStatus(
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
