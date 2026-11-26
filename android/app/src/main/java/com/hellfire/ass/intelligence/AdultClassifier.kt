package com.hellfire.ass.intelligence

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.net.Uri
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import com.hellfire.ass.ai.onnxEnvironment
import java.io.FileNotFoundException

internal data class ImageTensorShape(val width: Int, val height: Int, val channelsFirst: Boolean) {
    companion object {
        fun from(shape: LongArray): ImageTensorShape {
            require(shape.size == 4 && shape[0] in listOf(-1L, 1L)) { "Image input must have one batch and four dimensions" }
            val channelsFirst = shape[1] == 3L
            require(channelsFirst || shape[3] == 3L) { "Image input must have three RGB channels" }
            fun dimension(value: Long): Int {
                require(value == -1L || value in 1..2048) { "Image input dimension is unsupported" }
                return if (value == -1L) 224 else value.toInt()
            }
            val height = dimension(shape[if (channelsFirst) 2 else 1])
            val width = dimension(shape[if (channelsFirst) 3 else 2])
            require(width.toLong() * height <= 1_048_576) { "Image input exceeds the one-megapixel inference bound" }
            return ImageTensorShape(width, height, channelsFirst)
        }
    }
}

data class AdultVerdict(
    val installed: Boolean,
    val applied: Boolean,
    val label: String?,
    val confidence: Double?,
    val verified: Boolean = false,
    val message: String,
)

class AdultClassifier(private val app: HellFireApp) {
    private val mutex = Mutex()
    val modelFile get() = File(app.filesDir, "models/adult.onnx")
    val labelsFile get() = File(app.filesDir, "models/adult-labels.txt")
    val installed get() = modelFile.isFile
    fun status(): String = if (installed) "classifier installed" else NOT_INSTALLED
    fun labels(): List<String> = if (!labelsFile.isFile) emptyList() else labelsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.take(64)

    suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            modelFile.parentFile!!.mkdirs()
            val temp = File(modelFile.parentFile, "adult.part")
            try {
                copyLimited(uri, temp, 300L * 1024 * 1024)
                val opened = runCatching {
                    onnxEnvironment.createSession(temp.path).use { it.inputNames.isNotEmpty() }
                }.getOrDefault(false)
                require(opened) { "Select an ONNX classifier file. Nothing was installed." }
                java.nio.file.Files.move(temp.toPath(), modelFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
    }

    suspend fun importLabels(uri: Uri) = withContext(Dispatchers.IO) {
        val text = (app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Android could not open that file")).use { it.readBytes().toString(Charsets.UTF_8) }
        require(text.length <= 16_000) { "Label file is too large" }
        labelsFile.parentFile!!.mkdirs()
        labelsFile.writeText(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(64).joinToString("\n"))
    }

    fun classify(bitmap: Bitmap): AdultVerdict {
        if (!installed) return AdultVerdict(false, false, null, null, message = NOT_INSTALLED)
        return runCatching {
            onnxEnvironment.createSession(modelFile.path).use { session ->
                val name = session.inputNames.firstOrNull() ?: return AdultVerdict(true, false, null, null, message = "classifier installed but it has no input")
                val shape = (session.inputInfo[name]?.info as? TensorInfo)?.shape
                if (shape == null || shape.size != 4) return AdultVerdict(true, false, null, null, message = "classifier installed but input shape is unsupported")
                val layout = ImageTensorShape.from(shape)
                val height = layout.height
                val width = layout.width
                val channelsFirst = layout.channelsFirst
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                if (scaled !== bitmap) scaled.recycle()
                val buffer = FloatBuffer.allocate(3 * width * height)
                val values = FloatArray(3 * width * height)
                pixels.forEachIndexed { index, pixel ->
                    val r = ((pixel shr 16) and 0xff) / 255f
                    val g = ((pixel shr 8) and 0xff) / 255f
                    val b = (pixel and 0xff) / 255f
                    if (channelsFirst) {
                        values[index] = r
                        values[width * height + index] = g
                        values[2 * width * height + index] = b
                    } else {
                        values[index * 3] = r
                        values[index * 3 + 1] = g
                        values[index * 3 + 2] = b
                    }
                }
                buffer.put(values)
                buffer.rewind()
                val tensorShape = if (channelsFirst) longArrayOf(1, 3, height.toLong(), width.toLong()) else longArrayOf(1, height.toLong(), width.toLong(), 3)
                OnnxTensor.createTensor(onnxEnvironment, buffer, tensorShape).use { tensor ->
                    session.run(mapOf(name to tensor)).use { result ->
                        val scores = flatten(result.get(0).value)
                        val index = scores.indices.maxByOrNull { scores[it] } ?: return AdultVerdict(true, false, null, null, message = "classifier returned no scores")
                        val confidence = softmax(scores)[index].toDouble()
                        val names = labels()
                        val label = names.getOrNull(index)
                        AdultVerdict(true, label != null, label, confidence, verified = false,
                            message = if (label == null) "uncertain class index $index; labels not configured" else "uncertain")
                    }
                }
            }
        }.getOrElse { AdultVerdict(true, false, null, null, message = "classifier installed but did not run") }
    }

    private suspend fun copyLimited(uri: Uri, dest: File, max: Long) {
        (app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Android could not open that file")).use { input -> dest.outputStream().use { output ->
            val buffer = ByteArray(65536)
            var size = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                require(size <= max) { "Classifier file is too large" }
                output.write(buffer, 0, n)
            }
        } }
    }

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.map { flatten(it) }.reduce { a, b -> a + b }
        else -> floatArrayOf()
    }

    private fun softmax(values: FloatArray): FloatArray {
        val max = values.maxOrNull() ?: return values
        val exp = values.map { kotlin.math.exp((it - max).toDouble()) }
        val sum = exp.sum().takeIf { it > 0 } ?: return FloatArray(values.size)
        return FloatArray(values.size) { (exp[it] / sum).toFloat() }
    }

    companion object {
        const val NOT_INSTALLED = "classifier not installed"
    }
}
