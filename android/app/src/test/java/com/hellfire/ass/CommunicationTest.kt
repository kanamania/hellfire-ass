package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.communications.*
import com.hellfire.ass.data.EventDatabase
import javax.mail.Session
import javax.mail.internet.MimeMessage
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CommunicationTest {
    private lateinit var db: EventDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build() }
    @After fun close() = db.close()
    @Test fun `external sends require fresh approval and a single atomic claim`() = runBlocking {
        val dao = db.communications()
        dao.draft(OutboundDraft("fixture", "EMAIL_IMAP", "account", "fixture@example.invalid", "Subject", "Synthetic", 1))
        assertEquals(0, dao.claim("fixture", "nonce", 0))
        assertEquals(1, dao.approve("fixture", "nonce", 100))
        assertEquals(0, dao.claim("fixture", "wrong", 0))
        assertEquals(0, dao.claim("fixture", "nonce", 101))
        assertEquals(1, dao.claim("fixture", "nonce", 99))
        assertEquals(0, dao.claim("fixture", "nonce", 99))
    }
    @Test fun `WhatsApp out of order statuses never downgrade read or delivered`() = runBlocking {
        val dao = db.communications()
        dao.draft(OutboundDraft("fixture", "WHATSAPP_BUSINESS", "account", "255700000000", "", "Synthetic", 1))
        dao.delivery("fixture", "SUBMITTED", "external", null)
        dao.whatsappStatus("external", "DELIVERED")
        dao.whatsappStatus("external", "SENT_CONFIRMED")
        assertEquals("DELIVERED", dao.draft("fixture")!!.state)
        dao.whatsappStatus("external", "READ")
        dao.whatsappStatus("external", "DELIVERED")
        assertEquals("READ", dao.draft("fixture")!!.state)
    }
    @Test fun `MIME alternatives prefer plain text and decode Unicode safely`() {
        val raw = """From: fixture@example.invalid
To: owner@example.invalid
Subject: Synthetic MIME
MIME-Version: 1.0
Content-Type: multipart/alternative; boundary="fixture"

--fixture
Content-Type: text/plain; charset=UTF-8

Habari, synthetic text.
--fixture
Content-Type: text/html; charset=UTF-8

<b>HTML alternative</b>
--fixture--
""".replace("\n", "\r\n")
        val message = MimeMessage(Session.getInstance(Properties()), raw.byteInputStream())
        val text = MailMime.text(message)
        assertTrue(text.contains("Habari"))
        assertFalse(text.contains("HTML alternative"))
    }
    @Test fun `mail configuration rejects insecure ports and header injection`() {
        val good = MailConfig("fixture", "owner@example.invalid", "synthetic-password", "imap.example.invalid", "smtp.example.invalid", 587)
        good.validate()
        assertTrue(runCatching { good.copy(smtpPort = 25).validate() }.isFailure)
        assertTrue(runCatching { good.copy(address = "owner@example.invalid\r\nBcc: hidden@example.invalid").validate() }.isFailure)
        assertTrue(runCatching { good.copy(imapHost = "https://not-an-imap-host").validate() }.isFailure)
    }
    @Test fun `UIDVALIDITY checkpoints are scoped to account and folder`() = runBlocking {
        val dao = db.communications()
        dao.cursor(MailCursor("a", "INBOX", 1, 10))
        dao.cursor(MailCursor("a", "Sent", 1, 20))
        dao.cursor(MailCursor("b", "INBOX", 2, 5))
        dao.cursor(MailCursor("a", "INBOX", 3, 1))
        assertEquals(3, dao.cursor("a", "INBOX")!!.uidValidity)
        assertEquals(20, dao.cursor("a", "Sent")!!.lastUid)
        assertEquals(5, dao.cursor("b", "INBOX")!!.lastUid)
    }
    @Test fun `deep MIME nesting fails within a bounded traversal`() {
        val message = MimeMessage(Session.getInstance(Properties()))
        var part = javax.mail.internet.MimeBodyPart().apply { setText("Synthetic deepest text") }
        repeat(14) {
            val multi = javax.mail.internet.MimeMultipart().apply { addBodyPart(part) }
            part = javax.mail.internet.MimeBodyPart().apply { setContent(multi) }
        }
        val root = javax.mail.internet.MimeMultipart().apply { addBodyPart(part) }
        message.setContent(root); message.saveChanges()
        assertTrue(runCatching { MailMime.text(message) }.isFailure)
        assertTrue(runCatching { MailMime.attachments(message) }.isFailure)
    }

    @Test fun `process recovery revokes orphan approvals and marks in flight sends uncertain`() = runBlocking {
        val dao = db.communications()
        for (id in listOf("review", "pending", "inflight")) dao.draft(OutboundDraft(id, "EMAIL_IMAP", "account", "test@example.invalid", "", "Synthetic", 1))
        dao.approve("pending", "nonce", 100)
        dao.approve("inflight", "nonce", 100); dao.claim("inflight", "nonce", 0)
        dao.recoverAfterProcessStart()
        assertEquals("DRAFT", dao.draft("pending")!!.state)
        assertNull(dao.draft("pending")!!.approvalId)
        assertEquals("UNKNOWN", dao.draft("inflight")!!.state)
        assertEquals("DRAFT", dao.draft("review")!!.state)
        assertEquals(0, dao.claim("pending", "nonce", 0))
    }

}
