package com.hellfire.ass.finance

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ui.EventText
import org.json.JSONObject

/** Owner-approved defaults only suggest selections. They cannot approve or post a payment. */
object ReviewChoices {
    private const val STORE = "payment-review-choices"
    suspend fun key(app: HellFireApp, candidate: FinanceCandidate): String {
        val event = app.database.events().find(candidate.sourceId)
        val sender = event?.let { EventText.fields(app, it)["sender_hint"]?.firstOrNull() }.orEmpty()
        return candidate.provider + "|" + (candidate.merchant?.let { "merchant:$it" } ?: "sender:" + sender.lowercase())
    }
    suspend fun read(app: HellFireApp, key: String): Pair<String, String>? = app.secrets.read(STORE)?.optJSONObject(key)?.let {
        it.getString("account") to it.getString("category")
    }
    suspend fun save(app: HellFireApp, key: String, account: String, category: String) {
        val id = app.skills.suggest("Payment defaults", "PAYMENT_DEFAULT", key,
            JSONObject().put("account", account).put("category", category).toString(), "You chose Remember my account and category after reviewing a payment.", null)
        val old = requireNotNull(app.database.skills().find(id))
        app.skills.save(old.copy(advice = JSONObject().put("account", account).put("category", category).toString(), state = "ACTIVE"))
        app.secrets.read(STORE)?.let { legacy -> legacy.remove(key); if (legacy.length() == 0) app.secrets.remove(STORE) else app.secrets.save(STORE, legacy) }
    }
    fun clear(app: HellFireApp) { app.secrets.remove(STORE) }
}
