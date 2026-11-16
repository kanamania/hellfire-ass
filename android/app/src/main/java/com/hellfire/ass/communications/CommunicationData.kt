package com.hellfire.ass.communications

import androidx.room.*
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "communication_message", indices = [Index(value = ["channel", "accountId", "externalId"], unique = true), Index("sourceId")],
    foreignKeys = [ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class CommunicationMessage(@PrimaryKey val id: String, val channel: String, val accountId: String,
    val externalId: String, val sourceId: String, val threadKey: String, val sender: String,
    val recipient: String, val subject: String, val body: String, val occurredAt: Long, val direction: String)

@Entity(tableName = "mail_cursor", primaryKeys = ["accountId", "folder"])
data class MailCursor(val accountId: String, val folder: String, val uidValidity: Long, val lastUid: Long)

@Entity(tableName = "outbound_draft")
data class OutboundDraft(@PrimaryKey val id: String, val channel: String, val accountId: String,
    val recipient: String, val subject: String, val body: String, val createdAt: Long,
    val state: String = "DRAFT", val approvalId: String? = null, val approvedAt: Long? = null,
    val externalId: String? = null, val error: String? = null, val replyToMessageId: String? = null)

@Entity(tableName = "action_audit", indices = [Index("draftId")])
data class ActionAudit(@PrimaryKey val id: String, val draftId: String, val action: String,
    val state: String, val occurredAt: Long, val error: String? = null)

@Entity(tableName = "integration_state")
data class IntegrationState(@PrimaryKey val adapter: String, val state: String, val updatedAt: Long, val error: String? = null)

@Dao
interface CommunicationDao {
    @Query("SELECT * FROM communication_message WHERE :query = '' OR instr(lower(sender || ' ' || recipient || ' ' || subject || ' ' || body), lower(:query)) > 0 ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun inbox(query: String, limit: Int): Flow<List<CommunicationMessage>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun message(value: CommunicationMessage): Long
    @Query("SELECT * FROM communication_message ORDER BY occurredAt DESC LIMIT 100") fun messages(): Flow<List<CommunicationMessage>>
    @Query("SELECT * FROM mail_cursor WHERE accountId = :account AND folder = :folder") suspend fun cursor(account: String, folder: String): MailCursor?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cursor(value: MailCursor)
    @Insert suspend fun draft(value: OutboundDraft)
    @Query("SELECT * FROM outbound_draft ORDER BY createdAt DESC LIMIT 100") fun drafts(): Flow<List<OutboundDraft>>
    @Query("SELECT * FROM outbound_draft WHERE id = :id") suspend fun draft(id: String): OutboundDraft?
    @Query("UPDATE outbound_draft SET state = 'APPROVED', approvalId = :approval, approvedAt = :now WHERE id = :id AND state = 'DRAFT'")
    suspend fun approve(id: String, approval: String, now: Long): Int
    @Query("UPDATE outbound_draft SET state='DRAFT', approvalId=NULL, approvedAt=NULL, error='APPROVAL_INTERRUPTED' WHERE state='APPROVED'")
    suspend fun recoverApprovals()
    @Query("UPDATE outbound_draft SET state='UNKNOWN', error='DELIVERY_INTERRUPTED' WHERE state='SENDING'")
    suspend fun recoverSending()
    @Transaction suspend fun recoverAfterProcessStart() { recoverApprovals(); recoverSending() }
    @Query("UPDATE outbound_draft SET state='DRAFT', approvalId=NULL, approvedAt=NULL, error=:error WHERE id=:id AND state='APPROVED'")
    suspend fun resetApproval(id: String, error: String)
    @Query("UPDATE outbound_draft SET state = 'SENDING' WHERE id = :id AND state = 'APPROVED' AND approvalId = :approval AND approvedAt >= :since")
    suspend fun claim(id: String, approval: String, since: Long): Int
    @Query("UPDATE outbound_draft SET state = :state, externalId = :external, error = :error WHERE id = :id")
    suspend fun delivery(id: String, state: String, external: String?, error: String?)
    @Query("UPDATE outbound_draft SET state = 'SENT_CONFIRMED' WHERE channel = 'EMAIL_IMAP' AND externalId = :messageId AND state = 'SUBMITTED'")
    suspend fun confirmSent(messageId: String)
    @Query("UPDATE outbound_draft SET state = :state WHERE channel = 'WHATSAPP_BUSINESS' AND externalId = :messageId AND ((:state = 'READ' AND state IN ('SUBMITTED', 'SENT_CONFIRMED', 'DELIVERED')) OR (:state = 'DELIVERED' AND state IN ('SUBMITTED', 'SENT_CONFIRMED')) OR (:state = 'SENT_CONFIRMED' AND state = 'SUBMITTED'))")
    suspend fun whatsappStatus(messageId: String, state: String)
    @Query("SELECT COUNT(*) FROM outbound_draft WHERE state='DRAFT'") suspend fun pendingCount(): Int
    /** Owner edits apply only while a draft is unapproved; approval and sending use the edited row. */
    @Query("UPDATE outbound_draft SET channel=:channel, accountId=:accountId, recipient=:recipient, subject=:subject, body=:body, replyToMessageId=:replyTo, error=NULL WHERE id=:id AND state='DRAFT'")
    suspend fun editDraft(id: String, channel: String, accountId: String, recipient: String, subject: String, body: String, replyTo: String?): Int
    @Query("DELETE FROM integration_state WHERE adapter = :adapter") suspend fun clearIntegration(adapter: String)
    @Insert suspend fun audit(value: ActionAudit)
    @Query("SELECT * FROM action_audit ORDER BY occurredAt DESC LIMIT 50") fun audit(): Flow<List<ActionAudit>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun integration(value: IntegrationState)
    @Query("SELECT * FROM integration_state ORDER BY adapter") fun integrations(): Flow<List<IntegrationState>>
    @Query("SELECT * FROM communication_message WHERE id = :id") suspend fun message(id: String): CommunicationMessage?
    @Query("SELECT * FROM integration_state ORDER BY adapter") suspend fun integrationsNow(): List<IntegrationState>
    @Query("SELECT * FROM communication_message WHERE occurredAt >= :start AND occurredAt < :end") suspend fun messagesIn(start: Long, end: Long): List<CommunicationMessage>
    @Query("SELECT * FROM outbound_draft") suspend fun allDrafts(): List<OutboundDraft>
    @Query("SELECT * FROM action_audit WHERE action = :action") suspend fun audits(action: String): List<ActionAudit>
}
