package com.hellfire.ass.media

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.hellfire.ass.ai.onnxEnvironment
import com.hellfire.ass.audio.MicrophoneStatus

/** Silero recurrent ONNX contract: 512 mono samples at 16 kHz, 64 samples of context. CPU only. */
class LearnedVad private constructor(private val session: OrtSession) : Closeable {
    private val environment = onnxEnvironment
    private val context = FloatArray(64)
    private val state = FloatArray(256)

    fun probability(frame: FloatArray): Float {
        require(frame.size == 512 && frame.all { it.isFinite() && it in -1f..1f })
        val input = context + frame
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 576)).use { audio ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { recurrent ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()).use { rate ->
                    session.run(mapOf("input" to audio, "state" to recurrent, "sr" to rate)).use { result ->
                        val score = (result.get("output").orElseThrow() as OnnxTensor).floatBuffer.get()
                        require(score.isFinite() && score in 0f..1f) { "VAD returned an invalid probability" }
                        val next = (result.get("stateN").orElseThrow() as OnnxTensor).floatBuffer
                        require(next.remaining() == state.size)
                        val values = FloatArray(state.size).also { next.get(it) }
                        require(values.all(Float::isFinite)) { "VAD returned an invalid recurrent state" }
                        values.copyInto(state)
                        frame.copyInto(context, 0, frame.size - context.size)
                        return score
                    }
                }
            }
        }
    }

    fun reset() { context.fill(0f); state.fill(0f) }
    override fun close() { reset(); session.close() }

    companion object {
        fun open(file: File): LearnedVad {
            require(file.isFile && file.length() in 1..8L * 1024 * 1024) { "VAD model must be a standalone ONNX file under 8 MiB" }
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
                onnxEnvironment.createSession(file.path, options)
            }
            try {
                require(session.inputNames == setOf("input", "state", "sr") && session.outputNames.containsAll(setOf("output", "stateN"))) {
                    "Select a Silero recurrent ONNX model with input, state and sr inputs"
                }
                for ((name, type) in mapOf("input" to OnnxJavaType.FLOAT, "state" to OnnxJavaType.FLOAT, "sr" to OnnxJavaType.INT64)) {
                    require((session.inputInfo[name]?.info as? TensorInfo)?.type == type) { "Unsupported VAD input type" }
                }
                val vad = LearnedVad(session)
                vad.probability(FloatArray(512)) // Validate actual shapes, state and output before installation.
                vad.reset()
                return vad
            } catch (error: Throwable) { session.close(); throw error }
        }
    }
}

object VadModels {
    fun file(context: Context) = File(context.filesDir, "models/vad.onnx")
    suspend fun import(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        check(!MicrophoneStatus.busy.value) { "Stop recording before replacing the VAD model" }
        val target = file(context)
        target.parentFile!!.mkdirs()
        val temporary = File.createTempFile("vad-", ".part", target.parentFile)
        try {
            context.contentResolver.openInputStream(uri)!!.use { source -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer); if (count < 0) break
                    total += count; require(total <= 8L * 1024 * 1024) { "VAD model exceeds 8 MiB" }
                    output.write(buffer, 0, count)
                }
            } }
            LearnedVad.open(temporary).close()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun remove(context: Context) {
        check(!MicrophoneStatus.busy.value) { "Stop recording before removing the VAD model" }
        val file = file(context)
        check(!file.exists() || file.delete()) { "Could not remove VAD model" }
    }
}
