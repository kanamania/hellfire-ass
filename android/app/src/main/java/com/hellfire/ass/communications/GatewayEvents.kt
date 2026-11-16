package com.hellfire.ass.communications

import com.hellfire.ass.events.NotificationSnapshot
import org.json.JSONObject

object GatewayEvents {
    fun channel(transport: String): String = when (transport) {
        "web" -> "WHATSAPP_WEB"
        "meta" -> "WHATSAPP_BUSINESS"
        else -> error("Unsupported WhatsApp transport")
    }

    fun snapshot(event: JSONObject, id: String, accountId: String, transport: String, timestamp: Long, receivedAt: Long): NotificationSnapshot {
        channel(transport)
        val web = transport == "web"
        require(if (web) event.optString("transport") == "whatsapp_web" else event.optString("transport") != "whatsapp_web")
        val item = event.optJSONObject("item")
        val collection = event.optString("collection")
        // The account is part of the key: two linked accounts in one group receive the same message ID.
        return NotificationSnapshot("relay:$accountId:$id", if (web) "web.whatsapp" else "meta.whatsapp.business", timestamp, receivedAt,
            source = if (web) "whatsapp_web" else "whatsapp_business",
            kind = when (collection) { "messages" -> "INCOMING"; "message_echoes" -> "OUTGOING"; else -> collection.uppercase() },
            groupConversation = web && item?.optBoolean("is_group") == true,
            provenance = mapOf("relay_id" to id, "account_id" to accountId) + if (web) mapOf("transport" to transport) else emptyMap(),
            fields = mapOf("text" to listOf(item?.optJSONObject("text")?.optString("body").orEmpty()),
                "sender_hint" to listOf(if (web) item?.optString("author").orEmpty().ifBlank { item?.optString("from").orEmpty() } else item?.optString("from").orEmpty()),
                "recipient_hint" to listOf(item?.optString("to").orEmpty()),
                "event_envelope" to listOf(event.toString())) + if (web) mapOf("category" to listOf("msg")) else emptyMap())
    }

    fun thread(item: JSONObject, collection: String, transport: String): String =
        if (transport == "web") item.getString("thread_id")
        else if (collection == "messages") item.optString("from") else item.optString("to")
}
