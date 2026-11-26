package com.hellfire.ass.assistant

/** What the voice session says aloud and accepts as a spoken confirmation. Pure, so it can be tested without Android. */
object SpokenAnswer {
    /** Footers that explain the evidence model; useful on screen, noise when spoken. */
    private val footer = Regex("^(Evidence IDs:|Only confirmed payments|Balances reflect|Keyword search covers|Sources and inferences|Meaning matches|Import a text-embedding|Exact phrases are matched|Local model interpretation)", RegexOption.IGNORE_CASE)

    /** The answer without footers or source IDs, at most three lines and about 320 characters. */
    fun of(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !footer.containsMatchIn(it) }
        val short = lines.take(3).joinToString(". ").replace(Regex("\\.\\s*\\."), ".")
        return if (short.length <= 320) short else short.take(317).substringBeforeLast(' ') + "…"
    }

    private val yes = Regex("^(yes|yeah|yep|confirm|do it|ok(ay)?|sure|ndiyo|ndio|sawa|thibitisha|endelea)\\b", RegexOption.IGNORE_CASE)
    private val no = Regex("^(no|nope|cancel|stop|don't|hapana|acha|ghairi|sitaki)\\b", RegexOption.IGNORE_CASE)
    /** true to confirm, false to cancel, null when the reply is a new question. */
    fun confirmation(reply: String): Boolean? {
        val text = reply.trim().trimEnd('.', '!', '?')
        return when { yes.containsMatchIn(text) -> true; no.containsMatchIn(text) -> false; else -> null }
    }
}
