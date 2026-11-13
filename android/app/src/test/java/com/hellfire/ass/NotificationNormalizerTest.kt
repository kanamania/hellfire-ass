package com.hellfire.ass

import com.hellfire.ass.events.*
import org.junit.Assert.*
import org.junit.Test

class NotificationNormalizerTest {
    private fun raw(vararg fields: Pair<String, List<String>>) = NotificationSnapshot(
        key = "external-key", packageName = "example.any.app", postedAt = 100, receivedAt = 200,
        fields = mapOf(*fields),
    )
    private fun normalize(raw: NotificationSnapshot) = NotificationNormalizer.normalize(raw, "com.hellfire.ass")!!

    @Test fun `empty protected content remains unavailable`() {
        val event = normalize(raw())
        assertFalse(event.contentAvailable)
        assertFalse(event.contentRedacted)
    }
    @Test fun `capture arbitrary apps without an allowlist`() {
        for (pkg in listOf("example.chat", "example.finance", "android", "example.delivery")) {
            assertNotNull(NotificationNormalizer.normalize(raw().copy(packageName = pkg), "com.hellfire.ass"))
        }
    }
    @Test fun `own notifications are excluded`() {
        assertNull(NotificationNormalizer.normalize(raw().copy(packageName = "com.hellfire.ass"), "com.hellfire.ass"))
    }
    @Test fun `reconnect replay excludes received time from identity`() {
        assertEquals(normalize(raw()).id, normalize(raw().copy(receivedAt = 999)).id)
    }
    @Test fun `field order is irrelevant`() {
        val a = "title" to listOf("hello")
        val b = "text" to listOf("world")
        assertEquals(normalize(raw(a, b)).id, normalize(raw(b, a)).id)
    }
    @Test fun `edited content and removal retain separate evidence`() {
        val raw = raw("text" to listOf("original"))
        assertNotEquals(normalize(raw).id, normalize(raw.copy(fields = mapOf("text" to listOf("edited")))).id)
        assertNotEquals(normalize(raw).id, normalize(raw.copy(kind = "REMOVED", removedReason = 2)).id)
    }
    @Test fun `reposted key at new post time is a new event`() {
        assertNotEquals(normalize(raw()).id, normalize(raw().copy(postedAt = 101)).id)
    }
    @Test fun `ongoing rank and group changes survive`() {
        val original = normalize(raw()).id
        assertNotEquals(original, normalize(raw().copy(ongoing = true)).id)
        assertNotEquals(original, normalize(raw().copy(rank = 1)).id)
        assertNotEquals(original, normalize(raw().copy(groupConversation = true)).id)
    }
    @Test fun `split title and body OTP redacts all sensitive surfaces`() {
        val event = normalize(raw("title" to listOf("Verification"), "text" to listOf("AB7K9Z"),
            "sender_hint" to listOf("Account"), "action_metadata" to listOf("Copy AB7K9Z")))
        assertTrue(event.securitySensitive)
        assertTrue(event.contentRedacted)
        assertFalse(event.snapshot.fields.toString().contains("AB7K9Z"))
        assertTrue(event.contentAvailable)
    }
    @Test fun `Kiswahili authentication fields are removed`() {
        val event = normalize(raw("text_lines" to listOf("Msimbo wako ni 765432")))
        assertTrue(event.securitySensitive)
        assertFalse(event.snapshot.fields.toString().contains("765432"))
    }
    @Test fun `bare numeric codes and unicode codes are redacted`() {
        val event = normalize(raw("text" to listOf("Use 432198", "１２３４５６")))
        assertTrue(event.contentRedacted)
        assertFalse(event.snapshot.fields.toString().contains("432198"))
        assertFalse(event.snapshot.fields.toString().contains("１２３４５６"))
    }
    @Test fun `raw key is never retained`() {
        assertFalse(normalize(raw()).snapshot.key.contains("external-key"))
    }
    @Test fun `canonical framing prevents delimiter collisions`() {
        assertNotEquals(normalize(raw("text" to listOf("a", "bc"))).id,
            normalize(raw("text" to listOf("ab", "c"))).id)
    }
    @Test fun `synthetic fixtures are distinct from device delivery`() {
        assertNotEquals(normalize(raw()).id, normalize(raw().copy(source = "synthetic_notification")).id)
    }
}
