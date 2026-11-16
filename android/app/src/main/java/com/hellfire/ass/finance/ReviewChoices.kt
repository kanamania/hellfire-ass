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
    fun read(app: HellFireApp, key: String): Pair<String, String>? = app.secrets.read(STORE)?.optJSONObject(key)?.let {
        it.getString("account") to it.getString("category")
    }
    fun save(app: HellFireApp, key: String, account: String, category: String) {
        val all = app.secrets.read(STORE) ?: JSONObject()
        all.put(key, JSONObject().put("account", account).put("category", category))
        app.secrets.save(STORE, all)
    }
    fun clear(app: HellFireApp) { app.secrets.remove(STORE) }
}
