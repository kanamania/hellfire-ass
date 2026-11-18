package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hellfire.ass.communications.*
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.security.PayloadCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Real TLS/IMAP/SMTP to a non-forwarding localhost fixture, never a real account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailProtocolFixtureTest {
    @Test fun tlsAuthenticationCaptureReplayAndSecurityRedactionUseRealProtocols() = runBlocking {
        val truststore = System.getenv("HELLFIRE_MAIL_FIXTURE_TRUSTSTORE")
        assumeTrue("Run tools/test_mail_fixture.sh for the disposable protocol server", truststore != null)
        val context = RuntimeEnvironment.getApplication()
        val app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        val db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        ReflectionHelpers.setField(app, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        val previous = SSLContext.getDefault()
        val config = MailConfig("fixture", "fixture@localhost", "synthetic-pass", "localhost", "localhost", 465)
        val adapter = MailAdapter(app)
        fun draft(id: String, subject: String, body: String) = OutboundDraft(id, "EMAIL_IMAP", "fixture", "fixture@localhost", subject, body, 1)
        try {
            assertTrue("Untrusted fixture certificate was accepted", runCatching {
                adapter.send(config, draft("untrusted", "Synthetic", "Synthetic fixture"))
            }.isFailure)
            val store = KeyStore.getInstance("PKCS12").apply { File(truststore!!).inputStream().use { load(it, "synthetic-fixture-only".toCharArray()) } }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            SSLContext.setDefault(SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) })
            assertTrue("Certificate hostname mismatch was accepted", runCatching {
                adapter.send(config.copy(smtpHost = "127.0.0.1"), draft("wrong-host", "Synthetic", "Synthetic fixture"))
            }.isFailure)
            assertTrue("Wrong fixture password was accepted", runCatching {
                adapter.send(config.copy(password = "wrong-fixture-password"), draft("wrong-auth", "Synthetic", "Synthetic fixture"))
            }.isFailure)
            assertEquals("<bilingual@localhost>", adapter.send(config, draft("bilingual", "Synthetic bilingual fixture", "Habari dunia. Invoice TZS 15,000.00.")))
            assertFalse(adapter.sync(config))
            val messages = db.communications().messages().first()
            assertEquals(1, messages.size)
            assertTrue(messages.single().body.contains("Habari dunia"))
            assertTrue(messages.single().body.contains("15,000.00"))
            assertNotNull(db.communications().cursor("fixture", "INBOX"))
            assertFalse(adapter.sync(config))
            assertEquals(1, db.communications().messages().first().size)
            adapter.send(config, draft("security", "Your verification code", "Your OTP is 123456"))
            assertFalse(adapter.sync(config))
            val redacted = db.communications().messages().first().single { it.externalId.contains("security") }
            assertFalse(redacted.body.contains("123456"))
            assertTrue(redacted.body.contains("REDACTED"))
            val event = db.events().find(redacted.sourceId)!!
            assertTrue(event.securitySensitive)
            assertTrue(event.contentRedacted)
            assertFalse(event.encryptedPayload.toString(Charsets.UTF_8).contains("123456"))
        } finally { SSLContext.setDefault(previous); db.close() }
    }
}
