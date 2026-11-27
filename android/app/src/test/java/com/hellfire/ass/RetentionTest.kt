package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.Retention
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.news.NewsEvent
import com.hellfire.ass.news.NewsEventState
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.tasks.AssistantTask
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RetentionTest {
    private lateinit var db: EventDatabase
    private lateinit var app: HellFireApp
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        ReflectionHelpers.setField(app, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
    }
    @After fun close() = db.close()

    private suspend fun event(id: String, kind: String, at: Long, source: String = "android_notification") =
        db.events().insertCaptured(SourceEvent(id, source = source, externalId = id, packageName = "fixture", occurredAt = at, receivedAt = at, kind = kind,
            payloadHash = id, encryptedPayload = app.cipher.encrypt("{\"fields\":{\"text\":[\"Synthetic $id\"]}}".toByteArray()),
            contentAvailable = true, contentRedacted = false, securitySensitive = false), "Synthetic $id")
    private fun story(id: String, updated: Long) = NewsEvent(id, "Headline $id", "OTHER", "global", signature = id, firstSeenAt = updated, updatedAt = updated, lastArticleAt = updated)

    @Test fun `daily prune keeps anything linked or saved`() = runBlocking {
        event("old-removal", "REMOVED", now - 40 * day)
        event("linked-removal", "REMOVED", now - 40 * day)
        db.tasks().insert(AssistantTask("t", "linked-removal", "Task", now, party = "OWNER", evidence = "x", createdAt = 1, updatedAt = 1))
        event("recent-removal", "REMOVED", now - 5 * day)
        event("old-posted", "POSTED", now - 40 * day)
        db.news().saveEvent(story("old-story", now - 200 * day))
        db.news().saveEvent(story("saved-story", now - 200 * day))
        db.news().saveState(NewsEventState("saved-story", bookmarked = true, updatedAt = 1))
        db.news().saveEvent(story("new-story", now - 10 * day))

        assertEquals(2, Retention.prune(db, now)) // one removal record, one story
        assertNull(db.events().find("old-removal"))
        assertTrue(db.events().search("old*", 10).none { it.eventId == "old-removal" })
        for (kept in listOf("linked-removal", "recent-removal", "old-posted")) assertNotNull(kept, db.events().find(kept))
        assertNull(db.news().event("old-story"))
        assertNotNull(db.news().event("saved-story")); assertNotNull(db.news().event("new-story"))
    }
    @Test fun `owner delete removes a record, or clears its text when something was made from it`() = runBlocking {
        event("alone", "POSTED", now)
        event("evidence", "POSTED", now)
        db.tasks().insert(AssistantTask("t2", "evidence", "Task", now, party = "OWNER", evidence = "x", createdAt = 1, updatedAt = 1))
        assertTrue(Retention.deleteRecord(app, "alone"))
        assertNull(db.events().find("alone"))
        assertFalse(Retention.deleteRecord(app, "evidence"))
        val kept = db.events().find("evidence")!!
        assertFalse(kept.contentAvailable)
        assertFalse(String(app.cipher.decrypt(kept.encryptedPayload)).contains("Synthetic"))
        assertTrue(db.events().search("synthetic*", 10).isEmpty())
    }
}
