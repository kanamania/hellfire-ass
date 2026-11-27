package com.hellfire.ass.assistant

/**
 * A model answer may only cite the sources it was given. Bracketed ids outside that set are marked, and the answer
 * lists only the sources it actually cited, so the owner checks the evidence the answer rests on.
 */
object Citations {
    data class Checked(val text: String, val sources: List<String>, val invented: Int)

    private val bracket = Regex("\\[([A-Za-z0-9:_-]{6,80})]")

    fun check(answer: String, supplied: List<String>): Checked {
        val allowed = supplied.toSet()
        var invented = 0
        val cited = linkedSetOf<String>()
        val text = bracket.replace(answer) { match ->
            val id = match.groupValues[1]
            when {
                id in allowed -> { cited += id; match.value }
                else -> { invented++; "[not a supplied source]" }
            }
        }
        val note = if (invented > 0) "\nThe model cited $invented source(s) it was not given; those parts are not supported by your saved evidence." else ""
        return Checked(text + note, if (cited.isEmpty()) supplied else cited.toList(), invented)
    }
}
