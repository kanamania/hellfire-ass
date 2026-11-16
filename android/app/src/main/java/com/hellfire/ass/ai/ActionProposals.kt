package com.hellfire.ass.ai

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.communications.OutboundDraft
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import androidx.room.withTransaction
import org.json.JSONObject
import java.util.UUID

sealed interface ActionProposal {
    val sourceId: String
    val evidence: String
    data class Task(override val sourceId: String, override val evidence: String, val title: String, val dueAt: Long, val party: String) : ActionProposal
    data class Reply(override val sourceId: String, override val evidence: String, val channel: String, val account: String,
        val recipient: String, val subject: String, val body: String) : ActionProposal
}

object ActionProposals {
    fun parse(text: String): ActionProposal {
        val o = CandidateContracts.objectValue(text, setOf("schema_version", "tool", "source_id", "evidence", "title", "due_at", "timezone", "party", "channel", "account_id", "recipient", "subject", "body"))
        val source = o.getString("source_id").also { require(it.length in 1..100) }
        val evidence = o.getString("evidence").also { require(it.length in 1..4000) }
        return when (o.getString("tool")) {
            "create_task" -> {
                require(o.keys().asSequence().all { it in setOf("schema_version", "tool", "source_id", "evidence", "title", "due_at", "timezone", "party") })
                require(o.getString("timezone") == "Africa/Dar_es_Salaam")
                val title = o.getString("title").also { require(it.isNotBlank() && it.length <= 300) }
                val party = o.getString("party").also { require(it in setOf("OWNER", "OTHER", "UNKNOWN")) }
                ActionProposal.Task(source, evidence, title, TaskTime.parse(o.getString("due_at")), party)
            }
            "prepare_reply" -> {
                require(o.keys().asSequence().all { it in setOf("schema_version", "tool", "source_id", "evidence", "channel", "account_id", "recipient", "subject", "body") })
                val channel = o.getString("channel").also { require(it in setOf("EMAIL_IMAP", "WHATSAPP_BUSINESS")) }
                val recipient = o.getString("recipient").also { require(it.length in 1..320 && !it.contains(Regex("[\\r\\n]"))) }
                val subject = o.optString("subject").also { require(it.length <= 500 && !it.contains(Regex("[\\r\\n]"))) }
                val body = o.getString("body").also { require(it.isNotBlank() && it.length <= 12000) }
                ActionProposal.Reply(source, evidence, channel, o.getString("account_id"), recipient, subject, body)
            }
            else -> error("Unsupported action. Sends, recording starts and arbitrary execution cannot be requested through model JSON.")
        }
    }
    suspend fun retainForReview(app: HellFireApp, json: String): String = app.database.withTransaction {
        val proposal = parse(json)
        val event = requireNotNull(app.database.events().find(proposal.sourceId))
        require(event.contentAvailable && !event.securitySensitive)
        val fields = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8)).getJSONObject("fields")
        val texts = fields.keys().asSequence().flatMap { key -> fields.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().asSequence() }.toList()
        require(texts.any { it.contains(proposal.evidence) }) { "Evidence must occur verbatim in the saved source" }
        val id = NotificationNormalizer.sha256("proposal:${proposal}")
        val now = System.currentTimeMillis()
        when (proposal) {
            is ActionProposal.Task -> app.database.tasks().insert(AssistantTask(id, proposal.sourceId, proposal.title, proposal.dueAt,
                party = proposal.party, evidence = proposal.evidence, createdAt = now, updatedAt = now))
            is ActionProposal.Reply -> {
                val config = requireNotNull(com.hellfire.ass.communications.Accounts.find(app.secrets,
                    if (proposal.channel == "EMAIL_IMAP") com.hellfire.ass.communications.Accounts.MAIL else com.hellfire.ass.communications.Accounts.GATEWAY,
                    proposal.account)) { "Proposal names an account that is not connected" }
                if (proposal.channel == "EMAIL_IMAP") javax.mail.internet.InternetAddress(proposal.recipient, true).validate()
                else {
                    require(com.hellfire.ass.communications.Accounts.transport(config) == "meta") { "WhatsApp Web is capture-only" }
                    require(proposal.recipient.matches(Regex("[0-9]{6,15}")))
                }
                if (app.database.communications().draft(id) == null) app.database.communications().draft(OutboundDraft(id, proposal.channel,
                    proposal.account, proposal.recipient, proposal.subject, proposal.body, now))
            }
        }
        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_RETAINED_PROPOSAL", "REVIEW_ONLY", now))
        "Proposal retained for review in ${if (proposal is ActionProposal.Task) "Tasks" else "Messages"}. No alarm or external send was executed."
    }
}
