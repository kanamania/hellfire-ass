package com.hellfire.ass.ui.reports

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.reports.DateRange
import com.hellfire.ass.tasks.AssistantTask

/** Draft states that mean the owner approved and the channel accepted the send. */
val SENT_STATES = setOf("SUBMITTED", "SENT_CONFIRMED", "DELIVERED", "READ")

/** When the task was marked done, if known. Tasks finished before v10 without an audit record return null ("done, time unknown"). */
fun AssistantTask.completedAtOrNull(): Long? = completedAt

/** Recording length if stored at import (v10). */
fun MediaItem.lengthMs(): Long? = durationMs

/** One AI request: metadata only, never prompt or answer text. */
data class UsageRow(val at: Long, val task: String, val provider: String, val model: String, val result: String,
    val input: Long, val output: Long, val microUsd: Long)

/** AI requests in [range] from the v10 usage log. Call off the main thread. */
suspend fun aiUsage(app: HellFireApp, range: DateRange): List<UsageRow> = app.database.aiUsage().period(range.start, range.end)
    .map { UsageRow(it.at, it.task, it.provider, it.model, it.result, it.inputTokens ?: 0, it.outputTokens ?: 0, it.microUsd) }
