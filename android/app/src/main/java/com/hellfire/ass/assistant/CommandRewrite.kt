package com.hellfire.ass.assistant

import java.time.Instant
import java.util.Locale

/**
 * Audit HF-050: when the fixed rules do not recognise an instruction, the on-device model may rewrite the owner's own
 * words into one of the existing command forms. Its output is only text: it goes back through [AssistantCommands.parse]
 * and the usual confirmation, so the model can never trigger anything the rules would not. Never used on captured text.
 */
object CommandRewrite {
    private val instruction = Regex("^(please |tafadhali )?(remind|set|add|create|schedule|put|note|save|log|record|track|keep|" +
        "nikumbushe|niwekee|weka|ongeza|andika|hifadhi|rekodi|kumbuka|panga)\\b")

    /** Looks like an instruction rather than a question; only these are worth a rewrite attempt. */
    fun looksLikeInstruction(lower: String): Boolean = lower.length in 6..300 && !lower.trimEnd().endsWith("?") &&
        instruction.containsMatchIn(lower.trim())

    fun prompt(owner: String): String = """
        Rewrite the owner's request as exactly ONE line in one of these forms, using the owner's own words for the task, or reply NONE.
        remind me to <task> <when, e.g. tomorrow 17:00, on friday 9am, in 20 minutes, the 1st at 9:00>
        remember <subject>: <note>
        log cash <item> <whole-shilling amount>
        log income <item> <whole-shilling amount>
        Do not add facts the owner did not say. Output only the line.
        OWNER REQUEST (their own words): ${owner.take(300)}
    """.trimIndent()

    private val allowed = Regex("^(remind me to |remember |log cash |log income )")

    /** The model's line as a command, or null when it said NONE, wrote more than one line, or the rules reject it. */
    fun accept(output: String, now: Instant): OwnerCommand? {
        val line = output.trim().removeSurrounding("`").trim().trim('"').trim()
        if (line.isEmpty() || line.contains('\n') || line.length > 300 || line.uppercase(Locale.ROOT).startsWith("NONE")) return null
        if (!allowed.containsMatchIn(line.lowercase(Locale.ROOT))) return null
        return runCatching { AssistantCommands.parse(line, now) }.getOrNull()
    }
}
