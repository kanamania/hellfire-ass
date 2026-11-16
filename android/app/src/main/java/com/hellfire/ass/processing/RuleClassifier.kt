package com.hellfire.ass.processing

/** Candidate labels only; these rules never post transactions or perform actions. */
object RuleClassifier {
    // A money provider or currency mark is enough; a bare "paid"/"received" needs an amount with it.
    private val provider = Regex("(?i)\\b(tzs|tsh|tshs|mpesa|m-pesa|airtel money|mixx|tigo ?pesa|halopesa|azam ?pesa|selcom|crdb|nmb|nbc|lipa namba|salio|muamala)\\b")
    private val financeWord = Regex("(?i)\\b(paid|payment|received|umelipa|umepokea|umetuma|imeingia|debited|credited)\\b")
    private val amount = Regex("[0-9][0-9,]{2,}")
    private val delivery = Regex("(?i)\\b(parcel|courier|shipment|delivery|tracking|mzigo)\\b")
    private val promotion = Regex("(?i)\\b(promotion|discount|sale|offer|punguzo|ofa)\\b")
    fun classify(text: String, category: String?, source: String): String = when {
        provider.containsMatchIn(text) || (financeWord.containsMatchIn(text) && amount.containsMatchIn(text)) -> "FINANCE_CANDIDATE"
        delivery.containsMatchIn(text) -> "DELIVERY_CANDIDATE"
        category == "promo" || promotion.containsMatchIn(text) -> "PROMOTION_CANDIDATE"
        category in setOf("sys", "service", "err", "progress", "transport") -> "SYSTEM"
        category in setOf("msg", "email", "call", "social") || source == "android_sms" -> "MESSAGE"
        else -> "UNCLASSIFIED"
    }
}
