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
            "EMAIL_IMAP" -> requireNotNull(Accounts.find(app.secrets, Accounts.MAIL, draft.accountId)) { "Email account was disconnected" }
            "WHATSAPP_BUSINESS" -> requireNotNull(Accounts.find(app.secrets, Accounts.GATEWAY, draft.accountId)) { "WhatsApp account was disconnected" }.also {
                require(Accounts.transport(it) == "meta") { "WhatsApp Web is capture-only" }
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
        } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught);
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
        val dao = app.database.communications()
        // Each inbox and each WhatsApp relay syncs independently; one failing account does not stop the others.
        val mail = try { Accounts.mail(app.secrets) } catch (e: CancellationException) { throw e } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); retry = true; emptyList() }
        for (config in mail) {
            try { more = MailAdapter(app).sync(config) || more }
            catch (e: CancellationException) { throw e }
            catch (caught: Exception) {
                com.hellfire.ass.Diagnostics.warn(caught)
                retry = true
                dao.integration(IntegrationState(Accounts.mailState(config.id), "FAILED", System.currentTimeMillis(), "CONNECTION_OR_CREDENTIALS_UNAVAILABLE"))
            }
        }
        val gateways = try { Accounts.gateways(app.secrets) } catch (e: CancellationException) { throw e } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); retry = true; emptyList() }
        for (config in gateways) {
            try { more = GatewayAdapter(app).sync(config) || more }
            catch (e: CancellationException) { throw e }
            catch (caught: Exception) {
                com.hellfire.ass.Diagnostics.warn(caught)
                retry = true
                dao.integration(IntegrationState(Accounts.gatewayState(config.getString("id")), "FAILED", System.currentTimeMillis(), "CONNECTION_OR_CREDENTIALS_UNAVAILABLE"))
            }
        }
        CaptureWork.process(app)
        if (more) CommunicationWork.schedule(app, true)
        if (retry) Result.retry() else Result.success()
    }
}
