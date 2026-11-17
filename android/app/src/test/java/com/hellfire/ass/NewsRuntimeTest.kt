package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.news.*
import com.hellfire.ass.security.BackupTables
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NewsRuntimeTest {
    private val zone = ZoneId.of("Africa/Dar_es_Salaam")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun `scheduled slots preserve owner times including extra briefings`() {
        val settings = NewsSettings(extra = listOf("12:30", "15:00"))
        val morning = NewsWork.nextBriefing(settings, at("2026-11-17T03:00:00Z"))
        assertEquals("MORNING", morning.slot)
        assertEquals(at("2026-11-17T04:00:00Z"), morning.at)
        val midday = NewsWork.nextBriefing(settings, morning.at)
        assertEquals("CUSTOM", midday.slot)
        assertEquals(at("2026-11-17T09:30:00Z"), midday.at)
        assertEquals(at("2026-11-17T12:00:00Z"), NewsWork.nextBriefing(settings, midday.at).at)
        val tomorrow = NewsWork.nextBriefing(settings, at("2026-11-17T17:00:00Z"))
        assertEquals("MORNING", tomorrow.slot)
        assertEquals(at("2026-11-18T04:00:00Z"), tomorrow.at)
    }

    @Test fun `news questions separate greetings finances and saved news windows`() {
        val now = at("2026-11-17T09:00:00Z")
        assertNull(NewsAsk.parse("habari yako", now, zone))
        assertNull(NewsAsk.parse("spending today", now, zone))
        val sw = NewsAsk.parse("Habari muhimu Tanzania leo", now, zone)!!
        assertTrue(sw.important); assertEquals(setOf("tanzania"), sw.places)
        assertEquals(at("2026-11-16T21:00:00Z"), sw.from)
        assertTrue(NewsAsk.parse("What changed in the story I heard yesterday?", now, zone)!!.changed)
        assertTrue(NewsAsk.parse("What are people on X saying about technology?", now, zone)!!.social)
        assertEquals(0L, NewsAsk.parse("show saved news", now, zone)!!.from)
    }

    @Test fun `quiet hours only allow opted in critical public safety overrides`() {
        val now = at("2026-11-17T20:00:00Z")
        val event = NewsEvent("flood", "Flood warning", "DISASTER", "tanzania", signature = "flood",
            firstSeenAt = now, updatedAt = now, lastArticleAt = now, level = "CRITICAL", protectedPublic = true)
        assertFalse(NewsAlerts.decide(event, null, null, NewsSettings(), now)!!.quiet)
        assertTrue(NewsAlerts.decide(event, null, null, NewsSettings(criticalOverridesQuiet = false), now)!!.quiet)
        assertTrue(NewsAlerts.decide(event.copy(protectedPublic = false), null, null, NewsSettings(), now)!!.quiet)
        assertNull(NewsAlerts.decide(event, null, null, NewsSettings(breakingAlerts = false), now))
    }

    @Test fun `audio queue resumes within the saved story and respects noncontinuous listening`() {
        val stories = listOf(SpokenStory("a", "First", "First sentence. Second sentence."), SpokenStory("b", "Second", "Another story."))
        val queue = NewsAudioQueue(stories)
        assertEquals(NewsAudioQueue.Step.SENTENCE, queue.advance(false))
        val ms = queue.audioMs(1f)
        assertEquals("Second sentence.", NewsAudioQueue.resume(stories, 0, ms, 1f).currentSentence())
        assertEquals("Another story.", NewsAudioQueue.resume(stories, 1, ms, 1f).currentSentence())
        assertEquals(NewsAudioQueue.Step.END, queue.advance(false))
        assertTrue(queue.finished)
    }

    @Test fun `conditional feeds avoid parsing a 304 and x without credentials makes no request`() {
        val source = NewsSource("fixture", "Fixture", "RSS", "https://news.example/feed", "CUSTOM", "en", 60,
            etag = "cached", createdAt = 1, updatedAt = 1)
        val feed = FeedConnector(NewsHttp { url, headers, method ->
            assertEquals("GET", method); assertEquals("cached", headers["If-None-Match"])
            NewsResponse(304, byteArrayOf(), emptyMap(), url)
        }).fetch(source, 100)
        assertTrue(feed.notModified); assertTrue(feed.items.isEmpty())
        val x = XConnector(NewsHttp { _, _, _ -> error("Must not request without a token") }) { null }
            .fetch(source.copy(kind = "X_ACCOUNT", url = "fixture"), 100)
        assertEquals("NEEDS_KEY", x.source.state)
    }

    @Test fun `schema 18 library upgrades to news 19 with portable table registration`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "news-migration-fixture.db"
        MigrationFixture.create(context, name, 18) { old ->
            old.execSQL("INSERT INTO media_collection (id,name,createdAt,updatedAt) VALUES ('saved','Saved',1,1)")
        }
        val db = Room.databaseBuilder(context, EventDatabase::class.java, name).addMigrations(*EventDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals(19, db.openHelper.writableDatabase.version)
            assertEquals("Saved", db.library().collections().let { kotlinx.coroutines.flow.first(it) }.single().name)
            db.news().saveSource(NewsSource("fixture", "Fixture", "RSS", "https://news.example/feed", "CUSTOM", "en", 60, createdAt = 1, updatedAt = 1))
            assertEquals("Fixture", db.news().source("fixture")!!.name)
            assertEquals(NewsSchema.tables.toSet(), (BackupTables.tablesFor(19) - BackupTables.tablesFor(18).toSet()).toSet())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
