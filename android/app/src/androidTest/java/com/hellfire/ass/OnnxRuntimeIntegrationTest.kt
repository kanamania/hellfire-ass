package com.hellfire.ass

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.hellfire.ass.intelligence.AdultClassifier
import com.hellfire.ass.media.LearnedVad
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OnnxRuntimeIntegrationTest {
    @Test fun realCpuVadRejectsDigitalSilenceAndResetsItsRecurrentState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = listOf(context.getExternalFilesDir(null), context.filesDir).map { File(it, "models/silero_vad.onnx") }.firstOrNull { it.isFile } ?: File(context.filesDir, "models/silero_vad.onnx")
        assumeTrue("Install the pinned public VAD model with tools/test_emulator.sh", model.isFile)
        LearnedVad.open(model).use { vad ->
            val first = FloatArray(50) { vad.probability(FloatArray(512)) }
            assertTrue(first.all { it.isFinite() && it < 0.5f })
            vad.reset()
            val second = FloatArray(50) { vad.probability(FloatArray(512)) }
            assertArrayEquals(first, second, 0.0000001f)
            assertTrue(runCatching { vad.probability(FloatArray(511)) }.isFailure)
            assertTrue(runCatching { vad.probability(FloatArray(512) { Float.NaN }) }.isFailure)
        }
    }

    @Test fun realOnnxRuntimeProcessesNonsquareNchwAndNhwcFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as HellFireApp
        val classifier = AdultClassifier(app)
        val priorModel = File(app.cacheDir, "fixture-prior-adult.onnx")
        val priorLabels = File(app.cacheDir, "fixture-prior-labels.txt")
        classifier.modelFile.parentFile!!.mkdirs()
        check(!priorModel.exists() && !priorLabels.exists())
        if (classifier.modelFile.exists()) check(classifier.modelFile.renameTo(priorModel))
        if (classifier.labelsFile.exists()) check(classifier.labelsFile.renameTo(priorLabels))
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            classifier.labelsFile.writeText("red\ngreen\nblue\n")
            for (layout in listOf("nchw", "nhwc")) {
                instrumentation.context.assets.open("onnx/color-$layout.onnx").use { input -> classifier.modelFile.outputStream().use { output -> input.copyTo(output) } }
                val actual = classifier.classify(bitmap)
                assertTrue("$layout: ${actual.message}", actual.applied)
                assertEquals("red", actual.label)
                assertTrue(actual.confidence!! in 0.57..0.59)
                assertFalse(actual.verified)
            }
        } finally {
            bitmap.recycle()
            classifier.modelFile.delete(); classifier.labelsFile.delete()
            if (priorModel.exists()) check(priorModel.renameTo(classifier.modelFile))
            if (priorLabels.exists()) check(priorLabels.renameTo(classifier.labelsFile))
        }
    }
}
