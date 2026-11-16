package com.hellfire.ass.communications

import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Encrypted lists of connected email inboxes and WhatsApp gateways. Each account keeps a stable id that
 * messages, drafts, mail cursors and integration states refer to. A gateway is one relay, and one relay
 * serves one WhatsApp account, so several WhatsApp accounts mean several relay URLs.
 */
object Accounts {
    const val MAIL = "mail-accounts"
    const val GATEWAY = "gateway-accounts"
    const val MAX = 10
    private val legacy = mapOf(MAIL to "mail", GATEWAY to "gateway")

    @Synchronized
    fun list(secrets: SecretStore, kind: String): List<JSONObject> {
        secrets.read(kind)?.let { stored ->
            val array = stored.getJSONArray("accounts")
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        // Single-account settings from earlier versions become the first list entry.
        val old = secrets.read(legacy.getValue(kind)) ?: return emptyList()
        write(secrets, kind, listOf(old))
        secrets.remove(legacy.getValue(kind))
        return listOf(old)
    }

    @Synchronized
    fun find(secrets: SecretStore, kind: String, id: String): JSONObject? = list(secrets, kind).firstOrNull { it.getString("id") == id }

    /** Adds the account, or replaces the one with the same id in place. */
    @Synchronized
    fun save(secrets: SecretStore, kind: String, account: JSONObject) {
        val id = account.getString("id")
        val current = list(secrets, kind)
        val next = if (current.any { it.getString("id") == id }) current.map { if (it.getString("id") == id) account else it }
            else current + account
        require(next.size <= MAX) { "At most $MAX accounts of each kind" }
        write(secrets, kind, next)
    }

    @Synchronized
    fun remove(secrets: SecretStore, kind: String, id: String) {
        val next = list(secrets, kind).filter { it.getString("id") != id }
        if (next.isEmpty()) secrets.remove(kind) else write(secrets, kind, next)
    }

    fun any(secrets: SecretStore): Boolean = list(secrets, MAIL).isNotEmpty() || list(secrets, GATEWAY).isNotEmpty()

    fun mail(secrets: SecretStore): List<MailConfig> = list(secrets, MAIL).map(MailConfig::from)
    fun gateways(secrets: SecretStore): List<JSONObject> = list(secrets, GATEWAY)
    /** Gateways that can send (official Business transport). WhatsApp Web is capture-only. */
    fun sendingGateways(secrets: SecretStore): List<JSONObject> = gateways(secrets).filter { transport(it) == "meta" }

    fun transport(gateway: JSONObject): String = gateway.optString("transport", "meta")
    fun label(gateway: JSONObject): String = gateway.optString("label").ifBlank {
        runCatching { java.net.URI(gateway.getString("url")).host }.getOrNull() ?: "WhatsApp"
    }

    /** Account id to a short display name: the email address, or the WhatsApp gateway's label. */
    fun labels(secrets: SecretStore): Map<String, String> =
        list(secrets, MAIL).associate { it.getString("id") to it.optString("address") } +
            gateways(secrets).associate { it.getString("id") to label(it) }

    fun mailId(address: String, imapHost: String): String = NotificationNormalizer.sha256(
        "imap:${address.trim().lowercase(Locale.ROOT)}|${imapHost.trim().lowercase(Locale.ROOT)}")
    fun gatewayId(transport: String, url: String): String = NotificationNormalizer.sha256("gateway:$transport:${url.trimEnd('/')}")

    /** Integration-state keys are per account. */
    fun mailState(id: String) = "EMAIL_IMAP:$id"
    fun gatewayState(id: String) = "WHATSAPP_GATEWAY:$id"
    fun idleState(id: String) = "IMAP_IDLE:$id"

    private fun write(secrets: SecretStore, kind: String, accounts: List<JSONObject>) {
        secrets.save(kind, JSONObject().put("accounts", JSONArray().apply { accounts.forEach { put(it) } }))
    }
}
