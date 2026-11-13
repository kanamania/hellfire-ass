package com.hellfire.ass.communications

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.processing.CaptureWork
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class CommunicationRepository(private val app: HellFireApp) {
    suspend fun approve(id: String): String = app.database.withTransaction {
        val nonce = UUID.randomUUID().toString()
        require(app.database.communications().approve(id, nonce, System.currentTimeMillis()) == 1) { "Draft is no longer awaiting approval" }
        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_APPROVAL", "APPROVED", System.currentTimeMillis()))
        nonce
    }
    suspend fun sendApproved(id: String, approval: String) = withContext(Dispatchers.IO) {
        val dao = app.database.communications()
        val draft = requireNotNull(dao.draft(id))
        val config = try { when (draft.channel) {
            "EMAIL_IMAP" -> requireNotNull(app.secrets.read("mail")).also { require(it.getString("id") == draft.accountId) }
            "WHATSAPP_BUSINESS" -> requireNotNull(app.secrets.read("gateway")).also {
                require(it.getString("id") == draft.accountId && it.optString("transport", "meta") == "meta") { "WhatsApp Web is capture-only" }
            }
            else -> error("This channel does not support sending")
        }
        } catch (error: Exception) { dao.resetApproval(id, "CONFIGURATION_UNAVAILABLE"); throw error }
        require(dao.claim(id, approval, System.currentTimeMillis() - 300_000) == 1) { "Fresh owner approval required; automatic retries are prohibited" }
        try {
            val external = if (draft.channel == "EMAIL_IMAP") MailAdapter(app).send(MailConfig.from(config), draft)
                else GatewayAdapter(app).send(config, draft)
            dao.delivery(id, "SUBMITTED", external, null)
            dao.audit(ActionAudit(UUID.randomUUID().toString(), id, "SEND", "SUBMITTED", System.currentTimeMillis()))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.delivery(id, "UNKNOWN", null, "DELIVERY_UNCONFIRMED") }
            throw e
        } catch (_: Exception) {
            dao.delivery(id, "UNKNOWN", null, "DELIVERY_UNCONFIRMED")
            dao.audit(ActionAudit(UUID.randomUUID().toString(), id, "SEND", "UNKNOWN", System.currentTimeMillis(), "DELIVERY_UNCONFIRMED"))
            error("Delivery unconfirmed. Check the source channel before creating another send.")
        }
        runCatching { CommunicationWork.schedule(app) }
    }
}

object CommunicationWork {
    const val NAME = "communication-sync"
    suspend fun schedule(context: Context, followUp: Boolean = false) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).enqueueUniqueWork(NAME,
            if (followUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CommunicationSyncWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()).result.get()
        Unit
    }
}

class CommunicationSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HellFireApp
        var retry = false
        var more = false
        for ((name, adapter) in listOf("mail" to "EMAIL_IMAP", "gateway" to "WHATSAPP_GATEWAY")) {
            try {
                val config = app.secrets.read(name)
                if (config == null) {
                    app.database.communications().integration(IntegrationState(adapter, "NOT_CONFIGURED", System.currentTimeMillis()))
                } else {
                    more = (if (name == "mail") MailAdapter(app).sync(MailConfig.from(config)) else GatewayAdapter(app).sync(config)) || more
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                retry = true
                app.database.communications().integration(IntegrationState(adapter, "FAILED", System.currentTimeMillis(), "CONNECTION_OR_CREDENTIALS_UNAVAILABLE"))
            }
        }
        CaptureWork.process(app)
        if (more) CommunicationWork.schedule(app, true)
        if (retry) Result.retry() else Result.success()
    }
}
