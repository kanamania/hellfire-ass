package com.hellfire.ass.ai

import com.hellfire.ass.finance.ParsedFinance
import org.json.JSONObject
import org.json.JSONTokener

object CandidateContracts {
    fun objectValue(text: String, keys: Set<String>): JSONObject {
        require(text.length <= 16_384)
        val tokens = JSONTokener(text.trim())
        val value = tokens.nextValue() as? JSONObject ?: error("Expected a JSON object")
        require(tokens.nextClean() == '\u0000') { "Trailing model output is not allowed" }
        require(value.keys().asSequence().all { it in keys }) { "Unexpected output field" }
        require(integer(value, "schema_version") == 1L)
        return value
    }
    fun integer(value: JSONObject, key: String): Long {
        val raw = value.get(key)
        require(raw is Int || raw is Long) { "$key must be an integer" }
        return (raw as Number).toLong()
    }
    fun finance(text: String): ParsedFinance {
        val o = objectValue(text, setOf("schema_version", "kind", "currency", "provider", "reference", "amount_minor", "fee_minor", "merchant_number", "evidence"))
        require(o.getString("currency") == "TZS")
        val kind = o.getString("kind")
        require(kind in setOf("INCOME", "EXPENSE", "REFUND"))
        val amount = integer(o, "amount_minor").also { require(it > 0) }
        val fee = integer(o, "fee_minor").also { require(it >= 0); Math.addExact(amount, it) }
        val provider = o.getString("provider").also { require(it in setOf("MPESA", "AIRTEL", "MIXX", "HALOPESA", "AZAMPESA", "SELCOM", "CRDB", "NMB", "NBC")) }
        val reference = o.optString("reference").takeIf { it.isNotBlank() && it != "null" }
        require(reference == null || Regex("[A-Za-z0-9-]{5,40}").matches(reference))
        val evidence = o.getString("evidence").also { require(it.isNotBlank() && it.length <= 4000) }
        return ParsedFinance(provider, reference?.uppercase(), kind, amount, fee,
            o.optString("merchant_number").takeIf { it.matches(Regex("[0-9]{3,12}")) }, evidence)
    }
}
