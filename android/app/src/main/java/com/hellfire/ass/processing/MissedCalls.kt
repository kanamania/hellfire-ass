package com.hellfire.ass.processing

/** A dialer's missed-call notification, read from what the notification shows (no call-log permission). */
object MissedCalls {
    private val phrase = Regex("(?i)\\b(missed calls?|simu (?:uliyokosa|ulizokosa|ambayo hukupokea|zilizokosa))\\b")

    /** The caller's name or number, or null when this is not a missed-call notification. */
    fun caller(category: String?, title: String, text: String): String? {
        val missedTitle = phrase.containsMatchIn(title)
        if (category != "missed_call" && !missedTitle && !phrase.containsMatchIn(text)) return null
        // Dialers put the caller in the title ("Asha") with "Missed call" as text, or the other way round.
        val caller = (if (missedTitle) text else title).replace(phrase, "").trim().trim(':', '·', '-', ' ')
        return caller.takeIf { it.isNotEmpty() && it.length <= 80 }
    }
}
