package com.hellfire.ass.media

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Bundled on-device ML Kit labeler; labels are generic categories, not identities or verified facts. */
object LocalImageLabels {
    const val THRESHOLD = 0.7f
    suspend fun read(bitmap: Bitmap): List<Pair<String, Float>> {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(THRESHOLD).build())
        return suspendCancellableCoroutine { continuation ->
            labeler.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { labels -> if (continuation.isActive) continuation.resume(labels.map { it.text to it.confidence }) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                .addOnCompleteListener { labeler.close() }
        }
    }
    fun describe(labels: List<Pair<String, Float>>): String? = labels.filter { it.second >= THRESHOLD }.sortedByDescending { it.second }.take(10)
        .takeIf { it.isNotEmpty() }?.joinToString(", ", "On-device image labels (unverified): ") { "${it.first} ${(it.second * 100).roundToInt()}%" }
}
