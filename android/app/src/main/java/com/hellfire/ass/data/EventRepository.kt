package com.hellfire.ass.data

import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.security.PayloadCipher
import org.json.JSONArray
import org.json.JSONObject

class EventRepository(private val dao: EventDao, private val cipher: () -> PayloadCipher) {
    val count = dao.count()
    val recent = dao.recent()

    suspend fun capture(raw: NotificationSnapshot, ownPackage: String): Boolean {
        val event = NotificationNormalizer.normalize(raw, ownPackage) ?: return false
        if (dao.exists(event.id)) return false
        val safe = event.snapshot
        val payload = JSONObject().apply {
            put("notification_key_hash", safe.key)
            put("fields", JSONObject().apply {
                safe.fields.forEach { (name, values) -> put(name, JSONArray(values)) }
            })
            put("ongoing", safe.ongoing)
            put("removed_reason", safe.removedReason)
            put("rank", safe.rank)
            put("importance", safe.importance)
            put("is_group_conversation", safe.groupConversation)
            put("remote_input_count", safe.remoteInputCount)
            put("content_status", if (event.contentAvailable) "AVAILABLE_AS_DELIVERED" else "UNAVAILABLE")
        }.toString().toByteArray(Charsets.UTF_8)
        return dao.insertCaptured(SourceEvent(
            id = event.id, source = safe.source, externalId = event.externalId, packageName = safe.packageName,
            occurredAt = safe.postedAt, receivedAt = safe.receivedAt, kind = safe.kind,
            payloadHash = event.payloadHash, encryptedPayload = cipher().encrypt(payload),
            contentAvailable = event.contentAvailable, contentRedacted = event.contentRedacted,
            securitySensitive = event.securitySensitive,
        ))
    }
}
