package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.ai.*
import com.hellfire.ass.assistant.QueryDates
import com.hellfire.ass.tasks.TaskTime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AssistantContractTest {
    private val now = Instant.parse("2026-11-13T22:30:00Z")
    @Test fun `ranges use source timezone and explicit inclusive date boundaries`() {
        assertEquals("2026-11-14 00:00", TaskTime.display(QueryDates.range("spending today", now).first))
        assertEquals("2026-11-13 00:00", TaskTime.display(QueryDates.range("spending yesterday", now).first))
        assertEquals("2026-10-01 00:00", TaskTime.display(QueryDates.range("income last month", now).first))
        val explicit = QueryDates.range("spending 2026-11-01 to 2026-11-03", now)
        assertEquals("2026-11-04 00:00", TaskTime.display(explicit.second))
        assertTrue(runCatching { QueryDates.range("2026-02-30", now) }.isFailure)
        assertTrue(runCatching { QueryDates.range("2026-11-30 to 2026-11-01", now) }.isFailure)
    }
    private val task = """{"schema_version":1,"tool":"create_task","source_id":"synthetic","evidence":"tomorrow 10:00","title":"Synthetic review","due_at":"2026-11-14 10:00","timezone":"Africa/Dar_es_Salaam","party":"UNKNOWN"}"""
    @Test fun `validated proposals are typed and reject execution permissions or invented dates`() {
        assertTrue(ActionProposals.parse(task) is ActionProposal.Task)
        for (bad in listOf(task.replace("create_task", "send_approved_reply"), task.replace("create_task", "shell"),
            task.replace("2026-11-14", "2026-02-30"), task.replace("UNKNOWN", "AUTOMATIC_OWNER"),
            task.replace("\"schema_version\":1", "\"schema_version\":\"1\""), task.dropLast(1) + ",\"approval_token\":\"model-approved\"}")) {
            assertTrue(bad, runCatching { ActionProposals.parse(bad) }.isFailure)
        }
    }
    @Test fun `reply drafts reject arbitrary channels and header injection`() {
        val json = """{"schema_version":1,"tool":"prepare_reply","source_id":"synthetic","evidence":"question","channel":"EMAIL_IMAP","account_id":"account","recipient":"test@example.invalid","subject":"Reply","body":"Owner review required"}"""
        assertTrue(ActionProposals.parse(json) is ActionProposal.Reply)
        assertTrue(runCatching { ActionProposals.parse(json.replace("EMAIL_IMAP", "SHELL")) }.isFailure)
        assertTrue(runCatching { ActionProposals.parse(json.replace("Reply", "Reply\\r\\nBcc: other@example.invalid")) }.isFailure)
    }
}
