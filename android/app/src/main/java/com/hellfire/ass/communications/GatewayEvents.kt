package com.hellfire.ass.communications

import com.hellfire.ass.events.NotificationSnapshot
import org.json.JSONObject

object GatewayEvents {
    fun channel(transport: String): String = when (transport) {
        "web" -> "WHATSAPP_WEB"
        "meta" -> "WHATSAPP_BUSINESS"
        "telegram" -> "TELEGRAM"
        else -> error("Unsupported relay transport")
    }
    /** Capture-only linked sessions (WhatsApp Web, Telegram): same spool and item schema, never sending. */
    fun linked(transport: String) = transport == "web" || transport == "telegram"
    private fun eventTransport(transport: String) = when (transport) { "web" -> "whatsapp_web"; "telegram" -> "telegram"; else -> null }

    fun snapshot(event: JSONObject, id: String, accountId: String, transport: String, timestamp: Long, receivedAt: Long): NotificationSnapshot {
        channel(transport)
        val web = linked(transport)
        val expected = eventTransport(transport)
        require(if (expected != null) event.optString("transport") == expected else event.optString("transport") !in setOf("whatsapp_web", "telegram"))
        val item = event.optJSONObject("item")
        val collection = event.optString("collection")
        // The account is part of the key: two linked accounts in one group receive the same message ID.
        return NotificationSnapshot("relay:$accountId:$id", when (transport) { "telegram" -> "relay.telegram"; "web" -> "web.whatsapp"; else -> "meta.whatsapp.business" },
            timestamp, receivedAt, source = when (transport) { "telegram" -> "telegram"; "web" -> "whatsapp_web"; else -> "whatsapp_business" },
            kind = when (collection) { "messages" -> "INCOMING"; "message_echoes" -> "OUTGOING"; else -> collection.uppercase() },
            groupConversation = web && item?.optBoolean("is_group") == true,
            provenance = mapOf("relay_id" to id, "account_id" to accountId) + if (web) mapOf("transport" to transport) else emptyMap(),
            fields = mapOf("text" to listOf(item?.optJSONObject("text")?.optString("body").orEmpty()),
                "sender_hint" to listOf(if (web) item?.optString("author").orEmpty().ifBlank { item?.optString("from").orEmpty() } else item?.optString("from").orEmpty()),
                "recipient_hint" to listOf(item?.optString("to").orEmpty()),
                "event_envelope" to listOf(event.toString())) + if (web) mapOf("category" to listOf("msg")) else emptyMap())
    }

    fun thread(item: JSONObject, collection: String, transport: String): String =
        if (linked(transport)) item.getString("thread_id")
        else if (collection == "messages") item.optString("from") else item.optString("to")
}
