package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.news.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NewsSyncTest {
    private lateinit var db: EventDatabase
    private val dao get() = db.news()
    private var seq = 0L

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        dao.saveEvent(NewsEvent("e1", "Floods in Dar", "DISASTER", "tanzania", signature = "s", firstSeenAt = 1_000, updatedAt = 1_000, lastArticleAt = 1_000, version = 3))
        dao.saveBriefing(NewsBriefing("MORNING:2026-11-17", "MORNING", "Morning", 1_000, 1_000, 0, 1_000, "[]"))
    }
    @After fun close() = db.close()

    private fun action(at: Long, field: String, value: Any, target: String = "e1") = NewsSync.Action(++seq, at, target, field, value)
    private fun apply(vararg actions: NewsSync.Action) = runBlocking { NewsSync.apply(dao, actions.toList()) }
    private fun state() = runBlocking { dao.state("e1")!! }

    @Test fun `flags follow the last writer per field`() = runBlocking {
        dao.saveState(NewsEventState("e1", bookmarked = true, followed = true, fieldTimes = JSONObject().put("bookmarked", 5_000).put("followed", 1_000).toString(), updatedAt = 5_000))
        // Older than the phone's own bookmark change: ignored. Newer than the follow change: applied.
        assertEquals(1, apply(action(4_000, "bookmarked", false), action(4_000, "followed", false)))
        assertTrue(state().bookmarked)
        assertFalse(state().followed)
        val times = JSONObject(state().fieldTimes)
        assertEquals(4_000L, times.getLong("followed"))
        assertEquals(5_000L, times.getLong("bookmarked"))
        assertEquals(5_000L, state().updatedAt)
        // A field without its own time falls back to updatedAt.
        assertEquals(0, apply(action(4_500, "readLater", true)))
        assertEquals(2, apply(action(6_000, "readLater", true), action(6_500, "hidden", true)))
        assertTrue(state().readLater)
        assertTrue(state().hidden)
        assertEquals(6_500L, state().updatedAt)
    }

    @Test fun `a choice for an event without local state creates it and unknown events are dropped`() {
        assertEquals(1, apply(action(2_000, "bookmarked", true), action(2_000, "bookmarked", true, target = "unknown")))
        assertTrue(state().bookmarked)
        assertNull(runBlocking { dao.state("unknown") })
    }

    @Test fun `the read version only grows and counts as delivered`() = runBlocking {
        dao.saveState(NewsEventState("e1", deliveredVersion = 1, seenVersion = 2, readAt = 900, updatedAt = 9_000))
        assertEquals(0, apply(action(10_000, "seen", 1)))
        assertEquals(2, state().seenVersion)
        assertEquals(900L, state().readAt)
        assertEquals(1, apply(action(3_000, "seen", 3))) // even an older web read raises it
        assertEquals(3, state().seenVersion)
        assertEquals(3, state().deliveredVersion)
        assertEquals(3_000L, state().readAt)
        assertEquals(0, apply(action(11_000, "seen", 2)))
        assertEquals(3, state().seenVersion)
    }

    @Test fun `briefing position is taken only when newer`() = runBlocking {
        val id = "MORNING:2026-11-17"
        val position = { p: Int, at: Long -> action(at, "position", JSONObject().put("position", p).put("audioMs", 1_500), target = id) }
        assertEquals(1, apply(position(4, 2_000)))
        assertEquals(NewsProgress(id, 4, 1_500, 2_000), dao.progress(id))
        dao.saveProgress(NewsProgress(id, 6, 0, 5_000))
        assertEquals(0, apply(position(2, 4_000)))
        assertEquals(6, dao.progress(id)!!.position)
        assertEquals(1, apply(position(1, 6_000)))
        assertEquals(1, dao.progress(id)!!.position)
        assertEquals(0, apply(position(1, 7_000).copy(target = "no-such-briefing")))
        assertEquals(0, apply(action(8_000, "bookmarked", "yes"), action(8_000, "deleted", true)))
    }
}
