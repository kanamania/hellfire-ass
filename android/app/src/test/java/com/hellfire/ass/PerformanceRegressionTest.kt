package com.hellfire.ass

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import com.hellfire.ass.ai.HeavyModelGate
import com.hellfire.ass.ai.LocalModel
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.intelligence.ContentEngine
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.media.MediaWork
import com.hellfire.ass.media.MediaWorker
import com.hellfire.ass.performance.ModelSlots
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE, shadows = [CountingRetriever::class])
class PerformanceRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    private lateinit var app: HellFireApp
    private lateinit var manager: WorkManagerImpl

    @Before fun setup() {
        WorkManager.initialize(context, Configuration.Builder().setExecutor { }.build())
        manager = WorkManager.getInstance(context) as WorkManagerImpl
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        CountingRetriever.reset()
        ModelSlots.reset()
    }

    @After fun close() {
        app.captureScope.cancel()
        db.close()
        manager.cancelAllWork().result.get()
        manager.closeDatabase()
        WorkManagerImpl.setDelegate(null)
        ReflectionHelpers.setStaticField(WorkManagerImpl::class.java, "sDefaultInstance", null)
        ModelSlots.reset()
    }

    private fun item(id: String, at: Long) = MediaItem(id, null, "Synthetic", "video/mp4", "VIDEO", null, 1, at, null,
        state = "QUEUED", storage = "EXTERNAL", contentKind = "VIDEO", externalUri = "content://synthetic/$id")

    @Test fun `pump fills oldest bounded slots with legacy jobs then resumes backlog`() = runBlocking {
        db.withTransaction { repeat(1000) { db.media().insert(item("m$it", it.toLong())) } }
        // Already-enqueued work from older APKs has no per-item tag.
        for (id in listOf("m0", "m1")) manager.enqueueUniqueWork("analyze-media:$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaWorker>().setInputData(workDataOf("id" to id)).addTag("analyze-media").build()).result.get()
        MediaWork.pump(app)
        val active = manager.getWorkInfosByTag("analyze-media").get().filter { !it.state.isFinished }
        assertEquals(20, active.size)
        for (i in 0 until 20) assertEquals(1, manager.getWorkInfosForUniqueWork("analyze-media:m$i").get().size)
        assertTrue(manager.getWorkInfosForUniqueWork("analyze-media:m20").get().isEmpty())
        MediaWork.pump(app)
        assertEquals(20, manager.getWorkInfosByTag("analyze-media").get().size)
        val finished = manager.getWorkInfosForUniqueWork("analyze-media:m0").get().single()
        withContext(Dispatchers.IO) { manager.workDatabase.workSpecDao().setState(WorkInfo.State.SUCCEEDED, finished.id.toString()) }
        db.media().state("m0", "INDEXED", null)
        MediaWork.pump(app)
        assertEquals(1, manager.getWorkInfosForUniqueWork("analyze-media:m20").get().size)
        assertEquals(999, db.media().unfinished().size)
    }

    @Test fun `backlog selection excludes active ids and only reads requested ids in stable order`() = runBlocking {
        db.withTransaction {
            listOf("c", "a", "b", "z").forEach { db.media().insert(item(it, 1)) }
            db.media().insert(item("done", 0).copy(state = "INDEXED"))
        }
        assertEquals(listOf("a", "b"), db.media().unfinishedIds(emptyList(), 2))
        assertEquals(listOf("b", "z"), db.media().unfinishedIds(listOf("a", "c"), 3))
        assertTrue(db.media().unfinishedIds(emptyList(), 0).isEmpty())
    }

    @Test fun `pressure eviction waits for active inference and never initializes unused models`() = runBlocking {
        var initialized = 0
        val owner = lazy { initialized++; LocalModel(app) }
        ReflectionHelpers.setField(app, "localModelInstance", owner)
        app.releaseIdleModels()
        assertEquals(0, initialized)
        app.localModel
        ModelSlots.mark("LANGUAGE")
        HeavyModelGate.mutex.lock()
        val release = async(start = CoroutineStart.UNDISPATCHED) { app.releaseIdleModels() }
        try {
            assertFalse(release.isCompleted)
            assertEquals(setOf("LANGUAGE"), ModelSlots.snapshot())
        } finally { HeavyModelGate.mutex.unlock() }
        release.await()
        assertTrue(ModelSlots.snapshot().isEmpty())
        assertEquals(1, initialized)
        for (field in listOf("speechInstance", "semanticInstance", "speakersInstance")) {
            assertFalse(ReflectionHelpers.getField<Lazy<*>>(app, field).isInitialized())
        }
    }

    @Test fun `one retriever serves six frame timestamps and closes on cancellation`() = runBlocking {
        val engine = ContentEngine(app)
        try {
            engine.withRetriever(item("video", 0)) { retriever ->
                repeat(6) { engine.frame(retriever, it * 5_000L) }
                throw CancellationException("synthetic interruption")
            }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { /* WorkManager must be allowed to resume. */ }
        assertEquals(1, CountingRetriever.opened)
        assertEquals(1, CountingRetriever.closed)
        assertEquals(listOf(0L, 5_000_000L, 10_000_000L, 15_000_000L, 20_000_000L, 25_000_000L), CountingRetriever.scaled)
        assertEquals(CountingRetriever.scaled, CountingRetriever.full)
    }

    @Test fun `source initialization failure still releases retriever`() = runBlocking {
        CountingRetriever.failOpen = true
        try { ContentEngine(app).withRetriever(item("bad", 0)) { fail("Opened invalid source") }; fail("Expected source error") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, CountingRetriever.closed)
    }
}

@Implements(MediaMetadataRetriever::class)
class CountingRetriever {
    @Implementation fun setDataSource(context: Context, uri: Uri) {
        opened++
        if (failOpen) throw IllegalArgumentException("synthetic source failure")
    }
    @Implementation fun getScaledFrameAtTime(at: Long, option: Int, width: Int, height: Int): android.graphics.Bitmap? { scaled += at; return null }
    @Implementation fun getFrameAtTime(at: Long, option: Int): android.graphics.Bitmap? { full += at; return null }
    @Implementation fun release() { closed++ }
    companion object {
        var opened = 0
        var closed = 0
        var failOpen = false
        val scaled = mutableListOf<Long>()
        val full = mutableListOf<Long>()
        fun reset() { opened = 0; closed = 0; failOpen = false; scaled.clear(); full.clear() }
    }
}
