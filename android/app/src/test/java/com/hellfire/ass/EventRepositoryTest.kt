package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.*
import com.hellfire.ass.events.*
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
class EventRepositoryTest {
    private lateinit var database: EventDatabase
    private lateinit var cipher: PayloadCipher
    private lateinit var repository: EventRepository
    private val raw = NotificationSnapshot(key = "fixture-key", packageName = "fixture.app", postedAt = 100,
        receivedAt = 101, fields = mapOf("title" to listOf("Synthetic hello")))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        repository = EventRepository(database.events()) { cipher }
    }
    @After fun close() { database.close() }

    @Test fun `replay is idempotent in actual SQLite`() = runBlocking {
        assertTrue(repository.capture(raw, "com.hellfire.ass"))
        assertFalse(repository.capture(raw.copy(receivedAt = 999), "com.hellfire.ass"))
        assertEquals(1, repository.count.first())
    }
    @Test fun `concurrent duplicate inserts keep one event`() = runBlocking {
        coroutineScope {
            (1..12).map { async(Dispatchers.IO) { repository.capture(raw, "com.hellfire.ass") } }.awaitAll()
        }
        assertEquals(1, repository.count.first())
    }
    @Test fun `edits and removals preserve immutable events`() = runBlocking {
        repository.capture(raw, "com.hellfire.ass")
        repository.capture(raw.copy(fields = mapOf("title" to listOf("Edited fixture"))), "com.hellfire.ass")
        repository.capture(raw.copy(kind = "REMOVED", removedReason = 2), "com.hellfire.ass")
        assertEquals(3, repository.count.first())
        val id = NotificationNormalizer.normalize(raw, "com.hellfire.ass")!!.id
        val stored = database.events().find(id)!!
        assertTrue(cipher.decrypt(stored.encryptedPayload).toString(Charsets.UTF_8).contains("Synthetic hello"))
    }
    @Test fun `OTP never reaches persisted payload even when decrypted`() = runBlocking {
        val security = raw.copy(fields = mapOf("title" to listOf("Your OTP"), "text" to listOf("923751")))
        repository.capture(security, "com.hellfire.ass")
        val stored = database.events().find(NotificationNormalizer.normalize(security, "com.hellfire.ass")!!.id)!!
        assertTrue(stored.securitySensitive)
        assertTrue(stored.contentRedacted)
        assertFalse(cipher.decrypt(stored.encryptedPayload).toString(Charsets.UTF_8).contains("923751"))
    }
    @Test fun `own notifications never reach Room`() = runBlocking {
        assertFalse(repository.capture(raw.copy(packageName = "com.hellfire.ass"), "com.hellfire.ass"))
        assertEquals(0, repository.count.first())
    }
    @Test fun `missing content persists without inventing a body`() = runBlocking {
        val empty = raw.copy(fields = emptyMap())
        repository.capture(empty, "com.hellfire.ass")
        val stored = database.events().find(NotificationNormalizer.normalize(empty, "com.hellfire.ass")!!.id)!!
        assertFalse(stored.contentAvailable)
        assertTrue(cipher.decrypt(stored.encryptedPayload).toString(Charsets.UTF_8).contains("UNAVAILABLE"))
    }
    @Test fun `encryption failure does not fall back to plaintext`() = runBlocking {
        val failed = EventRepository(database.events()) { error("Synthetic key failure") }
        try { failed.capture(raw, "com.hellfire.ass"); fail("Must reject failed encryption") }
        catch (_: IllegalStateException) { }
        assertEquals(0, repository.count.first())
    }
}
