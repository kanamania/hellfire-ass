package com.hellfire.ass.assistant

import com.hellfire.ass.communications.Accounts

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
import com.hellfire.ass.tasks.TaskConditions
import com.hellfire.ass.tasks.TaskExtractor
import com.hellfire.ass.tasks.TaskScheduler
import com.hellfire.ass.tasks.TaskTime
import java.time.Instant
import java.util.UUID
import com.hellfire.ass.templates.TemplateItems

/** An owner-spoken or typed instruction. Nothing is applied until the owner confirms it; sends still need Messages approval. */
sealed interface OwnerCommand {
    val utterance: String
    val summary: String
    data class Remind(override val utterance: String, val title: String, val dueAt: Long, val condition: String? = null) : OwnerCommand {
        override val summary get() = "Reminder: $title · due ${TaskTime.display(dueAt)} EAT" +
            (condition?.let { ", " + TaskConditions.describe(it) } ?: "") + ". Alarms start after you confirm."
    }
    data class Remember(override val utterance: String, val subject: String, val text: String) : OwnerCommand {
        override val summary get() = "Save memory note · $subject: $text"
    }
    data class TaskAction(override val utterance: String, val query: String, val action: String, val dueAt: Long? = null,
        val taskId: String? = null, val title: String? = null) : OwnerCommand {
        override val summary get() = when (action) {
            "DONE" -> "Mark task done: ${title ?: query}"
            "SNOOZE" -> "Snooze task: ${title ?: query}"
            else -> "Reschedule task: ${title ?: query} → ${dueAt?.let(TaskTime::display) ?: "no time given"} EAT"
        }
    }
    data class Draft(override val utterance: String, val channel: String, val recipient: String, val subject: String, val body: String) : OwnerCommand {
        override val summary get() = "${if (channel == "EMAIL_IMAP") "Email" else "WhatsApp"} draft to $recipient" +
            (if (subject.isNotEmpty()) " · $subject" else "") + "\n$body\nConfirming saves a draft only; sending needs separate approval in Messages."
    }
    /** [kind] is EXPENSE or INCOME. Either way the items wait in Money › To confirm. */
    data class Cash(override val utterance: String, val items: List<Pair<String, Long>>, val occurredAt: Long, val kind: String = "EXPENSE") : OwnerCommand {
        override val summary get() = "${if (kind == "INCOME") "Income" else "Cash expenses"} for Finance review:\n" + items.joinToString("\n") { "${it.first}: ${Money.display(it.second)}" }
    }
    /** Use a saved template by name. [templateId] and [lines] are filled in when the name matches exactly one template. */
    data class UseTemplate(override val utterance: String, val query: String, val templateId: String? = null, val name: String? = null,
        val lines: List<String> = emptyList()) : OwnerCommand {
        override val summary get() = "Use template ${name ?: query}:\n" + lines.joinToString("\n")
    }
}

object AssistantCommands {
    private val remind = Regex("(?is)^(?:remind me(?: to)?|nikumbushe)\\s+(.+)$")
    private val remember = Regex("(?is)^(?:remember(?: that)?|kumbuka(?: kwamba)?)\\s+(.+)$")
    private val cash = Regex("(?is)^(?:log cash|cash expense)\\s*:?\\s+(.+)$|^(nimelipa\\s+.+)$")
    private val income = Regex("(?is)^(?:log income|nimepokea|i received)\\s*:?\\s+(.+)$")
    private val template = Regex("(?is)^(?:(?:use|run|apply)\\s+(?:the\\s+)?template|tumia\\s+kiolezo|kiolezo)\\s*:?\\s+(.+)$")
    // Spoken to the assistant: "HellFire, nimelipa bodaboda elfu tano."
    private val invocation = Regex("(?i)^(?:hey\\s+|ok\\s+)?hell\\s?fire\\s*[,:.!]?\\s+")
    private val email = Regex("(?is)^(?:email|send email to)\\s+([^\\s:]+@[^\\s:]+)(?:\\s+(?:about|subject|kuhusu)\\s+([^:\\r\\n]+))?\\s*:\\s*(.+)$")
    private val whatsapp = Regex("(?is)^whatsapp\\s+(\\+?[0-9][0-9 ]{5,20})\\s*:\\s*(.+)$")
    private val done = Regex("(?is)^(?:mark\\s+(?:task\\s+)?(.+?)(?:\\s+as)?\\s+(?:done|complete|completed)|(?:complete|finish|maliza)\\s+(?:task\\s+)?(.+))$")
    private val snooze = Regex("(?is)^(?:snooze|ahirisha)\\s+(?:task\\s+)?(.+)$")
    private val reschedule = Regex("(?is)^(?:reschedule|move|hamisha)\\s+(?:task\\s+)?(.+?)\\s+(?:to|hadi)\\s+(.+)$")
    private const val WHEN_HINT = "Say when: today, tomorrow, a weekday, the 1st or yyyy-MM-dd, optionally with a time (17:00, 5pm, saa tisa alasiri), or in 20 minutes."

    /** Returns null when the text is a question rather than a command. Throws IllegalArgumentException with an owner-facing message for malformed commands. */
    fun parse(text: String, now: Instant): OwnerCommand? {
        val input = text.trim().replace(invocation, "")
        remind.find(input)?.let { m ->
            val conditional = TaskConditions.parse(m.groupValues[1].trim())
            val rest = conditional.rest
            val place = TaskConditions.arrival(conditional.condition)
            // A place reminder needs no time; it stays armed for 30 days unless the owner gives an end time.
            val due = TaskExtractor.extract(rest, now.toEpochMilli(), outgoing = true, requireCommitment = false)?.dueAt
                ?: if (place) now.toEpochMilli() + 30L * 86_400_000L else throw IllegalArgumentException(WHEN_HINT)
            require(due >= now.toEpochMilli() - 60_000) { "That time has already passed (${TaskTime.display(due)} EAT)." }
            val title = stripWhen(rest).also { require(it.isNotEmpty() && it.length <= 300) { "Say what to be reminded about (up to 300 characters)." } }
            return OwnerCommand.Remind(input, title, due, conditional.condition)
        }
        remember.find(input)?.takeUnless { question(it.groupValues[1]) }?.let { m ->
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
        income.find(input)?.let { m ->
            val items = runCatching { CashParser.parseIncome(m.groupValues[1]) }.getOrNull()
            // "i received a message from Asha" is a question or a remark, not income; only "log income" insists on an amount.
            if (items == null && !input.trim().lowercase().startsWith("log income")) return null
            requireNotNull(items) { "Say what it was and a whole-shilling amount, e.g. nimepokea 50000 kutoka kwa Juma, or log income salary 500,000." }
            return OwnerCommand.Cash(input, items, now.toEpochMilli(), "INCOME")
        }
        template.find(input)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd('.', '!', '?').removeSurrounding("\"").also { require(it.length in 1..80) { "Say the template's name." } }
            return OwnerCommand.UseTemplate(input, name)
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
        done.find(input)?.let { m ->
            // "finish the report by friday" is an instruction with a deadline, not "mark it done".
            if (m.groupValues[2].isNotEmpty() && TaskExtractor.extract(m.groupValues[2], now.toEpochMilli(), outgoing = true, requireCommitment = false) != null) return null
            return taskAction(input, m.groupValues[1].ifEmpty { m.groupValues[2] }, "DONE", null)
        }
        reschedule.find(input)?.let { m ->
            val parsed = TaskExtractor.extract(m.groupValues[2], now.toEpochMilli(), outgoing = true, requireCommitment = false)
            // "move 50000 to savings" is not a reschedule; only "reschedule"/"hamisha" insist on a time.
            if (parsed == null && input.trim().lowercase().startsWith("move")) return null
            val due = requireNotNull(parsed) { WHEN_HINT }.dueAt
            require(due > now.toEpochMilli()) { "The new time must be in the future." }
            return taskAction(input, m.groupValues[1], "RESCHEDULE", due)
        }
        snooze.find(input)?.let { m -> return taskAction(input, m.groupValues[1], "SNOOZE", null) }
        return null
    }

    /** "remember when I paid rent?" asks; "remember Asha: prefers calls" instructs. */
    private fun question(rest: String): Boolean = rest.trim().endsWith("?") ||
        Regex("(?i)^(when|what|who|where|why|how|which|did|do|does|was|were|is|are|lini|nini|nani|wapi|vipi|je)\\b").containsMatchIn(rest.trim())

    private fun taskAction(input: String, query: String, action: String, due: Long?): OwnerCommand.TaskAction {
        val q = query.trim().removeSurrounding("\"").also { require(it.length in 2..300) { "Name the task with 2–300 characters of its title." } }
        return OwnerCommand.TaskAction(input, q, action, due)
    }

    internal fun stripWhen(text: String): String =
        TaskExtractor.whenPhrases.fold(text) { acc, phrase -> phrase.replace(acc, " ") }
            .replace(Regex("\\s+"), " ").trim()
            .replace(Regex("(?i)\\s+(?:at|on|by|ifikapo|saa)$"), "")
            .trimEnd(',', '.', ';').trim()

    /** Applies a confirmed command. Callers must only invoke this after explicit owner confirmation. */
    /**
     * Proposals applied in this process, by identity: a second tap on the same proposal card is a no-op, while asking
     * again (a new proposal, e.g. a second snooze) still works.
     */
    private val applied = ArrayDeque<OwnerCommand>()
    private fun claim(command: OwnerCommand): Boolean = synchronized(applied) {
        if (applied.any { it === command }) return false
        applied.addLast(command)
        if (applied.size > 200) applied.removeFirst()
        true
    }

    suspend fun execute(app: HellFireApp, command: OwnerCommand): String {
        if (!claim(command)) return "Already applied."
        return try { apply(app, command) } catch (e: Throwable) {
            synchronized(applied) { applied.removeAll { it === command } }
            throw e
        }
    }

    private suspend fun apply(app: HellFireApp, command: OwnerCommand): String {
        val now = System.currentTimeMillis()
        return when (command) {
            is OwnerCommand.Remind -> {
                // A proposal confirmed late must not start with an immediate overdue alarm.
                require(command.dueAt >= now - 60_000) { "That time has already passed (${TaskTime.display(command.dueAt)} EAT). Ask again with a new time." }
                val condition = command.condition?.let { c ->
                    if (!c.startsWith("ARRIVE:")) c else {
                        val name = c.substringAfter(':')
                        val place = com.hellfire.ass.tasks.Geofences.place(app.database.inbox().locationsNow(), name)
                            ?: throw IllegalArgumentException("No saved place called \"$name\" with a map position. Share a map location to HellFire and name it $name first.")
                        "ARRIVE:${place.id}|${place.label}"
                    }
                }
                val sourceId = ownerSource(app, command.utterance, now)
                val id = NotificationNormalizer.sha256("task:$sourceId")
                app.database.tasks().insert(AssistantTask(id, sourceId, command.title, command.dueAt, party = "OWNER",
                    evidence = command.utterance, createdAt = now, updatedAt = now, condition = condition))
                TaskScheduler.activate(app, id, command.title, command.dueAt, "OWNER")
                if (TaskConditions.arrival(condition)) com.hellfire.ass.tasks.Geofences.sync(app)
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
                        val dueAt = requireNotNull(command.dueAt) { "Reschedule needs a new time" }
                        require(dueAt > now) { "That time has already passed. Ask again with a new time." }
                        TaskScheduler.activate(app, id, task.title, dueAt, task.party)
                        "Task moved to ${TaskTime.display(dueAt)} EAT."
                    }
                    else -> {
                        TaskScheduler.action(app, id, command.action)
                        if (command.action == "DONE") "Task marked done."
                        else "Task snoozed for ${app.database.tasks().task(id)?.snoozeMinutes ?: 10} minutes."
                    }
                }
            }
            is OwnerCommand.Draft -> {
                // With several accounts the first one sends; the draft names it and the owner can change it before approving.
                val email = command.channel == "EMAIL_IMAP"
                val config = requireNotNull(if (email) Accounts.list(app.secrets, Accounts.MAIL).firstOrNull()
                    else Accounts.sendingGateways(app.secrets).firstOrNull()) {
                    if (email) "Set up an email account in Settings › Email first."
                    else "WhatsApp sending needs a Business API connection in Settings › WhatsApp. WhatsApp Web is capture-only."
                }
                if (email) javax.mail.internet.InternetAddress(command.recipient, true).validate()
                val from = if (email) config.getString("address") else Accounts.label(config)
                val id = UUID.randomUUID().toString()
                app.database.withTransaction {
                    app.database.communications().draft(OutboundDraft(id, command.channel, config.getString("id"), command.recipient, command.subject, command.body, now))
                    app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_COMMAND_DRAFT", "DRAFT", now))
                }
                "Draft saved from $from. Review and approve it in Inbox to send; nothing was sent."
            }
            is OwnerCommand.Cash -> {
                app.database.withTransaction {
                    val sourceId = ownerSource(app, command.utterance, command.occurredAt)
                    command.items.forEachIndexed { index, (description, amount) ->
                        app.database.finances().candidate(FinanceCandidate(UUID.randomUUID().toString(), sourceId, index, "MANUAL", null,
                            command.kind, amount, 0, null, description, command.occurredAt))
                    }
                }
                "${command.items.size} ${if (command.kind == "INCOME") "income" else "cash"} item(s) added to Finance review. They reach the ledger only after you approve them there."
            }
            is OwnerCommand.UseTemplate -> {
                val saved = requireNotNull(command.templateId?.let { app.database.templates().find(it) }) { "That template no longer exists." }
                // Run exactly what the owner was shown; an edit in between needs a fresh look.
                require(TemplateItems.decode(saved.items).map(TemplateItems::describe) == command.lines) { "Template ${saved.name} changed since you asked. Ask again to see its current lines." }
                val result = app.templates.run(saved, TemplateItems.decode(saved.items), now)
                if (result.tasks > 0) TaskScheduler.restore(app)
                "Template ${saved.name}: ${result.message}."
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
