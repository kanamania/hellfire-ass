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
    /** Content-derived id used before v0.5 for every source; equal to [id] except for provider-keyed sources. */
    val legacyId: String = id,
)

object NotificationNormalizer {
    // Security context can be split across title, body, sender and action labels; any of these withholds all content.
    private val security = Regex(
        "(?i)\\b(otp|password|passcode|verification|authentication|authenticate|" +
            "one[ -]?time|security code|reset code|login code|log[ -]?in code|2fa|mfa|" +
            "nenosiri|nywila|msimbo|nambari ya siri)\\b"
    )
    // Everyday words ("code review", "Usitoe PIN yako") only lose the code value that follows them.
    private val keyedCode = Regex("(?i)\\b(?:code|pin|token|verify)\\b[\\s:#=-]{0,3}(?:(?:is|ni|yako ni|wako ni)\\s+)?([\\p{L}\\p{N}-]{4,40})")
    // Owner decision 2026-11-27: a purchased LUKU electricity token (20 digits, often in groups of four) is kept so the
    // owner can find it again. It only loads units on that meter; it is not a login or payment code.
    private val lukuContext = Regex("(?i)\\b(?:luku|tanesco|umeme|electricity|prepaid\\s+meter)\\b")
    private val lukuToken = Regex("(?<![\\p{L}\\p{N}])[0-9]{4}(?:[ -]?[0-9]{4}){4}(?![\\p{L}\\p{N}])")
    private val numericCode = Regex("(?<![\\p{L}\\p{N}])[\\p{Nd}]{4,8}(?![\\p{L}\\p{N}])")
    private val currencyAmount = Regex("(?i)\\b(?:TZS|TShs?|Tsh|Sh\\.?)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    // "5,000 TZS", "120000/=", "elfu 5000 shilingi"
    private val trailingCurrency = Regex("(?i)(?<![\\p{L}\\p{N}])([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:TZS|TShs?|Tsh|/=|shilingi)")
    // Amounts named by a finance word; kept only in messages that are clearly financial.
    private val keywordAmount = Regex("(?i)\\b(?:umelipa|umepokea|umetuma|umetoa|paid|received|sent|withdrawn|deposited|kiasi|amount|jumla|total|salio(?:\\s+jipya)?|balance|ada|fee|charge)\\b[\\s:]*(?:of|ya|cha|la|ni|is)?[\\s:]*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val financeContext = Regex("(?i)\\b(?:TZS|TShs?|Tsh|m-?pesa|airtel\\s+money|mixx|tigo\\s*pesa|halopesa|azam\\s*pesa|selcom|crdb|nmb|nbc|dtb|diamond\\s+trust|lipa\\s+namba|salio|muamala)\\b")
    private val financialIdentifier = Regex("(?i)\\b(?:lipa\\s+namba|till(?:\\s+(?:no|number))?|merchant(?:\\s+id)?|ref(?:erence)?|kumbukumbu|muamala(?:\\s+no)?|transaction\\s+id)[ :#.-]+([A-Z0-9-]{3,40})")
    // 13/11/2026, 13-11-26, 13.11.2026
    private val dayMonthYear = Regex("(?<![\\p{L}\\p{N}])[0-9]{1,2}[/.-][0-9]{1,2}[/.-](?:[0-9]{4}|[0-9]{2})(?![\\p{L}\\p{N}])")
    /** Sources whose provider supplies a durable identity (row ID, IMAP UID, relay message ID). */
    private val providerKeyed = setOf("android_sms", "email_imap", "whatsapp_web", "whatsapp_business")
    private val contentFields = setOf("title", "text", "big_text", "text_lines", "sub_text", "messages")

    fun isProviderKeyed(source: String) = source in providerKeyed

    fun normalize(raw: NotificationSnapshot, ownPackage: String): NormalizedNotification? {
        if (raw.packageName == ownPackage) return null
        val sensitive = raw.fields.values.flatten().any { security.containsMatchIn(it) }
        // A keyed code is removed everywhere it appears, including where it repeats as a reference.
        val luku = raw.fields.values.flatten().any { lukuContext.containsMatchIn(it) }
        val tokens = if (!luku) emptySet() else raw.fields.values.flatten().flatMap { value -> lukuToken.findAll(value).map { it.value.filter(Char::isDigit) } }.toSet()
        val keyed = raw.fields.values.flatten().flatMap { value -> keyedCode.findAll(value).map { it.groupValues[1] } }
            .filter { code -> code.any(Char::isDigit) && tokens.none { token -> token.contains(code.filter(Char::isDigit)) } }.toSet()
            .map { code -> Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(code) + "(?![\\p{L}\\p{N}])") }
        var redacted = false
        val financial = raw.fields.values.flatten().any { financeContext.containsMatchIn(it) }
        val fields = raw.fields.toSortedMap().mapValues { (_, values) ->
            values.map { value ->
                val clean = if (sensitive && value.isNotBlank()) "[SECURITY CONTENT REDACTED]"
                    else {
                        val dates = Regex("\\b20[0-9]{2}-[0-9]{2}-[0-9]{2}\\b").findAll(value).filter {
                            runCatching { java.time.LocalDate.parse(it.value) }.isSuccess
                        }.map { it.range }.toList() + dayMonthYear.findAll(value).map { it.range }.toList()
                        val marked = (currencyAmount.findAll(value) + trailingCurrency.findAll(value)).map { it.groups[1]!!.range }.toList()
                        val finance = financial || marked.isNotEmpty()
                        val kept = if (luku) lukuToken.findAll(value).map { it.range }.toList() else emptyList()
                        val amounts = dates + marked + kept + if (!finance) emptyList() else
                            (keywordAmount.findAll(value) + financialIdentifier.findAll(value)).map { it.groups[1]!!.range }.toList()
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
        val contentId = UUID.nameUUIDFromBytes(hash.toByteArray(Charsets.UTF_8)).toString()
        if (safe.source in providerKeyed) {
            // Identity comes from the provider key, not the redacted text, so a later change to the
            // redaction rules cannot turn an already-imported SMS or email into a new event.
            val identity = sha256("stable:${safe.source}:${safe.key}:${safe.postedAt}:${safe.kind}")
            return NormalizedNotification(UUID.nameUUIDFromBytes("stable:$identity".toByteArray(Charsets.UTF_8)).toString(),
                identity, hash, safe, available, redacted, sensitive, legacyId = contentId)
        }
        return NormalizedNotification(contentId, sha256("${safe.key}:${safe.postedAt}:${safe.kind}:$hash"),
            hash, safe, available, redacted, sensitive)
    }

    private fun StringBuilder.frame(value: String) { append(value.length).append(':').append(value) }
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
