package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchPhraseTest {
    @Test fun `noFullScanWhenIndexHasMatches - phrase queries need adjacent words`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        try {
            fun event(id: String) = SourceEvent(id, externalId = id, packageName = "fixture", occurredAt = 1, receivedAt = 1, kind = "INCOMING",
                payloadHash = id, encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false)
            db.events().insertCaptured(event("a"), "Asha: the invoice is due, Friday!")
            db.events().insertCaptured(event("b"), "Friday we discuss the invoice that is due")
            assertEquals(listOf("a"), db.events().search("\"invoice is due friday\"", 20).map { it.eventId })
            assertEquals(setOf("a", "b"), db.events().search("invoice due", 20).map { it.eventId }.toSet())
        } finally { db.close() }
    }
}
