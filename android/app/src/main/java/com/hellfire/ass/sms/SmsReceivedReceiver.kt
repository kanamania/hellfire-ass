package com.hellfire.ass.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.launch
import com.hellfire.ass.Diagnostics

/** The SMS provider holds the content; no private PDU/body goes into WorkManager Data. */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !SmsCaptureSettings.enabled(context)) return
        val result = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch {
            try { SmsSyncWorker.schedule(app) }
            catch (caught: Exception) { Diagnostics.warn(caught); runCatching { app.database.events().failure("android_sms", "SMS_SCHEDULING_FAILED") } }
            finally { result.finish() }
        }
    }
}
