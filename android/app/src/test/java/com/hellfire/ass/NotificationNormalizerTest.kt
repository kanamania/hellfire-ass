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
    @Test fun `marked currency amounts survive while unrelated bare codes are removed`() {
        val event = normalize(raw("text" to listOf("Paid TZS 15000, fee TZS 500, note 432198")))
        assertTrue(event.snapshot.fields.toString().contains("TZS 15000"))
        assertFalse(event.snapshot.fields.toString().contains("432198"))
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
    @Test fun `valid ISO dates survive and a keyed PIN is removed even where it repeats as a reference`() {
        val raw = NotificationSnapshot("date", "fixture", 1, 2, fields = mapOf("text" to listOf("Meeting 2026-11-14 at 10:00")))
        assertEquals("Meeting 2026-11-14 at 10:00", NotificationNormalizer.normalize(raw, "owner")!!.snapshot.fields["text"]!!.single())
        val auth = raw.copy(fields = mapOf("text" to listOf("PIN 432198 TZS 15000 ref 432198 on 2026-11-14")))
        val normalized = NotificationNormalizer.normalize(auth, "owner")!!
        assertTrue(normalized.contentRedacted)
        assertEquals("PIN [CODE REDACTED] TZS 15000 ref [CODE REDACTED] on 2026-11-14", normalized.snapshot.fields["text"]!!.single())
    }
    @Test fun `everyday code pin and token wording keeps finance and work content`() {
        val payment = "Umepokea Tsh 50,000 kutoka JOHN. Usitoe PIN yako kwa mtu yeyote. Ref ABC12345"
        val email = "Hi team, the code review for the payments module is done"
        for (text in listOf(payment, email)) {
            val event = normalize(raw("text" to listOf(text)))
            assertFalse(text, event.securitySensitive)
            assertFalse(text, event.contentRedacted)
            assertEquals(text, event.snapshot.fields["text"]!!.single())
        }
        val luku = normalize(raw("text" to listOf("M-PESA: Umelipa Tsh 25,000 kwa LUKU. Token: 12345678901234567890. Salio Tsh 80,000")))
        assertFalse(luku.securitySensitive)
        assertEquals("M-PESA: Umelipa Tsh 25,000 kwa LUKU. Token: [CODE REDACTED]. Salio Tsh 80,000", luku.snapshot.fields["text"]!!.single())
    }
    @Test fun `codes after code pin token or verify are removed including alphanumeric values`() {
        for (text in listOf("Your code is AB7K9Z", "Weka PIN ni 7Q4X2M", "verify: G7KX2Q", "Your code is 123456")) {
            val event = normalize(raw("text" to listOf(text), "action_metadata" to listOf("Copy ${text.takeLast(6)}")))
            assertTrue(text, event.contentRedacted)
            assertFalse(text, event.snapshot.fields.toString().contains(text.takeLast(6)))
        }
    }
    @Test fun `cloud prompts that mention code are not blanked`() {
        assertEquals("Explain this code review comment", com.hellfire.ass.ai.CloudPrivacy.redact("Explain this code review comment"))
        assertEquals("[SECURITY CONTENT REDACTED]", com.hellfire.ass.ai.CloudPrivacy.redact("My password is hunter2"))
    }
}
