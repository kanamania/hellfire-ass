package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.communications.Accounts
import com.hellfire.ass.communications.MailConfig
import com.hellfire.ass.data.*
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.finance.*
import com.hellfire.ass.media.ChunkedMedia
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.security.SecretStore
import com.hellfire.ass.sms.SmsSyncWorker
import com.hellfire.ass.tasks.SwahiliTime
import com.hellfire.ass.tasks.TaskExtractor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import javax.crypto.KeyGenerator

/** Regression tests for the 2026-11-16 review findings and multi-account storage. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReviewFixesTest {
    private lateinit var db: EventDatabase
    private val cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    private val own = "com.hellfire.ass"
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build() }
    @After fun close() = db.close()

    private fun sms(row: Long, text: String) = NotificationSnapshot("sms-provider:$row", "android.provider.Telephony.Sms", 1_000, 2_000,
        source = "android_sms", kind = "INCOMING", fields = mapOf("text" to listOf(text), "sender_hint" to listOf("M-PESA")),
        provenance = mapOf("provider_row_id" to row.toString()))
    private fun text(raw: NotificationSnapshot) = NotificationNormalizer.normalize(raw, own)!!.snapshot.fields.getValue("text").single()

    @Test fun `provider-keyed identity does not depend on redacted text`() {
        val a = NotificationNormalizer.normalize(sms(7, "Umelipa TZS 5,000"), own)!!
        val b = NotificationNormalizer.normalize(sms(7, "Umelipa TZS 5,000 (rules changed)"), own)!!
        assertEquals(a.id, b.id); assertEquals(a.externalId, b.externalId)
        assertNotEquals(a.id, a.legacyId); assertNotEquals(a.legacyId, b.legacyId)
        assertNotEquals(a.id, NotificationNormalizer.normalize(sms(8, "Umelipa TZS 5,000"), own)!!.id)
        // Notifications keep content identity: an edited notification is new evidence.
        val note = NotificationNormalizer.normalize(sms(7, "x").copy(source = "android_notification"), own)!!
        assertEquals(note.id, note.legacyId)
    }

    @Test fun `events captured under the old identity are reused instead of duplicated`() = runBlocking {
        val repository = EventRepository(db.events()) { cipher }
        val raw = sms(9, "Umelipa TZS 7,000 kwa duka")
        val normalized = NotificationNormalizer.normalize(raw, own)!!
        db.events().insertCaptured(SourceEvent(normalized.legacyId, source = "android_sms", externalId = "legacy", packageName = raw.packageName,
            occurredAt = 1_000, receivedAt = 2_000, kind = "INCOMING", payloadHash = normalized.payloadHash, encryptedPayload = cipher.encrypt(byteArrayOf(1)),
            contentAvailable = true, contentRedacted = false, securitySensitive = false))
        assertEquals(EventRepository.Stored(normalized.legacyId, false), repository.store(raw, own))
        val fresh = sms(10, "Umelipa TZS 7,000 kwa duka")
        assertEquals(EventRepository.Stored(NotificationNormalizer.normalize(fresh, own)!!.id, true), repository.store(fresh, own))
    }

    @Test fun `redaction keeps unmarked amounts, dates and tills in finance messages but still removes codes`() {
        assertEquals("M-PESA: Umelipa 5000 kwa duka", text(sms(1, "M-PESA: Umelipa 5000 kwa duka")))
        assertEquals("Received 120000 TZS on 13/11/2026", text(sms(1, "Received 120000 TZS on 13/11/2026")))
        assertEquals("M-PESA Lipa namba 123456 paid", text(sms(1, "M-PESA Lipa namba 123456 paid")))
        assertEquals("Use [CODE REDACTED]", text(sms(1, "Use 432198")))
        assertEquals("Paid TZS 15000, note [CODE REDACTED]", text(sms(1, "Paid TZS 15000, note 432198")))
        // Without finance context a bare "paid 4321" stays redacted.
        assertEquals("paid [CODE REDACTED]", text(NotificationSnapshot("n", "chat.app", 1, 1, fields = mapOf("text" to listOf("paid 4321")))))
    }

    @Test fun `parser reads trailing currency and leading transaction ids`() {
        val parsed = FinanceParser.parse("MPESA", "QK12ABC3D4 Confirmed. 5,000 TZS paid to SHOP")!!
        assertEquals(5000L, parsed.amount); assertEquals("QK12ABC3D4", parsed.reference)
        assertNull(FinanceParser.parse("MPESA", "CONFIRMED Ksh paid nothing"))
    }

    private suspend fun source(id: String, kind: String, app: String = "fixture") = db.events().insertCaptured(SourceEvent(id, source = kind, externalId = id, packageName = app,
        occurredAt = 1, receivedAt = 1, kind = "POSTED", payloadHash = id, encryptedPayload = byteArrayOf(1), contentAvailable = true,
        contentRedacted = false, securitySensitive = false))

    @Test fun `an SMS and its notification become one ledger entry with both sources as evidence`() = runBlocking {
        val repo = FinanceRepository(db)
        db.finances().addAccount(FinanceAccount("a", "Wallet"))
        source("sms", "android_sms"); source("note", "android_notification")
        repo.collect("sms", 1_000, "MPESA", "M-PESA umelipa TSh 3,000 kwa duka")
        repo.collect("note", 61_000, "MPESA", "M-PESA umelipa TSh 3,000 kwa duka")
        val note = db.finances().candidatesFor("note").single()
        assertEquals("DUPLICATE", note.state); assertNotNull(note.duplicateOf)
        val posted = repo.approve(db.finances().candidatesFor("sms").single().id, "a", "EXPENSE", "Shop", "Shop")
        assertTrue(posted.startsWith("Transaction posted"))
        assertEquals("DUPLICATE_LINKED", db.finances().candidatesFor("note").single().state)
        assertEquals(setOf("sms", "note"), db.finances().sources(db.finances().entriesFor("sms").single().id).toSet())
        assertEquals(-3000L, db.finances().balance("a"))
    }

    @Test fun `two equal payments from the same kind of source both stay in review`() = runBlocking {
        val repo = FinanceRepository(db)
        source("one", "android_sms"); source("two", "android_sms"); source("late", "android_notification")
        repo.collect("one", 1_000, "MPESA", "M-PESA umelipa TSh 2,000 bodaboda")
        repo.collect("two", 2_000, "MPESA", "M-PESA umelipa TSh 2,000 bodaboda")
        repo.collect("late", 2_000 + FinanceRepository.CROSS_SOURCE_WINDOW + 1, "MPESA", "M-PESA umelipa TSh 2,000 bodaboda")
        assertEquals(listOf("REVIEW", "REVIEW", "REVIEW"), listOf("one", "two", "late").map { db.finances().candidatesFor(it).single().state })
    }

    @Test fun `one payment seen as an SMS and two app notifications leaves one review item`() = runBlocking {
        val repo = FinanceRepository(db)
        source("note", "android_notification", "com.sms.app"); source("sms", "android_sms"); source("push", "android_notification", "com.wallet")
        repo.collect("note", 1_000, "MPESA", "M-PESA umelipa TSh 4,000 sokoni")
        repo.collect("sms", 2_000, "MPESA", "M-PESA umelipa TSh 4,000 sokoni")
        repo.collect("push", 3_000, "MPESA", "M-PESA umelipa TSh 4,000 sokoni")
        val original = db.finances().candidatesFor("note").single()
        assertEquals(listOf(original), db.finances().reviewCandidates())
        assertEquals(listOf(original.id, original.id), listOf("sms", "push").map { db.finances().candidatesFor(it).single().duplicateOf })
    }

    @Test fun `two equal payments notified by the same app both stay in review`() = runBlocking {
        val repo = FinanceRepository(db)
        source("one", "android_notification", "com.wallet"); source("two", "android_notification", "com.wallet")
        repo.collect("one", 1_000, "MPESA", "M-PESA umelipa TSh 2,000 bodaboda")
        repo.collect("two", 2_000, "MPESA", "M-PESA umelipa TSh 2,000 bodaboda")
        assertEquals(2, db.finances().reviewCandidates().size)
    }

    @Test fun `rejecting a payment also clears its copies from review`() = runBlocking {
        val repo = FinanceRepository(db)
        source("sms", "android_sms"); source("note", "android_notification")
        repo.collect("sms", 1_000, "MPESA", "M-PESA umelipa TSh 9,000")
        repo.collect("note", 1_000, "MPESA", "M-PESA umelipa TSh 9,000")
        repo.reject(db.finances().candidatesFor("sms").single().id)
        assertEquals("REJECTED", db.finances().candidatesFor("note").single().state)
    }

    @Test fun `full-text search finds old sources, skips sensitive ones and is cleared with them`() = runBlocking {
        val repository = EventRepository(db.events()) { cipher }
        repository.capture(NotificationSnapshot("a", "chat", 1, 1, fields = mapOf("text" to listOf("Invoice for Asha's mkutano"))), own)
        repository.capture(NotificationSnapshot("b", "bank", 2, 2, fields = mapOf("text" to listOf("Your OTP for invoice is 1234"))), own)
        val hits = db.events().search(SearchText.query("invoice asha")!!, 20)
        assertEquals(1, hits.size); assertTrue(hits.single().text.contains("mkutano"))
        assertTrue(db.events().search(SearchText.query("invo")!!, 20).isNotEmpty())
        // FTS operators are upper-case words; lower-casing turns them into plain terms.
        assertEquals("or* near*", SearchText.query("\" OR * - NEAR"))
        assertNull(SearchText.query("\" * - x"))
        db.events().deleteAll()
        assertTrue(db.events().search(SearchText.query("invoice")!!, 20).isEmpty())
    }

    @Test fun `interrupted AAC recordings are recoverable and keep their format`() {
        val file = File.createTempFile("recording", ".hfm")
        val id = UUID.randomUUID().toString()
        try {
            val source = object : InputStream() {
                var calls = 0
                override fun read() = error("unused")
                override fun read(b: ByteArray, off: Int, len: Int): Int { if (calls++ > 0) throw IOException("interruption"); b.fill(7, off, off + 300); return 300 }
            }
            ChunkedMedia.write(source, file, id, false, cipher, retainInterrupted = true)
            RandomAccessFile(file, "rw").use { it.seek(33); it.writeLong(-1) }
            val recovered = ChunkedMedia.recoverRecording(file, cipher)!!
            assertEquals(300L, recovered.size); assertFalse(recovered.pcm)
        } finally { file.delete() }
    }

    @Test fun `task proposals need commitment wording and understand Swahili clock time`() {
        val source = Instant.parse("2026-11-13T06:00:00Z").toEpochMilli()
        assertNull(TaskExtractor.extract("Habari ya leo", source, false))
        assertNull(TaskExtractor.extract("Weather tomorrow: sunny", source, false))
        assertNotNull(TaskExtractor.extract("Weather tomorrow: sunny", source, false, requireCommitment = false))
        assertNotNull(TaskExtractor.extract("Tafadhali lipa kodi kesho", source, false))
        assertEquals(LocalTime.of(10, 0), SwahiliTime.find("saa nne asubuhi"))
        assertEquals(LocalTime.of(13, 30), SwahiliTime.find("saa saba na nusu mchana"))
        assertEquals(LocalTime.of(20, 0), SwahiliTime.find("saa mbili usiku"))
        assertEquals(LocalTime.of(5, 0), SwahiliTime.find("saa kumi na moja alfajiri"))
        assertEquals(LocalTime.of(16, 0), SwahiliTime.find("saa 10 jioni"))
        assertNull(SwahiliTime.find("saa ngapi"))
    }

    @Test fun `fresh SMS imports start shortly before the last imported row`() {
        assertEquals(0L, SmsSyncWorker.startAfter(0))
        assertEquals(0L, SmsSyncWorker.startAfter(150))
        assertEquals(9_800L, SmsSyncWorker.startAfter(10_000))
    }

    @Test fun `account lists migrate single accounts and keep ids stable`() {
        val context = RuntimeEnvironment.getApplication()
        val secrets = SecretStore(context) { cipher }
        val legacy = MailConfig("old", "owner@example.invalid", "pw", "imap.example.invalid", "smtp.example.invalid", 465)
        secrets.save("mail", legacy.json())
        assertEquals(listOf("old"), Accounts.mail(secrets).map { it.id })
        assertNull(secrets.read("mail"))
        val second = MailConfig(Accounts.mailId("Work@Example.invalid ", "IMAP.example.invalid"), "work@example.invalid", "pw", "imap.example.invalid", "smtp.example.invalid", 587)
        assertEquals(Accounts.mailId("work@example.invalid", "imap.example.invalid"), second.id)
        Accounts.save(secrets, Accounts.MAIL, second.json())
        Accounts.save(secrets, Accounts.MAIL, legacy.copy(password = "changed").json())
        assertEquals(listOf("old", second.id), Accounts.mail(secrets).map { it.id })
        assertEquals("changed", Accounts.mail(secrets).first().password)
        Accounts.save(secrets, Accounts.GATEWAY, JSONObject().put("id", "w").put("url", "https://web.example.invalid").put("token", "t").put("transport", "web"))
        Accounts.save(secrets, Accounts.GATEWAY, JSONObject().put("id", "m").put("label", "Shop").put("url", "https://meta.example.invalid").put("token", "t").put("transport", "meta"))
        assertEquals(listOf("m"), Accounts.sendingGateways(secrets).map { it.getString("id") })
        assertEquals(mapOf("old" to "owner@example.invalid", second.id to "work@example.invalid", "w" to "web.example.invalid", "m" to "Shop"), Accounts.labels(secrets))
        Accounts.remove(secrets, Accounts.MAIL, "old"); Accounts.remove(secrets, Accounts.MAIL, second.id)
        assertTrue(Accounts.mail(secrets).isEmpty()); assertTrue(Accounts.any(secrets))
        Accounts.remove(secrets, Accounts.GATEWAY, "w"); Accounts.remove(secrets, Accounts.GATEWAY, "m")
        assertFalse(Accounts.any(secrets))
    }

    @Test fun `diagnostics record exception classes and frames but never messages`() {
        val described = Diagnostics.describe(IllegalStateException("secret body text", IOException("owner@example.invalid")))
        assertTrue(described.contains("IllegalStateException")); assertTrue(described.contains("IOException"))
        assertFalse(described.contains("secret")); assertFalse(described.contains("owner@"))
    }
}
