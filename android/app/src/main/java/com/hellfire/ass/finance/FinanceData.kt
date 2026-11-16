package com.hellfire.ass.finance

import androidx.room.*
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "finance_account")
data class FinanceAccount(@PrimaryKey val id: String = UUID.randomUUID().toString(), val label: String,
    val provider: String = "MANUAL", val currency: String = "TZS", val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "finance_candidate", indices = [Index(value = ["sourceId", "position"], unique = true)],
    foreignKeys = [ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class FinanceCandidate(@PrimaryKey val id: String, val sourceId: String, val position: Int = 0,
    val provider: String, val reference: String?, val kind: String, val amount: Long, val fee: Long,
    val merchant: String?, val evidence: String, val occurredAt: Long, val state: String = "REVIEW",
    val accountId: String? = null, val category: String? = null)

@Entity(tableName = "ledger_entry", indices = [Index(value = ["dedupeKey"], unique = true), Index(value = ["reversesId"], unique = true), Index("accountId")],
    foreignKeys = [ForeignKey(entity = FinanceAccount::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)])
data class LedgerEntry(@PrimaryKey val id: String, val accountId: String, val dedupeKey: String,
    val kind: String, val amount: Long, val category: String, val description: String,
    val occurredAt: Long, val createdAt: Long, val groupId: String, val reversesId: String? = null)

@Entity(tableName = "ledger_evidence", primaryKeys = ["ledgerId", "sourceId"], indices = [Index("sourceId")], foreignKeys = [
    ForeignKey(entity = LedgerEntry::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
])
data class LedgerEvidence(val ledgerId: String, val sourceId: String)

@Entity(tableName = "merchant_alias", primaryKeys = ["provider", "number"])
data class MerchantAlias(val provider: String, val number: String, val name: String, val category: String, val confirmedAt: Long)

@Dao
interface FinanceDao {
    @Insert suspend fun addAccount(account: FinanceAccount)
    @Query("SELECT * FROM finance_account ORDER BY label") fun accounts(): Flow<List<FinanceAccount>>
    @Query("SELECT * FROM finance_account ORDER BY label") suspend fun allAccounts(): List<FinanceAccount>
    @Query("SELECT COUNT(*) FROM finance_candidate WHERE state='REVIEW'") suspend fun reviewCount(): Int
    @Query("SELECT * FROM finance_account WHERE id = :id") suspend fun account(id: String): FinanceAccount?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun candidate(candidate: FinanceCandidate): Long
    @Query("SELECT * FROM finance_candidate WHERE state = 'REVIEW' ORDER BY occurredAt DESC") fun candidates(): Flow<List<FinanceCandidate>>
    @Query("SELECT * FROM finance_candidate WHERE id = :id") suspend fun findCandidate(id: String): FinanceCandidate?
    @Query("SELECT EXISTS(SELECT 1 FROM finance_candidate WHERE provider = :provider AND reference = :reference)") suspend fun hasReference(provider: String, reference: String): Boolean
    @Query("SELECT sourceId FROM finance_candidate WHERE provider = :provider AND reference = :reference AND state = 'DUPLICATE'") suspend fun duplicateSources(provider: String, reference: String): List<String>
    @Query("UPDATE finance_candidate SET state = 'DUPLICATE_LINKED' WHERE provider = :provider AND reference = :reference AND state = 'DUPLICATE'") suspend fun linkDuplicates(provider: String, reference: String)
    @Query("UPDATE finance_candidate SET state = :state WHERE id = :id") suspend fun candidateState(id: String, state: String)
    @Query("SELECT * FROM ledger_entry ORDER BY occurredAt DESC, createdAt DESC LIMIT 100") fun entries(): Flow<List<LedgerEntry>>
    @Query("SELECT * FROM ledger_entry WHERE dedupeKey = :key") suspend fun byKey(key: String): LedgerEntry?
    @Query("SELECT * FROM ledger_entry WHERE id = :id") suspend fun entry(id: String): LedgerEntry?
    @Query("SELECT * FROM ledger_entry WHERE groupId = :groupId") suspend fun group(groupId: String): List<LedgerEntry>
    @Query("SELECT EXISTS(SELECT 1 FROM ledger_entry WHERE reversesId = :id)") suspend fun reversed(id: String): Boolean
    @Insert suspend fun post(entry: LedgerEntry)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun evidence(evidence: LedgerEvidence)
    @Query("SELECT COALESCE(SUM(amount), 0) FROM ledger_entry WHERE accountId = :accountId") suspend fun balance(accountId: String): Long
    @Query("SELECT * FROM ledger_entry WHERE occurredAt >= :start AND occurredAt < :end") suspend fun period(start: Long, end: Long): List<LedgerEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun merchant(alias: MerchantAlias)
    @Query("SELECT * FROM merchant_alias WHERE provider = :provider AND number = :number") suspend fun merchant(provider: String, number: String): MerchantAlias?
    @Query("SELECT sourceId FROM ledger_evidence WHERE ledgerId=:id") suspend fun sources(id: String): List<String>
    @Query("SELECT COUNT(*) FROM ledger_entry") suspend fun entryCount(): Int
    @Query("SELECT * FROM merchant_alias ORDER BY name") fun aliases(): Flow<List<MerchantAlias>>
    @Query("SELECT * FROM ledger_entry WHERE occurredAt >= :start AND occurredAt < :end ORDER BY occurredAt DESC") fun periodFlow(start: Long, end: Long): Flow<List<LedgerEntry>>
    @Query("SELECT * FROM ledger_entry ORDER BY occurredAt") suspend fun allEntries(): List<LedgerEntry>
    @Query("SELECT * FROM ledger_evidence") suspend fun allEvidence(): List<LedgerEvidence>
    @Query("SELECT DISTINCT sourceId FROM ledger_evidence WHERE sourceId IN (:ids)") suspend fun evidenced(ids: List<String>): List<String>
    @Query("SELECT * FROM finance_candidate WHERE sourceId = :sourceId") suspend fun candidatesFor(sourceId: String): List<FinanceCandidate>
    @Query("SELECT * FROM ledger_entry WHERE id IN (SELECT ledgerId FROM ledger_evidence WHERE sourceId = :sourceId)") suspend fun entriesFor(sourceId: String): List<LedgerEntry>
    @Query("SELECT * FROM finance_candidate ORDER BY occurredAt DESC") suspend fun allCandidates(): List<FinanceCandidate>
    @Query("UPDATE ledger_entry SET category = :category WHERE id = :id") suspend fun setCategory(id: String, category: String): Int
    @Query("SELECT COALESCE(SUM(amount), 0) FROM ledger_entry WHERE accountId = :accountId AND occurredAt <= :at") suspend fun balanceAt(accountId: String, at: Long): Long
    @Query("SELECT COUNT(*) FROM ledger_entry WHERE accountId = :accountId") suspend fun entryCount(accountId: String): Int
    @Query("SELECT * FROM ledger_entry WHERE id = :id") fun observeEntry(id: String): Flow<LedgerEntry?>
}
