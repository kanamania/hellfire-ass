package com.hellfire.ass.finance

import androidx.room.withTransaction
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.events.NotificationNormalizer
import java.util.UUID

class FinanceRepository(private val db: EventDatabase) {
    private val dao get() = db.finances()
    suspend fun approve(candidateId: String, accountId: String, kind: String, category: String,
        description: String, transferTo: String? = null, amount: Long? = null, fee: Long? = null, occurredAt: Long? = null): String = db.withTransaction {
        val original = requireNotNull(dao.findCandidate(candidateId))
        val candidate = original.copy(amount = amount ?: original.amount, fee = fee ?: original.fee, occurredAt = occurredAt ?: original.occurredAt)
        require(candidate.occurredAt >= 0)
        require(candidate.state == "REVIEW") { "This candidate has already been reviewed" }
        val account = requireNotNull(dao.account(accountId))
        require(account.currency == "TZS" && candidate.amount > 0 && candidate.fee >= 0)
        Math.addExact(candidate.amount, candidate.fee)
        require(candidate.provider == "MANUAL" || candidate.provider == account.provider) { "Choose an account for this provider" }
        val key = NotificationNormalizer.sha256("$accountId|${candidate.provider}|${candidate.reference ?: candidate.id}")
        val existing = dao.byKey(key)
        if (existing != null) {
            dao.evidence(LedgerEvidence(existing.id, candidate.sourceId))
            linkReferenceDuplicates(existing.id, candidate)
            dao.candidateState(candidateId, "DUPLICATE_LINKED")
            return@withTransaction "Duplicate reference linked to existing evidence; no transaction added."
        }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val delta = if (kind == "OWN_TRANSFER") Math.negateExact(candidate.amount) else Money.delta(kind, candidate.amount)
        Math.addExact(dao.balance(accountId), Math.subtractExact(delta, candidate.fee))
        val destination = if (kind == "OWN_TRANSFER") requireNotNull(transferTo?.let { dao.account(it) }).also {
            require(it.id != accountId && it.currency == account.currency)
            Math.addExact(dao.balance(it.id), candidate.amount)
        } else null
        dao.post(LedgerEntry(id, accountId, key, kind, delta, category, description,
            candidate.occurredAt, now, id))
        dao.evidence(LedgerEvidence(id, candidate.sourceId))
        if (destination != null) {
            val credit = "$id:credit"
            dao.post(LedgerEntry(credit, destination.id, "$key:credit", kind, candidate.amount, "Own transfer", description, candidate.occurredAt, now, id))
            dao.evidence(LedgerEvidence(credit, candidate.sourceId))
        }
        if (candidate.fee > 0) {
            val feeId = "$id:fee"
            dao.post(LedgerEntry(feeId, accountId, "$key:fee", "FEE", Math.negateExact(candidate.fee), "Fees", description, candidate.occurredAt, now, id))
            dao.evidence(LedgerEvidence(feeId, candidate.sourceId))
        }
        linkReferenceDuplicates(id, candidate)
        dao.candidateState(candidateId, "APPROVED")
        db.communications().audit(com.hellfire.ass.communications.ActionAudit(UUID.randomUUID().toString(), id, "OWNER_LEDGER_APPROVAL", "POSTED", now))
        "Transaction posted with source evidence."
    }

    suspend fun reverse(id: String): Unit = db.withTransaction {
        val original = requireNotNull(dao.entry(id))
        require(original.reversesId == null && !dao.reversed(original.id)) { "Already corrected or a correction entry" }
        val group = dao.group(original.groupId)
        for (entry in group) {
            require(!dao.reversed(entry.id))
            val delta = Math.negateExact(entry.amount)
            Math.addExact(dao.balance(entry.accountId), delta)
            val reversalId = UUID.randomUUID().toString()
            dao.post(entry.copy(id = reversalId, dedupeKey = "reverse:${entry.id}",
                kind = "ADJUSTMENT", amount = delta, description = "Reversal: ${entry.description}",
                occurredAt = System.currentTimeMillis(), createdAt = System.currentTimeMillis(),
                groupId = "reverse:${original.groupId}", reversesId = entry.id))
            dao.sources(entry.id).forEach { dao.evidence(LedgerEvidence(reversalId, it)) }
            db.communications().audit(com.hellfire.ass.communications.ActionAudit(UUID.randomUUID().toString(), reversalId, "OWNER_LEDGER_REVERSAL", "POSTED", System.currentTimeMillis()))
        }
    }

    /**
     * Records the owner's stated balance for an account at [at] as one BALANCE ledger entry for the difference.
     * Evidence is [sourceId]: the owner's own statement, or the provider SMS that stated the balance.
     * BALANCE entries move the account balance but never count as income or spending; they can be reversed like any entry.
     */
    suspend fun setBalance(accountId: String, target: Long, at: Long, sourceId: String): Long = db.withTransaction {
        val account = requireNotNull(dao.account(accountId)) { "Unknown account" }
        require(account.currency == "TZS" && at >= 0 && target in -1_000_000_000_000L..1_000_000_000_000L)
        requireNotNull(db.events().find(sourceId)) { "Balance needs evidence" }
        val delta = Math.subtractExact(target, dao.balanceAt(accountId, at))
        if (delta == 0L) return@withTransaction 0L
        Math.addExact(dao.balance(accountId), delta)
        val opening = dao.entryCount(accountId) == 0
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.post(LedgerEntry(id, accountId, "balance:$accountId:$at:$id", "BALANCE", delta, if (opening) "Opening balance" else "Balance correction",
            "${if (opening) "Opening balance" else "Balance set"} · TZS ${String.format(java.util.Locale.ENGLISH, "%,d", target)}", at, now, id))
        dao.evidence(LedgerEvidence(id, sourceId))
        db.communications().audit(com.hellfire.ass.communications.ActionAudit(UUID.randomUUID().toString(), id, "OWNER_BALANCE_SET", "POSTED", now))
        delta
    }

    /** Changes only the category label of a posted entry. Amounts, accounts and balances never change here; those need a reversal. */
    suspend fun recategorize(id: String, category: String): Unit = db.withTransaction {
        require(category.isNotBlank() && category.length <= 60)
        val entry = requireNotNull(dao.entry(id))
        require(entry.reversesId == null && entry.kind != "FEE") { "Only original payments can be recategorised" }
        require(dao.setCategory(id, category) == 1)
        db.communications().audit(com.hellfire.ass.communications.ActionAudit(UUID.randomUUID().toString(), id, "OWNER_LEDGER_RECATEGORIZE", category, System.currentTimeMillis()))
    }

    suspend fun summary(start: Long, end: Long): FinanceSummary {
        require(start >= 0 && end > start)
        var income = 0L
        var expenses = 0L
        val sources = linkedSetOf<String>()
        for (entry in dao.period(start, end)) {
            val original = entry.reversesId?.let { dao.entry(it) }
            val kind = original?.kind ?: entry.kind
            when (kind) {
                "INCOME" -> income = Math.addExact(income, entry.amount)
                "EXPENSE", "FEE", "REFUND" -> expenses = Math.subtractExact(expenses, entry.amount)
            }
            dao.sources(entry.id).forEach(sources::add)
        }
        return FinanceSummary(income, expenses, sources.toList())
    }

    /** Other sources of the same provider reference stay as evidence, out of the review queue. */
    private suspend fun linkReferenceDuplicates(ledgerId: String, candidate: FinanceCandidate) {
        val reference = candidate.reference ?: return
        dao.duplicateSources(candidate.provider, reference).forEach { dao.evidence(LedgerEvidence(ledgerId, it)) }
        dao.linkDuplicates(candidate.provider, reference)
    }

    suspend fun collect(sourceId: String, occurredAt: Long, sender: String, text: String) {
        FinanceParser.balance(sender, text)?.let { (provider, amount) -> db.widgetData().observeBalance(ProviderBalance(provider, amount, occurredAt, sourceId)) }
        FinanceParser.parse(sender, text)?.let { parsed ->
            val duplicate = parsed.reference != null && dao.hasReference(parsed.provider, parsed.reference)
            dao.candidate(FinanceCandidate(NotificationNormalizer.sha256("finance:$sourceId:0"), sourceId,
                provider = parsed.provider, reference = parsed.reference, kind = parsed.kind,
                amount = parsed.amount, fee = parsed.fee, merchant = parsed.merchant,
                evidence = parsed.evidence, occurredAt = occurredAt, state = if (duplicate) "DUPLICATE" else "REVIEW"))
        }
    }
}

data class FinanceSummary(val income: Long, val expenses: Long, val sourceIds: List<String>)
