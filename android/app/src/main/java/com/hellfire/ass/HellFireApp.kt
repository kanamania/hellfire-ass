package com.hellfire.ass

import com.hellfire.ass.ai.*
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
    val ai by lazy {
        val transport = HttpsAiTransport()
        AiRouter(EncryptedAiStore(this) { cipher }, listOf(OfflineProvider(), OpenAiProvider(transport),
            ClaudeProvider(transport), GrokProvider(transport), DeepSeekProvider(transport)), onRecord = { audit ->
                captureScope.launch { runCatching { database.aiUsage().insert(audit.toUsage()) } }
            })
    }
    val database by lazy { EventDatabase.open(this) }
    val secrets by lazy { com.hellfire.ass.security.SecretStore(this) { cipher } }
    val communications by lazy { com.hellfire.ass.communications.CommunicationRepository(this) }
    val localModel by lazy { com.hellfire.ass.ai.LocalModel(this) }
    val semantic by lazy { com.hellfire.ass.memory.SemanticIndex(this) }
    val media by lazy { com.hellfire.ass.media.MediaVault(this) }
    val speech by lazy { com.hellfire.ass.media.LocalSpeech(this) }
    val speakers by lazy { com.hellfire.ass.media.SpeakerModel(this) }
    val finances by lazy { com.hellfire.ass.finance.FinanceRepository(database) }
    val cipher by lazy { PayloadCipher.deviceCipher() }
    val events by lazy { EventRepository(database.events()) { cipher } }
    val storageStatus = kotlinx.coroutines.flow.MutableStateFlow("STARTING")
    private var recoveredDrafts = false
    private val storageLock = kotlinx.coroutines.sync.Mutex()
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
        initializeStorage()
        refreshSmsCapture()
    }

    fun initializeStorage() {
        captureScope.launch {
            storageLock.lock()
            try {
                storageStatus.value = "STARTING"
                database.openHelper.writableDatabase
                if (!recoveredDrafts) { database.communications().recoverAfterProcessStart(); recoveredDrafts = true }
                storageStatus.value = "READY"
                try {
                    CaptureWork.recover(this@HellFireApp)
                    com.hellfire.ass.tasks.TaskScheduler.restore(this@HellFireApp)
                    com.hellfire.ass.media.MediaWork.recover(this@HellFireApp)
                    seedAiUsage()
                } catch (_: Exception) { runCatching { database.events().failure("processing", "WORK_RECOVERY_FAILED") } }
            } catch (_: Exception) {
                storageStatus.value = "UNAVAILABLE"
            } finally { storageLock.unlock() }
        }
    }

    /** One-time copy of the router's metadata audit (last 100 attempts) into the v10 usage log. */
    private suspend fun seedAiUsage() {
        val prefs = getSharedPreferences("ui", 0)
        if (prefs.getBoolean("ai_usage_seeded", false)) return
        if (database.aiUsage().count() == 0) ai.store.read().audit.filter { it.result != "STARTED" }.forEach { database.aiUsage().insert(it.toUsage()) }
        prefs.edit().putBoolean("ai_usage_seeded", true).apply()
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

private fun AiAudit.toUsage() = com.hellfire.ass.data.AiUsage(requestId = requestId, at = at, task = task.label, provider = provider.label,
    model = model, result = result, inputTokens = input, outputTokens = output, microUsd = estimatedMicroUsd)
