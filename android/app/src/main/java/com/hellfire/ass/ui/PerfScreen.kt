package com.hellfire.ass.ui

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
import kotlinx.coroutines.flow.first
import com.hellfire.ass.Diagnostics

/** Developer counters. Labels and numbers only — never prompts, transcripts, amounts or message text. */
@Composable
fun PerfScreen() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("No benchmark has been run on this phone.") }
    var readings by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(tick) {
        readings = withContext(Dispatchers.IO) {
            try { listOf(
                "Process PSS" to "${android.os.Debug.getPss()} KiB (snapshot, not peak)",
                "Analysis jobs" to app.database.jobs().counts().first().joinToString { "${it.state}: ${it.count}" }.ifEmpty { "none" },
            ) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { listOf("Readings" to "Unavailable") }
        }
    }
    PushedPage("Performance", breadcrumb = "This device") {
        Note("Local counters refresh on request. Stage times include failed attempts; completion counters end in _ok. These are not controlled device benchmarks.")
        SectionCard("Hardware") {
            KvList(DeviceProbe.hardwareSummary(app) + listOf(
                "Thermal / battery / memory" to runCatching {
                    val s = DeviceProbe.signals(app)
                    "thermal ${s.thermal} · battery ${s.batteryPercent}% · ${if (s.charging) "charging" else "battery"} · ${if (s.lowMemory) "low memory" else "memory ok"}"
                }.getOrDefault("Unavailable"),
                "Current" to (DeviceProbe.currentMicroamps(app)?.let { "$it µA" } ?: "Not exposed by this device"),
                "Profile" to OperatingProfile.of(runCatching { DeviceProbe.signals(app) }.getOrDefault(DeviceSignals())).mode.name,
            ) + readings)
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
            timingSummaries(PerfLog.samples()).forEach { Text("${it.name}: n=${it.count} p50=${it.p50} p95=${it.p95} ms") }
            SecondaryButton("Refresh counters", { tick++ })
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
                        Diagnostics.warn(caught)
                        "Benchmark did not finish. CPU remains the fallback. ${caught.message ?: "Check the model and temperature."}"
                    }
                    tick++
                }
            }, Modifier, enabled = app.localModel.configured)
            Text(message)
        }
        SectionCard("Not measured here") {
            Note("Use the benchmark module for startup/frame timing and Baseline Profile collection. No generated app profile or OnePlus 16 measurements are installed yet. Battery, thermals and codecs need the DEVICE_FEASIBILITY.md protocol.")
            Note("Web sync stays uncompressed. The server does not advertise gzip, so compressing requests would break sync.")
        }
    }
}
