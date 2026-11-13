package com.hellfire.ass.assistant

import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.communications.OutboundDraft
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.finance.CashParser
import com.hellfire.ass.finance.FinanceCandidate
import com.hellfire.ass.finance.Money
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskExtractor
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.tasks.TaskTime
import java.time.Instant
import java.util.UUID

/** An owner-spoken or typed instruction. Nothing is applied until the owner confirms it; sends still need Messages approval. */
sealed interface OwnerCommand {
    val utterance: String
    val summary: String
    data class Remind(override val utterance: String, val title: String, val dueAt: Long) : OwnerCommand {
        override val summary get() = "Reminder: $title · due ${TaskTime.display(dueAt)} EAT. Alarms start after you confirm."
    }
    data class Remember(override val utterance: String, val subject: String, val text: String) : OwnerCommand {
        override val summary get() = "Save memory note · $subject: $text"
    }
    data class TaskAction(override val utterance: String, val query: String, val action: String, val dueAt: Long? = null,
        val taskId: String? = null, val title: String? = null) : OwnerCommand {
        override val summary get() = when (action) {
            "DONE" -> "Mark task done: ${title ?: query}"
            "SNOOZE" -> "Snooze task 10 minutes: ${title ?: query}"
            else -> "Reschedule task: ${title ?: query} → ${TaskTime.display(dueAt!!)} EAT"
        }
    }
    data class Draft(override val utterance: String, val channel: String, val recipient: String, val subject: String, val body: String) : OwnerCommand {
        override val summary get() = "${if (channel == "EMAIL_IMAP") "Email" else "WhatsApp"} draft to $recipient" +
            (if (subject.isNotEmpty()) " · $subject" else "") + "\n$body\nConfirming saves a draft only; sending needs separate approval in Messages."
    }
    data class Cash(override val utterance: String, val items: List<Pair<String, Long>>, val occurredAt: Long) : OwnerCommand {
        override val summary get() = "Cash expenses for Finance review:\n" + items.joinToString("\n") { "${it.first}: ${Money.display(it.second)}" }
    }
}

object AssistantCommands {
    private val remind = Regex("(?is)^(?:remind me(?: to)?|nikumbushe)\\s+(.+)$")
    private val remember = Regex("(?is)^(?:remember(?: that)?|kumbuka(?: kwamba)?)\\s+(.+)$")
    private val cash = Regex("(?is)^(?:log cash|cash expense)\\s*:?\\s+(.+)$|^(nimelipa\\s+.+)$")
    private val email = Regex("(?is)^(?:email|send email to)\\s+([^\\s:]+@[^\\s:]+)(?:\\s+(?:about|subject|kuhusu)\\s+([^:\\r\\n]+))?\\s*:\\s*(.+)$")
    private val whatsapp = Regex("(?is)^whatsapp\\s+(\\+?[0-9][0-9 ]{5,20})\\s*:\\s*(.+)$")
    private val done = Regex("(?is)^(?:mark\\s+(?:task\\s+)?(.+?)(?:\\s+as)?\\s+(?:done|complete|completed)|(?:complete|finish|maliza)\\s+(?:task\\s+)?(.+))$")
    private val snooze = Regex("(?is)^(?:snooze|ahirisha)\\s+(?:task\\s+)?(.+)$")
    private val reschedule = Regex("(?is)^(?:reschedule|move|hamisha)\\s+(?:task\\s+)?(.+?)\\s+(?:to|hadi)\\s+(.+)$")
    private val weekdays = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|jumatatu|jumanne|jumatano|alhamisi|ijumaa|jumamosi|jumapili"
    private const val WHEN_HINT = "Say when: today, tomorrow, a weekday or yyyy-MM-dd, optionally with HH:MM (default 09:00)."

    /** Returns null when the text is a question rather than a command. Throws IllegalArgumentException with an owner-facing message for malformed commands. */
    fun parse(text: String, now: Instant): OwnerCommand? {
        val input = text.trim()
        remind.find(input)?.let { m ->
            val rest = m.groupValues[1].trim()
            val due = requireNotNull(TaskExtractor.extract(rest, now.toEpochMilli(), outgoing = true)) { WHEN_HINT }.dueAt
            require(due >= now.toEpochMilli() - 60_000) { "That time has already passed (${TaskTime.display(due)} EAT)." }
            val title = stripWhen(rest).also { require(it.isNotEmpty() && it.length <= 300) { "Say what to be reminded about (up to 300 characters)." } }
            return OwnerCommand.Remind(input, title, due)
        }
        remember.find(input)?.let { m ->
            val rest = m.groupValues[1].trim()
            val colon = rest.indexOf(':')
            val (subject, note) = if (colon in 1..200) rest.substring(0, colon).trim() to rest.substring(colon + 1).trim() else "Note" to rest
            require(subject.isNotEmpty() && note.isNotEmpty() && note.length <= 4000) { "Say what to remember, e.g. remember Asha: prefers calls after 17:00." }
            return OwnerCommand.Remember(input, subject, note)
        }
        cash.find(input)?.let { m ->
            val items = runCatching { CashParser.parse(m.groupValues[1].ifEmpty { m.groupValues[2] }) }.getOrNull()
            requireNotNull(items) { "Say each item with a whole-shilling amount, e.g. log cash bodaboda 3000 na chakula elfu saba." }
            return OwnerCommand.Cash(input, items, now.toEpochMilli())
        }
        email.find(input)?.let { m ->
            val (recipient, subject, body) = m.destructured
            require(!subject.contains(Regex("[\\r\\n]")) && subject.length <= 500) { "Keep the subject on one line (up to 500 characters)." }
            require(body.isNotBlank() && body.length <= 12000) { "Message body must be 1–12,000 characters." }
            return OwnerCommand.Draft(input, "EMAIL_IMAP", recipient, subject.trim(), body.trim())
        }
        whatsapp.find(input)?.let { m ->
            val number = m.groupValues[1].filter(Char::isDigit)
            require(number.length in 6..15) { "Use an international number with 6–15 digits." }
            val body = m.groupValues[2].trim().also { require(it.isNotEmpty() && it.length <= 12000) { "Message body must be 1–12,000 characters." } }
            return OwnerCommand.Draft(input, "WHATSAPP_BUSINESS", number, "", body)
        }
        done.find(input)?.let { m -> return taskAction(input, m.groupValues[1].ifEmpty { m.groupValues[2] }, "DONE", null) }
        reschedule.find(input)?.let { m ->
            val due = requireNotNull(TaskExtractor.extract(m.groupValues[2], now.toEpochMilli(), outgoing = true)) { WHEN_HINT }.dueAt
            require(due > now.toEpochMilli()) { "The new time must be in the future." }
            return taskAction(input, m.groupValues[1], "RESCHEDULE", due)
        }
        snooze.find(input)?.let { m -> return taskAction(input, m.groupValues[1], "SNOOZE", null) }
        return null
    }

    private fun taskAction(input: String, query: String, action: String, due: Long?): OwnerCommand.TaskAction {
        val q = query.trim().removeSurrounding("\"").also { require(it.length in 2..300) { "Name the task with 2–300 characters of its title." } }
        return OwnerCommand.TaskAction(input, q, action, due)
    }

    internal fun stripWhen(text: String): String = text
        .replace(Regex("(?i)\\bsaa nne asubuhi\\b"), " ")
        .replace(Regex("(?i)\\b(?:at\\s+|saa\\s+)?(?:[01]?[0-9]|2[0-3]):[0-5][0-9]\\b"), " ")
        .replace(Regex("(?i)\\b(?:on\\s+)?20[0-9]{2}-[0-9]{2}-[0-9]{2}\\b"), " ")
        .replace(Regex("(?i)\\b(?:the\\s+)?(?:day after tomorrow|keshokutwa|kesho kutwa)\\b"), " ")
        .replace(Regex("(?i)\\b(?:today|tomorrow|leo|kesho)\\b"), " ")
        .replace(Regex("(?i)\\b(?:on\\s+|next\\s+)?(?:$weekdays)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd(',', '.', ';').trim()

    /** Applies a confirmed command. Callers must only invoke this after explicit owner confirmation. */
    suspend fun execute(app: HellFireApp, command: OwnerCommand): String {
        val now = System.currentTimeMillis()
        return when (command) {
            is OwnerCommand.Remind -> {
                val sourceId = ownerSource(app, command.utterance, now)
                val id = NotificationNormalizer.sha256("task:$sourceId")
                app.database.tasks().insert(AssistantTask(id, sourceId, command.title, command.dueAt, party = "OWNER",
                    evidence = command.utterance, createdAt = now, updatedAt = now))
                TaskScheduler.activate(app, id, command.title, command.dueAt, "OWNER")
                "Reminder active for ${TaskTime.display(command.dueAt)} EAT. Manage it in Tasks."
            }
            is OwnerCommand.Remember -> {
                val sourceId = ownerSource(app, command.utterance, now, allowRedaction = false)
                app.database.memory().insert(MemoryItem(UUID.randomUUID().toString(), sourceId, command.subject, command.text, "OWNER_NOTE", 100, now, now))
                "Memory note saved with its source."
            }
            is OwnerCommand.TaskAction -> {
                val id = requireNotNull(command.taskId) { "Task must be resolved before confirmation" }
                when (command.action) {
                    "RESCHEDULE" -> {
                        val task = requireNotNull(app.database.tasks().task(id))
                        TaskScheduler.activate(app, id, task.title, command.dueAt!!, task.party)
                        "Task moved to ${TaskTime.display(command.dueAt)} EAT."
                    }
                    else -> { TaskScheduler.action(app, id, command.action); if (command.action == "DONE") "Task marked done." else "Task snoozed for 10 minutes." }
                }
            }
            is OwnerCommand.Draft -> {
                val config = requireNotNull(app.secrets.read(if (command.channel == "EMAIL_IMAP") "mail" else "gateway")) {
                    "Set up the ${if (command.channel == "EMAIL_IMAP") "email account" else "WhatsApp gateway"} in Messages first."
                }
                if (command.channel == "EMAIL_IMAP") javax.mail.internet.InternetAddress(command.recipient, true).validate()
                else require(config.optString("transport", "meta") == "meta") { "WhatsApp Web is capture-only. Reply in WhatsApp." }
                val id = UUID.randomUUID().toString()
                app.database.withTransaction {
                    app.database.communications().draft(OutboundDraft(id, command.channel, config.getString("id"), command.recipient, command.subject, command.body, now))
                    app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_COMMAND_DRAFT", "DRAFT", now))
                }
                "Draft saved. Review and approve it in Messages to send; nothing was sent."
            }
            is OwnerCommand.Cash -> {
                app.database.withTransaction {
                    val sourceId = ownerSource(app, command.utterance, command.occurredAt)
                    command.items.forEachIndexed { index, (description, amount) ->
                        app.database.finances().candidate(FinanceCandidate(UUID.randomUUID().toString(), sourceId, index, "MANUAL", null,
                            "EXPENSE", amount, 0, null, description, command.occurredAt))
                    }
                }
                "${command.items.size} cash item(s) added to Finance review. They reach the ledger only after you approve them there."
            }
        }
    }

    /** Memory notes must survive unredacted (as on the memory screen); other commands only reject authentication content. */
    private suspend fun ownerSource(app: HellFireApp, utterance: String, at: Long, allowRedaction: Boolean = true): String {
        val snapshot = NotificationSnapshot(UUID.randomUUID().toString(), "owner.command", at, System.currentTimeMillis(),
            source = "owner_command", fields = mapOf("text" to listOf(utterance)))
        val safe = requireNotNull(NotificationNormalizer.normalize(snapshot, app.packageName))
        require(!safe.securitySensitive && (allowRedaction || !safe.contentRedacted)) { "Remove authentication codes or long numbers from the command" }
        app.events.capture(snapshot, app.packageName)
        return safe.id
    }
}
