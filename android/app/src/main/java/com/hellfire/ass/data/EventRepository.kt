package com.hellfire.ass.data

import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.security.PayloadCipher
import org.json.JSONArray
import org.json.JSONObject

class EventRepository(private val dao: EventDao, private val cipher: () -> PayloadCipher) {
    val count = dao.count()
    val recent = dao.recent()

    /** The stored event's id, and whether this call created it. Null when the input is our own notification. */
    data class Stored(val id: String, val created: Boolean)

    suspend fun capture(raw: NotificationSnapshot, ownPackage: String): Boolean = store(raw, ownPackage)?.created == true

    suspend fun store(raw: NotificationSnapshot, ownPackage: String): Stored? {
        val event = NotificationNormalizer.normalize(raw, ownPackage) ?: return null
        if (dao.exists(event.id)) return Stored(event.id, false)
        // Provider-keyed events captured before v0.5 were identified by content; keep using those rows.
        if (event.legacyId != event.id && dao.exists(event.legacyId)) return Stored(event.legacyId, false)
        val safe = event.snapshot
        val payload = JSONObject().apply {
            put("notification_key_hash", safe.key)
            put("provenance", JSONObject(safe.provenance))
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
        val searchable = if (!event.contentAvailable || event.securitySensitive) null
            else SearchText.of(safe.fields)
        val created = dao.insertCaptured(SourceEvent(
            id = event.id, ingestVersion = if (event.legacyId != event.id) 3 else 2, source = safe.source, externalId = event.externalId, packageName = safe.packageName,
            occurredAt = safe.postedAt, receivedAt = safe.receivedAt, kind = safe.kind,
            payloadHash = event.payloadHash, encryptedPayload = cipher().encrypt(payload),
            contentAvailable = event.contentAvailable, contentRedacted = event.contentRedacted,
            securitySensitive = event.securitySensitive,
        ), searchable)
        return Stored(event.id, created)
    }
}

/** Plain text kept in the full-text index (inside the encrypted database) for long-term literal search. */
object SearchText {
    private val indexed = listOf("title", "text", "big_text", "text_lines", "sub_text", "messages", "sender_hint", "recipient", "recipient_hint")
    fun of(fields: Map<String, List<String>>): String? = indexed.flatMap { fields[it].orEmpty() }
        .filter(String::isNotBlank).joinToString("\n").take(20_000).ifBlank { null }
    fun of(fields: JSONObject): String? = of(fields.keys().asSequence().associateWith { key ->
        fields.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty() })

    private val questionWords = setOf("what", "when", "where", "how", "why", "which", "who", "did", "do", "does", "is", "are", "was", "were", "the", "a", "an", "i", "we", "me", "my", "our", "your", "you", "have", "has", "tell", "show", "can", "could", "please", "know", "about", "search", "find", "remember", "saved", "to", "of", "at", "and", "or", "nini", "nani", "lini", "wapi", "kwa", "na", "je", "yangu", "kuhusu", "tafuta")
    fun keywords(text: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value.lowercase(java.util.Locale.ROOT) }
        .filter { it.length >= 2 && it !in questionWords }.distinct().take(8).toList()
    fun anyQuery(words: List<String>): String? = words.flatMap { keywords(it) }.distinct().take(8).joinToString(" OR ") { "$it*" }.ifBlank { null }

    /** Prefix match on up to eight lower-case words (FTS operators are upper-case); other characters never reach FTS syntax. */
    fun query(text: String): String? = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value.lowercase(java.util.Locale.ROOT) }.filter { it.length >= 2 }
        .take(8).joinToString(" ") { "$it*" }.ifBlank { null }
}
