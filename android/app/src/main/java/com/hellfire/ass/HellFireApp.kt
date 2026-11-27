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
import kotlinx.coroutines.sync.withLock
import com.hellfire.ass.assistant.AssistantEngine
import com.hellfire.ass.calendar.CalendarRepository
import com.hellfire.ass.calendar.PhoneCalendar
import com.hellfire.ass.communications.CommunicationRepository
import com.hellfire.ass.data.AiUsage
import com.hellfire.ass.data.SearchBackfill
import com.hellfire.ass.finance.FinanceRepository
import com.hellfire.ass.intelligence.IntelligenceWork
import com.hellfire.ass.library.LibraryScanWork
import com.hellfire.ass.media.LocalSpeech
import com.hellfire.ass.media.MediaVault
import com.hellfire.ass.media.MediaWork
import com.hellfire.ass.media.SpeakerModel
import com.hellfire.ass.memory.AiSkills
import com.hellfire.ass.memory.SemanticIndex
import com.hellfire.ass.news.NewsRepository
import com.hellfire.ass.news.NewsWork
import com.hellfire.ass.performance.PerfLog
import com.hellfire.ass.security.DriveBackupWork
import com.hellfire.ass.security.SecretStore
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.templates.TemplateRunner
import com.hellfire.ass.ui.UiLanguage
import com.hellfire.ass.widgets.Widgets

class HellFireApp : Application() {
    val ai by lazy {
        val transport = HttpsAiTransport()
        val onDevice = object : OnDeviceModel {
            override suspend fun generate(prompt: String) = if (localModel.configured) localModel.generate(prompt).text else null
            override fun installed() = localModel.configured
        }
        AiRouter(EncryptedAiStore(this) { cipher }, listOf(OfflineProvider(onDevice = onDevice), OpenAiProvider(transport),
            ClaudeProvider(transport), GrokProvider(transport), DeepSeekProvider(transport)), onRecord = { audit ->
                captureScope.launch { runCatching { database.aiUsage().insert(audit.toUsage()) } }
            })
    }
    val database by lazy { EventDatabase.open(this) }
    val secrets by lazy { SecretStore(this) { cipher } }
    val communications by lazy { CommunicationRepository(this) }
    private val localModelInstance = lazy { LocalModel(this) }
    val localModel by localModelInstance
    private val semanticInstance = lazy { SemanticIndex(this) }
    val semantic by semanticInstance
    val media by lazy { MediaVault(this) }
    private val speechInstance = lazy { LocalSpeech(this) }
    val speech by speechInstance
    private val speakersInstance = lazy { SpeakerModel(this) }
    val speakers by speakersInstance
    val skills by lazy { AiSkills(database) }
    val assistant by lazy { AssistantEngine(this) }
    val finances by lazy { FinanceRepository(database) { id, text -> skills.ignoredFor(this, "IGNORE_FINANCE", id, text) } }
    val templates by lazy { TemplateRunner(database, finances, packageName) { events.capture(it, packageName) } }
    val cipher by lazy { PayloadCipher.deviceCipher() }
    val calendar by lazy { CalendarRepository(database, events, PhoneCalendar(this), packageName) }
    val events by lazy { EventRepository(database.events()) { cipher } }
    val news by lazy { NewsRepository(this) }
    val storageStatus = kotlinx.coroutines.flow.MutableStateFlow("STARTING")
    private var recoveredDrafts = false
    private val storageLock = kotlinx.coroutines.sync.Mutex()
    val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var smsObserver: ContentObserver? = null
    private val syncSms = Runnable {
        if (SmsCaptureSettings.enabled(this) && SmsCaptureSettings.canRead(this)) captureScope.launch {
            try { SmsSyncWorker.schedule(this@HellFireApp) }
            catch (caught: Exception) { Diagnostics.warn(caught); runCatching { database.events().failure("android_sms", "SMS_SCHEDULING_FAILED") } }
        }
    }

    private val processStarted = android.os.SystemClock.elapsedRealtime()
    override fun onCreate() {
        super.onCreate()
        PerfLog.record("startup_oncreate_ms", android.os.SystemClock.elapsedRealtime() - processStarted)
        UiLanguage.code = getSharedPreferences("ui", 0).getString("language", "en").orEmpty()
        Diagnostics.init(this)
        if (getProcessName() == "$packageName:guard") return
        initializeStorage()
        refreshSmsCapture()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) {
            captureScope.launch { releaseIdleModels() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        captureScope.launch { releaseIdleModels() }
    }

    /** Wait for active inference, then release only owners that have actually been used. */
    internal suspend fun releaseIdleModels() = HeavyModelGate.mutex.withLock {
        if (localModelInstance.isInitialized()) localModelInstance.value.closeCached()
        if (speechInstance.isInitialized()) speechInstance.value.closeCached()
        if (semanticInstance.isInitialized()) semanticInstance.value.closeCached()
        if (speakersInstance.isInitialized()) speakersInstance.value.closeCached()
    }

    fun initializeStorage() {
        captureScope.launch {
            storageLock.lock()
            try {
                storageStatus.value = "STARTING"
                database.openHelper.writableDatabase
                if (!recoveredDrafts) { database.communications().recoverAfterProcessStart(); calendar.recover(); recoveredDrafts = true }
                storageStatus.value = "READY"
                PerfLog.record("startup_ready_ms", android.os.SystemClock.elapsedRealtime() - processStarted)
                runCatching {
                    if (secrets.read(DriveBackupWork.CONFIG)?.optBoolean("enabled") == true)
                        DriveBackupWork.schedule(this@HellFireApp)
                }
                try {
                    CaptureWork.recover(this@HellFireApp)
                    TaskScheduler.restore(this@HellFireApp)
                    MediaWork.recover(this@HellFireApp)
                    IntelligenceWork.recover(this@HellFireApp)
                    seedAiUsage()
                    SearchBackfill.schedule(this@HellFireApp)
                    LibraryScanWork.schedule(this@HellFireApp)
                    LibraryScanWork.watch(this@HellFireApp)
                    runCatching { NewsWork.schedule(this@HellFireApp) }
                    ModelUpdates.onStartup(this@HellFireApp)
                    watchForWidgets()
                } catch (caught: Exception) { Diagnostics.warn(caught); runCatching { database.events().failure("processing", "WORK_RECOVERY_FAILED") } }
            } catch (caught: Exception) { Diagnostics.warn(caught);
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

    private val refreshWidgets = Runnable { captureScope.launch { runCatching { Widgets.refresh(this@HellFireApp) } } }
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

private fun AiAudit.toUsage() = AiUsage(requestId = requestId, at = at, task = task.label, provider = provider.label,
    model = model, result = result, inputTokens = input, outputTokens = output, microUsd = estimatedMicroUsd)
