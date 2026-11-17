package com.hellfire.ass.ui

import android.view.Choreographer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hellfire.ass.performance.*
import com.hellfire.ass.ui.components.KvList
import com.hellfire.ass.ui.components.Note
import com.hellfire.ass.ui.components.SecondaryButton
import com.hellfire.ass.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Developer counters. Labels and numbers only — never prompts, transcripts, amounts or message text. */
@Composable
fun PerfScreen() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("No benchmark has been run on this phone.") }
    var frames by remember { mutableStateOf("Sampling frames while this screen is open") }
    DisposableEffect(Unit) {
        var last = 0L
        var slow = 0
        var seen = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (last != 0L) {
                    val deltaMs = (time - last) / 1_000_000
                    seen++
                    if (deltaMs > 32) slow++
                    if (seen % 30 == 0) frames = "$slow of $seen frames over 32 ms on this screen"
                    PerfLog.record("frame_ms", deltaMs)
                }
                last = time
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
        onDispose { Choreographer.getInstance().removeFrameCallback(callback) }
    }
    PushedPage("Performance", breadcrumb = "This device") {
        Note("These figures are from this process only. They are not a battery, thermal or accelerator result for the OPPO until that phone is measured. Nothing here is personal content.")
        SectionCard("Hardware") {
            KvList(DeviceProbe.hardwareSummary(app) + listOf(
                "Thermal / battery / memory" to runCatching {
                    val s = DeviceProbe.signals(app)
                    "thermal ${s.thermal} · battery ${s.batteryPercent}% · ${if (s.charging) "charging" else "battery"} · ${if (s.lowMemory) "low memory" else "memory ok"}"
                }.getOrDefault("Unavailable"),
                "Current" to (DeviceProbe.currentMicroamps(app)?.let { "$it µA" } ?: "Not exposed by this device"),
                "Profile" to OperatingProfile.of(runCatching { DeviceProbe.signals(app) }.getOrDefault(DeviceSignals())).mode.name,
            ))
        }
        SectionCard("This process") {
            val samples = PerfLog.samples().takeLast(12).map { "${it.name} ${it.value}" }
            val queries = QueryTrace.snapshot().map { "${it.name} n=${it.count} max=${it.maxMs} ms rows=${it.rows}" }
            val resident = ModelSlots.snapshot().joinToString().ifEmpty { "none" }
            if (samples.isEmpty() && queries.isEmpty()) Note("No samples yet. Open the app, run a search, or start a benchmark.")
            samples.forEach { Text(it) }
            queries.forEach { Text(it) }
            Text("Cached heavy runtimes: $resident")
            Text("Waiting: ${ExecutionScheduler.waitingSnapshot().joinToString().ifEmpty { "none" }}")
            PerfLog.details().takeLast(6).forEach { Text("${it.first} ${it.second}") }
            Text(frames)
            if (tick > 0) Note("Refreshed.")
        }
        SectionCard("Language backend benchmark") {
            Note("Runs CPU, then GPU, then NPU on the imported language model and keeps the fastest backend that actually loaded. A failed accelerator falls back to CPU. This can take several minutes and is not run automatically. Output text is discarded.")
            SecondaryButton("Measure language backends", {
                scope.launch {
                    message = "Measuring. Keep the phone cool and unlocked."
                    message = try {
                        val results = withContext(Dispatchers.IO) { app.localModel.measureBackends() }
                        val chosen = fastestBackend(results)
                        results.entries.joinToString(" · ") { (name, measure) ->
                            "$name ${if (measure.ok) "ok ${measure.elapsedMs} ms" else "failed"}"
                        } + ". Selected $chosen. This is one probe, not a quality or battery result."
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (caught: Exception) {
                        com.hellfire.ass.Diagnostics.warn(caught)
                        "Benchmark did not finish. CPU remains the fallback. ${caught.message ?: "Check the model and temperature."}"
                    }
                    tick++
                }
            }, Modifier, enabled = app.localModel.configured)
            Text(message)
        }
        SectionCard("Not measured here") {
            Note("Startup and baseline profiles are not installed in the release APK. Macrobenchmark, overnight battery, ColorOS job quota and codec behaviour need the OPPO checklist in DEVICE_FEASIBILITY.md.")
            Note("Web sync stays uncompressed. The server does not advertise gzip, so compressing requests would break sync.")
        }
    }
}
