package com.hellfire.ass

import com.hellfire.ass.performance.ExecutionScheduler
import com.hellfire.ass.performance.WorkPriority
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeferredTurnTest {
    @Test fun `backgroundGenerateDoesNotClaimInteractive - deferred waits while the owner is queued`() = runBlocking {
        ExecutionScheduler.reset()
        ExecutionScheduler.noteWaiting(WorkPriority.INTERACTIVE)
        val deferred = async { ExecutionScheduler.awaitTurn(WorkPriority.DEFERRED, pollMillis = 10); true }
        delay(60)
        assertFalse("deferred work waits behind an interactive request", deferred.isCompleted)
        ExecutionScheduler.clearWaiting(WorkPriority.INTERACTIVE)
        assertTrue(withTimeout(1_000) { deferred.await() })
        // Interactive requests never wait on this gate.
        ExecutionScheduler.noteWaiting(WorkPriority.INTERACTIVE)
        withTimeout(100) { ExecutionScheduler.awaitTurn(WorkPriority.INTERACTIVE) }
        ExecutionScheduler.reset()
    }
}
