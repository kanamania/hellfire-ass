package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.*
import com.hellfire.ass.events.*
import com.hellfire.ass.processing.*
import com.hellfire.ass.security.PayloadCipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureProcessingTest {
    private lateinit var database: EventDatabase
    private lateinit var cipher: PayloadCipher
    private lateinit var repository: EventRepository
    private val raw = NotificationSnapshot(key = "fixture", packageName = "synthetic.bank",
        postedAt = 100, receivedAt = 200, fields = mapOf("text" to listOf("Umelipa TZS 15000 kwa duka")))
    private val own = "com.hellfire.ass"

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        repository = EventRepository(database.events()) { cipher }
    }
    @After fun close() { database.close() }
    private fun id(snapshot: NotificationSnapshot = raw) = NotificationNormalizer.normalize(snapshot, own)!!.id

    @Test fun `capture creates a pending job and unique capture audit atomically`() = runBlocking {
        repository.capture(raw, own)
        repository.capture(raw, own)
        assertEquals("PENDING", database.events().processing(id())!!.state)
        assertEquals(1, database.events().audits().first().single().captured)
        assertEquals(1, database.events().queueSummary().first().single().count)
    }
    @Test fun `failure to create a job rolls back source capture`() = runBlocking {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_jobs BEFORE INSERT ON event_processing BEGIN SELECT RAISE(ABORT, 'synthetic rejection'); END")
        var rejected = false
        try { repository.capture(raw, own) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, database.events().count().first())
        assertTrue(database.events().queueSummary().first().isEmpty())
        assertTrue(database.events().audits().first().isEmpty())
    }
    @Test fun `processing is idempotent and leaves source evidence unchanged`() = runBlocking {
        repository.capture(raw, own)
        val before = database.events().find(id())!!
        val processor = EventProcessor(database.events()) { cipher }
        processor.drain()
        processor.drain()
        val job = database.events().processing(id())!!
        assertEquals("DONE", job.state)
        assertEquals("FINANCE_CANDIDATE", job.label)
        assertEquals(1, job.attempts)
        val after = database.events().find(id())!!
        assertArrayEquals(before.encryptedPayload, after.encryptedPayload)
        assertEquals(before.payloadHash, after.payloadHash)
        assertEquals(before.processingStatus, after.processingStatus)
    }
    @Test fun `capture-only policy retains evidence without decrypting or classifying`() = runBlocking {
        database.events().setPolicy(NotificationPolicy(raw.packageName, false))
        repository.capture(raw, own)
        EventProcessor(database.events()) { error("Must not load a key for capture-only") }.drain()
        assertEquals(1, database.events().count().first())
        assertEquals("CAPTURE_ONLY", database.events().processing(id())!!.state)
        assertNull(database.events().processing(id())!!.label)
    }
    @Test fun `new packages default to processing all notifications`() = runBlocking {
        repository.capture(raw.copy(packageName = "any.unknown.app"), own)
        EventProcessor(database.events()) { cipher }.drain()
        assertEquals("DONE", database.events().queueSummary().first().single().state)
    }
    @Test fun `key errors persist failure and can be explicitly retried`() = runBlocking {
        repository.capture(raw, own)
        EventProcessor(database.events()) { error("Synthetic key unavailable") }.drain()
        var job = database.events().processing(id())!!
        assertEquals("FAILED", job.state)
        assertEquals("PAYLOAD_OR_KEY_UNAVAILABLE", job.error)
        assertFalse(job.error!!.contains("Synthetic"))
        database.events().retryFailed()
        EventProcessor(database.events()) { cipher }.drain()
        job = database.events().processing(id())!!
        assertEquals("DONE", job.state)
        assertEquals(2, job.attempts)
        assertNull(job.error)
    }
    @Test fun `security redaction does not enter general classification`() = runBlocking {
        val security = raw.copy(fields = mapOf("text" to listOf("OTP 482619")))
        repository.capture(security, own)
        EventProcessor(database.events()) { error("Security content is never decrypted by classifier") }.drain()
        assertEquals("SECURITY_REDACTED", database.events().processing(id(security))!!.label)
    }
    @Test fun `empty image-only content is explicitly unavailable`() = runBlocking {
        val empty = raw.copy(fields = emptyMap())
        repository.capture(empty, own)
        EventProcessor(database.events()) { cipher }.drain()
        assertEquals("CONTENT_UNAVAILABLE", database.events().processing(id(empty))!!.label)
    }
    @Test fun `deletion cascades to jobs and does not recreate evidence`() = runBlocking {
        repository.capture(raw, own)
        database.events().deleteAll()
        EventProcessor(database.events()) { cipher }.drain()
        assertEquals(0, database.events().count().first())
        assertTrue(database.events().queueSummary().first().isEmpty())
    }
    @Test fun `pending jobs resume after an actual database close and reopen`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "synthetic-restart.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, EventDatabase::class.java, name).build()
        try {
            EventRepository(disk.events()) { cipher }.capture(raw, own)
            disk.close()
            disk = Room.databaseBuilder(context, EventDatabase::class.java, name).build()
            EventProcessor(disk.events()) { cipher }.drain()
            assertEquals("DONE", disk.events().processing(id())!!.state)
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun `notification storm replays preserve every committed distinct version`() = runBlocking {
        // Anonymous host fixtures, not device-delivery or throughput claims.
        for (i in 0 until 500) {
            val snapshot = raw.copy(key = "synthetic-$i")
            repository.capture(snapshot, own)
            repository.capture(snapshot.copy(receivedAt = 999), own)
        }
        assertEquals(500, database.events().count().first())
        assertEquals(500, database.events().audits().first().single().captured)
        EventProcessor(database.events()) { cipher }.drain()
        val summary = database.events().queueSummary().first().single()
        assertEquals("DONE", summary.state)
        assertEquals(500, summary.count)
    }
    @Test fun `audit counters survive failures without storing exception content`() = runBlocking {
        database.events().failure("android_notification", "CAPTURE_QUEUE_OVERFLOW", 5)
        val audit = database.events().audits().first().single()
        assertEquals(5, audit.dropped)
        assertEquals(1, audit.failures)
        assertEquals("CAPTURE_QUEUE_OVERFLOW", audit.lastError)
    }
}
