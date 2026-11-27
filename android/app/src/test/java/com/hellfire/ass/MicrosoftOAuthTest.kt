package com.hellfire.ass

import com.hellfire.ass.communications.MailAdapter
import com.hellfire.ass.communications.MailConfig
import com.hellfire.ass.communications.MicrosoftOAuth
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Properties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicrosoftOAuthTest {
    private val client = "11111111-2222-3333-4444-555555555555"
    private class Fake(val replies: ArrayDeque<Pair<Int, JSONObject>>) : MicrosoftOAuth.Post {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun send(url: String, form: Map<String, String>): Pair<Int, JSONObject> { calls += url to form; return replies.removeFirst() }
    }

    @Test fun `device code sign-in, polling and refresh`() {
        val fake = Fake(ArrayDeque(listOf(
            200 to JSONObject().put("user_code", "ABCD-EFGH").put("verification_uri", "https://microsoft.com/devicelogin").put("device_code", "dev").put("interval", 5).put("expires_in", 900),
            400 to JSONObject().put("error", "authorization_pending"),
            400 to JSONObject().put("error", "slow_down"),
            200 to JSONObject().put("access_token", "at1").put("refresh_token", "rt1"),
            200 to JSONObject().put("access_token", "at2"),
            400 to JSONObject().put("error", "invalid_grant"))))
        val code = MicrosoftOAuth.start(client, "common", fake, now = 0)
        assertEquals("ABCD-EFGH", code.userCode); assertEquals(900_000, code.expiresAt)
        assertTrue(fake.calls[0].first.endsWith("/common/oauth2/v2.0/devicecode"))
        assertTrue(fake.calls[0].second.getValue("scope").contains("IMAP.AccessAsUser.All"))
        assertSame(MicrosoftOAuth.Poll.Pending, MicrosoftOAuth.poll(client, "common", "dev", fake))
        assertSame(MicrosoftOAuth.Poll.SlowDown, MicrosoftOAuth.poll(client, "common", "dev", fake))
        assertEquals(MicrosoftOAuth.Tokens("at1", "rt1"), (MicrosoftOAuth.poll(client, "common", "dev", fake) as MicrosoftOAuth.Poll.Done).tokens)
        // Microsoft may omit a rotated refresh token; the stored one is kept.
        assertEquals(MicrosoftOAuth.Tokens("at2", "rt1"), MicrosoftOAuth.refresh(client, "common", "rt1", fake))
        assertTrue(runCatching { MicrosoftOAuth.refresh(client, "common", "rt1", fake) }.exceptionOrNull() is javax.mail.AuthenticationFailedException)
        assertTrue(runCatching { MicrosoftOAuth.start("not-a-client", "common", fake) }.isFailure)
    }
    @Test fun `microsoft accounts round-trip and use XOAUTH2`() {
        val config = MailConfig("id", "owner@outlook.com", "refresh", MicrosoftOAuth.IMAP_HOST, MicrosoftOAuth.SMTP_HOST, 587, "MICROSOFT_OAUTH", client, "consumers")
        assertEquals(config, MailConfig.from(config.json()))
        val password = MailConfig("id2", "owner@example.com", "app-password", "imap.gmail.com", "smtp.gmail.com", 465)
        assertEquals(password, MailConfig.from(password.json()))
        assertFalse(password.json().has("auth"))
        val properties = MailAdapter.oauth(Properties())
        assertEquals("XOAUTH2", properties.getProperty("mail.imaps.auth.mechanisms"))
        assertEquals("XOAUTH2", properties.getProperty("mail.smtp.auth.mechanisms"))
        assertTrue(runCatching { config.copy(clientId = "x").validate() }.isFailure)
    }
}
