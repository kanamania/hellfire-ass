package com.hellfire.ass

import com.hellfire.ass.communications.GatewayEvents
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelegramEventsTest {
    private fun event(transport: String = "telegram", collection: String = "messages") = JSONObject().put("schema_version", 1).put("transport", transport)
        .put("collection", collection).put("item", JSONObject().put("id", "-100:42").put("from", "Asha").put("to", "me")
            .put("thread_id", "tg:-100").put("is_group", false).put("timestamp", "1794571200").put("text", JSONObject().put("body", "Synthetic")))

    @Test fun `telegram relay events become telegram sources and never pass as WhatsApp`() {
        assertEquals("TELEGRAM", GatewayEvents.channel("telegram"))
        assertTrue(GatewayEvents.linked("telegram"))
        val snapshot = GatewayEvents.snapshot(event(), "a".repeat(64), "acct", "telegram", 1000, 2000)
        assertEquals("telegram", snapshot.source)
        assertEquals("INCOMING", snapshot.kind)
        assertEquals(listOf("Asha"), snapshot.fields["sender_hint"])
        assertEquals("tg:-100", GatewayEvents.thread(event().getJSONObject("item"), "messages", "telegram"))
        assertEquals("OUTGOING", GatewayEvents.snapshot(event(collection = "message_echoes"), "b".repeat(64), "acct", "telegram", 1000, 2000).kind)
        assertTrue(runCatching { GatewayEvents.snapshot(event("whatsapp_web"), "c".repeat(64), "acct", "telegram", 1, 2) }.isFailure)
        assertTrue(runCatching { GatewayEvents.snapshot(event("telegram"), "d".repeat(64), "acct", "web", 1, 2) }.isFailure)
        assertTrue(runCatching { GatewayEvents.snapshot(event("telegram"), "e".repeat(64), "acct", "meta", 1, 2) }.isFailure)
    }
}
