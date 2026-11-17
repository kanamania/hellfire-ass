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
    val skills by lazy { com.hellfire.ass.memory.AiSkills(database) }
    val assistant by lazy { com.hellfire.ass.assistant.AssistantEngine(this) }
    val finances by lazy { com.hellfire.ass.finance.FinanceRepository(database) { id, text -> skills.ignoredFor(this, "IGNORE_FINANCE", id, text) } }
    val templates by lazy { com.hellfire.ass.templates.TemplateRunner(database, finances, packageName) { events.capture(it, packageName) } }
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
            catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); runCatching { database.events().failure("android_sms", "SMS_SCHEDULING_FAILED") } }
        }
    }

    private val processStarted = android.os.SystemClock.elapsedRealtime()
    override fun onCreate() {
        super.onCreate()
        com.hellfire.ass.performance.PerfLog.record("startup_oncreate_ms", android.os.SystemClock.elapsedRealtime() - processStarted)
        com.hellfire.ass.ui.UiLanguage.code = getSharedPreferences("ui", 0).getString("language", "en").orEmpty()
        Diagnostics.init(this)
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
                com.hellfire.ass.performance.PerfLog.record("startup_ready_ms", android.os.SystemClock.elapsedRealtime() - processStarted)
                runCatching {
                    if (secrets.read(com.hellfire.ass.security.DriveBackupWork.CONFIG)?.optBoolean("enabled") == true)
                        com.hellfire.ass.security.DriveBackupWork.schedule(this@HellFireApp)
                }
                try {
                    CaptureWork.recover(this@HellFireApp)
                    com.hellfire.ass.tasks.TaskScheduler.restore(this@HellFireApp)
                    com.hellfire.ass.media.MediaWork.recover(this@HellFireApp)
                    com.hellfire.ass.intelligence.IntelligenceWork.recover(this@HellFireApp)
                    seedAiUsage()
                    com.hellfire.ass.data.SearchBackfill.schedule(this@HellFireApp)
                    com.hellfire.ass.library.LibraryScanWork.schedule(this@HellFireApp)
                    com.hellfire.ass.library.LibraryScanWork.watch(this@HellFireApp)
                    com.hellfire.ass.ai.ModelUpdates.onStartup(this@HellFireApp)
                    watchForWidgets()
                } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); runCatching { database.events().failure("processing", "WORK_RECOVERY_FAILED") } }
            } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught);
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

    private val refreshWidgets = Runnable { captureScope.launch { runCatching { com.hellfire.ass.widgets.Widgets.refresh(this@HellFireApp) } } }
    private var watchingWidgets = false
    /** Widgets update when the ledger, tasks, inbox, media or budgets change (§8 freshness), debounced to one refresh. */
    private fun watchForWidgets() {
        if (watchingWidgets) return
        watchingWidgets = true
        database.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(arrayOf("ledger_entry", "finance_candidate", "finance_account",
            "assistant_task", "daily_review", "communication_message", "outbound_draft", "media_item", "category_budget", "provider_balance", "merchant_alias", "integration_state")) {
            override fun onInvalidated(tables: Set<String>) { handler.removeCallbacks(refreshWidgets); handler.postDelayed(refreshWidgets, 1500) }
        })
        handler.post(refreshWidgets)
    }
    fun requestWidgetRefresh() { handler.removeCallbacks(refreshWidgets); handler.postDelayed(refreshWidgets, 300) }

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
