package com.hellfire.ass.processing

/** Candidate labels only; these rules never post transactions or perform actions. */
object RuleClassifier {
    private val finance = Regex("(?i)\\b(tzs|tsh|mpesa|m-pesa|airtel money|mixx|halopesa|paid|payment|received|salio|umelipa|umepokea|muamala)\\b")
    private val delivery = Regex("(?i)\\b(parcel|courier|shipment|delivery|tracking|mzigo)\\b")
    private val promotion = Regex("(?i)\\b(promotion|discount|sale|offer|punguzo|ofa)\\b")
    fun classify(text: String, category: String?, source: String): String = when {
        finance.containsMatchIn(text) -> "FINANCE_CANDIDATE"
        delivery.containsMatchIn(text) -> "DELIVERY_CANDIDATE"
        category == "promo" || promotion.containsMatchIn(text) -> "PROMOTION_CANDIDATE"
        category in setOf("sys", "service", "err", "progress", "transport") -> "SYSTEM"
        category in setOf("msg", "email", "call", "social") || source == "android_sms" -> "MESSAGE"
        else -> "UNCLASSIFIED"
    }
}
