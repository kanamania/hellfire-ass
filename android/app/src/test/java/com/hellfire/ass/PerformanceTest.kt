package com.hellfire.ass

import com.hellfire.ass.performance.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PerformanceTest {
    @After fun reset() {
        ExecutionScheduler.reset()
        ModelSlots.reset()
        QueryTrace.reset()
        PerfLog.reset()
    }

    @Test fun `profiles conserve under heat low battery or low memory and defer only when comfortable`() {
        assertEquals(OperatingMode.CONSERVE, OperatingProfile.of(DeviceSignals(thermal = 4)).mode)
        assertEquals(OperatingMode.CONSERVE, OperatingProfile.of(DeviceSignals(batteryPercent = 10)).mode)
        assertEquals(OperatingMode.CONSERVE, OperatingProfile.of(DeviceSignals(lowMemory = true)).mode)
        assertFalse(OperatingProfile.of(DeviceSignals(lowMemory = true)).reuseModels)
        assertEquals(60, OperatingProfile.of(DeviceSignals(thermal = 4)).audioChunkSeconds)
        val deferred = OperatingProfile.of(DeviceSignals(charging = true, thermal = 0, batteryPercent = 80))
        assertEquals(OperatingMode.DEFER, deferred.mode)
        assertEquals(120, deferred.audioChunkSeconds)
        assertEquals(OperatingMode.INTERACTIVE, OperatingProfile.of(DeviceSignals(foreground = true)).mode)
        assertFalse(OperatingProfile.of(DeviceSignals(thermal = 4, foreground = true)).deferHeavy)
    }

    @Test fun `backend stays on CPU until a measurement for this file succeeds`() {
        assertEquals("CPU", selectBackend(emptyMap(), "file", "", "GPU"))
        assertEquals("CPU", selectBackend(mapOf("GPU" to BackendMeasure(false, 10)), "file", "file", "GPU"))
        assertEquals("GPU", selectBackend(mapOf("GPU" to BackendMeasure(true, 10)), "file", "file", "GPU"))
        assertEquals("CPU", selectBackend(mapOf("GPU" to BackendMeasure(true, 10)), "other", "file", "GPU"))
        assertEquals("CPU", fastestBackend(mapOf("GPU" to BackendMeasure(false, 5), "NPU" to BackendMeasure(false, 1))))
        assertEquals("NPU", fastestBackend(mapOf("CPU" to BackendMeasure(true, 40), "NPU" to BackendMeasure(true, 12), "GPU" to BackendMeasure(false, 1))))
    }

    @Test fun `interactive work makes deferred work yield at the next boundary`() {
        assertFalse(ExecutionScheduler.shouldYield(WorkPriority.DEFERRED))
        ExecutionScheduler.noteWaiting(WorkPriority.INTERACTIVE)
        assertTrue(ExecutionScheduler.shouldYield(WorkPriority.DEFERRED))
        assertFalse(ExecutionScheduler.shouldYield(WorkPriority.INTERACTIVE))
        ExecutionScheduler.clearWaiting(WorkPriority.INTERACTIVE)
        assertFalse(ExecutionScheduler.shouldYield(WorkPriority.DEFERRED))
    }

    @Test fun `only one heavy runtime stays resident unless memory pressure clears the cache`() {
        val cool = OperatingProfile.of(DeviceSignals())
        assertEquals(setOf("SPEECH"), rolesToEvict("LANGUAGE", setOf("SPEECH"), cool))
        assertEquals(emptySet<String>(), rolesToEvict("LANGUAGE", setOf("LANGUAGE"), cool))
        assertEquals(setOf("EMBEDDING", "SPEECH"), rolesToEvict("LANGUAGE", setOf("SPEECH", "EMBEDDING"), cool))
        assertEquals(setOf("SPEECH", "EMBEDDING"), rolesToEvict("LANGUAGE", setOf("SPEECH", "EMBEDDING"), OperatingProfile.of(DeviceSignals(lowMemory = true))))
    }

    @Test fun `incremental hash matches a one-shot digest and resumes from a checkpoint`() {
        val data = ByteArray(2000) { (it * 17).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val whole = IncrementalDigest().also { it.update(data, 0, data.size) }
        assertEquals(expected, whole.finish())
        val part = IncrementalDigest().also { it.update(data, 0, 500) }
        val saved = part.checkpoint()
        val resumed = IncrementalDigest().also { it.restore(saved, part.bytes); it.update(data, 500, data.size - 500) }
        assertEquals(expected, resumed.finish())
        assertEquals(data.size.toLong(), resumed.bytes)
    }

    @Test fun `scene filter drops duplicate frames and keeps the uniform sample when frames differ`() {
        val duration = 600_000L
        val uniform = SceneSampler.uniform(duration)
        assertEquals(20, uniform.size)
        assertEquals(uniform, SceneSampler.select(duration) { at -> if ((at / 30_000) % 2 == 0L) 0x00FFL else 0xFF00L })
        assertEquals(uniform, SceneSampler.select(duration) { null })
        val duplicates = SceneSampler.select(duration) { 1L }
        assertEquals(1, duplicates.size)
        assertEquals(0L, duplicates.single())
        assertTrue(SceneSampler.hamming(0L, 1L) < 8)
    }

    @Test fun `silence skips whisper and a tone above the floor does not`() {
        assertFalse(SpeechPlan.needsTranscription(FloatArray(16000)))
        val tone = FloatArray(16000) { if (it % 20 < 10) 0.2f else -0.2f }
        assertTrue(SpeechPlan.needsTranscription(tone))
        val quiet = FloatArray(16000) { 0.004f }
        assertFalse(SpeechPlan.needsTranscription(quiet))
    }

    @Test fun `media job cap leaves backlog unscheduled`() {
        assertEquals(20, JobBudget.slots(0))
        assertEquals(0, JobBudget.slots(20))
        assertEquals(3, JobBudget.slots(17))
    }

    @Test fun `gzip is used only when the peer accepts it and the body is large`() {
        assertFalse(PayloadCompression.shouldCompress(100_000, false))
        assertFalse(PayloadCompression.shouldCompress(100, true))
        assertTrue(PayloadCompression.shouldCompress(9_000, true))
        val raw = "sync-row ".repeat(2000).toByteArray()
        val back = PayloadCompression.gunzip(PayloadCompression.gzip(raw))
        assertArrayEquals(raw, back)
        assertTrue(PayloadCompression.gzip(raw).size < raw.size)
    }

    @Test fun `query and perf logs keep labels and numbers only`() {
        QueryTrace.record("source_search", 12, 4)
        QueryTrace.record("source_search", 30, 1)
        val stat = QueryTrace.snapshot().single()
        assertEquals(2, stat.count)
        assertEquals(30, stat.maxMs)
        assertEquals(5, stat.rows)
        assertTrue(runCatching { QueryTrace.record("user text", 1, 1) }.isFailure)
        PerfLog.record("inference_ms", 40)
        PerfLog.detail("inference_backend", "CPU")
        assertEquals(40L, PerfLog.latest("inference_ms"))
        assertEquals("CPU", PerfLog.details().single().second)
        assertTrue(runCatching { PerfLog.detail("note", "hello world") }.isFailure)
    }

    @Test fun `compose windows grow by a page and startup profile stays explicit`() {
        assertEquals(30, visibleWindow(100, 0))
        assertEquals(60, visibleWindow(100, 1))
        assertEquals(100, visibleWindow(100, 5))
        assertEquals(5, StartupProfile.critical.size)
        assertTrue(StartupProfile.critical.all { it.startsWith("com.hellfire.ass.") })
    }
}
