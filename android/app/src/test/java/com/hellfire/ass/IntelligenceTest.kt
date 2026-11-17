package com.hellfire.ass

import android.app.Application
import android.os.PowerManager
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SearchText
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.data.SourceSearch
import com.hellfire.ass.intelligence.*
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.TranscriptSegment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IntelligenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun `scene boundaries follow colour and luma changes`() {
        fun solid(at: Long, color: Int) = RgbFrame(at, IntArray(16) { color })
        val frames = listOf(solid(0, 0xFFFF0000.toInt()), solid(1_000, 0xFFFF0000.toInt()), solid(3_000, 0xFF0000FF.toInt()), solid(4_000, 0xFF0000FF.toInt()))
        assertEquals(listOf(3_000L), SceneMath.boundaries(frames))
        assertEquals(0.0, SceneMath.histogramDistance(SceneMath.histogram(frames[0].pixels), SceneMath.histogram(frames[1].pixels)), 0.001)
        assertTrue(SceneMath.histogramDistance(SceneMath.histogram(frames[1].pixels), SceneMath.histogram(frames[2].pixels)) > 0.35)
    }

    @Test fun `sampling schedules match fast balanced and deep`() {
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), Sampling.schedule(30_000, SampleMode.FAST))
        assertEquals(listOf(0L, 5_000L, 7_000L, 10_000L), Sampling.schedule(12_000, SampleMode.BALANCED, listOf(7_000)))
        val deep = Sampling.schedule(12_000, SampleMode.DEEP, listOf(7_000))
        assertTrue(deep.containsAll(listOf(5_000L, 6_000L, 7_000L, 8_000L, 9_000L)))
        assertFalse(deep.contains(7_000L + 20_000L))
    }

    @Test fun `bpm estimate matches a synthetic click track`() {
        val bpm = BpmEstimator.estimate(BpmEstimator.clickTrack(120, 8))
        assertNotNull(bpm)
        assertTrue("expected near 120, was $bpm", bpm!! in 116..124)
        assertNull(BpmEstimator.estimate(FloatArray(16_000)))
    }

    @Test fun `conversational search keeps prior constraints`() {
        val zone = ZoneId.of("Africa/Nairobi")
        val now = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val first = QueryParser.parse("Find my travel videos.", now, zone)
        assertEquals(setOf(ContentKind.VIDEO), first.kinds)
        assertTrue(first.categories.contains("travel"))
        val second = QueryParser.refine(first, "Only from last year.", now, zone)
        assertEquals(setOf(ContentKind.VIDEO), second.kinds)
        assertTrue(second.categories.contains("travel"))
        val yearStart = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val lastStart = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(lastStart, second.fromMs)
        assertEquals(yearStart, second.toMs)
        val third = QueryParser.refine(second, "Show the ones containing boats.", now, zone)
        assertEquals(setOf(ContentKind.VIDEO), third.kinds)
        assertTrue(third.categories.contains("travel"))
        assertEquals(lastStart, third.fromMs)
        assertTrue(third.text.contains("boats"))
    }

    @Test fun `hybrid rank prefers combined evidence and is stable`() {
        val ranked = HybridRanker.rank(listOf(
            Triple("a:-1", "FTS", 1.0),
            Triple("b:-1", "FTS", 1.0),
            Triple("b:-1", "CATEGORY", 1.0),
            Triple("c:-1", "SEMANTIC", 0.9),
        ))
        assertEquals("b", ranked.first().itemId)
        assertTrue(ranked.map { it.itemId }.contains("c"))
        assertEquals(ranked.map { it.key }, HybridRanker.rank(listOf(
            Triple("a:-1", "FTS", 1.0),
            Triple("b:-1", "FTS", 1.0),
            Triple("b:-1", "CATEGORY", 1.0),
            Triple("c:-1", "SEMANTIC", 0.9),
        )).map { it.key })
    }

    @Test fun `checkpoint resume skips completed frames and a failure does not stop the queue`() = runBlocking {
        val jobs = db.jobs()
        val now = 10L
        jobs.enqueue(ProcessingJob("bad", "intelligence", null, createdAt = 1, updatedAt = 1))
        jobs.enqueue(ProcessingJob("good", "other", null, priority = JobPriority.USER_REQUESTED, createdAt = 2, updatedAt = 2))
        val first = jobs.claim("t1", now, "intelligence")!!
        assertEquals("failed", QueueStep.run(jobs, first, "t1", now) { error("MODEL_MISSING") })
        assertEquals(JobState.RETRY_PENDING.name, jobs.job("bad")!!.state)
        assertEquals(1, jobs.retryFailed("bad", now))
        val resumed = jobs.claim("t2", now, "intelligence")!!
        val encoded = AnalysisCheckpoint(5_000).encode()
        assertEquals(1, jobs.checkpoint(resumed.id, "t2", JobState.PROCESSING.name, 0.2, encoded, now))
        val stored = AnalysisCheckpoint.decode(jobs.job("bad")!!.checkpoint)
        assertEquals(5_000L, stored.doneMs)
        assertTrue(AnalysisCheckpoint.skipFrame(stored, 4_000))
        assertFalse(AnalysisCheckpoint.skipFrame(stored, 5_000))
        jobs.requeue("bad", JobPriority.BACKGROUND, now)
        assertEquals(5_000L, AnalysisCheckpoint.decode(jobs.job("bad")!!.checkpoint).doneMs)
        val second = jobs.claim("t3", now, "other")!!
        assertEquals("good", second.id)
        assertEquals("completed", QueueStep.run(jobs, second, "t3", now) { jobs.complete(second.id, "t3", now) })
        assertEquals(JobState.COMPLETED.name, jobs.job("good")!!.state)
        assertEquals(JobState.PENDING.name, jobs.job("bad")!!.state)
    }

    @Test fun `user corrections outrank later ai category suggestions`() = runBlocking {
        db.library().insertItem(MediaItem("m", null, "m.jpg", "image/jpeg", "IMAGE", null, 10, 1, null, storage = StorageMode.EXTERNAL.name,
            contentKind = ContentKind.IMAGE.name, externalUri = "content://media/m", origin = CaptureOrigin.MEDIASTORE.name))
        val travel = MediaCategory("travel", null, "Travel", Origin.USER.name, createdAt = 1, updatedAt = 1)
        db.library().insertCategory(travel)
        db.library().setCategory(ItemCategory("m", "travel", Origin.USER.name, LinkState.EXCLUDED.name, updatedAt = 2))
        CategoryApply.suggest(db.library(), "m", listOf(CategorySuggestion(null, "Travel", 0.9), CategorySuggestion(null, "Animals", 0.6)), 3)
        val links = db.library().itemCategories("m").first().associateBy { it.category.name }
        assertEquals(LinkState.EXCLUDED.name, links["Travel"]!!.linkState)
        assertEquals(Origin.USER.name, links["Travel"]!!.linkOrigin)
        assertEquals(LinkState.ASSIGNED.name, links["Animals"]!!.linkState)
        assertTrue(CategoryMap.from(listOf("Person", "Dog"), "").none { it.name.equals("Adult", true) })
        assertTrue(CategoryMap.from(listOf("Dog"), "").any { it.name == "Animals" })
    }

    @Test fun `search within an item returns the scene moment`() = runBlocking {
        source("s")
        db.media().insert(MediaItem("v", "s", "v.mp4", "video/mp4", "VIDEO", "h", 10, 1, null, contentKind = ContentKind.VIDEO.name))
        db.library().saveSegment(MediaSegment("g1", "v", null, SegmentKind.SCENE.name, 12_000, 18_000, description = "a boat leaves the harbour", createdAt = 1))
        db.events().insertSearch(SourceSearch("s", "budget planning"))
        db.media().segment(TranscriptSegment("t1", "v", "s", 40_000, 45_000, text = "budget planning"))
        val hits = db.library().searchWithin("v", "boat")
        assertEquals(listOf(12_000L), hits.map { it.startMs })
        assertEquals("SEGMENT", hits.single().kind)
        assertEquals(listOf(40_000L), LibraryTimestampSearch(db.library()).searchWithin("v", "budget").map { it.startMs })
    }

    @Test fun `missing adult classifier is reported and timings are not invented`() {
        assertEquals("classifier not installed", AdultClassifier.NOT_INSTALLED)
        val text = BenchmarkFormat.format(listOf(Timing("transcription", null, "Transcription model not installed.")))
        assertTrue(text.contains("not measured"))
        assertFalse(Regex("transcription: \\d").containsMatchIn(text))
        assertEquals("waiting for charging", DeviceGate.blockReason(RunPolicy(true, 20, false, true), DeviceSnapshot(false, 80, PowerManager.THERMAL_STATUS_NONE, false)))
        assertNull(DeviceGate.blockReason(RunPolicy(false, 20, false, true), DeviceSnapshot(false, 80, PowerManager.THERMAL_STATUS_NONE, false)))
    }

    private suspend fun source(id: String) = db.events().insertCaptured(SourceEvent(id, externalId = id, packageName = "p", occurredAt = 1, receivedAt = 1,
        kind = "POSTED", payloadHash = "h", encryptedPayload = byteArrayOf(1), contentAvailable = true, contentRedacted = false, securitySensitive = false))
}
