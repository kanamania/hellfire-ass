package com.hellfire.ass.tasks

import com.hellfire.ass.insights.DebtBalance
import com.hellfire.ass.insights.Handles
import com.hellfire.ass.insights.Interaction
import java.util.Locale

/**
 * "Remind me Friday if Juma hasn't paid" / "nikumbushe ijumaa kama Juma hajalipa". The reminder fires as usual unless,
 * by then, HellFire can see the condition is met: the owner's debt record shows Juma settled, or Juma has written since.
 * When it cannot tell (no such debt or person saved), it reminds anyway.
 */
object TaskConditions {
    private val english = Regex("(?i)\\s*,?\\s*\\b(?:if|unless)\\s+(\\p{L}{2,})\\s+(?:still\\s+)?(?:hasn't|has not|hasnt|haven't|didn't|did not|doesn't|does not)\\s+" +
        "(?:yet\\s+)?(paid|pay|replied|reply|responded|respond|answered|answer|written|written back|called back)\\b(?:\\s+(?:me|back|yet))*")
    private val swahili = Regex("(?i)\\s*,?\\s*\\bkama\\s+(\\p{L}{2,})\\s+(?:bado\\s+)?(hajalipa|hajanilipa|hajarudisha|hajajibu|hajanijibu|hajaniandikia)\\b")

    data class Parsed(val rest: String, val condition: String?)

    fun parse(text: String): Parsed {
        english.find(text)?.let { m ->
            val kind = if (m.groupValues[2].startsWith("pa")) "DEBT_UNPAID" else "NO_REPLY"
            return Parsed(text.removeRange(m.range).trim(), "$kind:${m.groupValues[1].lowercase(Locale.ROOT)}")
        }
        swahili.find(text)?.let { m ->
            val kind = if (m.groupValues[2].contains("lipa") || m.groupValues[2] == "hajarudisha") "DEBT_UNPAID" else "NO_REPLY"
            return Parsed(text.removeRange(m.range).trim(), "$kind:${m.groupValues[1].lowercase(Locale.ROOT)}")
        }
        return Parsed(text, null)
    }

    fun describe(condition: String): String {
        val (kind, name) = condition.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" }.replaceFirstChar(Char::titlecase) }
        return if (kind == "DEBT_UNPAID") "only if $name still hasn't paid you back" else "only if $name still hasn't replied"
    }

    private fun words(text: String) = Regex("\\p{L}+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toSet()

    /** True when the reminder is no longer needed. [since] is when the owner set it. */
    fun met(condition: String, debts: List<DebtBalance>, interactions: List<Interaction>, aliases: Map<String, String>, since: Long): Boolean {
        val kind = condition.substringBefore(':')
        val name = condition.substringAfter(':')
        return when (kind) {
            "DEBT_UNPAID" -> debts.filter { name in words(it.person) }.let { theirs -> theirs.isNotEmpty() && theirs.all { it.balance <= 0 } }
            "NO_REPLY" -> interactions.any { it.incoming && it.at >= since &&
                (name in words(aliases[it.handle].orEmpty()) || name in words(Handles.display(it.raw))) }
            else -> false
        }
    }
}
