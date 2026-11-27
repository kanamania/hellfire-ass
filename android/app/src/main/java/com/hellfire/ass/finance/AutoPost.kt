package com.hellfire.ass.finance

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.events.NotificationNormalizer
import org.json.JSONObject
import java.util.UUID

/**
 * Owner decision 2026-11-27: optional automatic posting of trusted provider payments. Off by default
 * (Settings › Money). A payment posts on its own only if every check passes; anything else stays in To confirm.
 * Undo is the normal reversal, which returns the payment to To confirm.
 */
object AutoPost {
    data class Rule(val enabled: Boolean, val cap: Long)
    data class Facts(val fromSms: Boolean, val senderProvider: String?, val candidate: FinanceCandidate, val account: String?, val category: String?)

    fun rule(app: HellFireApp) = app.getSharedPreferences("capture", 0).let { Rule(it.getBoolean("auto_post", false), it.getLong("auto_post_cap", 50_000L)) }

    /** Pure decision, for tests. Null when the payment may post; otherwise why it waits for the owner. */
    fun refusal(rule: Rule, facts: Facts): String? {
        val c = facts.candidate
        return when {
            !rule.enabled -> "off"
            c.state != "REVIEW" -> "not in review"
            !facts.fromSms -> "not an SMS"
            facts.senderProvider != c.provider -> "sender is not ${c.provider}'s own sender ID"
            c.reference == null -> "no transaction reference"
            c.kind !in setOf("EXPENSE", "INCOME") -> "kind ${c.kind} needs review"
            Math.addExact(c.amount, c.fee) > rule.cap -> "above the auto-post limit"
            facts.account == null || facts.category == null -> "no saved account and category for this payee"
            else -> null
        }
    }

    /** Called after [FinanceRepository.collect] for a captured message. Returns true when it posted. */
    suspend fun consider(app: HellFireApp, sourceId: String, sender: String): Boolean {
        val rule = rule(app)
        if (!rule.enabled) return false
        val candidate = app.database.finances().findCandidate(NotificationNormalizer.sha256("finance:$sourceId:0")) ?: return false
        val event = app.database.events().find(sourceId) ?: return false
        val key = ReviewChoices.key(app, candidate)
        val saved = app.database.skills().active().firstOrNull { it.kind == "PAYMENT_DEFAULT" && it.trigger == key }
            ?.let { runCatching { JSONObject(it.advice).let { j -> j.getString("account") to j.getString("category") } }.getOrNull() }
            ?: ReviewChoices.read(app, key)
        val account = saved?.first?.takeIf { app.database.finances().account(it) != null }
        val facts = Facts(event.source == "android_sms", FinanceParser.senderProvider(sender), candidate, account, saved?.second)
        if (refusal(rule, facts) != null) return false
        val result = app.finances.approve(candidate.id, account!!, candidate.kind, saved!!.second, candidate.merchant ?: candidate.evidence.take(80))
        if (!result.startsWith("Transaction posted")) return false
        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), candidate.id, "AUTO_POLICY_POST", "POSTED", System.currentTimeMillis()))
        return true
    }
}
