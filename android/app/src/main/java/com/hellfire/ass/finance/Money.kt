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
    private val amount = Regex("(?i)\\b(?:TZS|TShs?|Tsh)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val reference = Regex("(?i)\\b(?:ref(?:erence)?|kumbukumbu|muamala(?:\\s+no)?)[ :#.-]+([A-Z0-9-]{5,40})\\b")
    private val merchant = Regex("(?i)\\b(?:lipa\\s+namba|merchant(?:\\s+id)?)[ :#-]*([0-9]{3,12})\\b")
    fun parse(sender: String, text: String): ParsedFinance? {
        val lower = "$sender $text".lowercase(Locale.ROOT)
        val provider = providers.entries.firstOrNull { (_, aliases) -> aliases.any(lower::contains) }?.key ?: return null
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
            val total = Money.tzs(main.groupValues[1])
            val fee = feeMatch?.groupValues?.get(1)?.let { if (BigDecimal(it.replace(",", "")).signum() == 0) 0 else Money.tzs(it) } ?: 0
            ParsedFinance(provider, reference.find(text)?.groupValues?.get(1)?.uppercase(Locale.ROOT), kind,
                total, fee, merchant.find(text)?.groupValues?.get(1), text)
        }.getOrNull()
    }
}

object CashParser {
    private val words = mapOf("moja" to 1L, "mbili" to 2L, "tatu" to 3L, "nne" to 4L,
        "tano" to 5L, "sita" to 6L, "saba" to 7L, "nane" to 8L, "tisa" to 9L, "kumi" to 10L)
    fun parse(text: String): List<Pair<String, Long>> = text.split(Regex("(?i)\\s+na\\s+|;|\\n")).map { part ->
        val plain = part.trim().removePrefix("Nimelipa ").removePrefix("nimelipa ")
        val match = Regex("(?i)(?:elfu\\s+([a-z]+)|(?:TZS\\s*)?([0-9][0-9,]*(?:\\.00)?))\\s*$").find(plain)
            ?: error("Each cash item needs a description and a whole-shilling amount")
        val description = plain.substring(0, match.range.first).trim()
        require(description.isNotEmpty())
        val value = if (match.groupValues[1].isNotEmpty()) Math.multiplyExact(words[match.groupValues[1].lowercase()] ?: error("Unknown Swahili number"), 1000L)
            else Money.tzs(match.groupValues[2])
        description to value
    }.also { require(it.isNotEmpty() && it.size <= 20) }
}
