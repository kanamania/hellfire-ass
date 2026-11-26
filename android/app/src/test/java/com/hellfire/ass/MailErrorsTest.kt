package com.hellfire.ass

import com.hellfire.ass.communications.MailErrors
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException

class MailErrorsTest {
    @Test fun `expired app password is not reported as a network outage`() {
        assertEquals("AUTHENTICATION_FAILED_CHECK_APP_PASSWORD", MailErrors.code(AuthenticationFailedException("[AUTHENTICATIONFAILED] Invalid credentials")))
        assertEquals("NETWORK_UNAVAILABLE", MailErrors.code(MessagingException("connect failed", java.net.UnknownHostException("imap.gmail.com"))))
        assertEquals("TLS_FAILED_CHECK_SERVER", MailErrors.code(MessagingException("handshake", javax.net.ssl.SSLHandshakeException("bad cert"))))
        assertEquals("CONNECTION_FAILED", MailErrors.code(MessagingException("closed", java.io.EOFException())))
        assertEquals("CONNECTION_OR_CREDENTIALS_UNAVAILABLE", MailErrors.code(IllegalStateException("other")))
    }
}
