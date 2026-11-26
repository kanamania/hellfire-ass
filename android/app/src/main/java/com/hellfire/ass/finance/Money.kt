package com.hellfire.ass.finance

import java.math.BigDecimal
import java.util.Locale

object Money {
    fun tzs(text: String): Long {
        val value = text.trim().replace(Regex("(?i)^(TZS|TSH|TShs)[ .]*"), "").trim()
        require(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.0{1,2})?").matches(value)) { "Use whole TZS, for example 15000 or 15,000.00" }
        return BigDecimal(value.replace(",", "")).longValueExact().also { require(it > 0) }
    }
    fun display(amount: Long): String = "TZS " + String.format(Locale.ENGLISH, "%,d", amount)
    fun delta(kind: String, amount: Long): Long {
        require(amount > 0)
        return when (kind) {
            "INCOME", "REFUND" -> amount
            "EXPENSE", "FEE" -> Math.negateExact(amount)
            else -> error("Choose an explicit supported transaction type")
        }
    }
}

data class ParsedFinance(val provider: String, val reference: String?, val kind: String,
    val amount: Long, val fee: Long, val merchant: String?, val evidence: String)

/** Conservative candidate recognizers. Real provider-format accuracy needs a reviewed corpus. */
object FinanceParser {
    private val providers = linkedMapOf(
        "MPESA" to listOf("m-pesa", "mpesa"), "AIRTEL" to listOf("airtel money", "airtelmoney", "airtel"),
        "MIXX" to listOf("mixx by yas", "mixx", "tigo pesa", "tigopesa"), "HALOPESA" to listOf("halopesa", "halo pesa"),
        "AZAMPESA" to listOf("azampesa", "azam pesa"), "SELCOM" to listOf("selcom pesa", "selcom"),
        "CRDB" to listOf("crdb bank", "crdb"), "NMB" to listOf("nmb bank", "nmb"), "NBC" to listOf("nbc bank", "nbc"),
    )
    /** Longest alias first, so "azampesa" is never read as "mpesa". */
    private val aliases = providers.flatMap { (code, names) -> names.map { it to code } }.sortedByDescending { it.first.length }
    private fun named(text: String): String? =
        aliases.firstOrNull { (alias, _) -> Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(alias) + "(?![\\p{L}\\p{N}])").containsMatchIn(text) }?.second
    // "TZS 5,000" or "5,000 TZS" / "5000/=".
    // Thousands groups are exact, so the comma in "TZS 300, tozo" is punctuation, not part of the number.
    private const val NUMBER = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?(?![0-9])"
    private val amount = Regex("(?i)\\b(?:TZS|TShs?|Tsh)\\s*($NUMBER)|(?<![\\p{L}\\p{N}])($NUMBER)\\s*(?:TZS|TShs?|Tsh|/=)")
    private val reference = Regex("(?i)\\b(?:ref(?:erence)?|kumbukumbu|muamala(?:\\s+no)?)[ :#.-]+([A-Z0-9-]{5,40})\\b")
    // M-Pesa style "QK12ABC3D4 Confirmed." where the transaction ID leads the message without a keyword.
    private val leadingId = Regex("^\\s*([A-Z0-9]{8,12})\\.?\\s+(?i:confirmed|imethibitishwa|imekamilika)")
    private fun reference(text: String): String? = reference.find(text)?.groupValues?.get(1)
        ?: leadingId.find(text)?.groupValues?.get(1)?.takeIf { id -> id.any(Char::isLetter) && id.any(Char::isDigit) }
    private val merchant = Regex("(?i)\\b(?:lipa\\s+namba|merchant(?:\\s+id)?)[ :#-]*([0-9]{3,12})\\b")
    /**
     * Provider for a message. A known provider sender ID (the SMS address, e.g. "M-PESA", "AzamPesa") decides first;
     * the message text only decides when the sender names no provider, e.g. a notification or an email.
     */
    fun provider(sender: String, text: String): String? =
        named(sender.lowercase(Locale.ROOT)) ?: named(text.lowercase(Locale.ROOT))
    private val balanceRegex = Regex("(?i)\\b(?:salio(?:\\s+jipya)?(?:\\s+(?:ni|la|lako))?|new\\s+balance(?:\\s+is)?|available\\s+balance(?:\\s+is)?|balance(?:\\s+is)?|bal)[\\s:.]*(?:TZS|TShs?|Tsh)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    /** Balance stated in a provider message, in whole TZS, or null. Display only. */
    fun balance(sender: String, text: String): Pair<String, Long>? {
        val provider = provider(sender, text) ?: return null
        val value = balanceRegex.find(text)?.groupValues?.get(1) ?: return null
        val amount = runCatching { java.math.BigDecimal(value.replace(",", "")).setScale(0, java.math.RoundingMode.DOWN).longValueExact() }.getOrNull() ?: return null
        return if (amount >= 0) provider to amount else null
    }
    fun parse(sender: String, text: String): ParsedFinance? {
        val provider = provider(sender, text) ?: return null
        if (Regex("(?i)\\b(failed|unsuccessful|declined|imeshindwa|umeshindwa|haijafanikiwa|haikufanikiwa|haijakamilika|haujakamilika|inasubiri|pending)\\b").containsMatchIn(text)) return null
        val kind = kind(text) ?: return null
        val amounts = amount.findAll(text).toList()
        val firstAmount = amounts.firstOrNull()?.range?.first ?: return null
        // Plain "ada" is a fee only after the main amount: "Umelipa ada TZS 500,000 kwa SHULE" is a school-fee payment.
        val fees = feeRegex.findAll(text).filter { m -> !m.groupValues[1].equals("ada", ignoreCase = true) || m.range.first > firstAmount }.toList()
        val main = amounts.firstOrNull { hit -> fees.none { hit.range.first in it.range } } ?: return null
        return runCatching {
            val total = Money.tzs(main.groups[1]?.value ?: main.groups[2]!!.value)
            val fee = fees.fold(0L) { sum, m ->
                val value = m.groupValues[2]
                Math.addExact(sum, if (BigDecimal(value.replace(",", "")).signum() == 0) 0 else Money.tzs(value))
            }
            ParsedFinance(provider, reference(text)?.uppercase(Locale.ROOT), kind,
                total, fee, merchant.find(text)?.groupValues?.get(1), text)
        }.getOrNull()
    }

    private val feeRegex = Regex("(?i)\\b((?:total\\s+)?transaction\\s+(?:cost|fee|charge)s?|fees?|charges?|makato|gharama(?:\\s+za\\s+muamala)?|tozo|levy|ada\\s+ya\\s+muamala|ada)" +
        "[\\s:,.-]*?(?:(?:ni|of|is)\\s+)?(?:TZS|TShs?|Tsh)?\\s*($NUMBER)")
    private val refund = Regex("(?i)\\b(reversal|reversed|imebatilishwa|refund|returned|umerejeshewa)\\b")
    private val incoming = Regex("(?i)\\b(received|umepokea|imeingia|umeweka|deposited|amekutumia|amekuwekea|sent\\s+you)\\b|\\bcredited\\b(?!\\s+to\\s+(?!your|ur|you|account|a/c|akaunti))")
    private val outgoing = Regex("(?i)\\b(paid|debited|sent(?!\\s+you\\b)|umelipa|umetuma|imelipwa|umetoa|withdrawn|withdrew|umenunua|bought|purchased)\\b|\\bcredited\\s+to\\s+(?!your|ur|you|account|a/c|akaunti)")

    /**
     * Direction from the owner's side. The earliest direction verb wins, so "debited TZS 50,000 and credited to JOHN"
     * is an expense. A reversed incoming payment ("the TZS 5,000 you received ... has been reversed") is money leaving.
     */
    private fun kind(text: String): String? {
        val inAt = incoming.find(text)?.range?.first
        val outAt = outgoing.find(text)?.range?.first
        if (refund.containsMatchIn(text)) return if (inAt != null && (outAt == null || inAt < outAt)) "EXPENSE" else "REFUND"
        return when {
            inAt == null && outAt == null -> null
            outAt == null -> "INCOME"
            inAt == null -> "EXPENSE"
            else -> if (outAt < inAt) "EXPENSE" else "INCOME"
        }
    }
}

/** Swahili amounts as spoken: "elfu tano", "elfu kumi na tano", "laki mbili", "elfu mbili mia tano", "milioni moja". */
object SwahiliAmount {
    private val units = mapOf("moja" to 1L, "mbili" to 2L, "tatu" to 3L, "nne" to 4L, "tano" to 5L, "sita" to 6L, "saba" to 7L, "nane" to 8L, "tisa" to 9L)
    private val tens = mapOf("kumi" to 10L, "ishirini" to 20L, "thelathini" to 30L, "arobaini" to 40L, "hamsini" to 50L, "sitini" to 60L,
        "sabini" to 70L, "themanini" to 80L, "tisini" to 90L)
    private val multipliers = mapOf("mia" to 100L, "elfu" to 1_000L, "laki" to 100_000L, "milioni" to 1_000_000L)
    fun isWord(token: String) = token in units || token in tens || token in multipliers

    /**
     * Each multiplier is followed by a number below 100; the groups add up. A unit after a ten joins it under the same
     * multiplier, so "elfu kumi na tano" is 15,000, as people say it. [tokens] are lower-case words without "na".
     */
    fun parse(tokens: List<String>): Long? {
        var total = 0L
        var index = 0
        if (tokens.isEmpty()) return null
        while (index < tokens.size) {
            val multiplier = multipliers[tokens[index++]] ?: return null
            var count = 0L
            tens[tokens.getOrNull(index)]?.let { count += it; index++ }
            units[tokens.getOrNull(index)]?.let { count += it; index++ }
            if (count == 0L) return null
            total = Math.addExact(total, Math.multiplyExact(multiplier, count))
        }
        return total
    }
}

object CashParser {
    private val digits = Regex("(?i)(?:^|\\s)(?:TZS\\s*)?([0-9][0-9,]*(?:\\.00)?)$")
    private val missing = "Each cash item needs a description and a whole-shilling amount"

    /** "bodaboda 3000 na chakula elfu saba; soda elfu mbili mia tano" → one description and amount per item. */
    fun parse(text: String): List<Pair<String, Long>> {
        // "na" separates items, but also joins the words of one amount ("elfu kumi na tano"): a part made only of number words continues the item before it.
        val parts = mutableListOf<String>()
        for (part in text.split(Regex("(?i)\\s+na\\s+|;|\\n")).map { it.trim().trimEnd('.', ',', '!', '?').trim() }.filter(String::isNotEmpty)) {
            val numeric = part.lowercase().split(Regex("\\s+")).all(SwahiliAmount::isWord)
            if (numeric && parts.isNotEmpty()) parts[parts.lastIndex] = parts.last() + " " + part else parts += part
        }
        return parts.map { part ->
            val plain = part.replace(Regex("(?i)^nimelipa\\s+"), "")
            val match = digits.find(plain)
            val (description, value) = if (match != null) plain.substring(0, match.range.first).trim() to Money.tzs(match.groupValues[1])
            else {
                val tokens = plain.split(Regex("\\s+"))
                // The longest run of number words at the end that reads as an amount; earlier number words belong to the description ("soda mbili elfu tatu").
                val start = tokens.indices.firstOrNull { from -> SwahiliAmount.parse(tokens.drop(from).map(String::lowercase)) != null } ?: error(missing)
                tokens.take(start).joinToString(" ") to SwahiliAmount.parse(tokens.drop(start).map(String::lowercase))!!
            }
            require(description.isNotEmpty() && value > 0) { missing }
            description to value
        }.also { require(it.isNotEmpty() && it.size <= 20) }
    }

    /** Income as spoken: "mshahara 500000", or amount first: "50,000 kutoka kwa Juma" / "50000 from Juma". */
    fun parseIncome(text: String): List<Pair<String, Long>> {
        val amountFirst = Regex("(?i)^(?:TZS\\s*)?([0-9][0-9,]*(?:\\.00)?)\\s+(.+)$").find(text.trim().trimEnd('.', '!', '?'))
        return if (amountFirst != null) listOf(amountFirst.groupValues[2].trim().replaceFirstChar { it.uppercase() } to Money.tzs(amountFirst.groupValues[1])) else parse(text)
    }
}
