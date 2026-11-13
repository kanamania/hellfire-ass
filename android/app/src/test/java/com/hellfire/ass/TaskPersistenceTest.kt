package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.*
import com.hellfire.ass.tasks.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaskPersistenceTest {
    private lateinit var db: EventDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build() }
    @After fun close() = db.close()
    @Test fun `extracted review candidates never become due alarms until approved`() = runBlocking {
        db.events().insertCaptured(SourceEvent("source", externalId="fixture", packageName="fixture", occurredAt=1, receivedAt=1,
            kind="INCOMING", payloadHash="fixture", encryptedPayload=byteArrayOf(1), contentAvailable=true, contentRedacted=false, securitySensitive=false))
        db.tasks().insert(AssistantTask("task", "source", "Synthetic commitment", 1000, party="UNKNOWN", evidence="Synthetic", createdAt=1, updatedAt=1))
        assertNull(db.tasks().nextAlert())
        assertTrue(db.tasks().due(5000).isEmpty())
        db.tasks().activate("task", "Reviewed commitment", 1000, "OWNER", 500, 1)
        assertEquals(1, db.tasks().due(5000).size)
        db.tasks().snooze("task", 10000, 5000)
        assertTrue(db.tasks().due(5000).isEmpty())
        db.tasks().close("task", "DONE", 5000)
        assertNull(db.tasks().nextAlert())
        assertTrue(runCatching { db.events().deleteAll() }.isFailure)
    }
    @Test fun `nightly review remains pending until explicit completion`() = runBlocking {
        db.tasks().review(DailyReview("2026-11-13", createdAt=1))
        db.tasks().review(DailyReview("2026-11-13", createdAt=2))
        assertEquals(1, db.tasks().reviews().first().size)
        assertTrue(db.tasks().reviewedOrPrompted("2026-11-13"))
        db.tasks().completeReview("2026-11-13", 3)
        assertTrue(db.tasks().reviews().first().isEmpty())
    }
}
