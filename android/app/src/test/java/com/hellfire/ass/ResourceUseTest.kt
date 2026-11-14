package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.memory.SemanticIndex
import com.hellfire.ass.memory.SemanticIndexWork
import com.hellfire.ass.processing.CaptureWork
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ResourceUseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var manager: WorkManagerImpl

    @Before fun setup() {
        // Persist real WorkManager requests without starting app/native workers.
        WorkManager.initialize(context, Configuration.Builder().setExecutor { }.build())
        manager = WorkManager.getInstance(context) as WorkManagerImpl
    }

    @After fun close() {
        if (::manager.isInitialized) {
            manager.cancelAllWork().result.get()
            manager.closeDatabase()
        }
        WorkManagerImpl.setDelegate(null)
        ReflectionHelpers.setStaticField(WorkManagerImpl::class.java, "sDefaultInstance", null)
    }

    @Test fun `concurrent capture triggers keep one queued drain`() = runBlocking {
        coroutineScope { repeat(100) { launch(Dispatchers.Default) { CaptureWork.process(context) } } }
        val queued = manager.getWorkInfosForUniqueWork("process-events").get()
        assertEquals(1, queued.size)
        assertEquals(WorkInfo.State.ENQUEUED, queued.single().state)
        assertFalse(queued.single().constraints.requiresCharging())
    }

    @Test fun `capture while draining retains one successor including final-empty-read race`() = runBlocking {
        CaptureWork.process(context)
        val first = manager.getWorkInfosForUniqueWork("process-events").get().single()
        withContext(Dispatchers.IO) { manager.workDatabase.workSpecDao().setState(WorkInfo.State.RUNNING, first.id.toString()) }
        repeat(50) { CaptureWork.process(context) }
        val chain = manager.getWorkInfosForUniqueWork("process-events").get()
        assertEquals(2, chain.size)
        assertEquals(1, chain.count { it.state == WorkInfo.State.RUNNING })
        assertEquals(1, chain.count { it.state == WorkInfo.State.BLOCKED })
    }

    @Test fun `failed drain does not block new capture work`() = runBlocking {
        CaptureWork.process(context)
        val first = manager.getWorkInfosForUniqueWork("process-events").get().single()
        withContext(Dispatchers.IO) { manager.workDatabase.workSpecDao().setState(WorkInfo.State.FAILED, first.id.toString()) }
        CaptureWork.process(context)
        val chain = manager.getWorkInfosForUniqueWork("process-events").get()
        assertEquals(1, chain.count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun `background meaning indexing is unique and waits for charging and adequate battery`() = runBlocking {
        repeat(20) { SemanticIndexWork.schedule(context) }
        val work = manager.getWorkInfosForUniqueWork(SemanticIndexWork.NAME).get().single()
        assertTrue(work.constraints.requiresCharging())
        assertTrue(work.constraints.requiresBatteryNotLow())
        assertEquals(15 * 60_000L, work.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test fun `idle index does not initialize a native model`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        val app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        val index = SemanticIndex(app)
        index.modelFile.parentFile!!.mkdirs()
        // Deliberately invalid weights: loading these would fail, even on a device.
        index.modelFile.writeText("synthetic invalid model")
        try {
            assertEquals(0, index.indexPending())
            assertEquals(0, index.indexPending(0))
            assertTrue(runCatching { index.indexPending(-1) }.isFailure)
        } finally { index.modelFile.delete(); db.close() }
    }
}
