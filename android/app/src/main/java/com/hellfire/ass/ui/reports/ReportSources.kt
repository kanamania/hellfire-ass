package com.hellfire.ass.ui.reports

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.reports.DateRange
import com.hellfire.ass.tasks.AssistantTask

/** Draft states that mean the owner approved and the channel accepted the send. */
val SENT_STATES = setOf("SUBMITTED", "SENT_CONFIRMED", "DELIVERED", "READ")

/** When the task was marked done, if known. Tasks finished before this was stored return null ("done, time unknown"). */
fun AssistantTask.completedAtOrNull(): Long? = null

/** Recording length if stored at import. */
fun MediaItem.lengthMs(): Long? = null

/** One AI request: metadata only, never prompt or answer text. */
data class UsageRow(val at: Long, val task: String, val provider: String, val model: String, val result: String,
    val input: Long, val output: Long, val microUsd: Long)

/** AI requests in [range]. Reads the encrypted router audit (last 100 requests). */
fun aiUsage(app: HellFireApp, range: DateRange): List<UsageRow> = runCatching { app.ai.store.read() }.getOrNull()?.audit.orEmpty()
    .filter { it.at in range && it.result != "STARTED" }
    .map { UsageRow(it.at, it.task.label, it.provider.label, it.model, it.result, it.input ?: 0, it.output ?: 0, it.estimatedMicroUsd) }
