package com.hellfire.ass.events

import java.security.MessageDigest
import java.util.UUID

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val postedAt: Long,
    val receivedAt: Long,
    val kind: String = "POSTED",
    val source: String = "android_notification",
    val fields: Map<String, List<String>> = emptyMap(),
    val ongoing: Boolean = false,
    val removedReason: Int? = null,
    val rank: Int? = null,
    val importance: Int? = null,
    val groupConversation: Boolean = false,
    val remoteInputCount: Int = 0,
    val provenance: Map<String, String> = emptyMap(),
)

data class NormalizedNotification(
    val id: String,
    val externalId: String,
    val payloadHash: String,
    val snapshot: NotificationSnapshot,
    val contentAvailable: Boolean,
    val contentRedacted: Boolean,
    val securitySensitive: Boolean,
)

object NotificationNormalizer {
    // Security context can be split across title, body, sender and action labels; any of these withholds all content.
    private val security = Regex(
        "(?i)\\b(otp|password|passcode|verification|authentication|authenticate|" +
            "one[ -]?time|security code|reset code|login code|log[ -]?in code|2fa|mfa|" +
            "nenosiri|nywila|msimbo|nambari ya siri)\\b"
    )
    // Everyday words ("code review", "Usitoe PIN yako", "LUKU token") only lose the code value that follows them.
    private val keyedCode = Regex("(?i)\\b(?:code|pin|token|verify)\\b[\\s:#=-]{0,3}(?:(?:is|ni|yako ni|wako ni)\\s+)?([\\p{L}\\p{N}-]{4,40})")
    private val numericCode = Regex("(?<![\\p{L}\\p{N}])[\\p{Nd}]{4,8}(?![\\p{L}\\p{N}])")
    private val currencyAmount = Regex("(?i)\\b(?:TZS|TShs?)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val financialIdentifier = Regex("(?i)\\b(?:lipa\\s+namba|merchant(?:\\s+id)?|ref(?:erence)?|kumbukumbu|muamala(?:\\s+no)?)[ :#.-]+([A-Z0-9-]{3,40})")
    private val contentFields = setOf("title", "text", "big_text", "text_lines", "sub_text", "messages")

    fun normalize(raw: NotificationSnapshot, ownPackage: String): NormalizedNotification? {
        if (raw.packageName == ownPackage) return null
        val sensitive = raw.fields.values.flatten().any { security.containsMatchIn(it) }
        // A keyed code is removed everywhere it appears, including where it repeats as a reference.
        val keyed = raw.fields.values.flatten().flatMap { value -> keyedCode.findAll(value).map { it.groupValues[1] } }
            .filter { code -> code.any(Char::isDigit) }.toSet()
            .map { code -> Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(code) + "(?![\\p{L}\\p{N}])") }
        var redacted = false
        val fields = raw.fields.toSortedMap().mapValues { (_, values) ->
            values.map { value ->
                val clean = if (sensitive && value.isNotBlank()) "[SECURITY CONTENT REDACTED]"
                    else {
                        val dates = Regex("\\b20[0-9]{2}-[0-9]{2}-[0-9]{2}\\b").findAll(value).filter {
                            runCatching { java.time.LocalDate.parse(it.value) }.isSuccess
                        }.map { it.range }.toList()
                        val amounts = dates + currencyAmount.findAll(value).map { it.groups[1]!!.range }.toList().let { money ->
                            if (money.isEmpty()) money else money + financialIdentifier.findAll(value).map { it.groups[1]!!.range }.toList()
                        }
                        val bare = numericCode.replace(value) { match ->
                            if (amounts.any { match.range.first >= it.first && match.range.last <= it.last }) match.value else "[CODE REDACTED]"
                        }
                        keyed.fold(bare) { text, code -> code.replace(text, "[CODE REDACTED]") }
                    }
                if (clean != value) redacted = true
                clean
            }
        }
        val available = raw.fields.any { (name, values) -> name in contentFields && values.any(String::isNotBlank) }
        val safe = raw.copy(key = sha256(raw.key), fields = fields)
        // Length framing prevents ambiguous concatenations; receipt time is deliberately excluded.
        val canonical = buildString {
            listOf(safe.source, safe.packageName, safe.key, safe.postedAt.toString(), safe.kind,
                safe.ongoing.toString(), safe.removedReason.toString(), safe.rank.toString(),
                safe.importance.toString(), safe.groupConversation.toString(),
                safe.remoteInputCount.toString()).forEach { frame(it) }
            safe.provenance.toSortedMap().forEach { (name, value) -> frame(name); frame(value) }
            fields.forEach { (name, values) ->
                frame(name); frame(values.size.toString()); values.forEach { frame(it) }
            }
        }
        val hash = sha256(canonical)
        return NormalizedNotification(
            UUID.nameUUIDFromBytes(hash.toByteArray(Charsets.UTF_8)).toString(),
            sha256("${safe.key}:${safe.postedAt}:${safe.kind}:$hash"),
            hash, safe, available, redacted, sensitive,
        )
    }

    private fun StringBuilder.frame(value: String) { append(value.length).append(':').append(value) }
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
