package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.communications.GatewayEvents
import com.hellfire.ass.events.NotificationNormalizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GatewayEventsTest {
    private fun event(outgoing: Boolean = false) = JSONObject().put("transport", "whatsapp_web")
        .put("collection", if (outgoing) "message_echoes" else "messages")
        .put("item", JSONObject().put("id", "synthetic").put("thread_id", "group@g.us")
            .put("from", if (outgoing) "owner@c.us" else "group@g.us").put("to", if (outgoing) "group@g.us" else "owner@c.us")
            .put("author", "member@c.us").put("is_group", true).put("text", JSONObject().put("body", "Nitakutumia invoice kesho")))

    @Test fun `linked group messages preserve conversation and author with separate provenance`() {
        val event = event()
        val snapshot = GatewayEvents.snapshot(event, "a".repeat(64), "account", "web", 1, 2)
        assertEquals("whatsapp_web", snapshot.source)
        assertEquals("WHATSAPP_WEB", GatewayEvents.channel("web"))
        assertEquals("member@c.us", snapshot.fields["sender_hint"]!!.single())
        assertEquals("group@g.us", GatewayEvents.thread(event.getJSONObject("item"), "messages", "web"))
        assertTrue(snapshot.groupConversation)
        assertEquals("INCOMING", snapshot.kind)
    }

    @Test fun `outgoing web messages retain outgoing direction for commitment extraction`() {
        val event = event(true)
        val snapshot = GatewayEvents.snapshot(event, "a".repeat(64), "account", "web", 1, 2)
        assertEquals("OUTGOING", snapshot.kind)
        assertEquals("group@g.us", GatewayEvents.thread(event.getJSONObject("item"), "message_echoes", "web"))
    }

    @Test fun `transport mismatch is rejected and meta provenance remains available`() {
        assertTrue(runCatching { GatewayEvents.snapshot(event(), "id", "account", "meta", 1, 2) }.isFailure)
        val meta = event().apply { remove("transport") }
        assertTrue(runCatching { GatewayEvents.snapshot(meta, "id", "account", "web", 1, 2) }.isFailure)
        assertEquals("whatsapp_business", GatewayEvents.snapshot(meta, "id", "account", "meta", 1, 2).source)
        assertTrue(runCatching { GatewayEvents.channel("unknown") }.isFailure)
    }

    @Test fun `security messages redact text and raw envelope before persistence`() {
        val event = event().apply { getJSONObject("item").getJSONObject("text").put("body", "Your OTP is 123456") }
        val snapshot = GatewayEvents.snapshot(event, "id", "account", "web", 1, 2)
        val safe = NotificationNormalizer.normalize(snapshot, "com.hellfire.ass")!!
        assertTrue(safe.securitySensitive)
        assertFalse(safe.snapshot.fields.values.flatten().any { "123456" in it })
    }
}
