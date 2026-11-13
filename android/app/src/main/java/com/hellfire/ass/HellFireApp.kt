package com.hellfire.ass

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.EventRepository
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.processing.CaptureWork
import com.hellfire.ass.sms.SmsCaptureSettings
import com.hellfire.ass.sms.SmsSyncWorker
import kotlinx.coroutines.*

class HellFireApp : Application() {
    val database by lazy { EventDatabase.open(this) }
    val cipher by lazy { PayloadCipher.deviceCipher() }
    val events by lazy { EventRepository(database.events()) { cipher } }
    val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var smsObserver: ContentObserver? = null
    private val syncSms = Runnable {
        if (SmsCaptureSettings.enabled(this) && SmsCaptureSettings.canRead(this)) captureScope.launch {
            try { SmsSyncWorker.schedule(this@HellFireApp) }
            catch (_: Exception) { runCatching { database.events().failure("android_sms", "SMS_SCHEDULING_FAILED") } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureScope.launch {
            try { CaptureWork.recover(this@HellFireApp) }
            catch (_: Exception) { runCatching { database.events().failure("processing", "WORK_SCHEDULING_FAILED") } }
        }
        refreshSmsCapture()
    }

    fun refreshSmsCapture() {
        smsObserver?.let(contentResolver::unregisterContentObserver)
        smsObserver = null
        handler.removeCallbacks(syncSms)
        if (SmsCaptureSettings.enabled(this) && SmsCaptureSettings.canRead(this)) {
            try {
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        handler.removeCallbacks(syncSms)
                        handler.postDelayed(syncSms, 1000)
                    }
                }
                contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
                smsObserver = observer
                handler.post(syncSms)
            } catch (_: SecurityException) {
                captureScope.launch { runCatching { database.events().failure("android_sms", "SMS_PROVIDER_ACCESS_DENIED") } }
            }
        }
    }
}
