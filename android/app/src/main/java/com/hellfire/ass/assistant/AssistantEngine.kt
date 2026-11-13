package com.hellfire.ass.assistant

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.finance.Money
import com.hellfire.ass.tasks.TaskTime
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.util.Locale

/** All queries are read-only. Untrusted source text never selects an executable action. */
class AssistantEngine(private val app: HellFireApp, private val clock: Clock = Clock.systemUTC()) {
    data class Answer(val text: String, val sources: List<String> = emptyList())
    suspend fun answer(query: String): Answer = withContext(Dispatchers.IO) {
        require(query.length <= 2000)
        val lower = query.trim().lowercase(Locale.ROOT)
        if (lower in setOf("hello", "hi", "hellfire", "habari", "hujambo", "time", "saa ngapi", "what time is it", "what time is it?"))
            return@withContext Answer(LocalAssistant(clock).answer(query))
        when {
            lower.startsWith("search ") || lower.startsWith("tafuta ") || lower.startsWith("find ") -> search(query.substringAfter(' ').trim())
            lower in setOf("", "help", "msaada") -> Answer("Ask: spending today, income this month, balances, tasks tomorrow, morning brief, evening brief, or search <words>. Answers use saved evidence; review actions in their own tabs.")
            "brief" in lower || "muhtasari" in lower -> brief(lower.contains("evening") || lower.contains("jioni"))
            lower.contains(Regex("\\b(balance|balances|salio)\\b")) -> {
                val accounts = app.database.finances().allAccounts()
                Answer(if (accounts.isEmpty()) "No accounts saved." else accounts.map { "${it.label}: ${Money.display(app.database.finances().balance(it.id))}" }.joinToString("\n") + "\nBalances reflect approved ledger entries only; opening balances require evidence and review.")
            }
            lower.contains(Regex("\\b(spend|spent|spending|expenses|income|earned|earn|matumizi|mapato)\\b")) -> {
                val range = QueryDates.range(lower, clock.instant())
                val totals = app.finances.summary(range.first, range.second)
                Answer("${rangeLabel(range)} · approved ledger\nIncome ${Money.display(totals.income)}\nNet expenses including fees/refunds ${Money.display(totals.expenses)}\nOwn transfers excluded. Missing/unapproved expenses are not included.", totals.sourceIds)
            }
            lower.contains(Regex("\\b(tasks|commitments|appointments|ahadi)\\b")) -> {
                val range = QueryDates.range(lower, clock.instant())
                val tasks = app.database.tasks().period(range.first, range.second)
                Answer(if (tasks.isEmpty()) "No active commitments in ${rangeLabel(range)}." else tasks.joinToString("\n") { "${TaskTime.display(it.dueAt)} · ${it.party} · ${it.title}" }, tasks.map { it.sourceId }.distinct())
            }
            else -> search(query.replace(Regex("(?i)^(search|find|what do you know about|tafuta)\\s+"), "").trim().trimEnd('?'))
        }
    }
    suspend fun source(id: String): String {
        val event = app.database.events().find(id) ?: return "Source no longer available."
        val payload = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8))
        return "${event.source} · ${TaskTime.display(event.occurredAt)} EAT · ${event.kind}\nSource ${event.id}\n${payload.toString(2)}"
    }
    private suspend fun search(query: String): Answer {
        require(query.length in 2..300) { "Use 2–300 characters for source search" }
        val memories = app.database.memory().search(query)
        val found = mutableListOf<Pair<String, String>>()
        var before = Long.MAX_VALUE
        var cursorId = "\uffff"
        var scanned = 0
        while (scanned < 2000 && found.size < 20) {
            currentCoroutineContext().ensureActive()
            val batch = app.database.events().searchBatch(before, cursorId)
            if (batch.isEmpty()) break
            for (event in batch) {
                scanned++
                if (!event.contentAvailable || event.securitySensitive) continue
                val payload = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8))
                val fields = payload.getJSONObject("fields")
                val text = fields.keys().asSequence().flatMap { key -> fields.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().asSequence() }.joinToString("\n")
                val at = text.indexOf(query, ignoreCase = true)
                if (at >= 0) found += event.id to "${event.source} · ${TaskTime.display(event.occurredAt)} · ${text.substring((at - 80).coerceAtLeast(0), (at + query.length + 200).coerceAtMost(text.length))}"
                if (found.size == 20) break
            }
            before = batch.last().receivedAt; cursorId = batch.last().id
        }
        val lines = memories.map { "${it.kind} (${it.confidence}%) · ${it.subject}: ${it.text}" } + found.map { it.second }
        return Answer((if (lines.isEmpty()) "No matching saved evidence found." else lines.joinToString("\n\n")) +
            "\nLiteral source search inspected $scanned recent events (limit 2,000; up to 20 matches). Sources and inferences can be incomplete.",
            (memories.map { it.sourceId } + found.map { it.first }).distinct())
    }
    private suspend fun brief(evening: Boolean): Answer {
        val today = clock.instant().atZone(TaskTime.zone).toLocalDate()
        val start = today.atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()
        val tomorrow = today.plusDays(1).atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()
        val following = today.plusDays(2).atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()
        val tasks = app.database.tasks().period(0, if (evening) following else tomorrow)
        val spendStart = if (evening) start else today.minusDays(1).atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()
        val finance = app.finances.summary(spendStart, if (evening) tomorrow else start)
        val drafts = app.database.communications().pendingCount()
        val candidates = app.database.finances().reviewCount()
        return Answer("${if (evening) "Evening" else "Morning"} brief · $today EAT\n" +
            "${if (evening) "Today" else "Yesterday"}: net expenses ${Money.display(finance.expenses)}, income ${Money.display(finance.income)}.\n" +
            "$candidates financial items await review; $drafts drafts await review.\n" +
            (if (tasks.isEmpty()) "No active due/overdue commitments in this briefing window." else tasks.take(30).joinToString("\n") { "${TaskTime.display(it.dueAt)} · ${it.party} · ${it.title}" }) +
            "\nOnly saved, owner-approved ledger/tasks are included. Check unlogged cash, bills, unconfirmed dates and important replies.",
            (finance.sourceIds + tasks.map { it.sourceId }).distinct())
    }
    private fun rangeLabel(range: Pair<Long, Long>) = "${TaskTime.display(range.first)} to ${TaskTime.display(range.second)} EAT (end exclusive)"
}

object QueryDates {
    fun range(query: String, now: Instant): Pair<Long, Long> {
        val today = now.atZone(TaskTime.zone).toLocalDate()
        val explicit = Regex("\\b20[0-9]{2}-[0-9]{2}-[0-9]{2}\\b").findAll(query).map { LocalDate.parse(it.value) }.toList()
        val start = when {
            explicit.isNotEmpty() -> explicit.first()
            "yesterday" in query || "jana" in query -> today.minusDays(1)
            "tomorrow" in query || "kesho" in query -> today.plusDays(1)
            "next month" in query || "mwezi ujao" in query -> today.withDayOfMonth(1).plusMonths(1)
            "last month" in query || "mwezi uliopita" in query -> today.withDayOfMonth(1).minusMonths(1)
            "last week" in query || "wiki iliyopita" in query -> today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            "next week" in query || "wiki ijayo" in query -> today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
            "month" in query || "mwezi" in query -> today.withDayOfMonth(1)
            "week" in query || "wiki" in query -> today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            else -> today
        }
        val end = when {
            explicit.size >= 2 -> explicit[1].plusDays(1)
            explicit.size == 1 -> start.plusDays(1)
            "month" in query || "mwezi" in query -> start.plusMonths(1)
            "week" in query || "wiki" in query -> start.plusWeeks(1)
            else -> start.plusDays(1)
        }
        require(end > start && java.time.temporal.ChronoUnit.DAYS.between(start, end) <= 3660)
        return start.atStartOfDay(TaskTime.zone).toInstant().toEpochMilli() to end.atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()
    }
}
