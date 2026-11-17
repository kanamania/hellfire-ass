package com.hellfire.ass.intelligence

import android.os.Debug
import android.os.SystemClock
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.data.SearchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticBenchmark(private val app: HellFireApp) {
    suspend fun run(): String = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Timing>()
        val pixels = IntArray(320 * 180) { if (it % 2 == 0) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val started = SystemClock.elapsedRealtime()
        SceneMath.histogram(pixels)
        rows += Timing("frame histogram", SystemClock.elapsedRealtime() - started, "Synthetic pixels only. Device frame extraction was not part of this measurement.")
        rows += if (app.localModel.configured) {
            val mark = SystemClock.elapsedRealtime()
            val answer = runCatching { app.localModel.generate("Reply with the single word ready.") }
            if (answer.isSuccess) Timing("language model", SystemClock.elapsedRealtime() - mark, "Text prompt only. No image was sent. Quality is not claimed.")
            else Timing("language model", null, answer.exceptionOrNull()?.message ?: "Language model did not run.")
        } else Timing("language model", null, "Language model not installed.")
        rows += if (app.speech.model.isFile) Timing("transcription", null, "A transcription model file is present. This diagnostic does not invent a timing without a user-selected recording.")
        else Timing("transcription", null, "Transcription model not installed.")
        rows += if (app.semantic.configured) {
            val mark = SystemClock.elapsedRealtime()
            val vector = runCatching { app.semantic.embedText("library search") }.getOrNull()
            if (vector != null) Timing("embedding", SystemClock.elapsedRealtime() - mark, "One text embedding. Scores are uncalibrated.")
            else Timing("embedding", null, "Embedding model is installed but did not return a vector.")
        } else Timing("embedding", null, "Embedding model not installed.")
        val searchMark = SystemClock.elapsedRealtime()
        val query = SearchText.query("library")
        if (query != null) runCatching { app.database.library().search(query, 5) }
        rows += Timing("search", SystemClock.elapsedRealtime() - searchMark, "One local full-text query.")
        rows += Timing("memory", null, "PSS ${Debug.getPss()} KB at the end of this run. Not a peak-memory claim.")
        BenchmarkFormat.format(rows)
    }
}
