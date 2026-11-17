package com.hellfire.ass.media

import java.io.File

object WhisperNative {
    /**
     * The native library is compiled for ARMv8.2-A with dot-product, FP16 and int8 matrix-multiply
     * instructions (Snapdragon 8 Elite-class CPUs). [open] refuses to load it on a CPU that lacks
     * them, so an unsupported phone gets an error instead of a SIGILL crash.
     */
    val requiredCpuFeatures = listOf("asimddp", "asimdhp", "i8mm")

    fun missingCpuFeatures(cpuinfo: String): List<String> {
        val present = cpuinfo.lineSequence()
            .filter { it.startsWith("Features") }
            .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }
            .toSet()
        return requiredCpuFeatures.filterNot { it in present }
    }

    private val missing by lazy { missingCpuFeatures(runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")) }

    fun open(path: String): Long {
        check(missing.isEmpty()) { "Local transcription needs CPU features this phone lacks: ${missing.joinToString()}" }
        System.loadLibrary("hellfire_whisper")
        return load(path)
    }

    private external fun load(path: String): Long
    external fun transcribe(context: Long, samples: FloatArray, language: String): ByteArray
    external fun abort(context: Long)
    external fun reset(context: Long)
    external fun release(context: Long)
}
