package com.hellfire.ass

import com.hellfire.ass.assistant.AskRouting
import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.FinanceQuestion
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.finance.FinanceParser
import com.hellfire.ass.news.NewsAsk
import com.hellfire.ass.tasks.TaskTime
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Bilingual evaluation sets (src/test/resources/eval/, synthetic) run through the real parsers and routers.
 * Scores go to build/reports/assistant-eval.json. Every row is a regression case, so any miss fails the build;
 * these are parser and routing checks, not model or real-message accuracy.
 */
class AssistantEvalTest {
    private val now = Instant.parse("2026-11-13T07:00:00Z")
    private fun rows(name: String) = javaClass.classLoader!!.getResource("eval/$name")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    private data class Score(val set: String, val total: Int, val misses: List<String>) {
        val accuracy get() = if (total == 0) 1.0 else (total - misses.size).toDouble() / total
    }

    private fun time(): Score {
        val misses = rows("time.tsv").mapNotNull { (text, expected) ->
            val got = runCatching { (AssistantCommands.parse(text, now) as OwnerCommand.Remind).dueAt.let(TaskTime::display) }.getOrElse { "error: ${it.message}" }
            if (got == expected) null else "$text → $got (expected $expected)"
        }
        return Score("time", rows("time.tsv").size, misses)
    }

    private fun finance(): Score {
        val all = rows("finance.tsv")
        val misses = all.mapNotNull { row ->
            val (sender, text) = row
            val expected = row.drop(2).map { it.trim() }.takeIf { it.firstOrNull().orEmpty().isNotEmpty() }
            val parsed = FinanceParser.parse(sender, text)
            val got = parsed?.let { listOf(it.provider, it.kind, it.amount.toString(), it.fee.toString()) }
            if (got == expected) null else "$text → $got (expected $expected)"
        }
        return Score("finance", all.size, misses)
    }

    private fun route(question: String): String {
        val lower = question.trim().lowercase(Locale.ROOT)
        if (runCatching { AssistantCommands.parse(question, now) }.getOrNull() != null) return "COMMAND"
        // Same order as AssistantEngine.answer after commands.
        if (!AskRouting.skipNews(lower) && NewsAsk.parse(question, now.toEpochMilli(), TaskTime.zone) != null) return "NEWS"
        if (AskRouting.brief(lower)) return "BRIEF"
        if (FinanceQuestion.isQuestion(lower)) return "FINANCE"
        if (AskRouting.tasks(lower)) return "TASKS"
        return "NOT_COMMAND"
    }

    private fun routing(): Score {
        val all = rows("routing.tsv")
        val misses = all.mapNotNull { (question, expected) -> route(question).let { if (it == expected) null else "$question → $it (expected $expected)" } }
        return Score("routing", all.size, misses)
    }

    @Test fun `bilingual parser and routing sets score 100 percent`() {
        val scores = listOf(time(), finance(), routing())
        fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val report = "{\n  \"synthetic\": true,\n  \"clock\": ${quote(now.toString())},\n  \"sets\": [\n" + scores.joinToString(",\n") {
            "    {\"set\": ${quote(it.set)}, \"total\": ${it.total}, \"accuracy\": ${it.accuracy}, \"misses\": [${it.misses.joinToString { m -> quote(m) }}]}"
        } + "\n  ]\n}\n"
        File("build/reports").mkdirs()
        File("build/reports/assistant-eval.json").writeText(report)
        for (score in scores) assertTrue("${score.set}: ${score.misses.joinToString("\n")}", score.misses.isEmpty())
    }
}
