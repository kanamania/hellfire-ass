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
        "MPESA" to listOf("m-pesa", "mpesa"), "AIRTEL" to listOf("airtel"),
        "MIXX" to listOf("mixx", "tigo pesa", "tigopesa"), "HALOPESA" to listOf("halopesa"),
        "CRDB" to listOf("crdb"), "NMB" to listOf("nmb"), "NBC" to listOf("nbc"),
    )
    // "TZS 5,000" or "5,000 TZS" / "5000/=".
    private val amount = Regex("(?i)\\b(?:TZS|TShs?|Tsh)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|(?<![\\p{L}\\p{N}])([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:TZS|TShs?|Tsh|/=)")
    private val reference = Regex("(?i)\\b(?:ref(?:erence)?|kumbukumbu|muamala(?:\\s+no)?)[ :#.-]+([A-Z0-9-]{5,40})\\b")
    // M-Pesa style "QK12ABC3D4 Confirmed." where the transaction ID leads the message without a keyword.
    private val leadingId = Regex("^\\s*([A-Z0-9]{8,12})\\.?\\s+(?i:confirmed|imethibitishwa|imekamilika)")
    private fun reference(text: String): String? = reference.find(text)?.groupValues?.get(1)
        ?: leadingId.find(text)?.groupValues?.get(1)?.takeIf { id -> id.any(Char::isLetter) && id.any(Char::isDigit) }
    private val merchant = Regex("(?i)\\b(?:lipa\\s+namba|merchant(?:\\s+id)?)[ :#-]*([0-9]{3,12})\\b")
    /** Provider named in the sender or text, or null. */
    fun provider(sender: String, text: String): String? {
        val lower = "$sender $text".lowercase(Locale.ROOT)
        return providers.entries.firstOrNull { (_, aliases) -> aliases.any(lower::contains) }?.key
    }
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
        if (Regex("(?i)\\b(failed|unsuccessful|declined|imeshindwa|haijafanikiwa|pending)\\b").containsMatchIn(text)) return null
        val kind = when {
            Regex("(?i)\\b(reversal|reversed|imebatilishwa|refund|returned|umerejeshewa)\\b").containsMatchIn(text) -> "REFUND"
            Regex("(?i)\\b(received|credited|umepokea|imeingia)\\b").containsMatchIn(text) -> "INCOME"
            Regex("(?i)\\b(paid|debited|sent|umelipa|umetuma|imelipwa)\\b").containsMatchIn(text) -> "EXPENSE"
            else -> return null
        }
        val feeMatch = Regex("(?i)\\b(?:fee|charge|ada)(?:\\s+ya\\s+muamala)?[ :]*?(?:TZS|TShs?)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)").find(text)
        val main = amount.findAll(text).firstOrNull { hit -> feeMatch == null || hit.range.first !in feeMatch.range } ?: return null
        return runCatching {
            val total = Money.tzs(main.groups[1]?.value ?: main.groups[2]!!.value)
            val fee = feeMatch?.groupValues?.get(1)?.let { if (BigDecimal(it.replace(",", "")).signum() == 0) 0 else Money.tzs(it) } ?: 0
            ParsedFinance(provider, reference(text)?.uppercase(Locale.ROOT), kind,
                total, fee, merchant.find(text)?.groupValues?.get(1), text)
        }.getOrNull()
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
