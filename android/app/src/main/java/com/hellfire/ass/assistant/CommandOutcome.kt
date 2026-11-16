package com.hellfire.ass.assistant

import kotlinx.coroutines.CancellationException

data class CommandOutcome(val completed: Boolean, val message: String)

/** Failure is a result, never a success badge. Call only after explicit owner approval. */
suspend fun runConfirmedCommand(action: suspend () -> String): CommandOutcome = try {
    CommandOutcome(true, action())
} catch (e: CancellationException) { throw e }
catch (e: IllegalArgumentException) { CommandOutcome(false, e.message ?: "Command could not be applied.") }
catch (e: Exception) { com.hellfire.ass.Diagnostics.warn(e); CommandOutcome(false, "Command could not be fully applied. Check Tasks, Inbox or Money before trying again; nothing was sent.") }
