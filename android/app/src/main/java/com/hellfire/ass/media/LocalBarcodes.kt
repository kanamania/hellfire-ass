package com.hellfire.ass.media

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Indexable QR evidence only. Never opens a link, joins a network, authenticates or sends. */
object BarcodeEvidence {
    fun safe(raw: String): String? {
        val text = raw.trim().takeIf { it.isNotBlank() && it.length <= 2000 } ?: return null
        if (text.startsWith("WIFI:", true) || text.startsWith("otpauth:", true) || text.startsWith("otpauth-migration:", true)) return null
        val uri = runCatching { URI(text) }.getOrNull()
        if (uri?.scheme != null && (uri.scheme.lowercase() !in setOf("http", "https") || uri.host == null ||
                    uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null)) return null
        val normalized = NotificationNormalizer.normalize(NotificationSnapshot("qr", "local.qr", 0, 0,
            fields = mapOf("text" to listOf(text))), "com.hellfire.ass") ?: return null
        if (normalized.securitySensitive) return null
        return normalized.snapshot.fields["text"]?.singleOrNull()?.takeUnless { it == "[CODE REDACTED]" }
    }
}

object LocalBarcodes {
    /** ML Kit tasks cannot be cancelled: retain the caller's bitmap until this task completes. */
    suspend fun read(bitmap: Bitmap): List<String> = withContext(NonCancellable) {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        try {
            suspendCoroutine { continuation ->
                scanner.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { codes -> continuation.resume(codes.take(16).mapNotNull { it.rawValue?.let(BarcodeEvidence::safe) }.distinct()) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } finally { scanner.close() }
    }
}
