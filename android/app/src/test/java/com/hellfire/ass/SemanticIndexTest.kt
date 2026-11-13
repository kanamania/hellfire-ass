package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.media.LocalImageLabels
import com.hellfire.ass.memory.SemanticVector
import com.hellfire.ass.memory.VectorMath
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SemanticIndexTest {
    private lateinit var db: EventDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build() }
    @After fun close() = db.close()
    private suspend fun source(id: String, received: Long, available: Boolean = true, sensitive: Boolean = false) =
        db.events().insertCaptured(SourceEvent(id, externalId = id, packageName = "fixture", occurredAt = received, receivedAt = received,
            kind = "POSTED", payloadHash = id, encryptedPayload = byteArrayOf(1), contentAvailable = available, contentRedacted = false, securitySensitive = sensitive))

    @Test fun `vectors normalize, round-trip and rank by cosine`() {
        val a = VectorMath.normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), a, 1e-6f)
        assertArrayEquals(a, VectorMath.decode(VectorMath.encode(a)), 0f)
        assertEquals(0, VectorMath.normalize(floatArrayOf(0f, 0f)).size)
        assertEquals(1f, VectorMath.dot(a, a), 1e-6f)
        assertTrue(runCatching { VectorMath.dot(a, floatArrayOf(1f)) }.isFailure)
        assertTrue(runCatching { VectorMath.decode(byteArrayOf(1, 2, 3)) }.isFailure)
        val ranked = VectorMath.top(2, sequenceOf("low" to 0.1f, "best" to 0.9f, "mid" to 0.5f, "neg" to -0.4f))
        assertEquals(listOf("best", "mid"), ranked.map { it.first })
    }
    @Test fun `pending excludes sensitive, unavailable and already indexed sources for the current model`() = runBlocking {
        source("old", 1); source("new", 2); source("otp", 3, sensitive = true); source("gone", 4, available = false)
        assertEquals(listOf("new", "old"), db.semantic().pending("m1", 10).map { it.id })
        db.semantic().put(listOf(SemanticVector("new", "m1", VectorMath.encode(floatArrayOf(1f, 0f)), 5),
            SemanticVector("old", "m1", ByteArray(0), 5)))
        assertTrue(db.semantic().pending("m1", 10).isEmpty())
        assertEquals(1, db.semantic().indexedCount("m1"))            // empty vector = no text, never searched
        assertEquals(listOf("new"), db.semantic().page("m1", "", 10).map { it.sourceId })
        assertEquals(2, db.semantic().pendingCount("m2"))            // a new model re-indexes everything
        db.semantic().dropOtherModels("m2")
        assertEquals(0, db.semantic().indexedCount("m1"))
    }
    @Test fun `vectors cascade with their source and pages are cursor ordered`() = runBlocking {
        for (id in listOf("a", "b", "c")) { source(id, 1); db.semantic().put(listOf(SemanticVector(id, "m", VectorMath.encode(floatArrayOf(1f)), 1))) }
        assertEquals(listOf("b", "c"), db.semantic().page("m", "a", 10).map { it.sourceId })
        db.events().deleteAll()
        assertEquals(0, db.semantic().indexedCount("m"))
    }
    @Test fun `image labels keep confident labels only and say they are unverified`() {
        assertNull(LocalImageLabels.describe(listOf("Cat" to 0.5f)))
        assertEquals("On-device image labels (unverified): Beach 93%, Sky 71%",
            LocalImageLabels.describe(listOf("Sky" to 0.71f, "Beach" to 0.93f, "Dog" to 0.2f)))
    }
}
