package com.hellfire.ass.performance

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.google.ai.edge.litertlm.Backend
import java.io.File

/** Reads device signals and the LiteRT backends this build can construct. Device speed is not claimed here. */
object DeviceProbe {
    private const val PREFS = "performance"

    fun signals(context: Context): DeviceSignals {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return DeviceSignals(
            thermal = power.currentThermalStatus,
            batteryPercent = percent,
            charging = plugged != 0,
            lowMemory = memory.lowMemory || activity.isLowRamDevice,
            memoryClassMb = activity.memoryClass,
            powerSave = power.isPowerSaveMode,
            foreground = false,
        )
    }

    fun litertBackends(): List<String> = listOf("CPU", "GPU", "NPU")

    fun backend(name: String): Backend = when (name) {
        "GPU" -> Backend.GPU()
        "NPU" -> Backend.NPU()
        else -> Backend.CPU()
    }

    fun fileKey(file: File): String = if (!file.isFile) "" else "${file.length()}:${file.lastModified()}"

    fun selectedLanguageBackend(context: Context, file: File): String {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return selectBackend(
            measuredLanguage(context),
            fileKey(file),
            prefs.getString("language-file", "").orEmpty(),
            prefs.getString("language-backend", "CPU").orEmpty(),
        )
    }

    fun measuredLanguage(context: Context): Map<String, BackendMeasure> {
        val raw = context.getSharedPreferences(PREFS, 0).getString("language-measured", "").orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val bits = part.split(',')
            if (bits.size != 3) null else bits[0] to BackendMeasure(bits[1] == "ok", bits[2].toLongOrNull() ?: 0)
        }.toMap()
    }

    fun storeLanguageMeasurement(context: Context, file: File, results: Map<String, BackendMeasure>) {
        val encoded = results.entries.joinToString(";") { (name, measure) ->
            "$name,${if (measure.ok) "ok" else "fail"},${measure.elapsedMs}"
        }
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("language-file", fileKey(file))
            .putString("language-backend", fastestBackend(results))
            .putString("language-measured", encoded)
            .apply()
    }

    fun clearLanguageBackend(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putString("language-backend", "CPU").apply()
    }

    fun hardwareSummary(context: Context): List<Pair<String, String>> {
        val signals = runCatching { signals(context) }.getOrNull()
        val prefs = context.getSharedPreferences(PREFS, 0)
        return listOf(
            "CPU cores" to Runtime.getRuntime().availableProcessors().toString(),
            "ABI" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            "Memory class" to "${signals?.memoryClassMb ?: "?"} MiB",
            "LiteRT backends in this build" to litertBackends().joinToString(),
            "Selected language backend" to prefs.getString("language-backend", "CPU").orEmpty(),
            "GPU / NPU speed" to "NOT MEASURED until a language benchmark succeeds on this phone",
            "whisper.cpp / ONNX / MediaPipe" to "CPU session reuse only; no accelerator switch",
        )
    }

    /** Current draw when the kernel exposes it. Many devices, including some OPPO builds, do not. */
    fun currentMicroamps(context: Context): Int? {
        val battery = context.getSystemService(BatteryManager::class.java)
        val current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return current.takeIf { it != Int.MIN_VALUE && it != 0 }
    }
}
