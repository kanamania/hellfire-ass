package com.hellfire.ass.sync

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.tasks.TaskScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Commands asked on the web (audit HF-061). The phone stays authoritative: each command is applied only if it is
 * still valid here, through the same code the phone's own buttons use, and audited as WEB_COMMAND_*. Only reversible
 * changes exist; posting money and sending messages are never web commands.
 */
object WebCommands {
    data class Command(val seq: Long, val kind: String, val target: String)
    data class Outcome(val seq: Long, val status: String, val result: String? = null)

    /** What a command would do given the target's current state: APPLY, or why it is stale. Pure, for tests. */
    fun decide(kind: String, taskState: String?, candidateState: String?): String? = when (kind) {
        "task.done", "task.snooze" -> if (taskState == "ACTIVE") null else "Task is ${taskState?.lowercase() ?: "gone"} on the phone"
        "candidate.dismiss" -> if (candidateState == "REVIEW") null else "Payment is ${candidateState?.lowercase() ?: "gone"} on the phone"
        else -> "Unsupported on this phone"
    }

    suspend fun apply(app: HellFireApp, command: Command): Outcome {
        val task = if (command.kind.startsWith("task.")) app.database.tasks().task(command.target) else null
        val candidate = if (command.kind == "candidate.dismiss") app.database.finances().findCandidate(command.target) else null
        decide(command.kind, task?.state, candidate?.state)?.let { return Outcome(command.seq, if (it.startsWith("Unsupported")) "REJECTED" else "STALE", it) }
        when (command.kind) {
            "task.done" -> TaskScheduler.action(app, command.target, "DONE")
            "task.snooze" -> TaskScheduler.action(app, command.target, "SNOOZE")
            "candidate.dismiss" -> app.finances.reject(command.target)
        }
        app.database.communications().audit(ActionAudit(UUID.randomUUID().toString(), command.target,
            "WEB_COMMAND_" + command.kind.uppercase().replace('.', '_'), "APPLIED", System.currentTimeMillis()))
        return Outcome(command.seq, "APPLIED")
    }

    /** Pulls pending commands, applies them and reports the outcomes. A server without commands (404) is skipped. */
    suspend fun sync(app: HellFireApp, call: WebSync.Call): Int {
        val page = try { call.send("GET", "/api/sync/commands?after=0", null) }
            catch (e: WebSync.Rejected) { if (e.status == 404) return 0 else throw e }
        val list = page.optJSONArray("commands") ?: return 0
        val outcomes = (0 until list.length()).map { list.getJSONObject(it) }.map { o ->
            apply(app, Command(o.getLong("seq"), o.getString("kind"), o.getString("target")))
        }
        if (outcomes.isNotEmpty()) call.send("POST", "/api/sync/commands/results", JSONObject().put("results", JSONArray(outcomes.map {
            JSONObject().put("seq", it.seq).put("status", it.status).apply { it.result?.let { r -> put("result", r) } }
        })))
        return outcomes.count { it.status == "APPLIED" }
    }
}
