package com.hellfire.ass.assistant

import com.hellfire.ass.finance.FinanceAccount
import java.time.Instant
import java.util.Locale

/** A validated read-only query. Unknown filters are clarified rather than silently dropped. */
data class FinanceQuestion(val start: Long, val end: Long, val income: Boolean, val category: String? = null,
    val accountId: String? = null, val accountLabel: String? = null, val merchant: String? = null, val personKey: String? = null, val personLabel: String? = null) {
    val scope: String get() = listOfNotNull(category, accountLabel, merchant?.let { "Merchant: $it" }, personLabel?.let { "Person: $it" }).joinToString(" · ").ifEmpty { "All accounts and categories" }

    companion object {
        fun isQuestion(text: String) = Regex("\\b(spend|spent|spending|expenses|income|earned|earn|matumizi|mapato)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        fun followUp(text: String) = Regex("^(and|what about|how about|na|je)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())

        fun parse(text: String, now: Instant, accounts: List<FinanceAccount>, categories: List<String>, previous: FinanceQuestion? = null, people: List<com.hellfire.ass.contacts.PersonContact> = emptyList()): FinanceQuestion {
            var rest = text.trim().lowercase(Locale.ROOT).trimEnd('?', '.')
            val follow = followUp(rest)
            require(!follow || previous != null) { "Ask a spending or income question first, then change its period or filters." }
            val periods = Regex("\\b(?:today|yesterday|tomorrow|leo|jana|kesho|(?:(?:this|last|next) )?(?:week|month|year)|(?:mwezi|wiki|mwaka)(?: (?:huu|uliopita|iliyopita|ujao|ijayo))?|(?:last|past) [0-9]+ days)\\b").findAll(rest).map { it.value }.toSet()
            val explicitDate = Regex("\\b20[0-9]{2}-[0-9]{2}-[0-9]{2}\\b").containsMatchIn(rest)
            require(periods.size <= 1 && (!explicitDate || periods.isEmpty())) { "Choose one period, or an explicit start and end date. Ask the other period as a follow-up." }
            val range = if (follow && periods.isEmpty() && !explicitDate) previous!!.start to previous.end else QueryDates.range(rest, now)
            val income = Regex("\\b(income|earned|earn|mapato)\\b").containsMatchIn(rest)
            var result = if (follow) previous!!.copy(start = range.first, end = range.second, income = if (isQuestion(rest)) income else previous.income) else
                FinanceQuestion(range.first, range.second, income)
            val merchantMatch = Regex("\\b(?:at merchant|merchant|kwa mfanyabiashara)\\s+[\"']([^\"']+)[\"']").find(rest)
            if (merchantMatch != null) { result = result.copy(merchant = merchantMatch.groupValues[1]); rest = rest.replace(merchantMatch.value, " ") }
            val personMatch = Regex("\\b(?:for person|person|kwa mtu|mtu)\\s+[\"']([^\"']+)[\"']").find(rest)
            if (personMatch != null) {
                val matches = people.filter { it.displayName.equals(personMatch.groupValues[1], true) }
                require(matches.size == 1) { if (matches.isEmpty()) "No saved person has that name. Link the person in People first." else "Several people have this name. Give them distinct display names in People before asking about one." }
                result = result.copy(personKey = matches.single().personKey, personLabel = matches.single().displayName)
                rest = rest.replace(personMatch.value, " ")
            }
            val foundAccounts = accounts.filter { a ->
                a.label.isNotBlank() && Regex("(?<![\\p{L}\\p{N}])" + a.label.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank).joinToString("[\\s-]*", transform = Regex::escape) + "(?![\\p{L}\\p{N}])").containsMatchIn(rest)
            }
            require(foundAccounts.size <= 1) { "Choose one account, or ask for all accounts." }
            foundAccounts.singleOrNull()?.let { a ->
                result = result.copy(accountId = a.id, accountLabel = a.label)
                val pattern = a.label.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank).joinToString("[\\s-]*", transform = Regex::escape)
                rest = rest.replace(Regex("(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])"), " ")
            }
            val aliases = mapOf("chakula" to "Food", "usafiri" to "Transport", "mafuta" to "Fuel", "kodi" to "Rent", "ada" to "Fees")
            val choices = (categories.associateBy { it.lowercase(Locale.ROOT) } + aliases).toList().sortedByDescending { it.first.length }
            val foundCategories = mutableSetOf<String>()
            for ((word, category) in choices) {
                if (word in setOf("income", "expenses", "spending", "matumizi", "mapato")) continue
                val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])")
                if (pattern.containsMatchIn(rest)) { foundCategories += category; rest = rest.replace(pattern, " ") }
            }
            require(foundCategories.size <= 1) { "Choose one category, or ask for all categories." }
            foundCategories.singleOrNull()?.let { result = result.copy(category = it) }
            if (Regex("\\ball (accounts|wallets)\\b").containsMatchIn(rest)) result = result.copy(accountId = null, accountLabel = null)
            if (Regex("\\ball categories\\b").containsMatchIn(rest)) result = result.copy(category = null)
            rest = rest.replace(Regex("\\b20[0-9]{2}-[0-9]{2}-[0-9]{2}\\b"), " ")
                .replace(Regex("\\b(?:last|past) [0-9]+ days\\b"), " ")
                .replace(Regex("\\b(?:how|much|did|do|have|has|i|we|my|our|me|the|a|an|on|in|from|for|of|to|and|what|about|all|accounts|account|wallet|wallets|categories|category|spend|spent|spending|expenses|income|earned|earn|today|yesterday|tomorrow|this|last|next|month|week|year|total|please|na|je|matumizi|mapato|leo|jana|kesho|mwezi|wiki|mwaka|uliopita|iliyopita|ujao|ijayo|huu|kwa|kiasi|gani|nime|nini)\\b"), " ")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            require(rest.isBlank()) { "I could not match \"$rest\" to an account, category or supported period. Try \"spending on Fuel this month\", an account name, or merchant \"name\"." }
            return result
        }
    }
}
